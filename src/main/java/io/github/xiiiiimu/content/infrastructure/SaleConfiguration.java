package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.worker.SaleConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="content.media.enabled",havingValue="true")
@Import(SaleConfiguration.Dispatch.class)
public class SaleConfiguration {
 @Bean SaleRedis saleRedis(FeedRedis redis){return new SaleRedis(redis);}

 @Bean SaleFaultHooks saleFaultHooks(){return new SaleFaultHooks();}
 @Bean SaleIngress saleIngress(SaleIngressMapper ingress,SaleMapper sales,SaleRedis redis,TransactionTemplate tx,SaleFaultHooks faults,SaleBridgeMapper batches,
  @Value("${content.sale.bridge-batch-size:20}")int batchSize){return new SaleIngress(ingress,sales,redis.entry(),tx,faults,batches,batchSize);}

 @Bean SaleService saleService(SaleMapper sales,SaleRedis redis,MaterialMapper materials,FeedMapper posts,
                               NotificationMapper notifications,VisibilityService visibility,TransactionTemplate tx,
                               @Value("${content.sale.sync-settle:false}") boolean syncSettle,SaleFaultHooks faults,SaleIngress ingress,RelationshipService relationships){
  var service=new SaleService(sales,redis,materials,posts,notifications,visibility,tx,syncSettle,faults);
  service.setIngress(ingress);relationships.setOfflineHandler(service::offlineInTransaction);return service;
 }

 @Bean @ConditionalOnProperty(name="content.sale.producer-enabled",havingValue="true")
 DefaultMQProducer saleProducer(@Value("${content.sale.namesrv:${content.relay.namesrv:127.0.0.1:9876}}")String namesrv){
  var p=new DefaultMQProducer("content-sale-producer");
  p.setNamesrvAddr(namesrv);p.setVipChannelEnabled(false);p.setInstanceName(UUID.randomUUID().toString());
  p.setRetryTimesWhenSendFailed(0);return p;
 }

 @Bean @ConditionalOnProperty(name="content.sale.producer-enabled",havingValue="true")
 SaleMessageSender saleMessageSender(@org.springframework.beans.factory.annotation.Qualifier("saleProducer") DefaultMQProducer saleProducer,
                                     com.fasterxml.jackson.databind.ObjectMapper json,
                                     @Value("${content.sale.topic:content-sale}")String topic,SaleMapper db,SaleService sales,
                                     @Value("${content.sale.producer-warmup-deadline-ms:60000}")long warmupDeadlineMs,
                                     @Value("${content.sale.producer-warmup-retry-ms:1000}")long warmupRetryMs)throws Exception{
  saleProducer.start();
  var sender=new RocketMqSaleSender(saleProducer,json,topic,db);
  // Dispatch sees no sender (rows stay PENDING) until the topic route is resolved and the brokers answered a heartbeat.
  var impl=saleProducer.getDefaultMQProducerImpl();
  SaleProducerWarmup.run(
   ()->{
    var f=impl.getmQClientFactory();
    if(!f.updateTopicRouteInfoFromNameServer(topic)&&!f.updateTopicRouteInfoFromNameServer(topic,true,saleProducer))
     throw new IllegalStateException("sale topic route not available: "+topic);
    var info=impl.getTopicPublishInfoTable().get(topic);
    if(info==null||!info.ok())throw new IllegalStateException("sale topic has no writable queue: "+topic);
   },
   ()->{
    var f=impl.getmQClientFactory();
    if(!f.sendHeartbeatToAllBrokerWithLock())throw new IllegalStateException("broker heartbeat not sent");
    for(var mq:impl.getTopicPublishInfoTable().get(topic).getMessageQueueList())
     if(f.findBrokerAddressInPublish(mq.getBrokerName())==null)throw new IllegalStateException("no broker address for "+mq.getBrokerName());
   },
   ()->sales.setSender(sender),warmupDeadlineMs,warmupRetryMs);
  return sender;
 }

 @Bean(initMethod="start",destroyMethod="close")
 @ConditionalOnProperty(name="content.sale.consumer-enabled",havingValue="true")
 SaleConsumer saleConsumer(SaleService sales,
                           @Value("${content.sale.namesrv:${content.relay.namesrv:127.0.0.1:9876}}")String namesrv,
                           @Value("${content.sale.topic:content-sale}")String topic,
                           @Value("${content.sale.metadata-timeout-millis:3000}")int timeout,
                           @Value("${content.sale.settle-delay-ms:0}")long settleDelayMs,
                           @Value("${content.sale.consume-threads:20}")int consumeThreads,
                           @Value("${content.sale.consumer-group:content-sale-v1}")String group){
  return new SaleConsumer(sales,namesrv,topic,timeout,settleDelayMs,consumeThreads,group);
}

