package io.github.xiiiiimu.content;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hardening A3 migration on a database that already holds duplicate reports: per (post, reporter) the earliest report
 * (created_at_ms, then id) is kept, post_stats.report_count is recomputed, and the unique key is in place afterwards.
 */
@Testcontainers
class ReportDedupMigrationIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");

 @Test void duplicatesAreReducedToTheEarliestReportBeforeTheUniqueKey()throws Exception{
  String url=MYSQL.getJdbcUrl();
  Flyway.configure().dataSource(url,MYSQL.getUsername(),MYSQL.getPassword()).target("28").load().migrate();
  try(var c=DriverManager.getConnection(url,MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement()){
   // post 1: reporter 10 three times (earliest is id 3 by time, not the lowest id), reporter 11 once; post 2: reporter 10 twice
   s.executeUpdate("INSERT INTO post_reports(id,post_id,reporter_user_id,reason,created_at_ms) VALUES"
    +"(1,1,10,'later',300),(2,1,10,'latest',400),(3,1,10,'earliest',100),(4,1,11,'other',200),(5,2,10,'p2-first',50),(6,2,10,'p2-tie-later-id',50)");
   s.executeUpdate("INSERT INTO post_stats(post_id,like_count,dislike_count,comment_count,report_count) VALUES(1,0,0,0,4),(2,0,0,0,2)");
  }
  var result=Flyway.configure().dataSource(url,MYSQL.getUsername(),MYSQL.getPassword()).load().migrate();
  assertThat(result.migrationsExecuted).as("V29 applied").isGreaterThanOrEqualTo(1);
  try(var c=DriverManager.getConnection(url,MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement()){
   var kept=new ArrayList<String>();
   try(var r=s.executeQuery("SELECT CONCAT(post_id,'/',reporter_user_id,'=',id,':',reason) FROM post_reports ORDER BY post_id,reporter_user_id")){while(r.next())kept.add(r.getString(1));}
   assertThat(kept).containsExactly("1/10=3:earliest","1/11=4:other","2/10=5:p2-first");
   var counts=new ArrayList<String>();
   try(var r=s.executeQuery("SELECT CONCAT(post_id,'=',report_count) FROM post_stats ORDER BY post_id")){while(r.next())counts.add(r.getString(1));}
   assertThat(counts).containsExactly("1=2","2=1");
   try(var r=s.executeQuery("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='post_reports' AND index_name='uq_report_post_reporter' AND non_unique=0")){
    r.next();assertThat(r.getString(1)).isEqualTo("post_id,reporter_user_id");
   }
  }
 }
}
