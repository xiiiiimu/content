package io.github.xiiiiimu.content.worker;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import java.util.UUID;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="content.relay.enabled",havingValue="true")
@EnableConfigurationProperties(RelaySettings.class)
@Import(RelayConfiguration.ProducerConfiguration.class)
public class RelayConfiguration {
    @Bean RelayStore relayStore(RelayMapper mapper,RelaySettings settings){return new MyBatisRelayStore(mapper,settings);}
    @Bean @ConditionalOnMissingBean(RelayObserver.class)
    RelayObserver relayObserver(){return new RelayObserver(){};}

    @ConditionalOnProperty(name="content.relay.producer-enabled",havingValue="true",matchIfMissing=true)
    @Import(ProducerConfiguration.Scanning.class)
    static class ProducerConfiguration {
        @Bean(initMethod="start",destroyMethod="shutdown") @org.springframework.context.annotation.Primary
        DefaultMQProducer relayProducer(RelaySettings settings) {
            var producer=new DefaultMQProducer("content-outbox-relay");
            producer.setNamesrvAddr(settings.namesrv());producer.setVipChannelEnabled(false);producer.setInstanceName(UUID.randomUUID().toString());
            producer.setRetryTimesWhenSendFailed(0);producer.setRetryTimesWhenSendAsyncFailed(0);producer.setRetryAnotherBrokerWhenNotStoreOK(false);
            producer.setSendMsgTimeout(Math.toIntExact(settings.sendTimeoutMillis()));
            return producer;
        }
        @Bean RelaySender relaySender(@org.springframework.beans.factory.annotation.Qualifier("relayProducer") DefaultMQProducer producer,ObjectMapper json,RelaySettings settings){return new RocketMqRelaySender(producer,json,settings);}
        @Bean RelayRunner relayRunner(RelayStore store,RelaySender sender,RelayObserver observer,RelaySettings settings){return new OutboxRelay(store,sender,observer,settings);}

        @EnableScheduling
        @ConditionalOnProperty(name="content.relay.scan-enabled",havingValue="true",matchIfMissing=true)
        static class Scanning {
            @Bean Scanner relayScanner(RelayRunner runner){return new Scanner(runner);}
        }
    }
    static class Scanner {
        private final RelayRunner runner;
        Scanner(RelayRunner runner){this.runner=runner;}
        @Scheduled(fixedDelayString="${content.relay.scan-delay-millis:1000}")
        public void scan(){try{runner.tick();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(Scanner.class).error("Relay scan failed; next scheduled scan will retry",e);}}
    }
}
