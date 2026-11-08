package io.github.xiiiiimu.content;

import org.testcontainers.containers.MySQLContainer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

final class PipelineProcesses {
 static final Path DIR=Path.of("target","distribution-evidence");
 static Process start(Class<?> main,MySQLContainer<?> mysql,String redis,String name,Map<String,String> extra,String... args)throws Exception{
  Files.createDirectories(DIR);var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
  if(main.equals(io.github.xiiiiimu.content.worker.FeedOpsCommand.class)){
   // Exercise the packaged operator entry point used by feed-ops.ps1, without any test classes.
   var jar=Path.of("target","content-0.1.0-SNAPSHOT.jar").toAbsolutePath();assertThat(jar).exists();
   command.add("-Dloader.main="+main.getName());command.add("-cp");command.add(jar.toString());command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
  }else{command.add("-cp");command.add(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));command.add(main.getName());}
  command.addAll(List.of(args));
  var p=new ProcessBuilder(command);p.environment().put("CONTENT_DB_URL",mysql.getJdbcUrl());p.environment().put("CONTENT_MYSQL_USER",mysql.getUsername());p.environment().put("CONTENT_MYSQL_PASSWORD",mysql.getPassword());
  p.environment().put("CONTENT_FEED_REDIS_URI",redis);p.environment().put("CONTENT_FEED_CURSOR_KEY","cursor-test-key-32-bytes-minimum-00001");p.environment().put("CONTENT_FEED_IDENTITY_KEY","identity-test-key-32-bytes-minimum-0001");p.environment().putAll(extra);
  p.environment().put("CONTENT_REDIS_URI",redis);p.environment().put("PIPELINE_TEST_REDIS",redis);p.environment().put("CONTENT_CURSOR_KEY","cursor-test-key-32-bytes-minimum-00001");p.environment().put("CONTENT_IDENTITY_KEY","identity-test-key-32-bytes-minimum-0001");
  return p.redirectErrorStream(true).redirectOutput(DIR.resolve(name+".log").toFile()).start();
 }
 static Process fault(MySQLContainer<?> mysql,String redis,String phase,long target,Path signal,Map<String,String> extra)throws Exception{
  Files.createDirectories(signal.toAbsolutePath().getParent());Files.deleteIfExists(signal);
  return start(PipelineFaultProcess.class,mysql,redis,signal.getFileName().toString(),extra,phase,signal.toAbsolutePath().toString(),Long.toString(target));
 }
 static void killAtBarrier(Process p,Path signal)throws Exception{
  // Setup watchdog only. Independent cold JVM startup is not part of a task lease/ACK deadline.
  await().atMost(java.time.Duration.ofSeconds(300)).until(()->{assertThat(p.isAlive()).as("child log: "+DIR.resolve(signal.getFileName()+".log")).isTrue();return Files.exists(signal);});
  p.destroyForcibly();assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isNotZero();
  Files.writeString(signal.resolveSibling(signal.getFileName()+".killed"),Files.readString(signal)+"\nkilledAt="+java.time.Instant.now()+" exit="+p.exitValue());
 }
 static void stop(Process p)throws Exception{if(p!=null && p.isAlive()){p.destroyForcibly();assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();}}
}
