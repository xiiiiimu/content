package io.github.xiiiiimu.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.domain.SaleMessageSender;
import io.github.xiiiiimu.content.domain.SaleService;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class RocketMqSaleSender implements SaleMessageSender {
 private final DefaultMQProducer producer;private final ObjectMapper json;private final String topic;private final SaleMapper db;
 public RocketMqSaleSender(DefaultMQProducer producer,ObjectMapper json,String topic,SaleMapper db){
  this.producer=producer;this.json=json;this.topic=topic;this.db=db;
 }
 @Override public void send(String reservationId)throws Exception{
  var req=db.request(reservationId);if(req==null)return;
  String body=json.writeValueAsString(Map.of(
   "reservationId",req.reservationId(),"campaignId",req.campaignId(),"userId",req.userId(),
   "postId",req.postId(),"merchantId",req.merchantId(),"clientKey",req.clientKey(),"priceCents",req.priceCents()
  ));
  var message=new Message(topic,"PurchaseSettle",reservationId,body.getBytes(StandardCharsets.UTF_8));
  producer.send(message,10_000);
 }

 /** No-op sender used when MQ is disabled; settle is invoked synchronously by SaleService. */
 public static final class Sync implements SaleMessageSender {
  private final SaleService sales;
  public Sync(SaleService sales){this.sales=sales;}
  @Override public void send(String reservationId){sales.settle(reservationId);}
 }
}
