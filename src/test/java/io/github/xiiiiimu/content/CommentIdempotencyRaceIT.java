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
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Hardening A5: one Idempotency-Key submitted concurrently by the same user. Every caller gets the first comment
 * (exactly one fresh result, the rest replay=true), never an error; one comment row, one CommentCreated outbox event and
 * one topic count are written.
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CommentIdempotencyRaceIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired RelationshipService follows;

 @Test void concurrentSubmissionsOfOneKeyReturnTheFirstCommentWithoutErrors()throws Exception{
  db.update("INSERT IGNORE INTO author_feed_mode(author_id,mode) VALUES(1501,'PULL')");
  follows.change(2501,1501,true);
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(150101,1501,'DRAFT','t','race')");
  publish.publish(1501,150101,UUID.randomUUID().toString(),0);
  long outboxBefore=db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='CommentCreated'",Long.class);

  int n=8;var barrier=new CyclicBarrier(n);var pool=Executors.newFixedThreadPool(n);
  var results=new ArrayList<InteractionService.CommentResult>();var errors=new ArrayList<Throwable>();
  try{
   var fs=new ArrayList<Future<InteractionService.CommentResult>>();
   for(int i=0;i<n;i++){int k=i;fs.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);
    return interactions.comment(2501,150101,"same comment "+k,"race-key-1","ignored");}));}
   for(var f:fs){try{results.add(f.get(60,TimeUnit.SECONDS));}catch(ExecutionException e){errors.add(e.getCause());}}
  }finally{pool.shutdownNow();}

  assertThat(errors).as("no caller fails").isEmpty();
  long id=db.queryForObject("SELECT id FROM post_comments WHERE user_id=2501 AND idempotency_key='race-key-1'",Long.class);
  assertThat(results).extracting(InteractionService.CommentResult::commentId).containsOnly(id);
  assertThat(results.stream().filter(r->!r.replay()).count()).as("exactly one fresh result").isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM post_comments WHERE post_id=150101",Long.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='CommentCreated'",Long.class)-outboxBefore).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COALESCE(SUM(net_events),0) FROM topic_minute_buckets WHERE topic='race'",Long.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT comment_count FROM post_stats WHERE post_id=150101",Long.class)).isEqualTo(1);
 }
}
