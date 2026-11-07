package io.github.xiiiiimu.content;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.xiiiiimu.content.domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class ProductionFeedIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.feed.history-limit",()->"3");r.add("content.feed.batch-size",()->"1");r.add("content.feed.max-scanned",()->"16");r.add("content.feed.max-reads",()->"17");
 }
 @Autowired MockMvc http; @Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 static final AtomicLong IDS=new AtomicLong(1000);
 @BeforeEach void clean() {
  for(String table:List.of("publish_requests","feed_delivery_tasks","consumer_inbox","outbox_events","follow_history","follows","follow_graph_version","posts","feed_mode_transitions","author_feed_mode"))db.update("DELETE FROM "+table);
  assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){assertThat(c.sync().ping()).isEqualTo("PONG");c.sync().flushdb();}
 }
 long author(String mode){long id=IDS.incrementAndGet();db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,?)",id,mode);return id;}
 long now(){return db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class);}
 void seed(long author,int n) {
  long time=now()-1000;db.update("UPDATE author_feed_mode SET publish_seq=? WHERE author_id=?",n,author);
  var args=new ArrayList<Object[]>();for(int i=1;i<=n;i++)args.add(new Object[]{author*10000+i,author,"fixture-"+i,time,i});
  db.batchUpdate("INSERT INTO posts(id,author_id,status,version,body,published_at_ms,publish_seq,delivery_mode,delivery_epoch) VALUES(?,?,'PUBLISHED',1,?,?,?,(SELECT mode FROM author_feed_mode WHERE author_id=?),0)",args.stream().map(a -> new Object[]{a[0],a[1],a[2],a[3],a[4],author}).toList());
 }
 JsonNode page(long reader,int limit,String cursor)throws Exception {
  var req=get("/api/feed/following").param("limit",Integer.toString(limit)).principal(()->Long.toString(reader));if(cursor!=null)req.param("cursor",cursor);
  return json.readTree(http.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
 }
 List<Long> drain(long reader,int limit)throws Exception {
  var ids=new ArrayList<Long>();String cursor=null;FeedCandidate previous=null;
  for(int call=0;call<1000;call++){
   var p=page(reader,limit,cursor);p.get("items").forEach(item->ids.add(item.get("postId").longValue()));
   if(p.get("nextCursor").isNull()){assertThat(p.get("pageState").asText()).isEqualTo("EXHAUSTED");return ids;}
   cursor=p.get("nextCursor").asText();var state=new FeedTokens("cursor-test-key-32-bytes-minimum-00001","CURSOR").verify(cursor,FollowingFeed.Cursor.class);
   if(previous!=null)assertThat(FeedMergerTest.ORDER.compare(state.consumedBoundary(),previous)).isNegative();previous=state.consumedBoundary();
  }throw new AssertionError("cursor did not terminate");
 }
 @Test void followPublishAndReadThroughProductionHttpAndAuthority() throws Exception {
  assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()) {assertThat(c.sync().ping()).isEqualTo("PONG");}
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(101,'PUSH')");
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(10101,101,'DRAFT','production chain')");
  http.perform(put("/api/follows/101").principal(()->"201")).andExpect(status().isOk());
  http.perform(post("/api/posts/10101/publish").principal(()->"101").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expectedVersion\":0}")).andExpect(status().isOk());
  http.perform(get("/api/feed/following?limit=20").principal(()->"201")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].postId").value(10101));
  http.perform(delete("/api/follows/101").principal(()->"201")).andExpect(status().isOk());
  http.perform(get("/api/feed/following").principal(()->"201")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
 }
 @Test void bearerEnablesRealPrincipalWithoutTrustingReaderHeader()throws Exception {
  var tokens=new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY");long now=System.currentTimeMillis();
  String signed=tokens.sign(new FeedTokens.Identity(1,201,now,now+60_000));
  http.perform(get("/api/feed/following").header("Authorization","Bearer "+signed)).andExpect(status().isOk());
  http.perform(get("/api/feed/following").header("X-User-Id","201")).andExpect(status().isUnauthorized());
  http.perform(get("/api/feed/following").header("Authorization","Bearer "+signed.substring(0,signed.length()-2)+"xx")).andExpect(status().isUnauthorized());
  String expired=tokens.sign(new FeedTokens.Identity(1,201,now-120_000,now-60_000));
  http.perform(get("/api/feed/following").header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized());
 }
 @Test void productionSameMillisecond501TraversesAllViaSignedHttpCursor()throws Exception {
  long a=author("PUSH"),r=201;http.perform(put("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk());seed(a,501);
  var actual=drain(r,7);var expected=db.queryForList("SELECT id FROM posts ORDER BY published_at_ms DESC,id DESC",Long.class);
  assertThat(actual).hasSize(501).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
 }
 @Test void hundredOfflinedCandidatesYieldAdvancingEmptyUnknownPages()throws Exception {
  long a=author("PUSH");http.perform(put("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk());seed(a,105);
  db.update("UPDATE posts SET status='OFFLINE' WHERE id>?",a*10000+5);
  var first=page(201,5,null);assertThat(first.get("items")).isEmpty();assertThat(first.get("pageState").asText()).isEqualTo("UNKNOWN");assertThat(first.get("scanned").intValue()).isBetween(1,16);
  assertThat(drain(201,5)).containsExactly(a*10000+5,a*10000+4,a*10000+3,a*10000+2,a*10000+1);
 }
 @Test void cursorBindsReaderLimitGraphAndExpiry()throws Exception {
  long a=author("PULL");http.perform(put("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk());seed(a,4);
  String cursor=page(201,1,null).get("nextCursor").asText();
  var signer=new FeedTokens("cursor-test-key-32-bytes-minimum-00001","CURSOR");var c=signer.verify(cursor,FollowingFeed.Cursor.class);
  String expired=signer.sign(new FollowingFeed.Cursor(c.version(),c.reader(),c.limit(),c.snapshot(),c.since(),c.globalBoundary(),c.consumedBoundary(),c.graph(),c.generation(),now()-1));
  http.perform(get("/api/feed/following").param("limit","1").param("cursor",expired).principal(()->"201")).andExpect(status().isBadRequest());
  http.perform(get("/api/feed/following").param("limit","1").param("cursor",cursor).principal(()->"202")).andExpect(status().isBadRequest());
  http.perform(get("/api/feed/following").param("limit","2").param("cursor",cursor).principal(()->"201")).andExpect(status().isBadRequest());
  http.perform(get("/api/feed/following").param("limit","1").param("cursor",cursor+"x").principal(()->"201")).andExpect(status().isBadRequest());
  long b=author("PUSH");http.perform(put("/api/follows/"+b).principal(()->"201")).andExpect(status().isOk());
  http.perform(get("/api/feed/following").param("limit","1").param("cursor",cursor).principal(()->"201")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURSOR_STALE"));
 }
 @Test void historyIsFixedNotRefilledAndRefollowCreatesNewEpoch()throws Exception {
  long a=author("PUSH");seed(a,5);
  http.perform(put("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk()).andExpect(jsonPath("$.epoch").value(1));
  http.perform(put("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk()).andExpect(jsonPath("$.epoch").value(1));
  assertThat(drain(201,20)).containsExactly(a*10000+5,a*10000+4,a*10000+3);
  http.perform(post("/api/posts/"+(a*10000+5)+"/offline").principal(()->Long.toString(a)).contentType("application/json").content("{\"expectedVersion\":1}")).andExpect(status().isOk());
  assertThat(drain(201,20)).containsExactly(a*10000+4,a*10000+3);
  http.perform(delete("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk());assertThat(drain(201,20)).isEmpty();
  http.perform(put("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk()).andExpect(jsonPath("$.epoch").value(2));
  assertThat(drain(201,20)).containsExactly(a*10000+4,a*10000+3,a*10000+2);
  assertThat(db.queryForObject("SELECT version FROM follow_graph_version WHERE follower_id=201",Long.class)).isEqualTo(3);
 }
 @Test void noOtherReaderOrUnfollowedHistoryCanAuthorizePosts()throws Exception {
  long a=author("PULL");seed(a,5);http.perform(put("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk());
  assertThat(drain(202,20)).isEmpty();http.perform(delete("/api/follows/"+a).principal(()->"201")).andExpect(status().isOk());assertThat(drain(201,20)).isEmpty();
  http.perform(get("/api/feed/following")).andExpect(status().isUnauthorized());
 }
}
