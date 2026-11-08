package io.github.xiiiiimu.content.worker;
import io.github.xiiiiimu.content.ContentApplication;
import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** OS/DB/Redis credentials are the operator boundary. This command exposes no HTTP maintenance endpoint. */
public final class FeedOpsCommand {
 public static void main(String[] args){int code=1;
  try{
   if(args.length<2 || (!args[0].equals("rebuild") && !args[0].equals("replay")))throw new IllegalArgumentException("Usage: rebuild reason | replay taskId reason");
   if((args[0].equals("rebuild") && args.length!=2) || (args[0].equals("replay") && args.length!=3))throw new IllegalArgumentException("Invalid operator arguments");
   String operator=ProcessHandle.current().info().user().orElseThrow(()->new IllegalStateException("OS operator identity unavailable"));
   var app=new SpringApplication(ContentApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
   try(var context=app.run("--spring.main.web-application-type=none","--content.feed.enabled=true","--content.pipeline.enabled=false","--content.pipeline.consumer-enabled=false","--content.pipeline.scan-enabled=false","--content.relay.enabled=false","--content.relay.scan-enabled=false")){
    if(args[0].equals("rebuild")){System.out.println("Recovery completed: "+context.getBean(RecoveryRunner.class).rebuild(operator,args[1]));code=0;}
    else{long task=Long.parseLong(args[1]);boolean replay=context.getBean(DeliveryStore.class).replay(task,operator,args[2]);System.out.println("DEAD task replay taskId="+task+" accepted="+replay);code=replay?0:2;}
   }
  }catch(Exception failure){System.err.println("Feed operator action failed: "+failure.getClass().getSimpleName()+": "+failure.getMessage());}
  System.exit(code);
 }
}
