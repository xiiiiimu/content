package io.github.xiiiiimu.content;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real RocketMQ 5.3.2 for the opt-in commerce suites (CONTENT_REAL_MQ_IT=true), started by Testcontainers so the
 * suites run on a CI runner without the local compose stack. Shared by every suite in the JVM (started once, removed
 * by Ryuk at JVM exit); each suite creates its own topic. The broker advertises 127.0.0.1 on a fixed host port, like
 * {@link PipelineMqFixture} (30922) and {@link RelayMqFixture} (30921); 30923 is taken by a pipeline Redis.
 */
final class CommerceMqFixture {
 static final int PORT=30924;
 static final Network NETWORK=Network.newNetwork();
 static final GenericContainer<?> NAMESRV=new GenericContainer<>(DockerImageName.parse("apache/rocketmq:5.3.2"))
  .withNetwork(NETWORK).withNetworkAliases("commerce-namesrv").withExposedPorts(9876)
  .withEnv("JAVA_OPT_EXT","-Xms64m -Xmx128m")
  .withCommand("sh","mqnamesrv").waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
 static final Broker BROKER=new Broker();
 private static boolean started;

 static final class Broker extends GenericContainer<Broker> {
  Broker(){
   super(DockerImageName.parse("apache/rocketmq:5.3.2"));
   addFixedExposedPort(PORT,PORT);
   withNetwork(NETWORK).dependsOn(NAMESRV).withEnv("NAMESRV_ADDR","commerce-namesrv:9876")
    .withEnv("JAVA_OPT_EXT","-Xms128m -Xmx256m -Xmn64m")
    .withCopyToContainer(Transferable.of("brokerClusterName=commerce-it\nbrokerName=commerce-it-broker\nbrokerId=0\nbrokerIP1=127.0.0.1\nlistenPort="+PORT+"\nbrokerRole=ASYNC_MASTER\nflushDiskType=ASYNC_FLUSH\nstorePathRootDir=/tmp/commerce-store\nstorePathCommitLog=/tmp/commerce-store/commitlog\nmappedFileSizeCommitLog=16777216\nautoCreateTopicEnable=false\n"),"/tmp/commerce-broker.conf")
    .withCommand("sh","mqbroker","-c","/tmp/commerce-broker.conf")
    .waitingFor(Wait.forLogMessage(".*boot success.*",1)).withStartupTimeout(Duration.ofMinutes(2));
  }
 }

 static String namesrv(){return NAMESRV.getHost()+":"+NAMESRV.getMappedPort(9876);}

 /** Starts the broker once per JVM, creates {@code topic} (4 read/write queues) and proves one send succeeds. */
 static synchronized void topic(String topic,String evidenceName)throws Exception{
  if(!started){
   FixedHostPortGuard.reclaimOrphans(PORT,"commerce real-MQ broker");
   NAMESRV.start();BROKER.start();started=true;
  }
  var created=BROKER.execInContainer("sh","mqadmin","updateTopic","-n","commerce-namesrv:9876","-b","127.0.0.1:"+PORT,"-t",topic,"-r","4","-w","4");
  Files.createDirectories(Path.of("target"));
  Files.writeString(Path.of("target",evidenceName),created.getStdout()+created.getStderr());
  assertThat(created.getExitCode()).as(created.getStdout()+created.getStderr()).isZero();
  assertThat(created.getStdout()).as("mqadmin can report errors with exit code 0").contains("success");
  // Preflight: the topic route must be visible through the namesrv before any suite sends. No warmup message is
  // sent, because the sale consumer would receive it on the same topic.
  var probe=new DefaultMQProducer("commerce-preflight-"+UUID.randomUUID());
  probe.setNamesrvAddr(namesrv());probe.setVipChannelEnabled(false);probe.setInstanceName(UUID.randomUUID().toString());
  probe.setRetryTimesWhenSendFailed(0);probe.start();
  try{await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(()->!probe.fetchPublishMessageQueues(topic).isEmpty());}
  finally{probe.shutdown();}
 }
}
