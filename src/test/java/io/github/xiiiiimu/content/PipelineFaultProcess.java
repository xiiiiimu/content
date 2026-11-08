package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

/** Test-only executable JVM. Production JAR contains neither this class nor its blocking observers. */
public final class PipelineFaultProcess {
 static String phase;static Path signal;static long target;
 static void barrier(String detail){try{Files.writeString(signal,"phase="+phase+" pid="+ProcessHandle.current().pid()+" time="+java.time.Instant.now()+" "+detail);new CountDownLatch(1).await();}catch(Exception e){throw new IllegalStateException(e);}}
 public static void main(String[] args){
  try{phase=args[0];signal=Path.of(args[1]);target=Long.parseLong(args[2]);boolean consumer=phase.startsWith("INBOX_");
   try(var context=new SpringApplicationBuilder(ContentApplication.class,Hooks.class).web(WebApplicationType.NONE).run(
    "--spring.main.web-application-type=none","--content.test.hooks=crash","--content.feed.enabled=true","--content.relay.enabled=false",
    "--content.feed.redis-uri="+java.util.Objects.requireNonNull(System.getenv("PIPELINE_TEST_REDIS"),"Explicit isolated Redis URI required"),
    "--content.pipeline.enabled="+consumer,"--content.pipeline.consumer-enabled="+consumer,"--content.pipeline.scan-enabled=false",
    "--content.relay.namesrv="+System.getenv().getOrDefault("PIPELINE_TEST_NAMESRV","127.0.0.1:29876"),"--content.relay.topic=content-pipeline-it")){
    if(consumer)new CountDownLatch(1).await();
    else if(phase.startsWith("RECOVERY_"))context.getBean(RecoveryRunner.class).rebuild(ProcessHandle.current().info().user().orElseThrow(),"independent process recovery fault validation");
    else context.getBean("deliveryRunner",Runnable.class).run();
   }
  }catch(Exception e){e.printStackTrace();System.exit(1);}System.exit(0);
 }
 @TestConfiguration @ConditionalOnProperty(name="content.test.hooks",havingValue="crash") static class Hooks {
  @Bean @Primary PipelineObserver crashPipelineObserver(){return new PipelineObserver(){
   @Override public void beforeInboxCommit(long id){if(phase.equals("INBOX_BEFORE") && id==target)barrier("eventId="+id+" transaction=uncommitted");}
   @Override public void afterInboxCommit(long id){if(phase.equals("INBOX_AFTER") && id==target)barrier("eventId="+id+" transaction=committed ack=pending");}
   @Override public void afterClaim(DeliveryLease lease){if(phase.equals("CLAIM") && lease.taskId()==target)barrier("taskId="+target+" token="+lease.token()+" attempt="+lease.attempt());}
   @Override public void afterRedis(DeliveryLease lease){if(phase.equals("REDIS") && lease.taskId()==target)barrier("taskId="+target+" token="+lease.token()+" redis=written progress=pending");}
   @Override public void afterRecoveryBatch(long generation,long token){if(phase.equals("RECOVERY_BATCH"))barrier("generation="+generation+" recoveryToken="+token+" ready=false");}
  };}
 }
}
