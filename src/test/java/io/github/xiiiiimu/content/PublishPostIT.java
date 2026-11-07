package io.github.xiiiiimu.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublishPostIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    @BeforeEach void fixtures() {
        db.execute("DROP TRIGGER IF EXISTS fail_outbox");
        db.execute("DROP TRIGGER IF EXISTS fail_receipt");
        // Receipt table is introduced by this Story's migration, absent in RED.
        if (db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='publish_requests'", Integer.class) > 0)
            db.update("DELETE FROM publish_requests");
        db.update("DELETE FROM outbox_events");
        db.update("DELETE FROM posts");
        db.update("DELETE FROM author_feed_mode");
        db.update("INSERT INTO author_feed_mode(author_id,mode,mode_epoch,publish_seq) VALUES (1,'PUSH',7,10),(2,'PULL',3,0)");
        db.update("INSERT INTO posts(id,author_id,status,body,version) VALUES (100,1,'DRAFT','original text',4),(101,1,'DRAFT','second',0),(200,2,'DRAFT','other author',0)");
    }
    MvcResult request(long author, long postId, String key, String body) throws Exception {
        var request = post("/api/posts/{id}/publish", postId).contentType("application/json").content(body);
        if (author > 0) request.principal(() -> Long.toString(author));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request).andReturn();
    }
    MvcResult publish(long postId, String key, long version) throws Exception {
        return request(1, postId, key, "{\"expectedVersion\":" + version + "}");
    }
    void code(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText()).isEqualTo(code);
    }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    void oneEffect() {
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("publish_requests")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT publish_seq FROM author_feed_mode WHERE author_id=1", Long.class)).isEqualTo(11);
    }
    @Test void publishesDraftAndPersistsMatchingEventAndReceipt() throws Exception {
        var result = publish(100,"key",4);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var post = db.queryForMap("SELECT * FROM posts WHERE id=100");
        assertThat(post).containsEntry("status","PUBLISHED").containsEntry("body","original text")
                .containsEntry("version",5L).containsEntry("delivery_mode","PUSH")
                .containsEntry("delivery_epoch",7L).containsEntry("publish_seq",11L);
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        JsonNode event = json.readTree(db.queryForObject("SELECT payload FROM outbox_events", String.class));
        assertThat(event).isEqualTo(response);
        assertThat(event.path("eventId").asLong()).isEqualTo(db.queryForObject("SELECT id FROM outbox_events",Long.class));
        assertThat(event.path("postId").asLong()).isEqualTo(100);
        assertThat(event.path("authorId").asLong()).isEqualTo(1);
        assertThat(event.path("deliveryMode").asText()).isEqualTo("PUSH");
        assertThat(event.path("deliveryEpoch").asLong()).isEqualTo(7);
        assertThat(event.path("publishSeq").asLong()).isEqualTo(11);
        assertThat(event.path("postVersion").asLong()).isEqualTo(5);
        assertThat(event.path("publishedAtMs").asLong()).isEqualTo(post.get("published_at_ms"));
        assertThat(db.queryForObject("SELECT event_type FROM outbox_events",String.class)).isEqualTo("PostPublished");
        assertThat(db.queryForObject("SELECT status FROM outbox_events",String.class)).isEqualTo("NEW");
        assertThat(json.readTree(db.queryForObject("SELECT response_snapshot FROM publish_requests",String.class))).isEqualTo(response);
        oneEffect();
    }
    @Test void replaysSnapshotBeforeVersionAndStateChecksAndAfterModeChanges() throws Exception {
        var first = publish(100,"key",4);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        var stored = db.queryForMap("SELECT * FROM posts WHERE id=100");
        db.update("UPDATE author_feed_mode SET mode='PULL',mode_epoch=8 WHERE author_id=1");
        var replay = publish(100,"key",4);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(db.queryForMap("SELECT * FROM posts WHERE id=100")).isEqualTo(stored);
        oneEffect();
    }
    @Test void rejectsKeyReusedForOtherDraft() throws Exception {
        assertThat(publish(100,"key",4).getResponse().getStatus()).isEqualTo(200);
        code(publish(101,"key",0),409,"IDEMPOTENCY_CONFLICT"); oneEffect();
    }
    @Test void rejectsKeyReusedWithChangedExpectedVersion() throws Exception {
        assertThat(publish(100,"key",4).getResponse().getStatus()).isEqualTo(200);
        code(publish(100,"key",5),409,"IDEMPOTENCY_CONFLICT"); oneEffect();
    }
    @Test void rejectsNewKeyForPublishedPost() throws Exception {
        assertThat(publish(100,"first",4).getResponse().getStatus()).isEqualTo(200);
        code(publish(100,"second",5),409,"POST_STATE_CONFLICT"); oneEffect();
    }
    @Test void rejectsStaleVersionWithoutConsumingKey() throws Exception {
        code(publish(100,"key",3),409,"POST_VERSION_CONFLICT");
        assertThat(count("outbox_events")).isZero();
        assertThat(publish(100,"key",4).getResponse().getStatus()).isEqualTo(200); oneEffect();
    }
    @Test void checksOwnershipAndExistenceAndAuthentication() throws Exception {
        code(request(2,100,"key","{\"expectedVersion\":4}"),403,"POST_FORBIDDEN");
        code(publish(999,"key",0),404,"POST_NOT_FOUND");
        code(request(0,100,"key","{\"expectedVersion\":4}"),401,"AUTHENTICATION_REQUIRED");
        assertThat(publish(100,"key",4).getResponse().getStatus()).isEqualTo(200);
        code(request(2,100,"key","{\"expectedVersion\":4}"),403,"POST_FORBIDDEN");
    }
    @Test void keysAreIsolatedByAuthor() throws Exception {
        assertThat(publish(100,"key",4).getResponse().getStatus()).isEqualTo(200);
        assertThat(request(2,200,"key","{\"expectedVersion\":0}").getResponse().getStatus()).isEqualTo(200);
        assertThat(count("outbox_events")).isEqualTo(2);
    }
    @Test void rejectsEveryNonDraftState() throws Exception {
        for (String state : List.of("PUBLISHED","PROCESSING","FAILED","OFFLINE")) {
            db.update("UPDATE posts SET status=? WHERE id=100",state);
            code(publish(100,"key",4),409,"POST_STATE_CONFLICT");
        }
        assertThat(count("outbox_events")).isZero();
    }
    @Test void validatesRequestFormat() throws Exception {
        for (String body : List.of("{}","{\"expectedVersion\":null}","{\"expectedVersion\":-1}","{\"expectedVersion\":1.5}","{\"expectedVersion\":\"4\"}","{\"expectedVersion\":true}","{\"expectedVersion\":9223372036854775808}","{\"expectedVersion\":4,\"body\":\"changed\"}","{"))
            assertThat(request(1,100,"key",body).getResponse().getStatus()).as(body).isEqualTo(400);
        for (String key : new String[]{null,""," ","x".repeat(129)})
            assertThat(request(1,100,key,"{\"expectedVersion\":4}").getResponse().getStatus()).isEqualTo(400);
    }
    @Test void concurrentSameRequestReturnsSameSnapshot() throws Exception {
        var results = compete("same","same",100,100,4,4);
        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        assertThat(results.get(0).getResponse().getContentAsString()).isEqualTo(results.get(1).getResponse().getContentAsString()); oneEffect();
    }
    @Test void concurrentDifferentKeysPublishOnlyOnce() throws Exception {
        var results = compete("one","two",100,100,4,4);
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200,409); oneEffect();
    }
    @Test void concurrentSameKeyDifferentDraftsConflict() throws Exception {
        var results = compete("same","same",100,101,4,0);
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200,409);
        code(results.stream().filter(r -> r.getResponse().getStatus()==409).findFirst().orElseThrow(),409,"IDEMPOTENCY_CONFLICT"); oneEffect();
    }
    List<MvcResult> compete(String k1,String k2,long p1,long p2,long v1,long v2) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var a = pool.submit(() -> { barrier.await(10,TimeUnit.SECONDS); return publish(p1,k1,v1); });
            var b = pool.submit(() -> { barrier.await(10,TimeUnit.SECONDS); return publish(p2,k2,v2); });
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test void outboxFailureRollsBackPostSequenceAndKey() throws Exception { rollback("outbox_events","fail_outbox"); }
    @Test void receiptFailureRollsBackEvenInsertedOutbox() throws Exception { rollback("publish_requests","fail_receipt"); }
    void rollback(String table,String trigger) throws Exception {
        var before = db.queryForMap("SELECT * FROM posts WHERE id=100");
        db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + table + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected failure'");
        try {
            assertThat(publish(100,"key",4).getResponse().getStatus()).isEqualTo(500);
            assertThat(db.queryForMap("SELECT * FROM posts WHERE id=100")).isEqualTo(before);
            assertThat(count("outbox_events")).isZero();
            assertThat(count("publish_requests")).isZero();
            assertThat(db.queryForObject("SELECT publish_seq FROM author_feed_mode WHERE author_id=1",Long.class)).isEqualTo(10);
        } finally { db.execute("DROP TRIGGER " + trigger); }
        assertThat(publish(100,"key",4).getResponse().getStatus()).isEqualTo(200); oneEffect();
    }
}
