package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.FeedConfiguration;
import io.github.xiiiiimu.content.infrastructure.InteractionMapper;
import io.github.xiiiiimu.content.infrastructure.TopicAggregatorMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

/**
 * Hardening B1: interactions write only the topic ledger; the background aggregator folds it into minute buckets (exactly
 * once, resumable, one executor under a lease), runs the spike check, applies R8 revocations, and supplies the processed
 * watermark that bounds the ledger purge (C-13). The background job is off here; tests drive the aggregator directly,
 * except {@link #backgroundJobFoldsWithinTheLagBudget}, which starts its own job.
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TopicAggregationIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.trendguard.spike-threshold",()->"2");
  r.add("content.trending.aggregator.enabled",()->"false");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired TrendingService trending;@Autowired RelationshipService follows;
 @Autowired TopicAggregator aggregator;@Autowired TopicAggregatorMapper mapper;@Autowired InteractionMapper interactionMapper;@Autowired TrendGuardService risks;@Autowired TransactionTemplate tx;
 final StatementDigests digests=new StatementDigests(MYSQL);
 static final long MIN=60_000L,HOUR=3_600_000L;
 @BeforeEach void reset(){
  db.update("DELETE FROM trend_risk_events");db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");db.update("DELETE FROM trend_topic_serial");
  db.update("UPDATE topic_aggregator_state SET owner=NULL,lease_until_ms=0,watermark_ms=0,last_batch_at_ms=0,folded_total=0,revoked_total=0,last_max_lag_ms=0 WHERE id=1");
  db.update("DELETE FROM post_dislikes");db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 void published(long id,String topic){
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(?,1001,'DRAFT','t',?)",id,topic);
  publish.publish(1001,id,UUID.randomUUID().toString(),0);
 }
 void followers(long from,int n){for(int i=0;i<n;i++)follows.change(from+i,1001,true);}
 long net(String topic){return db.queryForObject("SELECT COALESCE(SUM(net_events),0) FROM topic_minute_buckets WHERE topic=?",Long.class,topic);}
 long active(String topic){return db.queryForObject("SELECT COALESCE(SUM(active_interactions),0) FROM topic_minute_buckets WHERE topic=?",Long.class,topic);}
 long bucketRows(String topic){return db.queryForObject("SELECT COUNT(*) FROM topic_minute_buckets WHERE topic=?",Long.class,topic);}
 long pending(){return db.queryForObject("SELECT COUNT(*) FROM topic_event_ledger WHERE aggregated_at_ms IS NULL",Long.class);}
 long ledgerRows(long post){return db.queryForObject("SELECT COUNT(*) FROM topic_event_ledger WHERE post_id=?",Long.class,post);}
 long risks(String topic){return db.queryForObject("SELECT COUNT(*) FROM trend_risk_events WHERE topic=?",Long.class,topic);}
 void ledger(String id,String topic,long at,Long aggregatedAt){
  db.update("INSERT INTO topic_event_ledger(event_id,topic,post_id,occurred_at_ms,delta,kind,aggregated_at_ms) VALUES(?,?,1,?,1,'LIKE',?)",id,topic,at,aggregatedAt);
 }

 @Test void interactionTransactionsWriteOnlyTheLedger(){
  followers(2001,2);
  published(150101,"agg");
  digests.reset();
  interactions.like(2001,150101,"agg");
  interactions.comment(2002,150101,"hello","k-"+UUID.randomUUID(),null);
  assertThat(digests.executions("INSERT INTO `topic_minute_buckets`%")).as("bucket upserts inside interaction transactions").isZero();
  assertThat(digests.executions("%`trend_topic_serial`%")).as("topic serial lock inside interaction transactions").isZero();
  assertThat(digests.executions("SELECT%`topic_minute_buckets`%")).as("spike scan inside interaction transactions").isZero();
  assertThat(digests.executions("INSERT INTO `trend_risk_events`%")).as("risk insert inside interaction transactions").isZero();
  assertThat(net("agg")).as("nothing counted before the aggregator runs").isZero();
  assertThat(pending()).isEqualTo(2);
  assertThat(aggregator.drain()).isEqualTo(2);
  assertThat(net("agg")).isEqualTo(2);
  assertThat(active("agg")).isEqualTo(2);
  assertThat(pending()).isZero();
  assertThat(risks("agg")).as("threshold 2 reached when the batch was folded").isEqualTo(1);
 }

 @Test void drainIsIdempotentAndRecordsProgress(){
  followers(2101,3);
  published(150201,"idem");
  for(int i=0;i<3;i++)interactions.like(2101+i,150201,"idem");
  long before=System.currentTimeMillis();
  assertThat(aggregator.drain()).isEqualTo(3);
  assertThat(net("idem")).isEqualTo(3);
  assertThat(aggregator.drain()).as("a second run finds nothing to fold").isZero();
  assertThat(net("idem")).as("re-running never counts twice").isEqualTo(3);
  var s=mapper.state();
  assertThat(s.foldedTotal()).isEqualTo(3);
  assertThat(s.lastBatchAtMs()).isGreaterThanOrEqualTo(before);
  assertThat(s.watermarkMs()).as("nothing pending: watermark = batch time - in-flight margin").isGreaterThan(0).isLessThanOrEqualTo(s.lastBatchAtMs());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM topic_event_ledger WHERE aggregated_at_ms<occurred_at_ms",Long.class)).isZero();
 }

 @Test void batchRolledBackMidwayIsRedoneExactlyOnce(){
  followers(2201,2);
  published(150301,"crash");
  interactions.like(2201,150301,"crash");interactions.like(2202,150301,"crash");
  assertThatThrownBy(()->tx.executeWithoutResult(status->{
   aggregator.runBatch();
   throw new IllegalStateException("process died after folding, before commit");
  })).isInstanceOf(IllegalStateException.class);
  assertThat(net("crash")).as("the folded buckets rolled back with the batch").isZero();
  assertThat(pending()).as("the rows are still pending").isEqualTo(2);
  assertThat(mapper.state().foldedTotal()).isZero();
  assertThat(aggregator.drain()).isEqualTo(2);
  assertThat(net("crash")).isEqualTo(2);
  assertThat(aggregator.drain()).isZero();
  assertThat(net("crash")).isEqualTo(2);
 }

 @Test void onlyTheLeaseHolderAggregatesAndAnExpiredHolderIsFenced(){
  followers(2301,3);
  published(150401,"lease");
  AtomicLong clock=new AtomicLong(System.currentTimeMillis());
  var a=new TopicAggregator(mapper,tx,risks,0,"node-a",10_000,1000,60_000,clock::get);
  var b=new TopicAggregator(mapper,tx,risks,0,"node-b",10_000,1000,60_000,clock::get);
  interactions.like(2301,150401,"lease");
  var first=a.runBatch();
  assertThat(first.leader()).isTrue();assertThat(first.folded()).isEqualTo(1);
  interactions.like(2302,150401,"lease");
  var blocked=b.runBatch();
  assertThat(blocked.leader()).as("lease held by node-a").isFalse();assertThat(blocked.folded()).isZero();
  assertThat(pending()).isEqualTo(1);
  clock.addAndGet(10_001);
  var takeover=b.runBatch();
  assertThat(takeover.leader()).as("node-a stopped renewing; node-b takes over").isTrue();assertThat(takeover.folded()).isEqualTo(1);
  interactions.like(2303,150401,"lease");
  var stale=a.runBatch();
  assertThat(stale.leader()).as("the former holder is fenced").isFalse();
  assertThat(pending()).isEqualTo(1);
  assertThat(net("lease")).isEqualTo(2);
  assertThat(mapper.state().owner()).isEqualTo("node-b");
 }

 @Test void unlikeBeforeFoldingWithdrawsThePendingRow(){
  followers(2401,1);
  published(150501,"withdraw");
  interactions.like(2401,150501,"withdraw");
  interactions.unlike(2401,150501,"withdraw");
  assertThat(ledgerRows(150501)).isZero();
  aggregator.drain();
  assertThat(net("withdraw")).isZero();
  assertThat(bucketRows("withdraw")).as("a like withdrawn before folding never reaches a bucket").isZero();
 }

 @Test void unlikeAfterFoldingQueuesOneRevocationThatNeverDoubleSubtracts(){
  followers(2501,1);
  published(150601,"revoke");
  interactions.like(2501,150601,"revoke");
  aggregator.drain();
  assertThat(net("revoke")).isEqualTo(1);
  interactions.unlike(2501,150601,"revoke");
  var rows=db.queryForList("SELECT event_id,revoked_at_ms,aggregated_at_ms FROM topic_event_ledger WHERE post_id=150601");
  assertThat(rows).hasSize(1);
  assertThat((String)rows.get(0).get("event_id")).as("renamed off like:{post}:{user} so the user can like again").startsWith("rv:");
  assertThat(rows.get(0).get("revoked_at_ms")).isNotNull();
  assertThat(rows.get(0).get("aggregated_at_ms")).as("pending again").isNull();
  assertThat(net("revoke")).as("counted until the revocation is folded").isEqualTo(1);
  aggregator.drain();
  assertThat(net("revoke")).isZero();
  assertThat(ledgerRows(150601)).as("revocation applied and deleted").isZero();
  assertThat(mapper.state().revokedTotal()).isEqualTo(1);
  aggregator.drain();
  assertThat(net("revoke")).as("never subtracted twice").isZero();
  interactions.like(2501,150601,"revoke");
  aggregator.drain();
  assertThat(net("revoke")).as("liking again counts once").isEqualTo(1);
  assertThat(db.queryForObject("SELECT net_events FROM topic_minute_buckets WHERE topic='revoke' ORDER BY bucket_start_ms DESC LIMIT 1",Long.class)).isEqualTo(1);
 }

 @Test void revocationNeverDrivesABucketNegative(){
  followers(2601,1);
  published(150701,"floor");
  interactions.like(2601,150701,"floor");
  aggregator.drain();
  db.update("UPDATE topic_minute_buckets SET net_events=0,active_interactions=0 WHERE topic='floor'");
  interactions.unlike(2601,150701,"floor");
  aggregator.drain();
  assertThat(db.queryForObject("SELECT MIN(net_events) FROM topic_minute_buckets WHERE topic='floor'",Long.class)).isZero();
  assertThat(db.queryForObject("SELECT MIN(active_interactions) FROM topic_minute_buckets WHERE topic='floor'",Long.class)).isZero();
 }

 @Test void retentionKeepsEveryUnfoldedLedgerRow(){
  long now=System.currentTimeMillis();
  mapper.watermark(now-51*HOUR);            // the aggregator last recorded progress 51 h ago and has been down since
  ledger("folded-52h","ret",now-52*HOUR,0L);
  ledger("stalled-50h","ret",now-50*HOUR,null);
  ledger("folded-49h","ret",now-49*HOUR,0L);
  var retention=new TopicRetention(interactionMapper,tx,TopicRetention.DEFAULT_RETENTION_MS,1,aggregator::processedWatermarkMs);
  retention.purge(now);
  assertThat(db.queryForList("SELECT event_id FROM topic_event_ledger ORDER BY occurred_at_ms",String.class))
   .as("only rows below the recorded watermark may go").containsExactly("stalled-50h","folded-49h");
  assertThat(aggregator.drain()).isEqualTo(1);
  retention.purge(System.currentTimeMillis());
  assertThat(db.queryForList("SELECT event_id FROM topic_event_ledger",String.class)).as("folded now, so past retention").isEmpty();
 }

 @Test void watermarkStopsAtAPendingRowSkippedBecauseItIsLocked()throws Exception{
  followers(2701,1);
  published(150801,"locked");
  interactions.like(2701,150801,"locked");
  long at=db.queryForObject("SELECT occurred_at_ms FROM topic_event_ledger WHERE post_id=150801",Long.class);
  CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
  ExecutorService pool=Executors.newSingleThreadExecutor();
  Future<?> holder=pool.submit(()->tx.executeWithoutResult(status->{
   db.queryForList("SELECT event_id FROM topic_event_ledger WHERE post_id=150801 FOR UPDATE");
   locked.countDown();
   try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  }));
  try{
   assertThat(locked.await(30,TimeUnit.SECONDS)).isTrue();
   var b=aggregator.runBatch();
   assertThat(b.leader()).isTrue();
   assertThat(b.folded()).as("row held by an in-flight transaction is skipped, not waited on").isZero();
   assertThat(mapper.state().watermarkMs()).as("watermark never passes a pending row").isLessThanOrEqualTo(at);
  }finally{release.countDown();holder.get(30,TimeUnit.SECONDS);pool.shutdownNow();}
  assertThat(aggregator.drain()).isEqualTo(1);
  assertThat(mapper.state().watermarkMs()).isGreaterThan(at-60_000L);
 }

 @Test void spikeIsDetectedWhenTheBatchIsFolded(){
  followers(2801,3);
  published(150901,"spike");
  interactions.like(2801,150901,"spike");interactions.like(2802,150901,"spike");
  assertThat(risks("spike")).as("no spike check inside interaction transactions").isZero();
  aggregator.drain();
  assertThat(risks("spike")).isEqualTo(1);
  long likedAt=db.queryForObject("SELECT MAX(occurred_at_ms) FROM topic_event_ledger WHERE topic='spike'",Long.class);
  assertThat(db.queryForObject("SELECT event_id FROM trend_risk_events WHERE topic='spike'",String.class))
   .as("window = clock hour of the events").isEqualTo(TrendGuardService.spikeEventId("spike",likedAt/HOUR*HOUR));
  interactions.like(2803,150901,"spike");
  aggregator.drain();
  assertThat(risks("spike")).isEqualTo(1);
 }

 @Test void rolledBackBatchDoesNotMarkTheSpikeWindowReported(){
  followers(2901,2);
  published(151001,"rbspike");
  interactions.like(2901,151001,"rbspike");interactions.like(2902,151001,"rbspike");
  tx.executeWithoutResult(status->{aggregator.runBatch();status.setRollbackOnly();});
  assertThat(risks("rbspike")).isZero();
  aggregator.drain();
  assertThat(risks("rbspike")).as("the rolled-back emit must not suppress the real one").isEqualTo(1);
 }

 @Test void rebuildCountsWhatIsInTheBucketsThenFoldsPending(){
  followers(3001,2);
  published(151101,"rebuild");
  interactions.like(3001,151101,"rebuild");
  aggregator.drain();
  interactions.unlike(3001,151101,"rebuild");    // revocation pending: its +1 is still in the bucket
  interactions.like(3002,151101,"rebuild");      // pending
  trending.rebuild(HOUR);
  assertThat(net("rebuild")).isEqualTo(1);
  assertThat(pending()).isZero();
 }

 @Test void backgroundJobFoldsWithinTheLagBudget()throws Exception{
  followers(3101,3);
  published(151201,"lag");
  try(var job=new FeedConfiguration.TopicAggregatorJob(aggregator,1000,5000)){
   for(int i=0;i<3;i++)interactions.like(3101+i,151201,"lag");
   long deadline=System.currentTimeMillis()+10_000;
   while(pending()>0 && System.currentTimeMillis()<deadline)Thread.sleep(50);
  }
  assertThat(pending()).isZero();
  assertThat(net("lag")).isEqualTo(3);
  assertThat(db.queryForObject("SELECT MAX(aggregated_at_ms-occurred_at_ms) FROM topic_event_ledger WHERE topic='lag'",Long.class))
   .as("ledger write → counted in the buckets, against max-lag-millis").isLessThanOrEqualTo(5000L);
 }
}
