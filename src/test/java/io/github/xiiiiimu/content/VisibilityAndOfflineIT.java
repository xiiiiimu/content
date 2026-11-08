package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** T10a: warmed detail/feed caches cannot authorize after authoritative offline. */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class VisibilityAndOfflineIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379)
  .withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.test.hooks",()->"visibility");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired MockMvc http;
 @Autowired(required=false) VisibilityService visibility;@Autowired(required=false) PostDetailService details;
 @Autowired FollowingFeed feed;@Autowired Faults faults;
 static final Path EVIDENCE=Path.of("target","visibility-evidence");
 @TestConfiguration @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="content.test.hooks",havingValue="visibility")
 static class Hooks {
  @Bean @Primary Faults faults(){return new Faults();}
 }
 static class Faults implements PipelineObserver {
  volatile CountDownLatch hold=new CountDownLatch(0);
  volatile AtomicReference<String> lastInvalidate=new AtomicReference<>();
  @Override public void beforeCacheInvalidate(long postId){
   lastInvalidate.set("post="+postId);
   try{if(!hold.await(20,TimeUnit.SECONDS))throw new IllegalStateException("invalidate barrier timeout");}
   catch(InterruptedException e){throw new IllegalStateException(e);}
  }
 }
 @BeforeEach void reset()throws Exception{
  Files.createDirectories(EVIDENCE);
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(901,'PUSH')");
  faults.hold=new CountDownLatch(0);faults.lastInvalidate.set(null);
 }
 String bearer(long user){
  var tokens=new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY");
  long now=System.currentTimeMillis();return tokens.sign(new FeedTokens.Identity(1,user,now,now+300_000));
 }
 @Test void t10aWarmedDetailAndFeedRejectAfterAuthoritativeOfflineEvenWithDelayedInvalidate()throws Exception{
  assertThat(visibility).as("VisibilityService must exist for all read entries").isNotNull();
  assertThat(details).as("Post detail service with cache must exist").isNotNull();
  follows.change(902,901,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(90101,901,'DRAFT','visible then offline')");
  var publication=publish.publish(901,90101,UUID.randomUUID().toString(),0);
  http.perform(get("/api/posts/90101").header("Authorization","Bearer "+bearer(902)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.postId").value(90101)).andExpect(jsonPath("$.body").value("visible then offline"));
  assertThat(details.cachedVersion(90101)).as("detail cache must be warm before offline").isEqualTo(publication.postVersion());
  var page=feed.read(902,20,null);assertThat(page.items().stream().map(FollowingFeed.Item::postId)).contains(90101L);
  faults.hold=new CountDownLatch(1);
  var offline=CompletableFuture.runAsync(()->follows.offline(901,90101,publication.postVersion()));
  await().atMost(java.time.Duration.ofSeconds(5)).until(()->faults.lastInvalidate.get()!=null);
  http.perform(get("/api/posts/90101").header("Authorization","Bearer "+bearer(902))).andExpect(status().isNotFound());
  assertThat(visibility.read(902,90101)).isEmpty();
  assertThat(feed.read(902,20,null).items().stream().map(FollowingFeed.Item::postId)).doesNotContain(90101L);
  faults.hold.countDown();offline.get(10,TimeUnit.SECONDS);
  await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(()->assertThat(details.cachedVersion(90101)).isNull());
  Files.writeString(EVIDENCE.resolve("t10a.json"),"{\"postId\":90101,\"warmVersion\":"+publication.postVersion()+",\"invalidate\":\""+faults.lastInvalidate.get()+"\",\"eventId\":"+publication.eventId()+"}\n");
}
}
