package io.github.xiiiiimu.content;

import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.containers.wait.strategy.Wait;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class RelayMqFixture {
    static final String TOPIC = "content-relay-it";
    static final int PORT = 30921;
    static final Network NETWORK = Network.newNetwork();
    static final GenericContainer<?> NAMESRV = new GenericContainer<>(DockerImageName.parse("apache/rocketmq:5.3.2"))
            .withNetwork(NETWORK).withNetworkAliases("relay-namesrv").withExposedPorts(9876)
            .withEnv("JAVA_OPT_EXT","-Xms64m -Xmx128m")
            .withCommand("sh","mqnamesrv").waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
    static final Broker BROKER = new Broker();
    static DefaultMQProducer probe;
    static DefaultMQPullConsumer consumer;
    static String namesrv() { return NAMESRV.getHost()+":"+NAMESRV.getMappedPort(9876); }
    static class Broker extends GenericContainer<Broker> {
        Broker() {
            super(DockerImageName.parse("apache/rocketmq:5.3.2"));
            addFixedExposedPort(PORT,PORT);
            withNetwork(NETWORK).dependsOn(NAMESRV).withEnv("NAMESRV_ADDR","relay-namesrv:9876")
                    .withEnv("JAVA_OPT_EXT","-Xms128m -Xmx256m -Xmn64m")
                    .withCopyToContainer(Transferable.of("brokerClusterName=relay-it\nbrokerName=relay-it-broker\nbrokerId=0\nbrokerIP1=127.0.0.1\nlistenPort="+PORT+"\nbrokerRole=ASYNC_MASTER\nflushDiskType=ASYNC_FLUSH\nstorePathRootDir=/tmp/relay-store\nstorePathCommitLog=/tmp/relay-store/commitlog\nmappedFileSizeCommitLog=16777216\nautoCreateTopicEnable=false\n"),"/tmp/relay-broker.conf")
                    .withCommand("sh","mqbroker","-c","/tmp/relay-broker.conf")
                    .waitingFor(Wait.forLogMessage(".*boot success.*",1)).withStartupTimeout(Duration.ofMinutes(2));
        }
    }
    static void preflight() throws Exception {
        NAMESRV.start(); BROKER.start();
        var created = BROKER.execInContainer("sh","mqadmin","updateTopic","-n","relay-namesrv:9876","-b","127.0.0.1:"+PORT,"-t",TOPIC,"-r","1","-w","1");
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target","relay-evidence"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target","relay-evidence","topic-create.log"),created.getStdout()+created.getStderr());
        assertThat(created.getExitCode()).as(created.getStdout()+created.getStderr()).isZero();
        assertThat(created.getStdout()).as("mqadmin can report errors with exit code 0").contains("success");
        probe = new DefaultMQProducer("relay-preflight-"+UUID.randomUUID());
        probe.setNamesrvAddr(namesrv()); probe.setVipChannelEnabled(false); probe.setInstanceName(UUID.randomUUID().toString());
        probe.setRetryTimesWhenSendFailed(0); probe.start();
        consumer = new DefaultMQPullConsumer("relay-verifier-"+UUID.randomUUID());
        consumer.setNamesrvAddr(namesrv()); consumer.setVipChannelEnabled(false); consumer.setInstanceName(UUID.randomUUID().toString()); consumer.start();
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> !probe.fetchPublishMessageQueues(TOPIC).isEmpty());
        var message = new Message(TOPIC,"PostPublished","preflight","{\"eventId\":-1}".getBytes(StandardCharsets.UTF_8));
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().untilAsserted(() ->
                assertThat(probe.send(message,10000).getSendStatus()).isEqualTo(SendStatus.SEND_OK));
        await().atMost(Duration.ofSeconds(20)).ignoreExceptions().until(() -> !received("preflight").isEmpty());
    }
    static List<MessageExt> received(String eventId) throws Exception {
        var found = new ArrayList<MessageExt>();
        for (var queue : consumer.fetchSubscribeMessageQueues(TOPIC)) {
            long offset=0;
            for (int page=0;page<20;page++) {
                var result = consumer.pull(queue,"*",offset,100,1000);
                if (result.getMsgFoundList()!=null)
                    result.getMsgFoundList().stream().filter(m -> eventId.equals(m.getKeys())).forEach(found::add);
                if (result.getNextBeginOffset()<=offset) break;
                offset=result.getNextBeginOffset();
            }
        }
        return found;
    }
    static void close() throws Exception {
        var dir=java.nio.file.Path.of("target","relay-evidence"); java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("broker.log"),BROKER.getLogs());
        java.nio.file.Files.writeString(dir.resolve("namesrv.log"),NAMESRV.getLogs());
        var diagnostic=BROKER.execInContainer("sh","-c","find /home/rocketmq/logs -type f -name '*broker*.log' -exec tail -n 100 {} \\;");
        java.nio.file.Files.writeString(dir.resolve("broker-internal.log"),diagnostic.getStdout()+diagnostic.getStderr());
        if(consumer!=null) consumer.shutdown(); if(probe!=null) probe.shutdown();
        BROKER.stop(); NAMESRV.stop(); NETWORK.close();
    }
}