 /**
  * Default (docs/perf-evidence/commerce/campaign-lock-v26-clean-20261120.md, tier B2): batch dispatch, cap 20,
  * concurrency 2. With the campaign-row lock fixed (V26), concurrency 1 (tier B) is send-bound; 2 reaches the same
  * settle-commit ceiling as 4 (tier C) without piling messages into the Broker. "serial" keeps the original one-row
  * claim/send/confirm loop as a switch.
  * Not a @Configuration on purpose: it is only reached through the @Import above, so component scanning cannot pick it
  * up outside the content.media.enabled gate of the enclosing configuration.
  */
 @ConditionalOnProperty(name="content.sale.dispatch-mode",havingValue="batch",matchIfMissing=true)
 public static class Dispatch {
  @Bean(destroyMethod="close")
  SaleDispatcher saleDispatcher(SaleService sales,SaleIngress ingress,SaleFaultHooks faults,
   @Value("${content.sale.dispatch-batch-max:20}")int batchMax,@Value("${content.sale.dispatch-concurrency:2}")int concurrency,
   @Value("${content.sale.dispatch-slow-start-sends:4}")int slowStartSends){
   return new SaleDispatcher(ingress,sales::sender,faults,batchMax,concurrency,slowStartSends);
  }
 }

 @ConditionalOnProperty(name="content.sale.scan-enabled",havingValue="true")
 static class PendingScan {
  /**
   * content.sale.scan-delay-millis (1000): bridge cycle period, and the sender's pause after a pass that found work.
   * content.sale.dispatch-idle-max-millis (8000, hardening B4): after consecutive empty sender passes the pause doubles up
   * to this cap. A bridge cycle that persisted entries wakes the sender at once, so work enqueued by this instance never
   * waits for the backoff; rows only another instance enqueued, retries that become due and expired leases wait at most
   * the cap. Must be >= scan-delay-millis (equal = the old fixed polling).
   */
  @Bean(initMethod="start",destroyMethod="close") SalePendingScanner salePendingScanner(SaleService sales,SaleIngress ingress,
   org.springframework.beans.factory.ObjectProvider<SaleDispatcher> dispatcher,
   @Value("${content.sale.scan-delay-millis:1000}")long delay,@Value("${content.sale.dispatch-idle-max-millis:8000}")long idleMax){
   return new SalePendingScanner(sales,ingress,dispatcher.getIfAvailable(),delay,idleMax);
  }
 }
 static final class SalePendingScanner implements AutoCloseable {
  private final SaleService sales;private final SaleIngress ingress;private final SaleDispatcher dispatcher;private final long delay;
  private final java.util.concurrent.ScheduledExecutorService workers=java.util.concurrent.Executors.newScheduledThreadPool(1,r->{var t=new Thread(r,"sale-work");t.setDaemon(true);return t;});
  private final java.util.concurrent.atomic.AtomicLong lastWarning=new java.util.concurrent.atomic.AtomicLong();
  private final IdleBackoffLoop sender;
  SalePendingScanner(SaleService sales,SaleIngress ingress,SaleDispatcher dispatcher,long delay,long idleMax){
   this.sales=sales;this.ingress=ingress;this.dispatcher=dispatcher;this.delay=Math.max(10,delay);
   if(idleMax<this.delay)throw new IllegalArgumentException("content.sale.dispatch-idle-max-millis ("+idleMax+") must be >= scan-delay-millis ("+this.delay+")");
   this.sender=new IdleBackoffLoop(this::sendPass,this.delay,idleMax,this::warn);
  }
  private int sendPass(){
   if(dispatcher==null)return sales.dispatchPending(20);
   try{return dispatcher.pump(20);}catch(InterruptedException e){Thread.currentThread().interrupt();return 0;}
  }
  void start(){
   workers.scheduleWithFixedDelay(()->run(()->{try(var trace=SaleLatency.begin(null,"bridge-cycle",0,0)){
    ingress.prepare();int persisted=ingress.drain(200);ingress.syncJobs(200);
    if(persisted>0)sender.wake();
   }}),0,delay,java.util.concurrent.TimeUnit.MILLISECONDS);
   sender.start("sale-dispatch-loop");
  }
  private void run(Runnable task){try{task.run();}catch(Exception e){warn(e);}}
  private void warn(Exception e){long now=System.currentTimeMillis(),previous=lastWarning.get();if(now-previous>10000&&lastWarning.compareAndSet(previous,now))org.slf4j.LoggerFactory.getLogger(SalePendingScanner.class).warn("Sale background work will retry: {}",e.getClass().getSimpleName());}
  public void close(){sender.close();workers.shutdownNow();}
 }
}
