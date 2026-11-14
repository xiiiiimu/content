package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MaterialCommerceRefundBlacklistIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.media.enabled",()->"true");
  r.add("content.media.s3-endpoint",()->"http://"+S3.getHost()+":"+S3.getMappedPort(9090));
  r.add("content.media.bucket",()->"content-media");
  r.add("content.media.secret-key",()->"content-secret-key-32-bytes-minimum");
  r.add("content.media.ffmpeg-image",()->"");
  r.add("content.assistant.python-url",()->"");
  r.add("content.media.scan-delay-millis",()->"3600000");
  r.add("content.sale.sync-settle",()->"true");
 }
 @Autowired JdbcTemplate db;@Autowired MaterialService materials;@Autowired PublishService publish;
 @Autowired SaleService sales;@Autowired AuthService auth;@Autowired GiveawayService giveaways;
 @Autowired InteractionService interactions;@Autowired EligibilityRegistry eligibility;@Autowired NotificationService notifications;
 @Autowired RelationshipService follows;@Autowired com.fasterxml.jackson.databind.ObjectMapper json;

 @BeforeEach void wipe(){
  db.update("DELETE FROM material_sale_consume_receipts");
  db.update("DELETE FROM material_refund_requests");
  db.update("DELETE FROM material_orders");
  db.update("DELETE FROM material_purchase_requests");
  db.update("DELETE FROM material_sale_campaigns");
  db.update("DELETE FROM merchant_blacklist");
  db.update("DELETE FROM account_material_decision_locks");
  db.update("DELETE FROM giveaway_winners");
  db.update("DELETE FROM giveaway_candidates");
  db.update("DELETE FROM material_entitlements");
  db.update("DELETE FROM giveaways");
  db.update("DELETE FROM notifications");
 }

 @Test void autoRefundOnWinKeepsGiveawayEntitlementAndPurchaseEligibility()throws Exception{
  long author=register("rf_author","password-a1");
  long buyer=register("rf_buyer","password-b1");
  var mat=materials.createDraft(author,"退款素材","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  follows.change(buyer,author,true);
  long now=System.currentTimeMillis();
  var campaign=sales.createCampaign(author,mat.postId(),500,5,now-1_000,now+3_600_000);
  var purchase=sales.purchase(buyer,campaign.id(),"buy-1");
  assertThat(purchase.state()).isEqualTo("SUCCEEDED");
  long orderId=purchase.orderId();

  interactions.like(buyer,mat.postId(),"drone");
  long deadline=System.currentTimeMillis()+2_000;
  var ask=interactions.comment(author,mat.postId(),"@助手 从点赞过的人里抽1人 截止epoch:"+deadline,"g-rf","drone",null,null,null);
  // create giveaway directly via service path used by assistant
  var cmd=new AssistantParser.GiveawayCommand(
   "{\"op\":\"ALL\",\"children\":[{\"type\":\"LIKE\"}]}","Asia/Shanghai",null,deadline,1,"DEFAULT","DOWNLOAD","test");
  giveaways.createFromAssistant(author,mat.postId(),ask.commentId(),cmd);
  Long gid=db.queryForObject("SELECT id FROM giveaways WHERE source_comment_id=?",Long.class,ask.commentId());
  while(System.currentTimeMillis()<=deadline)Thread.sleep(40);
  giveaways.seal(gid);giveaways.draw(gid);
  giveaways.draw(gid);

  assertThat(db.queryForObject("SELECT state FROM material_orders WHERE id=?",String.class,orderId)).isEqualTo("REFUNDED");
  assertThat(db.queryForObject("SELECT refund_reason FROM material_orders WHERE id=?",String.class,orderId)).isEqualTo("GIVEAWAY_AUTO");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_refund_requests WHERE order_id=? AND state='SUCCEEDED'",Integer.class,orderId)).isEqualTo(1);
  long reward=db.queryForObject("SELECT reward_version_id FROM giveaways WHERE id=?",Long.class,gid);
  assertThat(giveaways.hasEntitlement(buyer,mat.postId(),reward)).isTrue();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE source_kind='PURCHASE' AND source_id=? AND state='ACTIVE'",Integer.class,orderId)).isZero();
  long refundNotices=notifications.list(buyer,20).stream().filter(n->"ORDER_REFUNDED".equals(n.kind())).count();
  assertThat(refundNotices).isEqualTo(1);

  var tree=json.readTree("{\"op\":\"ALL\",\"children\":[{\"type\":\"PURCHASE\"}]}");
  Set<Long> eligible=eligibility.evaluate(tree,mat.postId(),author,0L,null,null);
  assertThat(eligible).contains(buyer);

  assertThatThrownBy(()->sales.requestRefund(buyer,orderId)).isInstanceOf(FeedFailure.class);
 }

 @Test void buyerForcedRefundExcludesPurchaseAndCrossMaterialBlacklist()throws Exception{
  long author=register("bl_author","password-a1");
  long buyer=register("bl_buyer","password-b1");
  var mat1=materials.createDraft(author,"黑名单1","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat1.postId(),UUID.randomUUID().toString(),0);
  var mat2=materials.createDraft(author,"黑名单2","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video2".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat2.postId(),UUID.randomUUID().toString(),0);
  follows.change(buyer,author,true);

  long now=System.currentTimeMillis();
  var c1=sales.createCampaign(author,mat1.postId(),100,2,now-1_000,now+3_600_000);
  var c2=sales.createCampaign(author,mat2.postId(),100,2,now-1_000,now+3_600_000);
  var p=sales.purchase(buyer,c1.id(),"bl-buy");
  assertThat(p.state()).isEqualTo("SUCCEEDED");
  sales.forceRefund(author,p.orderId());
  assertThat(db.queryForObject("SELECT state FROM material_orders WHERE id=?",String.class,p.orderId())).isEqualTo("REFUNDED");
  assertThat(db.queryForObject("SELECT refund_reason FROM material_orders WHERE id=?",String.class,p.orderId())).isEqualTo("BUYER_FORCED");

  var tree=json.readTree("{\"op\":\"ALL\",\"children\":[{\"type\":\"PURCHASE\"}]}");
  assertThat(eligibility.evaluate(tree,mat1.postId(),author,0L,null,null)).doesNotContain(buyer);

  sales.block(author,buyer,"abuse");
  var blocked=sales.purchase(buyer,c2.id(),"bl-buy-2");
  assertThat(blocked.state()).isEqualTo("REJECTED");
  assertThat(blocked.rejectReason()).isEqualTo("BLACKLISTED");
  sales.unblock(author,buyer);
  var ok=sales.purchase(buyer,c2.id(),"bl-buy-3");
  assertThat(ok.state()).isEqualTo("SUCCEEDED");
 }

 private long register(String user,String pass){
  auth.register(user,pass);
  return db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,user);
 }
}
