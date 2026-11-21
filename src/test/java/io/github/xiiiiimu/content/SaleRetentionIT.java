package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.RetentionBounds;
import io.github.xiiiiimu.content.domain.SaleRetention;
import io.github.xiiiiimu.content.domain.SaleStreamRetention;
import io.github.xiiiiimu.content.infrastructure.FeedRedis;
import io.github.xiiiiimu.content.infrastructure.RetentionMapper;
import io.github.xiiiiimu.content.infrastructure.SaleEntryRedis;
import io.lettuce.core.Consumer;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Hardening C2 R-SALE (SENT dispatch + receipts of terminal requests) and R-STREAM (acknowledged Stream entries, t: copies, orphan stages). */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SaleRetentionIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired RetentionMapper retention;@Autowired TransactionTemplate tx;@Autowired FeedRedis feedRedis;
 static final long DAY=86_400_000L,HOUR=3_600_000L;
 SaleEntryRedis cache;RedisCommands<String,String> redis;

 @BeforeEach void reset(){
  for(String t:List.of("material_sale_consume_receipts","material_sale_dispatch","material_sale_stream_quarantine","material_purchase_requests"))db.update("DELETE FROM "+t);
  cache=new SaleEntryRedis(feedRedis);redis=cache.commands();redis.flushall();
 }
 long now(){return db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class);}
 int seq;
 String request(String state){
  String id=String.format("%032x",++seq);long t=now()-30*DAY;
  db.update("INSERT INTO material_purchase_requests(reservation_id,campaign_id,post_id,merchant_id,user_id,client_key,price_cents,state,created_at_ms,updated_at_ms) VALUES(?,1,1,1,?,?,100,?,?,?)",
   id,1000+seq,"ck-"+seq,state,t,t);
  return id;
 }
 void dispatch(String id,String state,Long sentDaysAgo,long availableAt){
  db.update("INSERT INTO material_sale_dispatch(reservation_id,state,sent_at_ms,available_at_ms) VALUES(?,?,?,?)",id,state,sentDaysAgo==null?null:now()-sentDaysAgo*DAY,availableAt);
 }
 void receipt(String id,long daysAgo){db.update("INSERT INTO material_sale_consume_receipts(reservation_id,decision,created_at_ms) VALUES(?,'SUCCEEDED',?)",id,now()-daysAgo*DAY);}
 SaleRetention sale(int batch){return new SaleRetention(retention,tx,new RetentionBounds(SaleRetention.DEFAULT_RETENTION_MS,batch,1000));}

 @Test void saleRetentionDeletesSentDispatchAndReceiptsOfTerminalRequestsTogether(){
  String done=request("SUCCEEDED");dispatch(done,"SENT",8L,0);receipt(done,8);
  String rejected=request("REJECTED");dispatch(rejected,"SENT",8L,0);
  String backedOff=request("SUCCEEDED");dispatch(backedOff,"SENT",9L,now()-9*DAY);receipt(backedOff,9);   // was retried: available_at_ms > 0
  String young=request("SUCCEEDED");dispatch(young,"SENT",6L,0);receipt(young,6);                    // inside the window
  String processing=request("PROCESSING");dispatch(processing,"SENT",8L,0);                          // not settled yet
  String youngReceipt=request("SUCCEEDED");dispatch(youngReceipt,"SENT",8L,0);receipt(youngReceipt,2); // consumed late: both kept
  String noTime=request("SUCCEEDED");dispatch(noTime,"SENT",null,0);receipt(noTime,8);               // C-6: ACK time unknown
  String pending=request("PROCESSING");dispatch(pending,"PENDING",null,0);
  String sending=request("PROCESSING");dispatch(sending,"SENDING",null,0);
  String legacy=request("SUCCEEDED");receipt(legacy,30);                                              // receipt without dispatch (pre-V20)

  assertThat(sale(2).purge()).isEqualTo(new SaleRetention.Result(3,2));
  assertThat(db.queryForList("SELECT reservation_id FROM material_sale_dispatch",String.class))
   .containsExactlyInAnyOrder(young,processing,youngReceipt,noTime,pending,sending);
  assertThat(db.queryForList("SELECT reservation_id FROM material_sale_consume_receipts",String.class))
   .containsExactlyInAnyOrder(young,youngReceipt,noTime,legacy);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Long.class)).as("business records untouched").isEqualTo(10);
  assertThat(sale(2).purge()).isEqualTo(new SaleRetention.Result(0,0));
 }

 String add(long ms,int s){
  String id=ms+"-"+s;redis.xadd(SaleEntryRedis.STREAM,new XAddArgs().id(id),Map.of("request","{}","generation","1"));
  redis.hset(SaleEntryRedis.STATE,"t:"+id,"{\"generation\":1}");return id;
 }
 SaleStreamRetention stream(int batch){return new SaleStreamRetention(retention,cache,new RetentionBounds(SaleStreamRetention.DEFAULT_RETENTION_MS,batch,1000));}
 List<String> streamIds(){return redis.xrange(SaleEntryRedis.STREAM,io.lettuce.core.Range.create("-","+")).stream().map(m->m.getId()).toList();}
 boolean trusted(String id){return redis.hexists(SaleEntryRedis.STATE,"t:"+id);}

 @Test void streamRetentionTrimsOnlyAcknowledgedEntriesBeforeTheOldestPendingAndOlderThanOneDay(){
  long old=now()-2*DAY,fresh=now()-HOUR;
  redis.xgroupCreate(XReadArgs.StreamOffset.from(SaleEntryRedis.STREAM,"0-0"),SaleEntryRedis.GROUP,io.lettuce.core.XGroupCreateArgs.Builder.mkstream());
  String o1=add(old,1),o2=add(old,2),o3=add(old,3),o4=add(old,4),o5=add(old,5),o6=add(old,6),y1=add(fresh,1);
  var delivered=redis.xreadgroup(Consumer.from(SaleEntryRedis.GROUP,"c1"),XReadArgs.Builder.count(4),XReadArgs.StreamOffset.lastConsumed(SaleEntryRedis.STREAM));
  assertThat(delivered).extracting(m->m.getId()).containsExactly(o1,o2,o3,o4);
  redis.xack(SaleEntryRedis.STREAM,SaleEntryRedis.GROUP,o1,o2,o4);   // o3 stays pending; o5, o6, y1 never delivered
  db.update("INSERT INTO material_sale_stream_quarantine(stream_id,payload,reason,disposition,created_at_ms,updated_at_ms) VALUES(?,'{}','TEST','RELEASE_PENDING',0,0)",o1);

  var first=stream(1).purge();

  assertThat(streamIds()).as("only acknowledged entries before the oldest pending one go").containsExactly(o3,o4,o5,o6,y1);
  assertThat(trusted(o1)).as("t: of an unresolved quarantine is kept for the release script").isTrue();
  assertThat(trusted(o2)).isFalse();
  assertThat(List.of(o3,o4,o5,o6,y1)).allMatch(this::trusted);
  assertThat(first).isEqualTo(new SaleStreamRetention.Result(2,1,0));

  redis.xack(SaleEntryRedis.STREAM,SaleEntryRedis.GROUP,o3);          // nothing pending: bound is the last delivered entry
  assertThat(stream(1).purge()).isEqualTo(new SaleStreamRetention.Result(2,2,0));
  assertThat(streamIds()).as("undelivered and fresh entries stay").containsExactly(o5,o6,y1);
  assertThat(redis.xpending(SaleEntryRedis.STREAM,SaleEntryRedis.GROUP).getCount()).isZero();
 }

 @Test void streamRetentionWithoutConsumerGroupTrimsNothing(){
  long old=now()-2*DAY;String a=add(old,1);
  assertThat(stream(10).purge()).isEqualTo(new SaleStreamRetention.Result(0,0,0));
  assertThat(streamIds()).containsExactly(a);assertThat(trusted(a)).isTrue();
 }

 @Test void orphanRecoveryStagesAreDroppedButNotTheOneBeingBuilt(){
  db.update("UPDATE material_sale_runtime SET state='RUNNING',generation=5 WHERE id=1");
  for(int g:new int[]{3,5,6})redis.hset(SaleEntryRedis.BASE+"stage:"+g,"generation",Integer.toString(g));
  assertThat(stream(10).purge().stages()).isEqualTo(2);
  assertThat(redis.exists(SaleEntryRedis.BASE+"stage:3",SaleEntryRedis.BASE+"stage:5")).isZero();
  assertThat(redis.exists(SaleEntryRedis.BASE+"stage:6")).as("newer than the runtime: a recovery may be starting").isEqualTo(1);
  db.update("UPDATE material_sale_runtime SET state='RECOVERING',generation=6 WHERE id=1");
  redis.hset(SaleEntryRedis.BASE+"stage:5","generation","5");
  assertThat(stream(10).purge().stages()).isEqualTo(1);
  assertThat(redis.exists(SaleEntryRedis.BASE+"stage:6")).as("the stage of the recovery in progress stays").isEqualTo(1);
  assertThat(redis.exists(SaleEntryRedis.BASE+"stage:5")).isZero();
 }
}
