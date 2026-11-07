package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

/** Separate JVM; fault barriers live only in test code, never behind production flags. */
public class RelayCrashProcess {
    static String phase;
    static Path signal;
    public static void main(String[] args) {
        phase=args[0];signal=Path.of(args[1]);
        try(var context=new SpringApplicationBuilder(ContentApplication.class,Hooks.class).web(WebApplicationType.NONE)
                .properties("content.relay.enabled=true","content.relay.scan-enabled="+phase.equals("SCAN"),
                        "content.relay.namesrv="+System.getenv("RELAY_TEST_NAMESRV"),"content.relay.topic=content-relay-it",
                        "content.relay.lease-millis=4000","content.relay.send-timeout-millis=1000","content.relay.max-attempts=3",
                        "content.relay.base-backoff-millis=100","content.relay.max-backoff-millis=1000","content.relay.batch-size=1").run()) {
            if(phase.equals("SCAN"))new CountDownLatch(1).await();else context.getBean(RelayRunner.class).tick();
        } catch(Exception e) {e.printStackTrace();System.exit(1);}
        System.exit(0);
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Hooks {
        @Bean RelayObserver crashObserver() {return new RelayObserver() {
            void barrier(RelayLease lease) {
                try {Files.writeString(signal,"eventId="+lease.eventId()+" token="+lease.token()+" attempt="+lease.attempt()+" phase="+phase);new CountDownLatch(1).await();}
                catch(Exception e){throw new IllegalStateException(e);}
            }
            @Override public void afterClaim(RelayLease lease){if(phase.equals("CLAIM"))barrier(lease);}
            @Override public void afterAck(RelayLease lease){if(phase.equals("ACK"))barrier(lease);}
        };}
    }
}
