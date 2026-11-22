package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.NotificationRetention;
import io.github.xiiiiimu.content.domain.RetentionBounds;
import io.github.xiiiiimu.content.infrastructure.RetentionMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

/** Hardening C2 R-NOTIFY: 180 day notifications and risk events; a feedback notify log row only after its notification (C-4). */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationRetentionIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired RetentionMapper retention;@Autowired TransactionTemplate tx;
 static final long DAY=86_400_000L;
 static final AtomicLong SOURCE=new AtomicLong(5_000_000);

 @BeforeEach void reset(){for(String t:List.of("notifications","feedback_notify_log","trend_risk_events"))db.update("DELETE FROM "+t);}
 long notification(long recipient,int daysAgo,String dedupe){
  long source=SOURCE.incrementAndGet();
  db.update("INSERT INTO notifications(recipient_user_id,source_event_id,kind,actor_user_id,post_id,comment_id,created_at_ms,dedupe_key) VALUES(?,?,'REPLY',2,3,4,?,?)",
   recipient,source,System.currentTimeMillis()-daysAgo*DAY,dedupe);
  return db.queryForObject("SELECT id FROM notifications WHERE source_event_id=?",Long.class,source);
 }
 void log(long account,long feedback,long version,int daysAgo){
  db.update("INSERT INTO feedback_notify_log(account_id,feedback_comment_id,new_version_id,created_at_ms) VALUES(?,?,?,?)",account,feedback,version,System.currentTimeMillis()-daysAgo*DAY);
 }
 void risk(String id,int daysAgo){
  long at=System.currentTimeMillis()-daysAgo*DAY;
  db.update("INSERT INTO trend_risk_events(event_id,topic,kind,window_start_ms,occurred_at_ms,created_at_ms) VALUES(?,'t','TOPIC_SPIKE',?,?,?)",id,at,at,at);
 }
 NotificationRetention purge(int batch){return new NotificationRetention(retention,tx,new RetentionBounds(NotificationRetention.DEFAULT_RETENTION_MS,batch,1000));}

 @Test void notificationRetentionDeletesOnly180DayOldRowsAndFeedbackLogAfterItsNotification(){
  long old1=notification(7,181,null),old2=notification(7,190,null),old3=notification(8,200,null);
  long feedbackOld=notification(10,181,"feedback-ready:10:20:30");     // its log row may go with it
  long feedbackYoung=notification(11,10,"feedback-ready:11:20:30");    // still visible: its log row must stay
  long young=notification(7,179,null);
  log(10,20,30,181);   // notification deleted in the same run -> log deleted
  log(11,20,30,181);   // notification still present -> kept, or a repeated resolve would notify again
  log(12,20,30,10);    // young
  log(13,20,30,181);   // no notification (never inserted) -> deleted
  risk("old",181);risk("young",10);

  var result=purge(2).purge();

  assertThat(result).isEqualTo(new NotificationRetention.Result(4,2,1));
  assertThat(db.queryForList("SELECT id FROM notifications ORDER BY id",Long.class)).containsExactly(feedbackYoung,young)
   .doesNotContain(old1,old2,old3,feedbackOld);
  assertThat(db.queryForList("SELECT account_id FROM feedback_notify_log ORDER BY account_id",Long.class)).containsExactly(11L,12L);
  assertThat(db.queryForList("SELECT event_id FROM trend_risk_events",String.class)).containsExactly("young");
  assertThat(purge(2).purge()).isEqualTo(new NotificationRetention.Result(0,0,0));
 }
}
