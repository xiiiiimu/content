package io.github.xiiiiimu.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.*;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** All ingress keys deliberately share one slot; Redis never authorizes a final MySQL order. */
public final class SaleEntryRedis {
 public static final String BASE="sale:{entry-v2}:",CONTROL=BASE+"control",STATE=BASE+"state",STREAM=BASE+"stream",GROUP="sale-persist-v2";
 private final FeedRedis redis;private final ObjectMapper json=new ObjectMapper();
 private final java.util.concurrent.ConcurrentHashMap<String,String> claimPositions=new java.util.concurrent.ConcurrentHashMap<>();
 public SaleEntryRedis(FeedRedis redis){this.redis=redis;}
 public record Request(String reservationId,long campaignId,long postId,long merchantId,long userId,String clientKey,
  long priceCents,String state,String rejectReason,Long orderId,long createdAtMs){}
 public record Result(String status,String detail,Request request){}
 public RedisCommands<String,String> commands(){return redis.connection().sync();}
 /** Hardening C3 (option B): clientKey identity scoped to the campaign, {@code k:<user>:<campaign>:<sha256(key)>}. */
 public static String client(long user,long campaign,String key){return "k:"+user+":"+campaign+":"+sha(key);}
 static String sha(String key){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
 }
 public static String client(long user,String key){
  try{return "k:"+user+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
 }
 public String encode(Request r){try{return json.writeValueAsString(r);}catch(Exception e){throw new IllegalStateException(e);}}
 public Request decode(String value){try{return json.readValue(value,Request.class);}catch(Exception e){throw new IllegalStateException("Invalid sale cache payload",e);}}
 public record Trusted(Request request,long generation,String raw){}
 public Trusted trusted(String raw){try{var n=json.readTree(raw);return new Trusted(json.treeToValue(n.get("request"),Request.class),n.get("generation").asLong(),raw);}catch(Exception e){return null;}}
 public Map<String,Trusted> trusted(List<String> ids){
  Map<String,Trusted> result=new LinkedHashMap<>();if(ids.isEmpty())return result;
  var values=commands().hmget(STATE,ids.stream().map(id->"t:"+id).toArray(String[]::new));
  for(int i=0;i<ids.size();i++)if(values.get(i).hasValue())result.put(ids.get(i),trusted(values.get(i).getValue()));
  return result;
 }
 public String body(Map<String,String> body){try{return json.writeValueAsString(body);}catch(Exception e){throw new IllegalStateException(e);}}
 private static final String RESERVE="""
 local ct=redis.call('TYPE',KEYS[1]).ok
 local st=redis.call('TYPE',KEYS[2]).ok
 local xt=redis.call('TYPE',KEYS[3]).ok
 if ct~='hash' or st~='hash' or (xt~='stream' and xt~='none') then return {'UNAVAILABLE','CACHE_TYPE'} end
 if redis.call('HGET',KEYS[1],'ready')~='1' or redis.call('HGET',KEYS[1],'manual')=='1' or redis.call('HGET',KEYS[1],'repair')=='1' then return {'UNAVAILABLE','GATEWAY_PAUSED'} end
 if redis.call('HGET',KEYS[1],'quarantine_pause')=='1' then return {'UNAVAILABLE','GATEWAY_PAUSED'} end
 if redis.call('HGET',KEYS[1],'q:'..ARGV[1])=='1' then return {'UNAVAILABLE','GATEWAY_PAUSED'} end
 local instance=string.match(redis.call('INFO','server'),'run_id:([a-z0-9]+)')
 if redis.call('HGET',KEYS[1],'run_id')~=instance then return {'UNAVAILABLE','CACHE_INSTANCE_CHANGED'} end
 local gen=redis.call('HGET',KEYS[1],'generation')
 if redis.call('HGET',KEYS[2],'generation')~=gen then return {'UNAVAILABLE','CACHE_GENERATION'} end
 -- C3 option B: clientKey identity is per campaign (ARGV[4]); ARGV[6] is the pre-C3 global field, consulted only until
 -- the in-place migration has set fmt=3 (keeps the old answer for requests made before the upgrade).
 local prior=redis.call('HGET',KEYS[2],ARGV[4])
 if not prior and redis.call('HGET',KEYS[1],'fmt')~='3' then prior=redis.call('HGET',KEYS[2],ARGV[6]) end
 if prior then
  local value=redis.call('HGET',KEYS[2],'r:'..prior)
  if not value then return {'UNAVAILABLE','REQUEST_CACHE_MISSING'} end
  local priorRequest=cjson.decode(value)
  if redis.call('HGET',KEYS[1],'q:'..priorRequest.campaignId)=='1' then return {'UNAVAILABLE','GATEWAY_PAUSED'} end
  return {'EXISTING',value}
 end
 local p='c:'..ARGV[1]..':'
 if redis.call('HGET',KEYS[2],p..'known')~='1' then return {'REJECT','CAMPAIGN_NOT_FOUND'} end
 local entry=redis.call('HGET',KEYS[2],p..'entry')
 if not entry then return {'UNAVAILABLE','CAMPAIGN_CACHE_MISSING'} end
 if entry~='1' then return {'REJECT','CAMPAIGN_CLOSED'} end
 local post=redis.call('HGET',KEYS[2],p..'post')
 local merchant=redis.call('HGET',KEYS[2],p..'merchant')
 local price=redis.call('HGET',KEYS[2],p..'price')
 local start=tonumber(redis.call('HGET',KEYS[2],p..'start'))
 local finish=tonumber(redis.call('HGET',KEYS[2],p..'end'))
 local stock=tonumber(redis.call('HGET',KEYS[2],p..'stock'))
 if not post or not merchant or not price or not start or not finish or not stock or stock<0 or stock%1~=0 then return {'UNAVAILABLE','CAMPAIGN_CACHE_INVALID'} end
 local t=redis.call('TIME');local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
 if now<start then return {'REJECT','NOT_STARTED'} end
 if now>finish then return {'REJECT','ENDED'} end
 if redis.call('HGET',KEYS[2],'b:'..merchant..':'..ARGV[2])=='1' then return {'REJECT','BLACKLISTED'} end
 local owner=redis.call('HGET',KEYS[2],'o:'..ARGV[2]..':'..post)
 if owner=='GIVEAWAY' then return {'REJECT','ALREADY_OWNED'} end
 local held=owner or redis.call('HGET',KEYS[2],'u:'..ARGV[2]..':'..post)
 if held then
  local value=redis.call('HGET',KEYS[2],'r:'..held)
  if not value then return {'UNAVAILABLE','OWNER_CACHE_MISSING'} end
  return {'EXISTING',value}
 end
 if stock==0 then return {'REJECT','SOLD_OUT'} end
 local r={reservationId=ARGV[3],campaignId=ARGV[1],postId=post,merchantId=merchant,userId=ARGV[2],clientKey=ARGV[5],priceCents=price,state='PROCESSING',createdAtMs=string.format('%.0f',now)}
 local value=cjson.encode(r)
 local id=redis.pcall('XADD',KEYS[3],'*','request',value,'generation',gen)
 if type(id)=='table' and id.err then return {'UNAVAILABLE','STREAM_WRITE_FAILED'} end
 redis.call('HSET',KEYS[2],'r:'..ARGV[3],value,ARGV[4],ARGV[3],'u:'..ARGV[2]..':'..post,ARGV[3])
 redis.call('HINCRBY',KEYS[2],p..'stock',-1)
 -- This is the completion proof, written only AFTER the reservation debit.
 redis.call('HSET',KEYS[2],'t:'..id,cjson.encode({request=r,generation=gen}))
 return {'OK',value}
 """;
 public Result reserve(long campaign,long user,String reservation,String key){
  try(var phase=SaleLatency.phase("redis.reserve.lua")){
   List<?> result=commands().eval(RESERVE,ScriptOutputType.MULTI,new String[]{CONTROL,STATE,STREAM},
    Long.toString(campaign),Long.toString(user),reservation,client(user,campaign,key),key,client(user,key));
   String status=String.valueOf(result.get(0)),value=String.valueOf(result.get(1));
   if(status.equals("OK")||status.equals("EXISTING"))return new Result(status,null,decode(value));
   if(status.equals("UNAVAILABLE")&&!value.equals("GATEWAY_PAUSED")){try{commands().hset(CONTROL,"repair","1");}catch(RedisException ignored){}}
   return new Result(status,value,null);
  }catch(RedisException failure){
   try{commands().hset(CONTROL,"repair","1");}catch(RedisException ignored){/* Reconnect/recovery also verifies run_id. */}
   throw failure;
  }
 }
 public Request request(String id){String value=commands().hget(STATE,"r:"+id);return value==null?null:decode(value);}
 public boolean quarantineReleased(String id){return "1".equals(commands().hget(STATE,"released:"+id));}
 public Long remaining(long campaign){String value=commands().hget(STATE,"c:"+campaign+":stock");return value==null?null:Long.valueOf(value);}
 public void manualPause(boolean pause){commands().hset(CONTROL,"manual",pause?"1":"0");}
 public boolean paused(){return !"1".equals(commands().hget(CONTROL,"ready"))||"1".equals(commands().hget(CONTROL,"manual"));}
 public String runId(){return Arrays.stream(commands().info("server").split("\\r?\\n")).filter(l->l.startsWith("run_id:")).findFirst().orElseThrow().substring(7).trim();}
 public boolean ready(long generation){return "1".equals(commands().hget(CONTROL,"ready"))&&Long.toString(generation).equals(commands().hget(CONTROL,"generation"))&&!"1".equals(commands().hget(CONTROL,"repair"));}
 private static final String FENCE="""
 local function older(a,b) return #a<#b or (#a==#b and a<b) end
 local t=redis.call('TYPE',KEYS[1]).ok
 if t~='none' and t~='hash' then redis.call('RENAME',KEYS[1],KEYS[2]) end
 local previous=redis.call('HGET',KEYS[1],'generation')
 if previous and older(ARGV[1],previous) then return 0 end
 redis.call('HSET',KEYS[1],'generation',ARGV[1],'ready','0');return 1
 """;
 public void fence(long generation){
  if(((Number)commands().eval(FENCE,ScriptOutputType.INTEGER,new String[]{CONTROL,BASE+"quarantine-control:"+generation},Long.toString(generation))).intValue()!=1)throw new IllegalStateException("Stale recovery fence");
  String type=commands().type(STREAM);
  if(!type.equals("none")&&!type.equals("stream"))commands().rename(STREAM,BASE+"quarantine-stream:"+generation);
  ensureGroup();
 }
 public void ensureGroup(){
  try{commands().xgroupCreate(XReadArgs.StreamOffset.from(STREAM,"0-0"),GROUP,XGroupCreateArgs.Builder.mkstream());}
  catch(RedisCommandExecutionException e){if(!e.getMessage().contains("BUSYGROUP"))throw e;}
 }
 public List<StreamMessage<String,String>> read(String consumer,int limit,long idleMillis){
  int n=Math.max(1,Math.min(limit,200));
  var claimed=commands().xautoclaim(STREAM,new XAutoClaimArgs<String>().consumer(Consumer.from(GROUP,consumer)).minIdleTime(idleMillis).startId(claimPositions.getOrDefault(consumer,"0-0")).count(n));
  claimPositions.put(consumer,claimed.getId());
  var result=new ArrayList<StreamMessage<String,String>>(claimed.getMessages());
  if(result.size()<n)result.addAll(commands().xreadgroup(Consumer.from(GROUP,consumer),XReadArgs.Builder.count(n-result.size()),XReadArgs.StreamOffset.lastConsumed(STREAM)));
  return result;
 }
 public void acknowledge(String id){commands().xack(STREAM,GROUP,id);}
 public long pending(){return commands().xpending(STREAM,GROUP).getCount();}
 public void acknowledge(List<String> ids){if(!ids.isEmpty())commands().xack(STREAM,GROUP,ids.toArray(String[]::new));}
 private static final String RELEASE_QUARANTINE="""
 if redis.call('TYPE',KEYS[1]).ok~='hash' or redis.call('TYPE',KEYS[2]).ok~='hash' then return 0 end
 if redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[2],'generation')~=ARGV[1] then return 0 end
 if redis.call('HGET',KEYS[1],'run_id')~=string.match(redis.call('INFO','server'),'run_id:([a-z0-9]+)') then return 0 end
 if redis.call('HGET',KEYS[2],'t:'..ARGV[2])~=ARGV[3] then return 0 end
 local original=cjson.decode(ARGV[3]).request;local raw=redis.call('HGET',KEYS[2],'r:'..original.reservationId)
 if not raw then return 0 end
 local r=cjson.decode(raw)
 for _,f in ipairs({'reservationId','campaignId','postId','merchantId','userId','clientKey','priceCents','createdAtMs'}) do
  if tostring(r[f])~=tostring(original[f]) then return 0 end
 end
 if redis.call('HGET',KEYS[2],'released:'..r.reservationId)=='1' and r.state=='REJECTED' and r.rejectReason=='INGRESS_QUARANTINED' then return 2 end
 if r.state~='PROCESSING' or (r.orderId and r.orderId~=cjson.null) then return 0 end
 local stock=tonumber(redis.call('HGET',KEYS[2],'c:'..r.campaignId..':stock'))
 if not stock or stock<0 or stock%1~=0 then return 0 end
 r.state='REJECTED';r.rejectReason='INGRESS_QUARANTINED'
 redis.call('HSET',KEYS[2],'r:'..r.reservationId,cjson.encode(r),'released:'..r.reservationId,'APPLYING')
 redis.call('HINCRBY',KEYS[2],'c:'..r.campaignId..':stock',1)
 local u='u:'..r.userId..':'..r.postId
 if redis.call('HGET',KEYS[2],u)==r.reservationId then redis.call('HDEL',KEYS[2],u) end
 if ARGV[4]~='' then
  local canonical=cjson.decode(ARGV[4])
  redis.call('HSETNX',KEYS[2],'r:'..canonical.reservationId,ARGV[4])
  local current=redis.call('HGET',KEYS[2],ARGV[5])
  if not current or current==r.reservationId then redis.call('HSET',KEYS[2],ARGV[5],canonical.reservationId) end
  if canonical.state=='PROCESSING' then redis.call('HSETNX',KEYS[2],'u:'..canonical.userId..':'..canonical.postId,canonical.reservationId) end
 end
 redis.call('HSET',KEYS[2],'released:'..r.reservationId,'1')
 return 1
 """;
 public boolean releaseQuarantine(SaleBridgeMapper.Quarantine q,Request canonical){
  Long n=commands().eval(RELEASE_QUARANTINE,ScriptOutputType.INTEGER,new String[]{CONTROL,STATE},
   Long.toString(q.trustedGeneration()),q.streamId(),q.trustedJson(),canonical==null?"":encode(canonical),
   canonical==null?"":client(canonical.userId(),canonical.campaignId(),canonical.clientKey()));return n==1||n==2;
 }
 public void quarantineGates(long generation,long revision,boolean unknown,List<Long> campaigns){
  String joined=String.join(",",campaigns.stream().map(String::valueOf).toList());
  Long n=commands().eval("if redis.call('HGET',KEYS[1],'generation')~=ARGV[3] then return 0 end;local rev=redis.call('HGET',KEYS[1],'quarantine_revision') or '0';if #rev>#ARGV[4] or (#rev==#ARGV[4] and rev>ARGV[4]) then return 2 end;local old=redis.call('HGET',KEYS[1],'quarantine_campaigns') or '';for id in string.gmatch(old,'[^,]+') do redis.call('HDEL',KEYS[1],'q:'..id) end;for id in string.gmatch(ARGV[2],'[^,]+') do redis.call('HSET',KEYS[1],'q:'..id,'1') end;redis.call('HSET',KEYS[1],'quarantine_campaigns',ARGV[2],'quarantine_pause',ARGV[1],'quarantine_revision',ARGV[4]);return 1",ScriptOutputType.INTEGER,new String[]{CONTROL},unknown?"1":"0",joined,Long.toString(generation),Long.toString(revision));
  if(n==0)throw new IllegalStateException("Quarantine gate generation changed");
 }
 public String staging(long generation){String key=BASE+"stage:"+generation;commands().hset(key,"generation",Long.toString(generation));return key;}
 public void put(String key,Map<String,String> fields){if(!fields.isEmpty())commands().hset(key,fields);}
 public void install(String stage,long generation){
  Long ok=commands().eval("if redis.call('HGET',KEYS[1],'generation')~=ARGV[1] then return 0 end; redis.call('RENAME',KEYS[2],KEYS[3]);return 1",ScriptOutputType.INTEGER,new String[]{CONTROL,stage,STATE},Long.toString(generation));
  if(ok!=1)throw new IllegalStateException("Stale recovery install");
 }
 public void publish(long generation,String runId){
  Long ok=commands().eval("if redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[2],'generation')~=ARGV[1] or string.match(redis.call('INFO','server'),'run_id:([a-z0-9]+)')~=ARGV[2] then return 0 end;redis.call('HSET',KEYS[1],'ready','1','repair','0','run_id',ARGV[2],'fmt','3');return 1",ScriptOutputType.INTEGER,new String[]{CONTROL,STATE},Long.toString(generation),runId);
  if(ok!=1)throw new IllegalStateException("Stale recovery publish");
 }
 public List<StreamMessage<String,String>> legacy(long campaign,String cursor){
  return commands().xrange("sale:{"+campaign+"}:pending",Range.from(Range.Boundary.excluding(cursor),Range.Boundary.unbounded()),Limit.create(0,200));
 }
 public static Map<String,String> campaignFields(SaleMapper.Campaign c,int reserved,boolean published){
  if(c.stockCap()-c.soldCount()-reserved<0)throw new IllegalStateException("Durable reservations exceed available inventory");
  String p="c:"+c.id()+":";Map<String,String> m=new LinkedHashMap<>();
  m.put(p+"known","1");m.put(p+"entry","OPEN".equals(c.state())&&published?"1":"0");
  m.put(p+"post",Long.toString(c.postId()));m.put(p+"merchant",Long.toString(c.merchantId()));m.put(p+"price",Long.toString(c.priceCents()));
  m.put(p+"version",c.frozenVersionId()==null?"legacy":Long.toString(c.frozenVersionId()));
  m.put(p+"start",Long.toString(c.startMs()));m.put(p+"end",Long.toString(c.endMs()));m.put(p+"stock",Integer.toString(Math.max(0,c.stockCap()-c.soldCount()-reserved)));
  return m;
 }
 public void restoreRequest(String stage,Request r){
  Map<String,String> m=new LinkedHashMap<>();m.put("r:"+r.reservationId(),encode(r));m.put(client(r.userId(),r.campaignId(),r.clientKey()),r.reservationId());
  if(r.state().equals("PROCESSING"))m.put("u:"+r.userId()+":"+r.postId(),r.reservationId());
  if(r.orderId()!=null)m.put("o:"+r.userId()+":"+r.postId(),r.reservationId());put(stage,m);
 }
 private static final String APPLY="""
 if redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[2],'generation')~=ARGV[1] then return 0 end
 local version='v:'..ARGV[2]
 local old=redis.call('HGET',KEYS[2],version)
 if old and (#old>#ARGV[3] or (#old==#ARGV[3] and old>ARGV[3])) then return 1 end
 -- C3: nothing is written back for an archived campaign (tombstone set by the archiver's first delete), and ownership is
 -- kept only for posts with an active campaign (pc:<post>, written by the campaign job and recovery) once fmt=3.
 if ARGV[9]~='' and redis.call('HGET',KEYS[2],'x:'..ARGV[9]) then return 1 end
 if ARGV[10]~='' and redis.call('HGET',KEYS[1],'fmt')=='3' and not redis.call('HGET',KEYS[2],'pc:'..ARGV[10]) then return 1 end
 local fields=cjson.decode(ARGV[4])
 for k,v in pairs(fields) do
  if v=='' then redis.call('HDEL',KEYS[2],k)
  elseif string.sub(k,-6)==':stock' then redis.call('HSETNX',KEYS[2],k,v)
  -- C3: a campaign job's owner snapshot only fills gaps; OWNER/REQUEST jobs stay authoritative for o: and r:.
  elseif string.sub(ARGV[2],1,9)=='CAMPAIGN:' and (string.sub(k,1,2)=='o:' or string.sub(k,1,2)=='r:') then redis.call('HSETNX',KEYS[2],k,v)
  else redis.call('HSET',KEYS[2],k,v) end
 end
 if ARGV[5]~='' then
  local r=cjson.decode(ARGV[5]);local previous=redis.call('HGET',KEYS[2],'r:'..r.reservationId)
  if previous then
   local prev=cjson.decode(previous)
   if prev.state=='PROCESSING' and r.state=='REJECTED' then redis.call('HINCRBY',KEYS[2],'c:'..r.campaignId..':stock',1) end
  end
  redis.call('HSET',KEYS[2],'r:'..r.reservationId,ARGV[5])
  if r.state=='SUCCEEDED' and redis.call('HGET',KEYS[2],'o:'..r.userId..':'..r.postId)~='GIVEAWAY' then
   redis.call('HSET',KEYS[2],'o:'..r.userId..':'..r.postId,r.reservationId)
  end
  local guard='u:'..r.userId..':'..r.postId
  if r.state~='PROCESSING' and redis.call('HGET',KEYS[2],guard)==r.reservationId then redis.call('HDEL',KEYS[2],guard) end
 end
 if ARGV[6]~='' and redis.call('HSETNX',KEYS[2],'refund:'..ARGV[6],'1')==1 and ARGV[7]=='1' then
  redis.call('HINCRBY',KEYS[2],'c:'..ARGV[8]..':stock',1)
 end
 redis.call('HSET',KEYS[2],version,ARGV[3]);return 1
 """;
 public boolean apply(long generation,SaleIngressMapper.CacheJob job,Map<String,String> fields,Request request,Long refund,boolean restore,long campaign){
  return apply(generation,job,fields,request,refund,restore,campaign,null,null);
 }
 /** @param scope campaign whose tombstone suppresses the write (null: none); @param ownerPost post whose ownership this job writes (null: none). */
 public boolean apply(long generation,SaleIngressMapper.CacheJob job,Map<String,String> fields,Request request,Long refund,boolean restore,long campaign,Long scope,Long ownerPost){
  try{
   Long result=commands().eval(APPLY,ScriptOutputType.INTEGER,new String[]{CONTROL,STATE},Long.toString(generation),job.kind()+":"+job.entityKey(),Long.toString(job.revision()),json.writeValueAsString(fields),request==null?"":encode(request),refund==null?"":Long.toString(refund),restore?"1":"0",Long.toString(campaign),
    scope==null?"":Long.toString(scope),ownerPost==null?"":Long.toString(ownerPost));
   return result==1;
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
 }

 // ---- Hardening C2 R-STREAM: retention of acknowledged Stream entries ----
 /**
  * Read-only: up to {@code n} Stream ids, oldest first, that every consumer group has been delivered and acknowledged and that
  * are older than {@code olderThanMs}. Bound per group: ids up to its last-delivered-id and strictly before its oldest pending id
  * (so no unacknowledged entry, and nothing XAUTOCLAIM could still take over, is returned). No group means nothing was consumed.
  */
 private static final String ACKED_BEFORE="""
 if redis.call('TYPE',KEYS[1]).ok~='stream' then return {} end
 local function parse(id) local ms,seq=string.match(id,'^(%d+)%-(%d+)$') return tonumber(ms),tonumber(seq) end
 local function less(a,b) local am,as=parse(a) local bm,bs=parse(b) if am~=bm then return am<bm end return as<bs end
 local groups=redis.call('XINFO','GROUPS',KEYS[1])
 if #groups==0 then return {} end
 local delivered=nil
 local pending=nil
 for _,g in ipairs(groups) do
  local name,last
  for i=1,#g,2 do if g[i]=='name' then name=g[i+1] elseif g[i]=='last-delivered-id' then last=g[i+1] end end
  if delivered==nil or less(last,delivered) then delivered=last end
  local p=redis.call('XPENDING',KEYS[1],name)
  if tonumber(p[1])>0 and (pending==nil or less(p[2],pending)) then pending=p[2] end
 end
 local out={}
 for _,e in ipairs(redis.call('XRANGE',KEYS[1],'-','('..ARGV[1]..'-0','COUNT',ARGV[2])) do
  local id=e[1]
  if less(delivered,id) or (pending and not less(id,pending)) then break end
  out[#out+1]=id
 end
 return out
 """;
 /** ARGV[1]=k, ARGV[2..k+1]=Stream ids to delete, the rest = ids whose t: copy must stay. Returns {entries deleted, t: fields deleted}. */
 private static final String DROP_ACKED="""
 local n=tonumber(ARGV[1])
 local keep={}
 for i=n+2,#ARGV do keep[ARGV[i]]=true end
 local ids={}
 local fields={}
 for i=2,n+1 do ids[#ids+1]=ARGV[i]; if not keep[ARGV[i]] then fields[#fields+1]='t:'..ARGV[i] end end
 local d=0
 local t=0
 if #ids>0 then d=redis.call('XDEL',KEYS[1],unpack(ids)) end
 if #fields>0 then t=redis.call('HDEL',KEYS[2],unpack(fields)) end
 return {d,t}
 """;
 public List<String> acknowledgedBefore(long olderThanMs,int n){
  if(olderThanMs<0 || n<1)throw new IllegalArgumentException("Invalid Stream retention bounds");
  List<String> ids=commands().eval(ACKED_BEFORE,ScriptOutputType.MULTI,new String[]{STREAM},Long.toString(olderThanMs),Integer.toString(n));
  return ids==null?List.of():ids;
 }
 /** Deletes acknowledged entries returned by {@link #acknowledgedBefore} and their trusted copies, except copies in {@code keepTrusted}. */
 public long[] dropAcknowledged(List<String> ids,Collection<String> keepTrusted){
  if(ids.isEmpty())return new long[2];
  List<String> args=new ArrayList<>();args.add(Integer.toString(ids.size()));args.addAll(ids);args.addAll(keepTrusted);
  List<Object> r=commands().eval(DROP_ACKED,ScriptOutputType.MULTI,new String[]{STREAM,STATE},args.toArray(String[]::new));
  return new long[]{((Number)r.get(0)).longValue(),((Number)r.get(1)).longValue()};
 }
 /** Generations of recovery staging hashes that currently exist ({@code stage:<generation>}). */
 public List<Long> stageGenerations(){
  List<Long> out=new ArrayList<>();ScanCursor cursor=ScanCursor.INITIAL;
  do{
   var page=commands().scan(cursor,ScanArgs.Builder.matches(BASE+"stage:*").limit(100));
   for(String key:page.getKeys()){String g=key.substring((BASE+"stage:").length());if(g.matches("[0-9]{1,18}"))out.add(Long.parseLong(g));}
   cursor=page;
  }while(!cursor.isFinished());
  return out;
 }
 public long dropStage(long generation){return commands().unlink(BASE+"stage:"+generation);}
 // ---- Hardening C3: archive, post sweep, in-place clientKey migration ----
 private static final String FENCE_OK="if redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[2],'generation')~=ARGV[1] then return -1 end\n";
 private static final String ARCHIVE=FENCE_OK+"""
 redis.call('HSET',KEYS[2],'x:'..ARGV[2],'1')
 local n=0
 for i=3,#ARGV do n=n+redis.call('HDEL',KEYS[2],ARGV[i]) end
 return n
 """;
 /** Deletes one batch of an archived campaign's fields and sets its tombstone; -1 when the generation moved (recovery). */
 public long archiveFields(long generation,long campaign,List<String> fields){
  List<String> args=new ArrayList<>(fields.size()+2);args.add(Long.toString(generation));args.add(Long.toString(campaign));args.addAll(fields);
  return commands().eval(ARCHIVE,ScriptOutputType.INTEGER,new String[]{CONTROL,STATE},args.toArray(String[]::new));
 }
 private static final String POST_SWEEP=FENCE_OK+"""
 local key='pc:'..ARGV[2]
 local drop={}
 for id in string.gmatch(ARGV[3],'[^,]+') do drop[id]=true end
 local keep={}
 for id in string.gmatch(redis.call('HGET',KEYS[2],key) or '','[^,]+') do if not drop[id] then table.insert(keep,id) end end
 if #keep>0 then redis.call('HSET',KEYS[2],key,table.concat(keep,',')) return 0 end
 redis.call('HDEL',KEYS[2],key)
 local n=0
 for i=4,#ARGV do n=n+redis.call('HDEL',KEYS[2],ARGV[i]) end
 return n+1
 """;
 /**
  * Removes the archived campaigns from {@code pc:<post>}; only when no campaign is left does it delete the post's ownership
  * fields. Returns 0 when an active campaign keeps them, -1 when the generation moved.
  */
 public long sweepPost(long generation,long post,List<Long> archived,List<String> ownerFields){
  List<String> args=new ArrayList<>(ownerFields.size()+3);args.add(Long.toString(generation));args.add(Long.toString(post));
  args.add(String.join(",",archived.stream().map(String::valueOf).toList()));args.addAll(ownerFields);
  return commands().eval(POST_SWEEP,ScriptOutputType.INTEGER,new String[]{CONTROL,STATE},args.toArray(String[]::new));
 }
 private static final String MIGRATE=FENCE_OK+"""
 local n=0
 local i=2
 while i+2<=#ARGV do
  local old,new,value=ARGV[i],ARGV[i+1],ARGV[i+2]
  if new~='' then redis.call('HSETNX',KEYS[2],new,value) end
  n=n+redis.call('HDEL',KEYS[2],old)
  i=i+3
 end
 return n
 """;
 /** Triples (old global field, new field or "" when the campaign is archived, value); -1 when the generation moved. */
 public long migrateKeys(long generation,List<String[]> triples){
  List<String> args=new ArrayList<>(triples.size()*3+1);args.add(Long.toString(generation));
  for(var t:triples){args.add(t[0]);args.add(t[1]);args.add(t[2]);}
  return commands().eval(MIGRATE,ScriptOutputType.INTEGER,new String[]{CONTROL,STATE},args.toArray(String[]::new));
 }
 public long putFenced(long generation,Map<String,String> fields){
  if(fields.isEmpty())return 0;
  List<String> args=new ArrayList<>();args.add(Long.toString(generation));fields.forEach((k,v)->{args.add(k);args.add(v);});
  return commands().eval(FENCE_OK+"for i=2,#ARGV,2 do redis.call('HSET',KEYS[2],ARGV[i],ARGV[i+1]) end return 1",ScriptOutputType.INTEGER,new String[]{CONTROL,STATE},args.toArray(String[]::new));
 }
 public boolean markFormat(long generation){
  Long n=commands().eval(FENCE_OK+"redis.call('HSET',KEYS[1],'fmt','3') return 1",ScriptOutputType.INTEGER,new String[]{CONTROL,STATE},Long.toString(generation));
  return n!=null&&n==1;
 }
 public String control(String field){return commands().hget(CONTROL,field);}
 public void setControl(String field,String value){commands().hset(CONTROL,field,value);}
 /**
  * Campaign ids of Stream entries not yet acknowledged (pending, or never delivered to the persist group), or empty when
  * more than {@code limit} such entries exist (the caller then treats every campaign as possibly unpersisted). An entry is
  * ACKed only after its request is committed to MySQL or quarantined.
  */
 public Optional<Set<Long>> unpersistedCampaigns(int limit){
  var entries=unpersisted(limit);if(entries.isEmpty())return Optional.empty();
  Set<Long> campaigns=new HashSet<>();
  for(var m:entries.get()){
   try{campaigns.add(json.readTree(m.getBody().get("request")).get("campaignId").asLong());}
   catch(Exception e){return Optional.empty();}   // unreadable entry: be conservative
  }
  return Optional.of(campaigns);
 }
 /** Millisecond time of the oldest unacknowledged Stream entry (Long.MAX_VALUE when none), empty above {@code limit}. */
 public Optional<Long> oldestUnpersistedMs(int limit){
  var entries=unpersisted(limit);if(entries.isEmpty())return Optional.empty();
  return Optional.of(entries.get().stream().mapToLong(m->Long.parseLong(m.getId().split("-")[0])).min().orElse(Long.MAX_VALUE));
 }
 private Optional<List<StreamMessage<String,String>>> unpersisted(int limit){
  var c=commands();
  if(!"stream".equals(c.type(STREAM)))return Optional.of(List.of());
  String lastDelivered=null;
  for(Object g:c.xinfoGroups(STREAM)){
   var info=(List<?>)g;String name=null,last=null;
   for(int i=0;i+1<info.size();i+=2){String k=String.valueOf(info.get(i));if(k.equals("name"))name=String.valueOf(info.get(i+1));if(k.equals("last-delivered-id"))last=String.valueOf(info.get(i+1));}
   if(GROUP.equals(name))lastDelivered=last;
  }
  List<StreamMessage<String,String>> messages=new ArrayList<>();
  if(lastDelivered==null)messages.addAll(c.xrange(STREAM,io.lettuce.core.Range.create("-","+"),io.lettuce.core.Limit.from(limit+1)));   // no group yet: nothing persisted
  else{
   var pending=c.xpending(STREAM,GROUP,io.lettuce.core.Range.create("-","+"),io.lettuce.core.Limit.from(limit+1));
   if(pending.size()>limit)return Optional.empty();
   for(var p:pending)messages.addAll(c.xrange(STREAM,io.lettuce.core.Range.create(p.getId(),p.getId())));
   messages.addAll(c.xrange(STREAM,io.lettuce.core.Range.from(io.lettuce.core.Range.Boundary.excluding(lastDelivered),io.lettuce.core.Range.Boundary.unbounded()),io.lettuce.core.Limit.from(limit+1)));
  }
  if(messages.size()>limit)return Optional.empty();
  return Optional.of(messages);
 }
}
