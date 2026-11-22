package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Hardening C2 retention jobs. Every job has its own switch, batch size, batches per run, interval and (where time based)
 * retention under {@code content.retention.<job>.*}. The retention objects are always created, so an unsafe retention (for
 * example receipts shorter than the broker can redeliver, C-14) stops startup even when the job itself is switched off.
 * Each job runs on its own daemon thread with a first run one interval after startup; a failed run is logged and retried.
 */
@Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class RetentionConfiguration {
 static final String DEFAULT_BATCH="500",DEFAULT_MAX_BATCHES="200",DEFAULT_INTERVAL="600000";

 @Bean FeedRetention feedRetention(RetentionMapper db,TransactionTemplate tx,
   @Value("${content.retention.feed.retention-millis:"+FeedRetention.DEFAULT_RETENTION_MS+"}")long retention,
   @Value("${content.retention.feed.batch:"+DEFAULT_BATCH+"}")int batch,@Value("${content.retention.feed.max-batches:"+DEFAULT_MAX_BATCHES+"}")int maxBatches,
   @Value("${content.retention.broker-reserved-hours:72}")long brokerHours){
  return new FeedRetention(db,tx,new RetentionBounds(retention,batch,maxBatches),brokerHours);
 }
 @Bean(destroyMethod="close") @ConditionalOnProperty(name="content.retention.feed.enabled",havingValue="true",matchIfMissing=true)
 RetentionJob feedRetentionJob(FeedRetention retention,@Value("${content.retention.feed.interval-millis:"+DEFAULT_INTERVAL+"}")long interval){
  return new RetentionJob("feed-retention",interval,retention::purge);
 }

 @Bean FollowHistoryRetention followHistoryRetention(RetentionMapper db,TransactionTemplate tx,
   @Value("${content.retention.follow-history.batch:"+DEFAULT_BATCH+"}")int batch,@Value("${content.retention.follow-history.max-batches:"+DEFAULT_MAX_BATCHES+"}")int maxBatches){
  return new FollowHistoryRetention(db,tx,batch,maxBatches);
 }
 @Bean(destroyMethod="close") @ConditionalOnProperty(name="content.retention.follow-history.enabled",havingValue="true",matchIfMissing=true)
 RetentionJob followHistoryRetentionJob(FollowHistoryRetention retention,@Value("${content.retention.follow-history.interval-millis:"+DEFAULT_INTERVAL+"}")long interval){
  return new RetentionJob("follow-history-retention",interval,retention::purge);
 }

 @Bean NotificationRetention notificationRetention(RetentionMapper db,TransactionTemplate tx,
   @Value("${content.retention.notifications.retention-millis:"+NotificationRetention.DEFAULT_RETENTION_MS+"}")long retention,
   @Value("${content.retention.notifications.batch:"+DEFAULT_BATCH+"}")int batch,@Value("${content.retention.notifications.max-batches:"+DEFAULT_MAX_BATCHES+"}")int maxBatches){
  return new NotificationRetention(db,tx,new RetentionBounds(retention,batch,maxBatches));
 }
 @Bean(destroyMethod="close") @ConditionalOnProperty(name="content.retention.notifications.enabled",havingValue="true",matchIfMissing=true)
 RetentionJob notificationRetentionJob(NotificationRetention retention,@Value("${content.retention.notifications.interval-millis:"+DEFAULT_INTERVAL+"}")long interval){
  return new RetentionJob("notification-retention",interval,retention::purge);
 }

 /** Commerce jobs exist only where commerce runs (SaleConfiguration is enabled by content.media.enabled). */
 @Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 static class Sale {
  @Bean SaleRetention saleRetention(RetentionMapper db,TransactionTemplate tx,
    @Value("${content.retention.sale.retention-millis:"+SaleRetention.DEFAULT_RETENTION_MS+"}")long retention,
    @Value("${content.retention.sale.batch:"+DEFAULT_BATCH+"}")int batch,@Value("${content.retention.sale.max-batches:"+DEFAULT_MAX_BATCHES+"}")int maxBatches,
    @Value("${content.retention.broker-reserved-hours:72}")long brokerHours){
   return new SaleRetention(db,tx,new RetentionBounds(retention,batch,maxBatches),brokerHours);
  }
  @Bean(destroyMethod="close") @ConditionalOnProperty(name="content.retention.sale.enabled",havingValue="true",matchIfMissing=true)
  RetentionJob saleRetentionJob(SaleRetention retention,@Value("${content.retention.sale.interval-millis:"+DEFAULT_INTERVAL+"}")long interval){
   return new RetentionJob("sale-retention",interval,retention::purge);
  }
  @Bean SaleStateArchiver saleStateArchiver(SaleIngressMapper db,SaleMapper sales,SaleRedis redis,TransactionTemplate tx,
    @Value("${content.retention.sale-state.grace-millis:"+SaleStateArchiver.DEFAULT_GRACE_MS+"}")long grace,
    @Value("${content.retention.sale-state.batch:"+DEFAULT_BATCH+"}")int batch,@Value("${content.retention.sale-state.max-batches:"+DEFAULT_MAX_BATCHES+"}")int maxBatches){
   return new SaleStateArchiver(db,sales,redis.entry(),tx,grace,batch,maxBatches);
  }
  @Bean(destroyMethod="close") @ConditionalOnProperty(name="content.retention.sale-state.enabled",havingValue="true",matchIfMissing=true)
  RetentionJob saleStateArchiverJob(SaleStateArchiver archiver,@Value("${content.retention.sale-state.interval-millis:"+DEFAULT_INTERVAL+"}")long interval){
   return new RetentionJob("sale-state-archiver",interval,()->{archiver.migrate();return archiver.archive();});
  }
  @Bean SaleStreamRetention saleStreamRetention(RetentionMapper db,SaleRedis redis,
    @Value("${content.retention.sale-stream.retention-millis:"+SaleStreamRetention.DEFAULT_RETENTION_MS+"}")long retention,
    @Value("${content.retention.sale-stream.batch:"+DEFAULT_BATCH+"}")int batch,@Value("${content.retention.sale-stream.max-batches:"+DEFAULT_MAX_BATCHES+"}")int maxBatches){
   return new SaleStreamRetention(db,redis.entry(),new RetentionBounds(retention,batch,maxBatches));
  }
  @Bean(destroyMethod="close") @ConditionalOnProperty(name="content.retention.sale-stream.enabled",havingValue="true",matchIfMissing=true)
  RetentionJob saleStreamRetentionJob(SaleStreamRetention retention,@Value("${content.retention.sale-stream.interval-millis:"+DEFAULT_INTERVAL+"}")long interval){
   return new RetentionJob("sale-stream-retention",interval,retention::purge);
  }
 }

 /** Own daemon thread so a purge never competes with request or pipeline executors; not an ExecutorService bean. */
 public static final class RetentionJob implements AutoCloseable {
  private final ScheduledExecutorService scheduler;
  RetentionJob(String name,long interval,Supplier<?> purge){
   long every=Math.max(60_000L,interval);
   scheduler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,name);t.setDaemon(true);return t;});
   var log=org.slf4j.LoggerFactory.getLogger(RetentionJob.class);
   scheduler.scheduleWithFixedDelay(()->{
    try{log.info("{} purged {}",name,purge.get());}
    catch(Exception e){log.warn("{} failed; will retry",name,e);}
   },every,every,TimeUnit.MILLISECONDS);
  }
  @Override public void close(){scheduler.shutdownNow();}
 }
}
