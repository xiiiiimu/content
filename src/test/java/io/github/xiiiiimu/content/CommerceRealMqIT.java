package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.infrastructure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real Broker tests on a Testcontainers RocketMQ ({@link CommerceMqFixture}); unique topic/group per suite. */
@EnabledIfEnvironmentVariable(named="CONTENT_REAL_MQ_IT",matches="true")
class CommerceRealMqIT extends CommerceIngressRevisionIT {
 static final String TOPIC="sale-revision-"+UUID.randomUUID().toString().replace("-","");
 @DynamicPropertySource static void mq(DynamicPropertyRegistry r){
  r.add("content.sale.producer-enabled",()->"true");r.add("content.sale.consumer-enabled",()->"true");
  r.add("content.sale.namesrv",CommerceMqFixture::namesrv);r.add("content.sale.topic",()->TOPIC);
  r.add("content.sale.consumer-group",()->TOPIC);r.add("content.sale.consume-threads",()->"4");
 }
 @BeforeAll static void topic()throws Exception{CommerceMqFixture.topic(TOPIC,"commerce-real-mq-topic.log");}
 @Autowired DefaultMQProducer producer;
 @Autowired SaleMapper saleMapper;
 @Test void realBrokerAckThenLostSenderConfirmationAndConsumerAckReplayOneOrder()throws Exception{
  var f=fixture(false,"AUTHOR_ONLY");var r=sales.purchase(f.buyer(),f.campaign().id(),"real-mq-replay");
  sales.faults().watchDeliveries(r.reservationId());sales.faults().armFailAfterCommitOnce();sales.faults().armFailure("dispatch.afterAck");
  sales.flushPending(10);
  assertThat(journal.dispatch(r.reservationId()).state()).isEqualTo("PENDING");
  org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(()->assertThat(sales.getRequest(f.buyer(),r.reservationId()).state()).isEqualTo("SUCCEEDED"));
  db.update("UPDATE material_sale_dispatch SET available_at_ms=0 WHERE reservation_id=?",r.reservationId());sales.flushPending(10);
  org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(()->assertThat(sales.faults().watchedDeliveries()).isGreaterThanOrEqualTo(2));
  assertThat(journal.dispatch(r.reservationId()).state()).isEqualTo("SENT");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE reservation_id=?",Integer.class,r.reservationId())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_consume_receipts WHERE reservation_id=?",Integer.class,r.reservationId())).isEqualTo(1);
 }
 @Test void unavailableRealProducerLeavesDurableRetryThenRealBrokerCompletes()throws Exception{
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer(),f.campaign().id(),"producer-unavailable");
  var unstarted=new DefaultMQProducer("unstarted-"+UUID.randomUUID());
  sales.setSender(new RocketMqSaleSender(unstarted,json,TOPIC,saleMapper));
  try{sales.flushPending(10);assertThat(journal.dispatch(r.reservationId()).state()).isEqualTo("PENDING");
   assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE reservation_id=?",Integer.class,r.reservationId())).isZero();
  }finally{sales.setSender(new RocketMqSaleSender(producer,json,TOPIC,saleMapper));}
  db.update("UPDATE material_sale_dispatch SET available_at_ms=0 WHERE reservation_id=?",r.reservationId());sales.flushPending(10);
  org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(()->assertThat(sales.getRequest(f.buyer(),r.reservationId()).state()).isEqualTo("SUCCEEDED"));
 }
}
