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
 private final long settleDelayMs;
 private DefaultMQPushConsumer consumer;
 public SaleConsumer(SaleService sales,String namesrv,String topic,int metadataTimeout){
  this(sales,namesrv,topic,metadataTimeout,0);
 }
 public SaleConsumer(SaleService sales,String namesrv,String topic,int metadataTimeout,long settleDelayMs){
  this.sales=sales;this.namesrv=namesrv;this.topic=topic;this.metadataTimeout=metadataTimeout;this.settleDelayMs=Math.max(0,settleDelayMs);
 }
 public synchronized void start()throws Exception{
  if(consumer!=null)return;
  Exception last=null;
  for(int attempt=0;attempt<5;attempt++){
   var c=new DefaultMQPushConsumer("content-sale-v1");
   c.setNamesrvAddr(namesrv);c.setInstanceName(UUID.randomUUID().toString());c.setVipChannelEnabled(false);
   c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
   c.setMqClientApiTimeout(metadataTimeout);
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
      sales.settle(reservationId);
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
