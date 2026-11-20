import org.apache.rocketmq.client.ClientConfig;
import org.apache.rocketmq.client.impl.MQClientManager;
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Long-lived Broker consumer-group backlog sampler (equivalent of mqadmin consumerProgress, without starting a JVM
 * inside the Broker container every tick). One NDJSON line per interval:
 * brokerOffset/consumerOffset sums, diffTotal (= Broker consumption backlog), inflight, per-queue max diff, sample cost.
 * Stops when the stop file exists. Read-only: no topic/group mutation.
 *
 *   java -cp <client classpath> infra/jmeter/ConsumerLagSampler.java 127.0.0.1:29876 content-sale content-sale-v1 5000 out.ndjson stop.flag
 */
public class ConsumerLagSampler {
 public static void main(String[] a)throws Exception{
  String namesrv=a[0],topic=a[1],group=a[2];long interval=Long.parseLong(a[3]);Path out=Path.of(a[4]),stop=Path.of(a[5]);
  var config=new ClientConfig();config.setNamesrvAddr(namesrv);config.setInstanceName("lag-sampler-"+UUID.randomUUID());config.setVipChannelEnabled(false);
  var instance=MQClientManager.getInstance().getOrCreateMQClientInstance(config);instance.start();
  var api=instance.getMQClientAPIImpl();
  try(var w=Files.newBufferedWriter(out,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   while(!Files.exists(stop)){
    long tick=System.nanoTime(),start=System.currentTimeMillis();
    var line=new StringBuilder("{\"startEpochMs\":").append(start);
    try{
     var route=api.getTopicRouteInfoFromNameServer(topic,3000);
     long broker=0,consumer=0,diff=0,inflight=0,maxQueueDiff=0;int queues=0;double tps=0;
     for(var b:route.getBrokerDatas()){
      String addr=b.selectBrokerAddr();if(addr==null)continue;
      ConsumeStats stats=api.getConsumeStats(addr,group,topic,3000);
      tps+=stats.getConsumeTps();
      for(var e:stats.getOffsetTable().entrySet()){
       var o=e.getValue();queues++;broker+=o.getBrokerOffset();consumer+=o.getConsumerOffset();
       long d=Math.max(0,o.getBrokerOffset()-o.getConsumerOffset());diff+=d;maxQueueDiff=Math.max(maxQueueDiff,d);
       long pulled=o.getPullOffset();if(pulled>o.getConsumerOffset())inflight+=pulled-o.getConsumerOffset();
      }
     }
     line.append(",\"ok\":true,\"queues\":").append(queues).append(",\"brokerOffset\":").append(broker).append(",\"consumerOffset\":").append(consumer)
      .append(",\"diffTotal\":").append(diff).append(",\"pulledNotCommitted\":").append(inflight).append(",\"maxQueueDiff\":").append(maxQueueDiff)
      .append(",\"consumeTps\":").append(String.format(Locale.ROOT,"%.3f",tps));
    }catch(Exception e){line.append(",\"ok\":false,\"error\":\"").append(e.getClass().getSimpleName()).append("\"");}
    line.append(",\"endEpochMs\":").append(System.currentTimeMillis()).append("}\n");
    w.write(line.toString());w.flush();
    long sleep=interval-(System.nanoTime()-tick)/1_000_000;
    while(sleep>0&&!Files.exists(stop)){Thread.sleep(Math.min(200,sleep));sleep=interval-(System.nanoTime()-tick)/1_000_000;}
   }
  }finally{instance.shutdown();}
 }
}
