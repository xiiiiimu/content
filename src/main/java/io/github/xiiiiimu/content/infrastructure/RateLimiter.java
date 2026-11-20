package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.RateLimited;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Hardening A2: token buckets per (rule, dimension value).
 * <ul>
 * <li>Normal path: one Lua script per decision in Redis, key {@code rl:v1:<rule>:<id>}, hash {t: tokens, ts: ms}, clock =
 *     Redis {@code TIME} (one clock for every instance), TTL = time to refill a full bucket + 1 s.</li>
 * <li>Redis failure (error or timeout): the decision is made by a per-instance in-memory bucket with the same rule
 *     (single-node granularity, never all-allow or all-deny), counted in {@code content.ratelimit.fallback}, WARN logged
 *     at most once per {@code WARN_EVERY_MS}. After a failure Redis is skipped for {@code cooldownMs} so requests do not
 *     each wait for a command timeout; then the next decision retries Redis.</li>
 * </ul>
 * Over the limit → {@link RateLimited} with Retry-After = ceil(ms until one token) in seconds.
 */
public final class RateLimiter implements AutoCloseable {
 /** {@code limit} tokens per {@code periodMs}, bucket capacity {@code burst}. */
 public record Rule(String name,long limit,long periodMs,long burst){
  public Rule{
   if(name==null || !name.matches("[a-z0-9-]{1,32}"))throw new IllegalArgumentException("rule name");
   if(limit<1 || periodMs<1 || burst<1)throw new IllegalArgumentException("rate limit "+name+": limit, period and burst must be >= 1");
  }
  double perMs(){return (double)limit/periodMs;}
  long ttlMs(){return (long)Math.ceil(burst/perMs())+1_000;}
 }
 public enum Decision {REDIS,LOCAL}

 static final String SCRIPT="""
  local rate=tonumber(ARGV[1]) local burst=tonumber(ARGV[2]) local ttl=tonumber(ARGV[3])
  local t=redis.call('TIME') local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
  local v=redis.call('HMGET',KEYS[1],'t','ts')
  local tokens=tonumber(v[1]) local ts=tonumber(v[2])
  if tokens==nil or ts==nil then tokens=burst ts=now end
  if now>ts then tokens=math.min(burst,tokens+(now-ts)*rate) end
  local allowed=0 local wait=0
  if tokens>=1 then tokens=tokens-1 allowed=1 else wait=math.ceil((1-tokens)/rate) end
  redis.call('HSET',KEYS[1],'t',string.format('%.6f',tokens),'ts',tostring(math.max(now,ts)))
  redis.call('PEXPIRE',KEYS[1],ttl)
  return {allowed,wait}
  """;
 static final long WARN_EVERY_MS=10_000;
 private static final Logger log=LoggerFactory.getLogger(RateLimiter.class);

 private final RedisClient client;private final Map<String,Rule> rules;private final long cooldownMs;
 private final MeterRegistry meters;private final LongSupplier clock;
 private final Map<String,LocalBucket> local=new ConcurrentHashMap<>();
 private final AtomicLong redisSkipUntil=new AtomicLong();private final AtomicLong lastWarn=new AtomicLong(Long.MIN_VALUE/2);
 private final AtomicLong decisions=new AtomicLong();
 private volatile StatefulRedisConnection<String,String> connection;private volatile String sha;

 public RateLimiter(RedisClient client,List<Rule> rules,long cooldownMs,MeterRegistry meters){this(client,rules,cooldownMs,meters,System::currentTimeMillis);}
 RateLimiter(RedisClient client,List<Rule> rules,long cooldownMs,MeterRegistry meters,LongSupplier clock){
  var m=new java.util.LinkedHashMap<String,Rule>();for(var r:rules)if(m.put(r.name(),r)!=null)throw new IllegalArgumentException("duplicate rule "+r.name());
  this.client=client;this.rules=Map.copyOf(m);this.cooldownMs=Math.max(0,cooldownMs);this.meters=meters;this.clock=clock;
 }
 public Rule rule(String name){var r=rules.get(name);if(r==null)throw new IllegalArgumentException("unknown rate limit rule "+name);return r;}

