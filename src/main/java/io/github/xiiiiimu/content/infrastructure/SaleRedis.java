package io.github.xiiiiimu.content.infrastructure;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;

/** Redis Lua entry for limited-sale: hash-tagged by campaign id for cluster slot affinity. */
public final class SaleRedis {
 private final FeedRedis redis;
 public SaleRedis(FeedRedis redis){this.redis=redis;}

 private static String stock(long c){return "sale:{"+c+"}:stock";}
 private static String meta(long c){return "sale:{"+c+"}:meta";}
 private static String userKey(long c,long u){return "sale:{"+c+"}:u:"+u;}
 private static String resKey(long c,String r){return "sale:{"+c+"}:r:"+r;}
 private static String pending(long c){return "sale:{"+c+"}:pending";}
 private static final String GATEWAY="sale:gateway:paused";

 private static final String RESERVE_LUA = """
  if redis.call('EXISTS', 'sale:gateway:paused') == 1 then return {'REJECT','GATEWAY_PAUSED'} end
  local meta = redis.call('HGETALL', KEYS[2])
  if #meta == 0 then return {'REJECT','CAMPAIGN_MISSING'} end
  local map = {}
  for i=1,#meta,2 do map[meta[i]] = meta[i+1] end
  if map['entry'] ~= '1' then return {'REJECT','NOT_OPEN'} end
  local now = tonumber(ARGV[1])
  local startMs = tonumber(map['start'])
  local endMs = tonumber(map['end'])
  if now < startMs then return {'REJECT','NOT_STARTED'} end
  if now > endMs then return {'REJECT','ENDED'} end
  local existing = redis.call('GET', KEYS[3])
  if existing then
    local st = redis.call('HGET', 'sale:{'..ARGV[5]..'}:r:'..existing, 'state')
    if st == 'RELEASED' or st == 'REJECTED' then
      redis.call('DEL', KEYS[3])
    else
      return {'EXISTING', existing, st or 'PROCESSING'}
    end
  end
  local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
  if stock == nil or stock <= 0 then return {'REJECT','SOLD_OUT'} end
  redis.call('DECR', KEYS[1])
  local rid = ARGV[3]
  redis.call('SET', KEYS[3], rid)
  redis.call('HSET', KEYS[4], 'state', 'PROCESSING', 'user', ARGV[2], 'client', ARGV[4], 'campaign', ARGV[5], 'price', map['price'], 'post', map['post'], 'merchant', map['merchant'])
  redis.call('XADD', KEYS[5], '*', 'reservationId', rid, 'userId', ARGV[2], 'clientKey', ARGV[4])
  return {'OK', rid, 'PROCESSING'}
  """;

 private static final String RELEASE_LUA = """
  local st = redis.call('HGET', KEYS[1], 'state')
  if st == false or st == nil then return {'MISSING'} end
  if st == 'RELEASED' or st == 'SUCCEEDED' then return {'DUP', st} end
  redis.call('HSET', KEYS[1], 'state', 'RELEASED', 'reason', ARGV[1] or 'RELEASE')
  redis.call('INCR', KEYS[2])
  local user = redis.call('HGET', KEYS[1], 'user')
  if user then
    local uk = KEYS[3]
    local cur = redis.call('GET', uk)
    if cur == ARGV[2] then redis.call('DEL', uk) end
  end
  return {'OK'}
  """;

 public void openCampaign(long campaignId,long postId,long merchantId,long priceCents,int stockCap,long startMs,long endMs){
  RedisCommands<String,String> c=redis.connection().sync();
  c.hset(meta(campaignId), Map.of(
   "entry","1","start",Long.toString(startMs),"end",Long.toString(endMs),
   "cap",Integer.toString(stockCap),"price",Long.toString(priceCents),
   "post",Long.toString(postId),"merchant",Long.toString(merchantId)
  ));
  c.set(stock(campaignId), Integer.toString(stockCap));
 }

 public void closeCampaign(long campaignId){
  RedisCommands<String,String> c=redis.connection().sync();
  c.hset(meta(campaignId), "entry", "0");
 }

 public void pauseGateway(boolean paused){
  RedisCommands<String,String> c=redis.connection().sync();
  if(paused)c.set(GATEWAY,"1"); else c.del(GATEWAY);
 }

 public boolean gatewayPaused(){return redis.connection().sync().exists(GATEWAY)>0;}

 /** Seed a one-shot release key so refund stock restore is idempotent under the release Lua. */
 public void connectionSeedForRelease(long campaignId,String reservationId,long userId){
  String key=resKey(campaignId,reservationId);
  RedisCommands<String,String> c=redis.connection().sync();
  if(c.exists(key)==0){
   c.hset(key, Map.of("state","PROCESSING","user",Long.toString(userId)));
  }
 }

 public record ReserveResult(String status,String reservationId,String detail) {}

 public ReserveResult reserve(long campaignId,long userId,String reservationId,String clientKey,long nowMs){
  String[] keys=new String[]{stock(campaignId),meta(campaignId),userKey(campaignId,userId),resKey(campaignId,reservationId),pending(campaignId)};
  List<?> raw=redis.connection().sync().eval(RESERVE_LUA, ScriptOutputType.MULTI, keys,
   Long.toString(nowMs), Long.toString(userId), reservationId, clientKey, Long.toString(campaignId));
  if(raw==null || raw.isEmpty())throw new IllegalStateException("sale lua empty");
  String status=String.valueOf(raw.get(0));
  String rid=raw.size()>1?String.valueOf(raw.get(1)):null;
  String detail=raw.size()>2?String.valueOf(raw.get(2)):status;
  if("REJECT".equals(status))return new ReserveResult("REJECT",null,rid!=null?rid:detail);
  if("EXISTING".equals(status))return new ReserveResult("EXISTING",rid,detail);
  return new ReserveResult("OK",rid,"PROCESSING");
 }

 public void release(long campaignId,String reservationId,long userId,String reason){
  String[] keys=new String[]{resKey(campaignId,reservationId),stock(campaignId),userKey(campaignId,userId)};
  redis.connection().sync().eval(RELEASE_LUA, ScriptOutputType.MULTI, keys, reason==null?"RELEASE":reason, reservationId);
 }

 public void markSucceeded(long campaignId,String reservationId){
  redis.connection().sync().hset(resKey(campaignId,reservationId), "state", "SUCCEEDED");
 }

 public Long remainingStock(long campaignId){
  String v=redis.connection().sync().get(stock(campaignId));
  return v==null?null:Long.parseLong(v);
 }
}
