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

 @Bean SaleService saleService(SaleMapper sales,SaleRedis redis,MaterialMapper materials,FeedMapper posts,
                               NotificationMapper notifications,VisibilityService visibility,TransactionTemplate tx,
                               @Value("${content.sale.sync-settle:false}") boolean syncSettle){
  return new SaleService(sales,redis,materials,posts,notifications,visibility,tx,syncSettle);
 }

 @Bean @ConditionalOnProperty(name="content.sale.producer-enabled",havingValue="true")
 DefaultMQProducer saleProducer(@Value("${content.sale.namesrv:${content.relay.namesrv:127.0.0.1:9876}}")String namesrv){
  var p=new DefaultMQProducer("content-sale-producer");
  p.setNamesrvAddr(namesrv);p.setVipChannelEnabled(false);p.setInstanceName(UUID.randomUUID().toString());
  p.setRetryTimesWhenSendFailed(0);return p;
 }

 @Bean @ConditionalOnProperty(name="content.sale.producer-enabled",havingValue="true")
 SaleMessageSender saleMessageSender(DefaultMQProducer saleProducer,com.fasterxml.jackson.databind.ObjectMapper json,
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
                           @Value("${content.sale.metadata-timeout-millis:3000}")int timeout){
  return new SaleConsumer(sales,namesrv,topic,timeout);
 }

 @EnableScheduling
 @ConditionalOnProperty(name="content.sale.scan-enabled",havingValue="true")
 static class PendingScan {
  @Bean SalePendingScanner salePendingScanner(SaleService sales){return new SalePendingScanner(sales);}
 }
 static final class SalePendingScanner {
  private final SaleService sales;
  SalePendingScanner(SaleService sales){this.sales=sales;}
  @Scheduled(fixedDelayString="${content.sale.scan-delay-millis:1000}")
  public void tick(){sales.flushPending(50);}
 }
}
