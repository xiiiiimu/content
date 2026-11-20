package io.github.xiiiiimu.content.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.domain.SaleService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Consumes PurchaseSettle; ACK only after SaleService.settle reaches a durable terminal state. */
 public final class SaleConsumer implements AutoCloseable {
 private final SaleService sales;private final String namesrv;private final String topic;private final int metadataTimeout;
 private final long settleDelayMs;private final int consumeThreads;
 private final String group;
 private DefaultMQPushConsumer consumer;
 public SaleConsumer(SaleService sales,String namesrv,String topic,int metadataTimeout){
  this(sales,namesrv,topic,metadataTimeout,0,20);
 }
 public SaleConsumer(SaleService sales,String namesrv,String topic,int metadataTimeout,long settleDelayMs){
  this(sales,namesrv,topic,metadataTimeout,settleDelayMs,20);
 }
 public SaleConsumer(SaleService sales,String namesrv,String topic,int metadataTimeout,long settleDelayMs,int consumeThreads){
  this(sales,namesrv,topic,metadataTimeout,settleDelayMs,consumeThreads,"content-sale-v1");
 }
 public SaleConsumer(SaleService sales,String namesrv,String topic,int metadataTimeout,long settleDelayMs,int consumeThreads,String group){
  this.sales=sales;this.namesrv=namesrv;this.topic=topic;this.metadataTimeout=metadataTimeout;
  this.settleDelayMs=Math.max(0,settleDelayMs);this.consumeThreads=Math.max(4,consumeThreads);
  this.group=group;
 }
 public synchronized void start()throws Exception{
  if(consumer!=null)return;
  Exception last=null;
  for(int attempt=0;attempt<5;attempt++){
   var c=new DefaultMQPushConsumer(group);
   c.setNamesrvAddr(namesrv);c.setInstanceName(UUID.randomUUID().toString());c.setVipChannelEnabled(false);
   c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
   c.setMqClientApiTimeout(metadataTimeout);
   // Default RocketMQ pool is 20; under 200-way flash settle locks starve purchase JDBC (mixed TIMEOUT).
   c.setConsumeThreadMin(consumeThreads);c.setConsumeThreadMax(consumeThreads);
   c.subscribe(topic,"PurchaseSettle");
   c.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
    try{
     if(settleDelayMs>0)Thread.sleep(settleDelayMs);
     for(var m:messages){
      String body=new String(m.getBody(),StandardCharsets.UTF_8);
      String reservationId=m.getKeys();
      if(reservationId==null || reservationId.isBlank()){
       var node=new ObjectMapper().readTree(body);reservationId=node.path("reservationId").asText();
      }
      sales.faults().recordDelivery(reservationId);sales.settle(reservationId);
      // After durable commit: optional one-shot failure before ACK to force MQ redelivery.
      sales.faults().maybeFailAfterCommit();
     }
     return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }catch(Exception e){
     org.slf4j.LoggerFactory.getLogger(SaleConsumer.class).warn("Sale settle consume retry",e);
     return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }
   });
   try{c.start();consumer=c;return;}catch(Exception failure){c.shutdown();last=failure;}
  }
  throw last==null?new IllegalStateException("sale consumer"):last;
 }
 @Override public synchronized void close(){if(consumer!=null){consumer.shutdown();consumer=null;}}
}
