package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

/** Adapter only. Composition and connection lifecycle belong to its caller. */
public final class RedisFeedCandidateStore {
    public static final int MAX_READ_SIZE=200;
    public static final int MAX_WRITE_SIZE=200;
    private static final Logger LOG=LoggerFactory.getLogger(RedisFeedCandidateStore.class);
    private final RedisCommands<String,String> commands;

    // Counts inspect the score invariant without returning or scanning all members.
    // Both keys use the same {uid} hash tag for atomic execution on Redis Cluster.
    private static final String GUARD="""
            local function untrusted(reason)
                redis.call('SET', KEYS[2], reason)
                return {'UNTRUSTED', reason}
            end
            if redis.call('EXISTS', KEYS[2]) ~= 0 then
                return {'UNTRUSTED', 'MARKED'}
            end
            local kind = redis.call('TYPE', KEYS[1]).ok
            if kind ~= 'none' and kind ~= 'zset' then
                return untrusted('WRONG_TYPE')
            end
            if kind == 'zset' and redis.call('ZCARD', KEYS[1]) ~= redis.call('ZCOUNT', KEYS[1], '0', '0') then
                return untrusted('NONZERO_SCORE')
            end
            """;
    private static final String WRITE=GUARD+"""
            local values = {}
            for i = 1, #ARGV do
                values[#values + 1] = '0'
                values[#values + 1] = ARGV[i]
            end
            redis.call('ZADD', KEYS[1], unpack(values))
            return {'OK'}
            """;
    private static final String READ=GUARD+"""
            if kind == 'none' then return {'OK'} end
            local members = redis.call('ZRANGE', KEYS[1], ARGV[1], '-', 'BYLEX', 'REV', 'LIMIT', '0', ARGV[2])
            local result = {'OK'}
            for _, member in ipairs(members) do
                local time = string.sub(member, 1, 13)
                local id = string.sub(member, 15, 34)
                if #member ~= 34 or string.sub(member, 14, 14) ~= ':'
                    or not string.match(time, '^[0-9]+$') or not string.match(id, '^[0-9]+$')
                    or id <= '00000000000000000000' or id > '09223372036854775807' then
                    return untrusted('INVALID_MEMBER')
                end
                result[#result + 1] = member
            end
            return result
            """;
    private static final String FENCED_WRITE="""
            if redis.call('GET', KEYS[3]) ~= ARGV[1] then return {'FENCED'} end
            """+GUARD+"""
            local values = {}
            for i = 2, #ARGV do
                values[#values + 1] = '0'
                values[#values + 1] = ARGV[i]
            end
            redis.call('ZADD', KEYS[1], unpack(values))
            return {'OK'}
            """;

    public RedisFeedCandidateStore(StatefulRedisConnection<String,String> connection) {
        if(connection==null)throw new IllegalArgumentException("Redis connection is required");
        commands=connection.sync();
    }

    public void add(long readerId,List<FeedCandidate> candidates) {
        String key=key(readerId);
        if(candidates==null || candidates.isEmpty() || candidates.size()>MAX_WRITE_SIZE)
            throw new IllegalArgumentException("Candidate write batch must contain 1.."+MAX_WRITE_SIZE+" entries");
        var encoded=new ArrayList<String>(candidates.size());
        // Validate the complete batch before any Redis command can mutate the index.
        for(var candidate:candidates)encoded.add(FeedMemberCodec.encode(candidate));
        execute(WRITE,key,encoded.toArray(String[]::new));
    }
    /** Single-node deployment: recovery generation fencing, not a guarantee against same-generation late lease writes. */
    public void addFenced(long readerId,List<FeedCandidate> candidates,long generation) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Redis write inside SQL transaction");
        String key=key(readerId);
        if(generation<=0 || candidates==null || candidates.isEmpty() || candidates.size()>MAX_WRITE_SIZE)throw new IllegalArgumentException("Invalid fenced write");
        var encoded=new ArrayList<String>();encoded.add(Long.toString(generation));for(var candidate:candidates)encoded.add(FeedMemberCodec.encode(candidate));
        executeKeys(FENCED_WRITE,key,new String[]{key,key+":untrusted","feed:write-generation"},encoded.toArray(String[]::new));
    }

    public CandidateSlice read(long readerId,long snapshotMs,String exclusiveBoundary,int limit) {
        String key=key(readerId);
        if(limit<1 || limit>MAX_READ_SIZE)throw new IllegalArgumentException("Read limit must be 1.."+MAX_READ_SIZE);
        String snapshot=FeedMemberCodec.encode(new FeedCandidate(snapshotMs,Long.MAX_VALUE));
        String upper="["+snapshot;
        if(exclusiveBoundary!=null) {
            FeedMemberCodec.decode(exclusiveBoundary);
            if(exclusiveBoundary.compareTo(snapshot)<=0)upper="("+exclusiveBoundary;
        }
        var members=execute(READ,key,upper,Integer.toString(limit));
        var items=new ArrayList<FeedCandidate>(members.size());
        for(String member:members)items.add(FeedMemberCodec.decode(member));
        return new CandidateSlice(items,members.isEmpty()?null:members.get(members.size()-1));
    }

    private String key(long readerId) {
        if(readerId<=0)throw new IllegalArgumentException("Reader id must be a positive BIGINT");
        return "feed:v2:{"+readerId+"}:inbox";
    }

    private List<String> execute(String script,String key,String... arguments) {
        return executeKeys(script,key,new String[]{key,key+":untrusted"},arguments);
    }
    private List<String> executeKeys(String script,String key,String[] keys,String... arguments) {
        try {
            List<?> response=commands.eval(script,ScriptOutputType.MULTI,keys,arguments);
            if(response==null || response.isEmpty())throw unavailable("Missing Redis script result",null);
            if("FENCED".equals(response.get(0)))throw unavailable("Redis recovery generation changed; old writer fenced",null);
            if("UNTRUSTED".equals(response.get(0))) {
                String detail=response.size()>1?String.valueOf(response.get(1)):"UNKNOWN";
                LOG.error("Feed candidate index untrusted key={} reason={}; authoritative recovery required",key,detail);
                throw new CandidateIndexUnavailableException(CandidateIndexUnavailableException.Reason.UNTRUSTED_INDEX,"Untrusted candidate index: "+detail,null);
            }
            if(!"OK".equals(response.get(0)))throw unavailable("Unexpected Redis script status",null);
            var members=new ArrayList<String>(response.size()-1);
            for(int i=1;i<response.size();i++) {
                if(!(response.get(i) instanceof String member))throw unavailable("Unexpected Redis member result",null);
                members.add(member);
            }
            return members;
        }catch(RedisException | IllegalStateException failure) {
            LOG.warn("Redis candidate operation failed for key={}",key,failure);
            throw unavailable("Redis candidate source unavailable",failure);
        }
    }
    private CandidateIndexUnavailableException unavailable(String message,Throwable cause) {
        return new CandidateIndexUnavailableException(CandidateIndexUnavailableException.Reason.REDIS_UNAVAILABLE,message,cause);
    }
}
