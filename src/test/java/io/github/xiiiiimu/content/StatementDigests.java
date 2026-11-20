package io.github.xiiiiimu.content;

import org.testcontainers.containers.MySQLContainer;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/** Reads MySQL performance_schema statement digests (as root) so tests can assert which SQL ran and how many rows it examined. */
final class StatementDigests {
 record Row(String digest,long count,long rowsExamined){long examinedPerExecution(){return rowsExamined/Math.max(1,count);}}
 private final MySQLContainer<?> mysql;
 StatementDigests(MySQLContainer<?> mysql){this.mysql=mysql;}
 void reset(){
  try(var c=DriverManager.getConnection(mysql.getJdbcUrl(),"root",mysql.getPassword());var s=c.createStatement()){
   s.execute("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest");
  }catch(Exception e){throw new AssertionError("performance_schema digest reset failed",e);}
 }
 /** Digests in the test schema whose normalized text matches the LIKE pattern. */
 List<Row> matching(String likePattern){
  try(var c=DriverManager.getConnection(mysql.getJdbcUrl(),"root",mysql.getPassword());
      var s=c.prepareStatement("SELECT DIGEST_TEXT,COUNT_STAR,SUM_ROWS_EXAMINED FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST_TEXT LIKE ?")){
   s.setString(1,mysql.getDatabaseName());s.setString(2,likePattern);
   var out=new ArrayList<Row>();
   try(var r=s.executeQuery()){while(r.next())out.add(new Row(r.getString(1),r.getLong(2),r.getLong(3)));}
   return out;
  }catch(Exception e){throw new AssertionError("performance_schema digest read failed",e);}
 }
 long executions(String likePattern){return matching(likePattern).stream().mapToLong(Row::count).sum();}
}
