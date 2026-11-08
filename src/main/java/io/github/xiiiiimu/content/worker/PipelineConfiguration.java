package io.github.xiiiiimu.content.worker;
import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.RelaySettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="content.pipeline.enabled",havingValue="true") @EnableConfigurationProperties(RelaySettings.class)
public class PipelineConfiguration {
 @Bean(initMethod="start",destroyMethod="close") @ConditionalOnProperty(name="content.pipeline.consumer-enabled",havingValue="true",matchIfMissing=true)
 PipelineConsumer pipelineConsumer(DistributionInbox inbox,RelaySettings settings){return new PipelineConsumer(inbox,settings);}
}
