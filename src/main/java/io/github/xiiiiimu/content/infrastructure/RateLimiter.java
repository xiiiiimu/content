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
 /**
  * Failure backoff (hardening A9): after {@code threshold} consecutive failures for one id, the next attempt must wait
  * {@code baseMs * 2^(failures - threshold)} (at most {@code maxMs}) after the last failure. Failures are forgotten
  * {@code windowMs} after the last one, or at once by {@link #clearFailures}.
  */
 public record Backoff(String name,int threshold,long baseMs,long maxMs,long windowMs){
  public Backoff{
   if(name==null || !name.matches("[a-z0-9-]{1,32}"))throw new IllegalArgumentException("backoff name");
   if(threshold<1 || baseMs<1 || maxMs<baseMs || windowMs<maxMs)throw new IllegalArgumentException("backoff "+name+": threshold>=1, 1<=base<=max<=window");
  }
  long delayMs(long failures){
   if(failures<threshold)return 0;
   return (long)Math.min((double)maxMs,baseMs*Math.pow(2,failures-threshold));
  }
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
 static final String BACKOFF_CHECK="""
  local v=redis.call('HMGET',KEYS[1],'n','last')
  local n=tonumber(v[1]) or 0 local last=tonumber(v[2]) or 0
  local threshold=tonumber(ARGV[1]) local base=tonumber(ARGV[2]) local cap=tonumber(ARGV[3])
  if n<threshold then return 0 end
  local t=redis.call('TIME') local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
  local wait=math.min(cap,base*(2^(n-threshold)))-(now-last)
  if wait>0 then return math.ceil(wait) end
  return 0
  """;
 static final String BACKOFF_FAIL="""
  local t=redis.call('TIME') local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
  redis.call('HINCRBY',KEYS[1],'n',1)
  redis.call('HSET',KEYS[1],'last',string.format('%d',now))
  redis.call('PEXPIRE',KEYS[1],tonumber(ARGV[1]))
  return 1
  """;
 static final long WARN_EVERY_MS=10_000;
 private static final Logger log=LoggerFactory.getLogger(RateLimiter.class);

 private final RedisClient client;private final Map<String,Rule> rules;private final Map<String,Backoff> backoffs;private final long cooldownMs;
 private final MeterRegistry meters;private final LongSupplier clock;
 private final Map<String,LocalBucket> local=new ConcurrentHashMap<>();
 private final Map<String,long[]> localFailures=new ConcurrentHashMap<>();
 private final Map<String,String> shas=new ConcurrentHashMap<>();
 private final AtomicLong redisSkipUntil=new AtomicLong();private final AtomicLong lastWarn=new AtomicLong(Long.MIN_VALUE/2);
 private final AtomicLong decisions=new AtomicLong();
 private volatile StatefulRedisConnection<String,String> connection;

 public RateLimiter(RedisClient client,List<Rule> rules,List<Backoff> backoffs,long cooldownMs,MeterRegistry meters){this(client,rules,backoffs,cooldownMs,meters,System::currentTimeMillis);}
 RateLimiter(RedisClient client,List<Rule> rules,List<Backoff> backoffs,long cooldownMs,MeterRegistry meters,LongSupplier clock){
  var m=new java.util.LinkedHashMap<String,Rule>();for(var r:rules)if(m.put(r.name(),r)!=null)throw new IllegalArgumentException("duplicate rule "+r.name());
  var b=new java.util.LinkedHashMap<String,Backoff>();
  for(var x:backoffs)if(b.put(x.name(),x)!=null || m.containsKey(x.name()))throw new IllegalArgumentException("duplicate name "+x.name());
  this.client=client;this.rules=Map.copyOf(m);this.backoffs=Map.copyOf(b);this.cooldownMs=Math.max(0,cooldownMs);this.meters=meters;this.clock=clock;
 }
 public Backoff backoff(String name){var b=backoffs.get(name);if(b==null)throw new IllegalArgumentException("unknown backoff "+name);return b;}
 public Rule rule(String name){var r=rules.get(name);if(r==null)throw new IllegalArgumentException("unknown rate limit rule "+name);return r;}

 /** Takes one token for {@code id} under {@code ruleName}, or throws {@link RateLimited}. Returns where the decision was made. */
 public Decision acquire(String ruleName,String id){
  Rule rule=rule(ruleName);String key="rl:v1:"+rule.name()+":"+id;
  long now=clock.getAsLong();
  if(redisUsable(now)){
   try{
    List<Object> res=eval(SCRIPT,ScriptOutputType.MULTI,key,Double.toString(rule.perMs()),Long.toString(rule.burst()),Long.toString(rule.ttlMs()));
    if(((Number)res.get(0)).longValue()!=1)reject(rule.name(),((Number)res.get(1)).longValue());
    return Decision.REDIS;
   }catch(RateLimited e){throw e;}
   catch(RuntimeException e){redisFailed(now,e);}
  }
  if(meters!=null)meters.counter("content.ratelimit.fallback","rule",rule.name()).increment();
  long wait=local.computeIfAbsent(key,k->new LocalBucket(rule.burst(),now)).take(rule,now);
  if((decisions.incrementAndGet()&1023)==0)sweep(now);
  if(wait>0)reject(rule.name(),wait);
  return Decision.LOCAL;
 }

 /** Throws {@link RateLimited} while {@code id} is in its backoff period under {@code name}. */
 public void checkBackoff(String name,String id){
  Backoff b=backoff(name);String key="rl:v1:"+b.name()+":"+id;long now=clock.getAsLong();
  if(redisUsable(now)){
   try{
    Long wait=eval(BACKOFF_CHECK,ScriptOutputType.INTEGER,key,Integer.toString(b.threshold()),Long.toString(b.baseMs()),Long.toString(b.maxMs()));
    if(wait!=null && wait>0)reject(b.name(),wait);
    return;
   }catch(RateLimited e){throw e;}
   catch(RuntimeException e){redisFailed(now,e);}
  }
  if(meters!=null)meters.counter("content.ratelimit.fallback","rule",b.name()).increment();
  long[] f=localFailures.get(key);
  if(f==null)return;
  long wait;
  synchronized(f){wait=b.delayMs(f[0])-(now-f[1]);}
  if(wait>0)reject(b.name(),wait);
 }
 /** Counts one failure for {@code id} under {@code name}. */
 public void recordFailure(String name,String id){
  Backoff b=backoff(name);String key="rl:v1:"+b.name()+":"+id;long now=clock.getAsLong();
  if(redisUsable(now)){
   try{eval(BACKOFF_FAIL,ScriptOutputType.INTEGER,key,Long.toString(b.windowMs()));return;}
   catch(RuntimeException e){redisFailed(now,e);}
  }
  long[] f=localFailures.computeIfAbsent(key,k->new long[2]);
  synchronized(f){if(now-f[1]>b.windowMs())f[0]=0;f[0]++;f[1]=now;}
 }
 /** Forgets the failures of {@code id} under {@code name} (after a success). */
 public void clearFailures(String name,String id){
  Backoff b=backoff(name);String key="rl:v1:"+b.name()+":"+id;long now=clock.getAsLong();
  localFailures.remove(key);
  if(redisUsable(now)){
   try{connection().sync().del(key);}catch(RuntimeException e){redisFailed(now,e);}
  }
 }

 private boolean redisUsable(long now){return client!=null && now>=redisSkipUntil.get();}
 private void redisFailed(long now,RuntimeException e){
  redisSkipUntil.set(now+cooldownMs);dropConnection();
  if(meters!=null)meters.counter("content.ratelimit.redis.errors").increment();
  long last=lastWarn.get();
  if(now-last>=WARN_EVERY_MS && lastWarn.compareAndSet(last,now))
   log.warn("Rate limit Redis unavailable, deciding with per-instance buckets for {} ms: {}",cooldownMs,e.toString());
 }
 private void reject(String name,long waitMs){
  if(meters!=null)meters.counter("content.ratelimit.rejected","rule",name).increment();
  throw new RateLimited(name,(waitMs+999)/1000);
 }
 private <T> T eval(String script,ScriptOutputType type,String key,String... args){
  var sync=connection().sync();
  String sha=shas.get(script);
  if(sha==null){sha=sync.scriptLoad(script);shas.put(script,sha);}
  try{return sync.evalsha(sha,type,new String[]{key},args);}
  catch(io.lettuce.core.RedisNoScriptException e){sha=sync.scriptLoad(script);shas.put(script,sha);return sync.evalsha(sha,type,new String[]{key},args);}
 }
 private synchronized StatefulRedisConnection<String,String> connection(){
  if(connection==null || !connection.isOpen())connection=client.connect();
  return connection;
 }
 private synchronized void dropConnection(){if(connection!=null){try{connection.close();}catch(RuntimeException ignored){}connection=null;}}
 /** Drops in-memory buckets that have been full again for a while, so the fallback map stays bounded by active ids. */
 private void sweep(long now){
  local.entrySet().removeIf(e->e.getValue().idleFull(rules.get(e.getKey().split(":")[2]),now));
  localFailures.entrySet().removeIf(e->{var b=backoffs.get(e.getKey().split(":")[2]);synchronized(e.getValue()){return b==null || now-e.getValue()[1]>b.windowMs();}});
 }
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
