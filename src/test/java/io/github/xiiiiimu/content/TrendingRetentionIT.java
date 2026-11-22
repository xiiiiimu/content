package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.InteractionMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Round-1 R2: the spike check reads only the interacted topic, and retention never deletes rows the trending windows can read. */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TrendingRetentionIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired TrendingService trending;@Autowired InteractionMapper interactions;@Autowired TransactionTemplate tx;@Autowired TopicAggregator aggregator;
 final StatementDigests digests=new StatementDigests(MYSQL);
 static final long MIN=60_000L,HOUR=3_600_000L;
 @BeforeEach void reset(){
  db.update("DELETE FROM trend_risk_events");db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");db.update("DELETE FROM trend_topic_serial");
 }
 static long minuteNow(){return System.currentTimeMillis()/MIN*MIN;}
 void bucket(String topic,long start,long net){db.update("INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions) VALUES(?,?,?,?)",topic,start,net,net);}

 @Test void spikeCheckExaminesOnlyTheInteractedTopicsBuckets(){
  long m=minuteNow();
  List<Object[]> rows=new ArrayList<>();
  for(int t=0;t<50;t++)for(int k=1;k<=59;k++)rows.add(new Object[]{"other_"+t,m-k*MIN});            // 2950 rows inside the last hour
  for(int t=0;t<50;t++)for(int k=0;k<60;k++)rows.add(new Object[]{"other_"+t,m-2*HOUR-k*MIN});      // 3000 historical rows
  for(int k=1;k<=10;k++)rows.add(new Object[]{"r2topic",m-k*MIN});                                     // 10 own buckets in window
  rows.add(new Object[]{"r2topic",m-3*HOUR});                                                           // own bucket outside window
  db.batchUpdate("INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions) VALUES(?,?,1,1)",rows);
  digests.reset();
  trending.record("r2:spike:"+System.nanoTime(),"r2topic",110101L,1,"LIKE");
  aggregator.drain();   // B1: the spike check runs when the aggregator folds the event
  var reads=digests.matching("SELECT%`topic_minute_buckets`%");
  assertThat(reads).as("spike check must read topic_minute_buckets").isNotEmpty();
  assertThat(reads).allSatisfy(r->assertThat(r.examinedPerExecution())
   .as("rows examined per spike-check read (%s); only the interacted topic's <=61 in-window buckets may be read",r.digest())
   .isLessThanOrEqualTo(61));
  assertThat(db.queryForObject("SELECT detail FROM trend_risk_events WHERE topic='r2topic'",String.class))
   .as("net = 10 seeded in-window + 1 recorded; other topics and the 3h-old bucket excluded").endsWith(";net=11");
 }

 @Test void retentionPurgeKeepsEveryRowTheLongestTrendingWindowCanRead(){
  long m=minuteNow(),now=System.currentTimeMillis();
  long[] keptAges={1*MIN,HOUR,24*HOUR-MIN,47*HOUR};          // inside the 24h window or the 48h retention
  long[] purgedAges={48*HOUR+MIN,72*HOUR};                    // older than 48h retention
  for(long age:keptAges){bucket("keep",m-age,1);db.update("INSERT INTO topic_event_ledger(event_id,topic,post_id,occurred_at_ms,delta,kind) VALUES(?,?,?,?,1,'LIKE')","keep:"+age,"keep",1L,now-age);}
  for(long age:purgedAges){bucket("keep",m-age,1);db.update("INSERT INTO topic_event_ledger(event_id,topic,post_id,occurred_at_ms,delta,kind) VALUES(?,?,?,?,1,'LIKE')","old:"+age,"keep",1L,now-age);}
  var window24Before=trending.window("net_events",24*HOUR,1L);
  var window1Before=trending.window("net_events",HOUR,1L);
  var result=new TopicRetention(interactions,tx,TopicRetention.DEFAULT_RETENTION_MS,1).purge(now);
  assertThat(result).as("batch size 1 still purges every expired row").isEqualTo(new TopicRetention.Result(2,2));
  assertThat(db.queryForList("SELECT bucket_start_ms FROM topic_minute_buckets WHERE topic='keep' ORDER BY bucket_start_ms DESC",Long.class))
   .containsExactly(m-keptAges[0],m-keptAges[1],m-keptAges[2],m-keptAges[3]);
  assertThat(db.queryForList("SELECT event_id FROM topic_event_ledger ORDER BY occurred_at_ms DESC",String.class))
   .containsExactly("keep:"+keptAges[0],"keep:"+keptAges[1],"keep:"+keptAges[2],"keep:"+keptAges[3]);
  assertThat(trending.window("net_events",24*HOUR,1L)).isEqualTo(window24Before);
  assertThat(trending.window("net_events",HOUR,1L)).isEqualTo(window1Before);
 }

 @Test void retentionShorterThanLongestWindowPlusMarginIsRejected(){
  assertThatThrownBy(()->new TopicRetention(interactions,tx,TopicRetention.LONGEST_WINDOW_MS,500)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new TopicRetention(interactions,tx,TopicRetention.DEFAULT_RETENTION_MS,0)).isInstanceOf(IllegalArgumentException.class);
 }
}
