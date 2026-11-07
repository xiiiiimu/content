package io.github.xiiiiimu.content.worker;

import io.github.xiiiiimu.content.ContentApplication;
import io.github.xiiiiimu.content.domain.RelayStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Local operator command; no HTTP identity or unauthenticated replay endpoint. */
public class OutboxReplayCommand {
    public static void main(String[] args) {
        int result=1;
        try {
            if(args.length!=2)throw new IllegalArgumentException("Usage: OutboxReplayCommand eventId reason");
            long id=Long.parseLong(args[0]);
            String operator=ProcessHandle.current().info().user().orElseThrow(() -> new IllegalStateException("OS operator identity unavailable"));
            var application=new SpringApplication(ContentApplication.class);application.setWebApplicationType(WebApplicationType.NONE);
            try(var context=application.run("--spring.main.web-application-type=none","--content.relay.enabled=true","--content.relay.producer-enabled=false","--content.relay.scan-enabled=false")) {
                if(context.getBean(RelayStore.class).replay(id,operator,args[1])){System.out.println("Replayed DEAD eventId="+id+" operator="+operator);result=0;}
                else {System.err.println("Replay rejected: event must be a DEAD PostPublished");result=2;}
            }
        }catch(Exception e){System.err.println("Outbox replay failed: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        System.exit(result);
    }
}
