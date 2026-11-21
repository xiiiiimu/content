package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.SaleDispatcher;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Opt-in batch dispatch contracts on the local real Broker (unique topic/group).
 * Run only: CONTENT_REAL_MQ_IT=true -Dit.test='CommerceDispatchBatchRealMqIT#realBatch*'.
 */
@EnabledIfEnvironmentVariable(named="CONTENT_REAL_MQ_IT",matches="true")
class CommerceDispatchBatchRealMqIT extends CommerceDispatchBatchIT {
 static final String TOPIC="sale-dispatch-batch-"+UUID.randomUUID().toString().replace("-","");
 @DynamicPropertySource static void mq(DynamicPropertyRegistry r){
  r.add("content.sale.producer-enabled",()->"true");r.add("content.sale.consumer-enabled",()->"true");
  r.add("content.sale.namesrv",()->"127.0.0.1:29876");r.add("content.sale.topic",()->TOPIC);
  r.add("content.sale.consumer-group",()->TOPIC);r.add("content.sale.consume-threads",()->"4");
 }
 @BeforeAll static void topic()throws Exception{
  Path log=Path.of("target","commerce-dispatch-batch-topic.log");
  var p=new ProcessBuilder("docker","exec","content-broker-1","sh","mqadmin","updateTopic","-n","namesrv:9876","-b","127.0.0.1:30911","-t",TOPIC,"-r","4","-w","4")
   .redirectErrorStream(true).redirectOutput(log.toFile()).start();
  if(!p.waitFor(60,TimeUnit.SECONDS)||p.exitValue()!=0)throw new IllegalStateException("Real Broker topic setup failed; see "+log);
 }
 int orders(String id){return db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE reservation_id=?",Integer.class,id);}
 int receipts(String id){return db.queryForObject("SELECT COUNT(*) FROM material_sale_consume_receipts WHERE reservation_id=?",Integer.class,id);}

 @Test void realBatchAckThenCrashBeforeConfirmIsTakenOverWithoutDuplicateOrder()throws Exception{
  var ids=ready(2);sales.faults().watchDeliveries(ids.get(0));
  try(var d=new SaleDispatcher(ingress,sales::sender,sales.faults(),20,1)){
   sales.faults().armFailure("dispatch.beforeConfirm");
   var crashed=d.dispatchOnce();
   assertThat(crashed.sent()).as("real Broker returned SEND_OK for both").isEqualTo(2);
   assertThat(crashed.outcomePersisted()).isFalse();
   assertThat(count("state='SENDING' AND token=1")).as("ACK without confirmation is not recorded as SENT").isEqualTo(2);
   await().atMost(Duration.ofSeconds(30)).untilAsserted(()->ids.forEach(id->assertThat(orders(id)).isEqualTo(1)));
   ids.forEach(this::expire);
   var takeover=d.dispatchOnce();
   assertThat(takeover.claimed()).isEqualTo(2);assertThat(takeover.sent()).isEqualTo(2);assertThat(takeover.outcomePersisted()).isTrue();
   await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(sales.faults().watchedDeliveries()).isGreaterThanOrEqualTo(2));
  }
  assertThat(count("state='SENT' AND token=2 AND attempts=2")).isEqualTo(2);
  for(var id:ids){assertThat(orders(id)).as("duplicate delivery does not create a second order").isEqualTo(1);assertThat(receipts(id)).isEqualTo(1);}
 }
 @Test void realBatchConcurrencyFourDeliversEveryClaimOnce()throws Exception{
  var ids=ready(8);
  try(var d=new SaleDispatcher(ingress,sales::sender,sales.faults(),20,4)){
   var r=d.dispatchOnce();
   assertThat(r.claimed()).as("4 idle senders x 2").isEqualTo(8);
   assertThat(r.sent()).isEqualTo(8);assertThat(r.confirmMissed()).isZero();assertThat(r.outcomePersisted()).isTrue();
  }
  assertThat(count("state='SENT' AND token=1")).isEqualTo(8);
  await().atMost(Duration.ofSeconds(30)).untilAsserted(()->ids.forEach(id->assertThat(orders(id)).isEqualTo(1)));
  for(var id:ids)assertThat(receipts(id)).isEqualTo(1);
 }
}
