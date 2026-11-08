package io.github.xiiiiimu.content.worker;
import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.RelaySettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="content.pipeline.enabled",havingValue="true") @EnableConfigurationProperties(RelaySettings.class) @Import(PipelineConfiguration.Scanning.class)
public class PipelineConfiguration {
 @Bean(initMethod="start",destroyMethod="close") @ConditionalOnProperty(name="content.pipeline.consumer-enabled",havingValue="true",matchIfMissing=true)
 PipelineConsumer pipelineConsumer(DistributionInbox inbox,RelaySettings settings){return new PipelineConsumer(inbox,settings);}
 @EnableScheduling @ConditionalOnProperty(name="content.pipeline.scan-enabled",havingValue="true",matchIfMissing=true)
 static class Scanning {
  @Bean Scanner deliveryScanner(DistributionWorker worker){return new Scanner(worker);}
 }
 static class Scanner {
  private final DistributionWorker worker;Scanner(DistributionWorker worker){this.worker=worker;}
  @Scheduled(fixedDelayString="${content.pipeline.scan-delay-millis:1000}") public void scan(){try{worker.tick();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(Scanner.class).error("Delivery scan failed; persistent work remains",e);}}
 }
}
