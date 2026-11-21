import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Environment baseline for single-row hot spots: "UPDATE one row + COMMIT" in a loop.
 * Runs only in its own database (default lockbench, table commit_bench); never touches business schemas and never
 * changes server flush settings (they are read and recorded). Same network path as the app (Windows -> Docker port),
 * READ COMMITTED like feedTransactions, autocommit off.
 *
 *   CONTENT_BENCH_DB_PASSWORD=... java -cp mysql-connector-j.jar infra/jmeter/CommitBaseline.java <jdbcBase> <user> <outJson> [iterations] [warmup] [distinctRowConnections]
 *   distinctRowConnections: comma list, e.g. "8,16" (default: single-connection same row + 4 connections distinct rows).
 *   jdbcBase e.g. jdbc:mysql://127.0.0.1:23306/  (database lockbench is created if missing)
 */
public class CommitBaseline {
 record Sample(long updateNs,long commitNs,long totalNs){}
 record Result(String scenario,int connections,int iterationsPerConnection,long wallNs,List<Sample> samples){}

 public static void main(String[] a)throws Exception{
  String base=a[0],user=a[1];Path out=Path.of(a[2]);int n=a.length>3?Integer.parseInt(a[3]):1000,warm=a.length>4?Integer.parseInt(a[4]):50;
  String pw=System.getenv("CONTENT_BENCH_DB_PASSWORD");if(pw==null)throw new IllegalStateException("CONTENT_BENCH_DB_PASSWORD not set");
  String params="?connectionTimeZone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
  Map<String,Object> server=new LinkedHashMap<>();
  try(var c=DriverManager.getConnection(base+params,user,pw);var s=c.createStatement()){
   s.execute("CREATE DATABASE IF NOT EXISTS lockbench");
   s.execute("CREATE TABLE IF NOT EXISTS lockbench.commit_bench(id INT PRIMARY KEY,v BIGINT NOT NULL,updated_at_ms BIGINT NOT NULL) ENGINE=InnoDB");
   var seed=new StringBuilder("INSERT INTO lockbench.commit_bench(id,v,updated_at_ms) VALUES ");
   for(int i=1;i<=64;i++)seed.append(i>1?",":"").append('(').append(i).append(",0,0)");
   s.execute(seed+" ON DUPLICATE KEY UPDATE id=id");
   try(var r=s.executeQuery("SELECT @@version,@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@log_bin,@@binlog_format,@@innodb_flush_method,@@innodb_doublewrite,@@innodb_redo_log_capacity")){
    r.next();String[] k={"version","innodb_flush_log_at_trx_commit","sync_binlog","log_bin","binlog_format","innodb_flush_method","innodb_doublewrite","innodb_redo_log_capacity"};
    for(int i=0;i<k.length;i++)server.put(k[i],r.getString(i+1));
   }
  }
  String url=base+"lockbench"+params;
  var results=new ArrayList<Result>();
  if(a.length>5){
   for(var c:a[5].split(",")){int k=Integer.parseInt(c.trim());if(k<1||k>64)throw new IllegalArgumentException("1..64 connections");
    results.add(run(k+"-connections-different-rows",url,user,pw,k,n,warm,true));}
  }else{
   results.add(run("single-connection-same-row",url,user,pw,1,n,warm,false));
   results.add(run("four-connections-different-rows",url,user,pw,4,n,warm,true));
  }
  var json=new StringBuilder("{\n \"server\":").append(map(server)).append(",\n \"iterationsPerConnection\":").append(n).append(",\"warmupPerConnection\":").append(warm)
   .append(",\n \"isolation\":\"READ COMMITTED\",\"autocommit\":false,\"path\":\"host JDBC -> Docker published port\",\n \"scenarios\":[");
  for(int i=0;i<results.size();i++){if(i>0)json.append(',');json.append('\n').append(summary(results.get(i)));}
  json.append("\n]}\n");
  Files.writeString(out,json.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
  System.out.print(json);
 }

 static Result run(String name,String url,String user,String pw,int conns,int n,int warm,boolean distinctRows)throws Exception{
  var pool=Executors.newFixedThreadPool(conns);var start=new CountDownLatch(1);var futures=new ArrayList<Future<List<Sample>>>();
  var ready=new CountDownLatch(conns);
  for(int t=0;t<conns;t++){int row=distinctRows?t+1:1;
   futures.add(pool.submit(()->{
    try(var c=DriverManager.getConnection(url,user,pw)){
     c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
     try(var ps=c.prepareStatement("UPDATE commit_bench SET v=v+1,updated_at_ms=? WHERE id=?")){
      for(int i=0;i<warm;i++){ps.setLong(1,System.currentTimeMillis());ps.setInt(2,row);ps.executeUpdate();c.commit();}
      ready.countDown();start.await();
      var list=new ArrayList<Sample>(n);
      for(int i=0;i<n;i++){
       long t0=System.nanoTime();ps.setLong(1,System.currentTimeMillis());ps.setInt(2,row);
       if(ps.executeUpdate()!=1)throw new IllegalStateException("row missing");
       long t1=System.nanoTime();c.commit();long t2=System.nanoTime();
       list.add(new Sample(t1-t0,t2-t1,t2-t0));
      }
      return list;
     }
    }}));
  }
  ready.await();long w0=System.nanoTime();start.countDown();
  var all=new ArrayList<Sample>();for(var f:futures)all.addAll(f.get());long wall=System.nanoTime()-w0;
  pool.shutdown();return new Result(name,conns,n,wall,all);
 }

 static String summary(Result r){
  long[] commit=r.samples().stream().mapToLong(Sample::commitNs).sorted().toArray();
  long[] update=r.samples().stream().mapToLong(Sample::updateNs).sorted().toArray();
  long[] total=r.samples().stream().mapToLong(Sample::totalNs).sorted().toArray();
  double sec=r.wallNs()/1e9;
  return String.format(Locale.ROOT,"  {\"scenario\":\"%s\",\"connections\":%d,\"transactions\":%d,\"wallSec\":%.3f,\"txPerSec\":%.1f,"
    +"\"commitMs\":%s,\"updateMs\":%s,\"txMs\":%s}",r.scenario(),r.connections(),total.length,sec,total.length/sec,dist(commit),dist(update),dist(total));
 }
 static String dist(long[] s){
  return String.format(Locale.ROOT,"{\"p50\":%.2f,\"p95\":%.2f,\"p99\":%.2f,\"max\":%.2f,\"mean\":%.2f}",p(s,.5),p(s,.95),p(s,.99),s[s.length-1]/1e6,Arrays.stream(s).average().orElse(0)/1e6);
 }
 static double p(long[] s,double q){return s[Math.max(0,(int)Math.ceil(s.length*q)-1)]/1e6;}
 static String map(Map<String,Object> m){var b=new StringBuilder("{");int i=0;for(var e:m.entrySet()){if(i++>0)b.append(',');b.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');}return b.append('}').toString();}
}
