package io.github.xiiiiimu.content.worker;
import io.github.xiiiiimu.content.domain.DistributionInbox;
import io.github.xiiiiimu.content.infrastructure.RelaySettings;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PipelineConsumer implements AutoCloseable {
 private final DistributionInbox inbox;private final RelaySettings settings;private final int metadataTimeout;private DefaultMQPushConsumer consumer;
 public PipelineConsumer(DistributionInbox inbox,RelaySettings settings,int metadataTimeout){
  if(metadataTimeout<1000 || metadataTimeout>120000)throw new IllegalArgumentException("Consumer metadata timeout must be 1000..120000 milliseconds");
  this.inbox=inbox;this.settings=settings;this.metadataTimeout=metadataTimeout;
 }
 public synchronized void start()throws Exception{
  if(consumer!=null)return;
  Exception last=null;
  for(int attempt=0;attempt<3;attempt++){
  var c=new DefaultMQPushConsumer("content-feed-v1");c.setNamesrvAddr(settings.namesrv());c.setInstanceName(UUID.randomUUID().toString());c.setVipChannelEnabled(false);
  c.setMqClientApiTimeout(metadataTimeout);c.setClientCallbackExecutorThreads(2);
  c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);c.setConsumeThreadMin(2);c.setConsumeThreadMax(2);c.setConsumeMessageBatchMaxSize(1);c.setPullBatchSize(16);c.setPullThresholdForQueue(100);
  c.subscribe(settings.topic(),"PostPublished || FollowChanged || FeedModeChanged || PostOfflined || CommentCreated");
  c.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
   try{for(var message:messages){if(!message.getKeys().equals(message.getUserProperty("eventId")))throw new IllegalArgumentException("MQ identity property mismatch");
    String payload=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(message.getBody())).toString();
    inbox.accept(payload,message.getKeys(),message.getTags());}
    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
   }catch(Exception failure){org.slf4j.LoggerFactory.getLogger(PipelineConsumer.class).error("MQ feed acceptance failed; Broker must retry",failure);return ConsumeConcurrentlyStatus.RECONSUME_LATER;}
  });
  try{c.start();consumer=c;return;}catch(Exception failure){c.shutdown();last=failure;org.slf4j.LoggerFactory.getLogger(PipelineConsumer.class).warn("Consumer startup metadata attempt {} failed",attempt+1,failure);}
  }
  throw new IllegalStateException("Consumer startup metadata unavailable after three bounded attempts",last);
 }
 @Override public synchronized void close(){if(consumer!=null){consumer.shutdown();consumer=null;}}
}
