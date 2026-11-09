package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.DeliveryStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * RED→GREEN regression for DeliveryMapper.exhausted under DONE+NEW volume.
 * Real MySQL (Testcontainers 8.4). Does not raise DeliveryStore's 5s TX timeout.
 * Fixture rows are labeled for this SQL-path only (not capacity E2E seed bypass).
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryExhaustedClaimIT {
 static final int DONE=40_000;
 static final int READY_NEW=10_000;
 static final Path EVIDENCE=Path.of("target","delivery-exhausted-evidence");
 /** Production exhaustedNew shape after OR-split (GREEN). */
 static final String EXHAUSTED_NEW_SQL="""
  SELECT id FROM feed_delivery_tasks
  WHERE state='NEW' AND next_retry_at<=CURRENT_TIMESTAMP(6) AND attempt>=5
  ORDER BY next_retry_at,id LIMIT 100 FOR UPDATE SKIP LOCKED
  """;
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired DeliveryStore store;

 @BeforeEach void reset()throws Exception{
  Files.createDirectories(EVIDENCE);
  db.update("DELETE FROM delivery_replay_audit");db.update("DELETE FROM feed_delivery_tasks");
  db.update("UPDATE feed_control SET state='READY',generation=1 WHERE id=1");
 }

 @Test void exhaustedPlanAndClaimMustNotFullScanPrimaryAcrossDoneHeap()throws Exception{
  seedMixedVolume();
  db.execute("ANALYZE TABLE feed_delivery_tasks");
  long total=db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks",Long.class);
  long done=db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state='DONE'",Long.class);
  long readyNew=db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state='NEW' AND attempt<5",Long.class);
  assertThat(done).isEqualTo(DONE);
  assertThat(readyNew).isEqualTo(READY_NEW);

  var explain=db.queryForList("EXPLAIN "+EXHAUSTED_NEW_SQL);
  Files.writeString(EVIDENCE.resolve("explain-exhausted.json"),explain+"\n");
  Files.writeString(EVIDENCE.resolve("counts.txt"),
   "total="+total+" done="+done+" readyNewAttemptLtMax="+readyNew
   +"\nindexes="+db.queryForList("SHOW INDEX FROM feed_delivery_tasks")+"\n");
  assertThat(db.queryForList("SHOW INDEX FROM feed_delivery_tasks WHERE Key_name='ix_delivery_retry_attempt'"))
   .as("V9 attempt-aware retry index required").isNotEmpty();
  assertThat(db.queryForList("SHOW INDEX FROM feed_delivery_tasks WHERE Key_name='ix_delivery_lease_attempt'"))
   .as("V9 attempt-aware lease index required").isNotEmpty();

  // Lock-wait probe: SKIP LOCKED claim must not sit behind a held non-matching row lock.
  var lockOwner=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
  lockOwner.setAutoCommit(false);
  try(var st=lockOwner.createStatement()){
   st.execute("SELECT id FROM feed_delivery_tasks WHERE state='DONE' ORDER BY id LIMIT 1 FOR UPDATE");
   long lockWaitBefore=innodbLockWaits();
   long t0=System.nanoTime();
   assertThatCode(()->{for(int i=0;i<20;i++)store.claim();})
    .as("exhausted+candidate under DONE heap must finish inside DeliveryStore 5s TX")
    .doesNotThrowAnyException();
   long claimMs=(System.nanoTime()-t0)/1_000_000L;
   long lockWaitAfter=innodbLockWaits();
   Files.writeString(EVIDENCE.resolve("claim-loop-ms.txt"),
    "claims=20 ms="+claimMs+" lockWaitsDelta="+(lockWaitAfter-lockWaitBefore)+"\n");
   assertThat(lockWaitAfter-lockWaitBefore).as("SKIP LOCKED must not accumulate InnoDB lock waits on DONE rows").isZero();
   assertThat(claimMs).as("20 claims against DONE+NEW volume").isLessThan(10_000L);
  }finally{
   lockOwner.rollback();
   lockOwner.close();
  }

  String accessType=String.valueOf(explain.get(0).get("type"));
  String key=String.valueOf(explain.get(0).get("key"));
  Files.writeString(EVIDENCE.resolve("plan-verdict.txt"),"type="+accessType+" key="+key+"\n");
  // RED on pre-fix schema: OR+attempt>=max chooses PRIMARY full index scan over the DONE heap.
  assertThat(key).as("exhausted must use attempt-selective secondary index, not PRIMARY full scan").isNotEqualTo("PRIMARY");
  assertThat(accessType).as("exhausted must not be type=index (full index scan)").isNotEqualTo("index");
 }

 private void seedMixedVolume(){
  // Batch INSERT keeps fixture setup out of formal capacity metrics.
  final int batch=500;
  for(int base=0;base<DONE;base+=batch){
   StringBuilder sql=new StringBuilder("INSERT INTO feed_delivery_tasks(event_id,author_id,delivery_epoch,kind,state,attempt,next_retry_at,lease_token,payload) VALUES ");
   List<Object> args=new ArrayList<>();
   int n=Math.min(batch,DONE-base);
   for(int i=0;i<n;i++){
    if(i>0)sql.append(',');
    sql.append("(?,?,1,'BACKFILL','DONE',0,CURRENT_TIMESTAMP(6),0,CAST(? AS JSON))");
    long id=700_000L+base+i;
    args.add(id);args.add(8_000L+((base+i)%100));args.add("{\"fixture\":\"DeliveryExhaustedClaimIT\",\"state\":\"DONE\"}");
   }
   db.update(sql.toString(),args.toArray());
  }
  for(int base=0;base<READY_NEW;base+=batch){
   StringBuilder sql=new StringBuilder("INSERT INTO feed_delivery_tasks(event_id,author_id,delivery_epoch,kind,state,attempt,next_retry_at,lease_token,payload,follower_id,follow_epoch) VALUES ");
   List<Object> args=new ArrayList<>();
   int n=Math.min(batch,READY_NEW-base);
   for(int i=0;i<n;i++){
    if(i>0)sql.append(',');
    sql.append("(?,?,1,'BACKFILL','NEW',0,CURRENT_TIMESTAMP(6),0,CAST(? AS JSON),?,1)");
    long id=800_000L+base+i;
    args.add(id);args.add(8_000L+((base+i)%100));args.add("{\"fixture\":\"DeliveryExhaustedClaimIT\",\"state\":\"NEW\"}");
    args.add(9_000L+((base+i)%200));
   }
   db.update(sql.toString(),args.toArray());
  }
 }

 private long innodbLockWaits(){
  try{
   Long v=db.queryForObject(
    "SELECT COUNT_RESET FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'",
    Long.class);
   return v==null?0L:v;
  }catch(Exception e){
   try{
    Long v=db.queryForObject("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'",(rs,row)->rs.getLong(2));
    return v==null?0L:v;
   }catch(Exception ignored){return 0L;}
  }
 }
}
