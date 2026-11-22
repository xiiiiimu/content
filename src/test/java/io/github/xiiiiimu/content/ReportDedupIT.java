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
 * Hardening A3: one report per (post, reporter). A repeat — sequential or concurrent — adds no row and returns the
 * first report's real id and the post's report count; it never fails with a server error.
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReportDedupIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired RelationshipService follows;
 static long nextPost=130_100;

 long published(){
  long id=nextPost++;
  db.update("INSERT IGNORE INTO author_feed_mode(author_id,mode) VALUES(1301,'PULL')");
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,1301,'DRAFT','t')",id);
  publish.publish(1301,id,UUID.randomUUID().toString(),0);
  return id;
 }
 /** Reports need a visible post: the reporter follows the author (same as other interaction ITs). */
 void follower(long user){db.update("INSERT IGNORE INTO author_feed_mode(author_id,mode) VALUES(1301,'PULL')");follows.change(user,1301,true);}
 long rows(long post){return db.queryForObject("SELECT COUNT(*) FROM post_reports WHERE post_id=?",Long.class,post);}

 @Test void aRepeatedReportKeepsOneRowAndReturnsTheFirstReportsId(){
  follower(2301);long post=published();
  var first=interactions.report(2301,post,"spam","first",null);
  var again=interactions.report(2301,post,"abuse","second",null);
  assertThat(rows(post)).isEqualTo(1);
  long id=db.queryForObject("SELECT id FROM post_reports WHERE post_id=?",Long.class,post);
  assertThat(first.reportId()).as("real id").isEqualTo(id).isPositive();
  assertThat(again.reportId()).isEqualTo(id);
  assertThat(first.reportCount()).isEqualTo(1);
  assertThat(again.reportCount()).isEqualTo(1);
  assertThat(db.queryForObject("SELECT reason FROM post_reports WHERE id=?",String.class,id)).as("first report kept").isEqualTo("spam");
  assertThat(db.queryForObject("SELECT report_count FROM post_stats WHERE post_id=?",Long.class,post)).isEqualTo(1);
 }

 @Test void differentReportersEachCountOnce(){
  follower(2311);follower(2312);long post=published();
  var a=interactions.report(2311,post,"spam","",null);
  var b=interactions.report(2312,post,"spam","",null);
  interactions.report(2311,post,"spam","",null);
  assertThat(rows(post)).isEqualTo(2);
  assertThat(a.reportId()).isNotEqualTo(b.reportId());
  assertThat(b.reportCount()).isEqualTo(2);
  assertThat(db.queryForObject("SELECT report_count FROM post_stats WHERE post_id=?",Long.class,post)).isEqualTo(2);
 }

 @Test void concurrentRepeatsCreateOneRowAndNoErrors()throws Exception{
  follower(2321);long post=published();
  int n=8;var start=new CyclicBarrier(n);var pool=Executors.newFixedThreadPool(n);
  try{
   var futures=new ArrayList<Future<InteractionService.ReportResult>>();
   for(int i=0;i<n;i++){int k=i;futures.add(pool.submit(()->{start.await(10,TimeUnit.SECONDS);return interactions.report(2321,post,"spam","t"+k,null);}));}
   var ids=new HashSet<Long>();
   for(var f:futures)ids.add(f.get(30,TimeUnit.SECONDS).reportId());
   assertThat(rows(post)).isEqualTo(1);
   long id=db.queryForObject("SELECT id FROM post_reports WHERE post_id=?",Long.class,post);
   assertThat(ids).containsExactly(id);
  }finally{pool.shutdownNow();}
 }
}