 /** Takes one token for {@code id} under {@code ruleName}, or throws {@link RateLimited}. Returns where the decision was made. */
 public Decision acquire(String ruleName,String id){
  Rule rule=rule(ruleName);String key="rl:v1:"+rule.name()+":"+id;
  long now=clock.getAsLong();
  if(client!=null && now>=redisSkipUntil.get()){
   try{
    long[] res=redis(rule,key);
    if(res[0]!=1)reject(rule,res[1]);
    return Decision.REDIS;
   }catch(RateLimited e){throw e;}
   catch(RuntimeException e){
    redisSkipUntil.set(now+cooldownMs);dropConnection();
    if(meters!=null)meters.counter("content.ratelimit.redis.errors").increment();
    long last=lastWarn.get();
    if(now-last>=WARN_EVERY_MS && lastWarn.compareAndSet(last,now))
     log.warn("Rate limit Redis unavailable, deciding with per-instance buckets for {} ms: {}",cooldownMs,e.toString());
   }
  }
  if(meters!=null)meters.counter("content.ratelimit.fallback","rule",rule.name()).increment();
  long wait=local.computeIfAbsent(key,k->new LocalBucket(rule.burst(),now)).take(rule,now);
  if((decisions.incrementAndGet()&1023)==0)sweep(now);
  if(wait>0)reject(rule,wait);
  return Decision.LOCAL;
 }
 private void reject(Rule rule,long waitMs){
  if(meters!=null)meters.counter("content.ratelimit.rejected","rule",rule.name()).increment();
  throw new RateLimited(rule.name(),(waitMs+999)/1000);
 }
 private long[] redis(Rule rule,String key){
  var sync=connection().sync();
  String[] args={Double.toString(rule.perMs()),Long.toString(rule.burst()),Long.toString(rule.ttlMs())};
  List<Object> out;
  String s=sha;
  if(s==null){s=sync.scriptLoad(SCRIPT);sha=s;}
  try{out=sync.evalsha(s,ScriptOutputType.MULTI,new String[]{key},args);}
  catch(io.lettuce.core.RedisNoScriptException e){sha=sync.scriptLoad(SCRIPT);out=sync.evalsha(sha,ScriptOutputType.MULTI,new String[]{key},args);}
  return new long[]{((Number)out.get(0)).longValue(),((Number)out.get(1)).longValue()};
 }
 private synchronized StatefulRedisConnection<String,String> connection(){
  if(connection==null || !connection.isOpen())connection=client.connect();
  return connection;
 }
 private synchronized void dropConnection(){if(connection!=null){try{connection.close();}catch(RuntimeException ignored){}connection=null;}}
 /** Drops in-memory buckets that have been full again for a while, so the fallback map stays bounded by active ids. */
 private void sweep(long now){local.entrySet().removeIf(e->e.getValue().idleFull(rules.get(e.getKey().split(":")[2]),now));}
 int localBuckets(){return local.size();}
 /** Closes the connection and shuts down the limiter's own Redis client. */
 @Override public synchronized void close(){dropConnection();if(client!=null)client.shutdown();}

 /** Hashed identifier for values that should not appear in Redis keys verbatim (usernames). */
 public static String hashed(String value){
  try{var d=MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
   return HexFormat.of().formatHex(d,0,16);}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }

 static final class LocalBucket {
  private double tokens;private long ts;
  LocalBucket(long burst,long now){tokens=burst;ts=now;}
  /** 0 when a token was taken, otherwise ms until one is available. */
  synchronized long take(Rule rule,long now){
   if(now>ts){tokens=Math.min(rule.burst(),tokens+(now-ts)*rule.perMs());ts=now;}
   if(tokens>=1){tokens-=1;return 0;}
   return (long)Math.ceil((1-tokens)/rule.perMs());
  }
  synchronized boolean idleFull(Rule rule,long now){return rule==null || tokens+(now-ts)*rule.perMs()>=rule.burst();}
 }
}
