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
                                     @Value("${content.sale.topic:content-sale}")String topic,SaleMapper db,SaleService sales)throws Exception{
  saleProducer.start();
  var sender=new RocketMqSaleSender(saleProducer,json,topic,db);
  sales.setSender(sender);
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

 @ConditionalOnProperty(name="content.sale.scan-enabled",havingValue="true")
 static class PendingScan {
  @Bean(initMethod="start",destroyMethod="close") SalePendingScanner salePendingScanner(SaleService sales,SaleIngress ingress,
   @Value("${content.sale.scan-delay-millis:1000}")long delay){return new SalePendingScanner(sales,ingress,delay);}
 }
 static final class SalePendingScanner implements AutoCloseable {
  private final SaleService sales;private final SaleIngress ingress;private final long delay;
  private final java.util.concurrent.ScheduledExecutorService workers=java.util.concurrent.Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"sale-work");t.setDaemon(true);return t;});
  private final java.util.concurrent.atomic.AtomicLong lastWarning=new java.util.concurrent.atomic.AtomicLong();
  SalePendingScanner(SaleService sales,SaleIngress ingress,long delay){this.sales=sales;this.ingress=ingress;this.delay=Math.max(10,delay);}
  void start(){
   workers.scheduleWithFixedDelay(()->run(()->{try(var trace=SaleLatency.begin(null,"bridge-cycle",0,0)){ingress.prepare();ingress.drain(200);ingress.syncJobs(200);}}),0,delay,java.util.concurrent.TimeUnit.MILLISECONDS);
   workers.scheduleWithFixedDelay(()->run(()->sales.dispatchPending(20)),0,delay,java.util.concurrent.TimeUnit.MILLISECONDS);
  }
  private void run(Runnable task){try{task.run();}catch(Exception e){long now=System.currentTimeMillis(),previous=lastWarning.get();if(now-previous>10000&&lastWarning.compareAndSet(previous,now))org.slf4j.LoggerFactory.getLogger(SalePendingScanner.class).warn("Sale background work will retry: {}",e.getClass().getSimpleName());}}
  public void close(){workers.shutdownNow();}
 }
}
