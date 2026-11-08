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
 PipelineConsumer pipelineConsumer(DistributionInbox inbox,RelaySettings settings,@org.springframework.beans.factory.annotation.Value("${content.pipeline.metadata-timeout-millis:60000}")int metadataTimeout){return new PipelineConsumer(inbox,settings,metadataTimeout);}
 @EnableScheduling @ConditionalOnProperty(name="content.pipeline.scan-enabled",havingValue="true",matchIfMissing=true)
 static class Scanning {
  @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler(){var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("content-pipeline-");return scheduler;}
  @Bean Scanner deliveryScanner(DistributionWorker worker,FeedModes modes,FeedTrimmer trimmer){return new Scanner(worker,modes,trimmer);}
 }
 static class Scanner {
  private final DistributionWorker worker;private final FeedModes modes;private final FeedTrimmer trimmer;Scanner(DistributionWorker worker,FeedModes modes,FeedTrimmer trimmer){this.worker=worker;this.modes=modes;this.trimmer=trimmer;}
  @Scheduled(fixedDelayString="${content.pipeline.scan-delay-millis:1000}") public void scan(){try{worker.tick();modes.scan();trimmer.tick();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(Scanner.class).error("Delivery scan failed; persistent work remains",e);}}
 }
}
