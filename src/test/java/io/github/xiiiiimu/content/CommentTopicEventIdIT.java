package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hardening A10 reproduction: the comment topic event id is "comment:"+idempotencyKey. Comment idempotency is per
 * (user, key), but topic_event_ledger.event_id is global, so a second user's comment with the same key (on any post,
 * any topic) is created yet its topic +1 is dropped by INSERT IGNORE.
 * Disabled until the fix is decided; the failing run is kept in target/hardening-a/A10-repro-*.log.
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.junit.jupiter.api.Disabled("A10: reproduces the lost topic event; fix awaits a decision (docs/hardening-plan.md A10)")
class CommentTopicEventIdIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired RelationshipService follows;

 long net(String topic){return db.queryForObject("SELECT COALESCE(SUM(net_events),0) FROM topic_minute_buckets WHERE topic=?",Long.class,topic);}

 @Test void twoUsersWithTheSameIdempotencyKeyEachCountOnce(){
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1101,'PULL')");
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(110101,1101,'DRAFT','a','sports')");
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(110102,1101,'DRAFT','b','music')");
  publish.publish(1101,110101,UUID.randomUUID().toString(),0);publish.publish(1101,110102,UUID.randomUUID().toString(),0);
  follows.change(2101,1101,true);follows.change(2102,1101,true);

  var a=interactions.comment(2101,110101,"first","k-1","sports");
  var b=interactions.comment(2102,110102,"second","k-1","music");
  assertThat(b.commentId()).as("comment idempotency is per user: both comments exist").isNotEqualTo(a.commentId());
  assertThat(b.replay()).isFalse();
  assertThat(net("sports")).isEqualTo(1);
  assertThat(net("music")).as("second user's comment on a music post counts once").isEqualTo(1);
 }
}
