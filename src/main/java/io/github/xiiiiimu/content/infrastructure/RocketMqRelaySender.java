package io.github.xiiiiimu.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.domain.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;

public class RocketMqRelaySender implements RelaySender {
    private final DefaultMQProducer producer;
    private final ObjectMapper json;
    private final RelaySettings settings;
    public RocketMqRelaySender(DefaultMQProducer producer,ObjectMapper json,RelaySettings settings){this.producer=producer;this.json=json;this.settings=settings;}
    @Override public Receipt send(RelayLease lease) throws Exception {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("MQ send cannot run inside a database transaction");
        var body=json.readTree(lease.payload());
        var eventId=body.get("eventId");
        if(eventId==null || !eventId.isIntegralNumber() || !eventId.canConvertToLong() || eventId.longValue()!=lease.eventId())
            throw new IllegalArgumentException("Outbox payload eventId does not match its immutable identity");
        String key=Long.toString(lease.eventId());
        String type=body.has("eventType")?body.get("eventType").asText():"PostPublished";
        if(!java.util.Set.of("PostPublished","FollowChanged","FeedModeChanged","PostOfflined").contains(type))throw new IllegalArgumentException("Unsupported Outbox routing type");
        var message=new Message(settings.topic(),type,key,lease.payload().getBytes(StandardCharsets.UTF_8));
        message.putUserProperty("eventId",key);
        var result=producer.send(message,settings.sendTimeoutMillis());
        return result==null ? new Receipt("NO_RESULT",null) : new Receipt(result.getSendStatus()==null?"NO_STATUS":result.getSendStatus().name(),result.getMsgId());
    }
}
