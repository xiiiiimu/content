package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.RequestCode;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties={"content.relay.enabled=true","content.relay.scan-enabled=false","content.relay.topic=content-relay-it",
        "content.relay.lease-millis=4000","content.relay.send-timeout-millis=1000","content.relay.max-attempts=3",
        "content.relay.base-backoff-millis=100","content.relay.max-backoff-millis=1000","content.relay.batch-size=1"})
@Testcontainers
class OutboxRelayIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static final AtomicLong IDS=new AtomicLong(10000);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",MYSQL::getUsername); r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("content.relay.namesrv",RelayMqFixture::namesrv);
    }
    @BeforeAll static void preflight() throws Exception { RelayMqFixture.preflight(); }
    @AfterAll static void close() throws Exception { RelayMqFixture.close(); }
    @Autowired JdbcTemplate db;
    @Autowired(required=false) RelayRunner runner;
    @Autowired(required=false) RelayStore store;
    @Autowired(required=false) DefaultMQProducer relayProducer;
    long id;
    String payload;
    @BeforeEach void fixture() {
        db.execute("DROP TRIGGER IF EXISTS fail_relay_sent"); db.execute("DROP TRIGGER IF EXISTS fail_relay_audit");
        if (db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='outbox_replay_audit'",Integer.class)>0) db.update("DELETE FROM outbox_replay_audit");
        db.update("DELETE FROM outbox_events"); id=IDS.incrementAndGet();
        db.update("INSERT INTO outbox_events(id,event_type,aggregate_id,payload) VALUES (?,'PostPublished',?,?)",id,id,
                "{\"eventId\":"+id+",\"postId\":"+id+",\"authorId\":1,\"publishedAtMs\":1794000000000,\"deliveryMode\":\"PUSH\",\"deliveryEpoch\":7,\"postVersion\":1,\"publishSeq\":1}");
        payload=db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,id);
    }
    RelayStore control() { assertThat(store).as("Persistent Relay implementation is required after real MySQL/MQ preflight").isNotNull(); return store; }
    void tick() { assertThat(runner).as("Relay missing; real MQ SEND_OK and consumer preflight succeeded").isNotNull(); runner.tick(); }
    String state() { return db.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,id); }
    int attempt() { return db.queryForObject("SELECT attempt FROM outbox_events WHERE id=?",Integer.class,id); }
    RelayLease claim() { return control().claim().orElseThrow(); }
    void expire() { db.update("UPDATE outbox_events SET lease_until=CURRENT_TIMESTAMP(6) WHERE id=?",id); }
    void eligible() { db.update("UPDATE outbox_events SET next_retry_at=CURRENT_TIMESTAMP(6) WHERE id=?",id); }
    void messages(int count) {
        await().atMost(Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(() -> {
            var found=RelayMqFixture.received(Long.toString(id)); assertThat(found).hasSize(count);
            assertThat(found).allSatisfy(m -> {
                assertThat(new String(m.getBody(),StandardCharsets.UTF_8)).isEqualTo(payload);
                assertThat(m.getUserProperty("eventId")).isEqualTo(Long.toString(id));
                assertThat(m.getTags()).isEqualTo("PostPublished");
            });
        });
    }
    @Test void sendsCommittedEventAndMarksSentOnlyAfterAck() {
        tick(); assertThat(state()).isEqualTo("SENT"); assertThat(attempt()).isEqualTo(1); messages(1);
    }
    @Test void uncommittedAndRolledBackOutboxIsNotSent() throws Exception {
        db.update("DELETE FROM outbox_events");
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("INSERT INTO outbox_events(id,event_type,aggregate_id,payload) VALUES (?,'PostPublished',?,?)")) {
                s.setLong(1,id);s.setLong(2,id);s.setString(3,payload);s.executeUpdate();
            }
            tick(); assertThat(RelayMqFixture.received(Long.toString(id))).isEmpty(); c.rollback();
        }
        tick(); assertThat(RelayMqFixture.received(Long.toString(id))).isEmpty();
    }
    @Test void concurrentClaimHasSingleOwner() throws Exception {
        var pool=Executors.newFixedThreadPool(3); var gate=new CyclicBarrier(3);
        try {
            var tasks=new ArrayList<Future<Optional<RelayLease>>>();
            for(int i=0;i<3;i++) tasks.add(pool.submit(() -> {gate.await(10,TimeUnit.SECONDS);return control().claim();}));
            int owners=0;for(var f:tasks) if(f.get(15,TimeUnit.SECONDS).isPresent()) owners++;
            assertThat(owners).isEqualTo(1);assertThat(attempt()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @ParameterizedTest
    @EnumSource(value=SendStatus.class, mode=EnumSource.Mode.EXCLUDE, names="SEND_OK")
    void nonSuccessfulAckIsRetriedUsingRealSendWithResponseFaultHook(SendStatus status) throws Exception {
        control();assertThat(relayProducer).isNotNull();
        var enabled=new AtomicBoolean(true);var injected=new AtomicInteger();
        int code=switch(status) {
            case FLUSH_DISK_TIMEOUT -> ResponseCode.FLUSH_DISK_TIMEOUT;
            case FLUSH_SLAVE_TIMEOUT -> ResponseCode.FLUSH_SLAVE_TIMEOUT;
            case SLAVE_NOT_AVAILABLE -> ResponseCode.SLAVE_NOT_AVAILABLE;
            default -> throw new IllegalArgumentException();
        };
        // Real request reaches the Broker; substitute its actual response at the client RPC boundary.
        // This proves protocol handling, not an actual disk flush or replica failure.
        relayProducer.getDefaultMQProducerImpl().getmQClientFactory().getMQClientAPIImpl().getRemotingClient().registerRPCHook(new RPCHook() {
            public void doBeforeRequest(String address,RemotingCommand request) {}
            public void doAfterResponse(String address,RemotingCommand request,RemotingCommand response) {
                if(enabled.get() && response!=null && response.getCode()==ResponseCode.SUCCESS
                        && (request.getCode()==RequestCode.SEND_MESSAGE || request.getCode()==RequestCode.SEND_MESSAGE_V2)) {
                    response.setCode(code);injected.incrementAndGet();
                }
            }
        });
        try {tick();assertThat(injected.get()).isEqualTo(1);assertThat(state()).isEqualTo("NEW");assertThat(attempt()).isEqualTo(1);messages(1);}
        finally {enabled.set(false);}
        eligible();tick();assertThat(state()).isEqualTo("SENT");messages(2);
    }
    @Test void pausedBrokerProducesRealSendTimeoutAndRetry() throws Exception {
        control();relayProducer.fetchPublishMessageQueues(RelayMqFixture.TOPIC);
        var docker=RelayMqFixture.BROKER.getDockerClient();var container=RelayMqFixture.BROKER.getContainerId();
        docker.pauseContainerCmd(container).exec();
        try {tick();assertThat(state()).isEqualTo("NEW");assertThat(attempt()).isEqualTo(1);}
        finally {docker.unpauseContainerCmd(container).exec();}
        assertThat(db.queryForObject("SELECT last_error FROM outbox_events WHERE id=?",String.class,id).toLowerCase()).contains("timeout");
        eligible();tick();assertThat(state()).isEqualTo("SENT");
        await().atMost(Duration.ofSeconds(20)).ignoreExceptions().until(() -> !RelayMqFixture.received(Long.toString(id)).isEmpty());
    }
    @Test void stoppedBrokerCannotBeMarkedSentAndRecovers() throws Exception {
        control();relayProducer.fetchPublishMessageQueues(RelayMqFixture.TOPIC);
        var docker=RelayMqFixture.BROKER.getDockerClient();var container=RelayMqFixture.BROKER.getContainerId();
        docker.stopContainerCmd(container).withTimeout(0).exec();
        try {tick();assertThat(state()).isEqualTo("NEW");assertThat(attempt()).isEqualTo(1);}
        finally {
            docker.startContainerCmd(container).exec();
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                try(var socket=new java.net.Socket("127.0.0.1",RelayMqFixture.PORT)){return socket.isConnected();}
            });
        }
        eligible();tick();assertThat(state()).isEqualTo("SENT");messages(1);
    }
    @Test void expiredUnclaimedTokenCannotCompleteOrRetry() {
        var old=claim();expire();assertThat(control().sent(old)).isFalse();assertThat(control().failed(old,"late")).isFalse();
        assertThat(state()).isEqualTo("RUNNING");assertThat(attempt()).isEqualTo(1);
    }
    @Test void reclaimedTokenCannotCompleteOrRetry() {
        var old=claim();expire();var current=claim();assertThat(current.token()).isGreaterThan(old.token());
        assertThat(control().sent(old)).isFalse();assertThat(control().failed(old,"late")).isFalse();
        assertThat(control().sent(current)).isTrue();assertThat(state()).isEqualTo("SENT");
    }
    @Test void retryUsesFrozenBackoffAndCountsClaims() {
        var lease=claim();assertThat(control().failed(lease,"failure")).isTrue();assertThat(state()).isEqualTo("NEW");
        assertThat(db.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,last_attempt_at,next_retry_at) FROM outbox_events WHERE id=?",Long.class,id)).isGreaterThanOrEqualTo(100000);
        db.update("UPDATE outbox_events SET next_retry_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
        assertThat(control().claim()).isEmpty(); eligible();var second=claim();assertThat(second.attempt()).isEqualTo(2);
    }
    @Test void finalFailedAttemptBecomesDead() {
        db.update("UPDATE outbox_events SET attempt=2 WHERE id=?",id);var last=claim();
        assertThat(control().failed(last,"last failure")).isTrue();assertThat(state()).isEqualTo("DEAD");assertThat(attempt()).isEqualTo(3);
        assertThat(control().claim()).isEmpty();
    }
    @Test void finalExpiredAttemptBecomesDeadWithoutExtraClaim() {
        db.update("UPDATE outbox_events SET attempt=2 WHERE id=?",id);var last=claim();expire();
        assertThat(control().reapExhausted()).isEqualTo(1);assertThat(state()).isEqualTo("DEAD");assertThat(attempt()).isEqualTo(3);
        assertThat(control().claim()).isEmpty();assertThat(control().sent(last)).isFalse();assertThat(control().failed(last,"late")).isFalse();
    }
    @Test void deadReplayResetsAttemptFencesOldOwnerAndAuditsAtomically() {
        db.update("UPDATE outbox_events SET attempt=2 WHERE id=?",id);var old=claim();control().failed(old,"dead");
        assertThat(control().replay(id,"operator","incident-42")).isTrue();assertThat(state()).isEqualTo("NEW");assertThat(attempt()).isZero();
        assertThat(db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,id)).isEqualTo(payload);
        assertThat(db.queryForMap("SELECT operator,reason,old_token,new_token,previous_attempt FROM outbox_replay_audit WHERE event_id=?",id))
                .containsEntry("operator","operator").containsEntry("reason","incident-42").containsEntry("old_token",old.token()).containsEntry("new_token",old.token()+1).containsEntry("previous_attempt",3);
        assertThat(control().sent(old)).isFalse();assertThat(control().failed(old,"late")).isFalse();
        var replayed=claim();assertThat(replayed.attempt()).isEqualTo(1);assertThat(replayed.token()).isEqualTo(old.token()+2);
        assertThat(control().replay(id,"operator","not dead")).isFalse();
    }
    @Test void rejectsReplayOutsideDeadOrWithoutAuditIdentity() {
        assertThat(control().replay(id,"operator","reason")).isFalse();
        assertThatThrownBy(() -> control().replay(id," ","reason")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control().replay(id,"operator","")).isInstanceOf(IllegalArgumentException.class);
    }
    void trigger(String name,String table,String operation,String condition) throws Exception {
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var s=c.createStatement()) {
            s.execute("CREATE TRIGGER "+name+" BEFORE "+operation+" ON "+table+" FOR EACH ROW BEGIN "+condition+" SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='relay injected failure'; "+(condition.isEmpty()?"":"END IF;")+" END");
        }
    }
    @Test void ackThenDatabaseFailureRemainsRecoverable() throws Exception {
        control();trigger("fail_relay_sent","outbox_events","UPDATE","IF NEW.status='SENT' THEN");
        tick();messages(1);assertThat(state()).isNotEqualTo("SENT");
        db.execute("DROP TRIGGER fail_relay_sent");
        if(state().equals("RUNNING"))expire();else eligible(); tick();messages(2);assertThat(state()).isEqualTo("SENT");
    }
    @Test void auditFailureRollsBackReplay() throws Exception {
        control();db.update("UPDATE outbox_events SET status='DEAD',attempt=3 WHERE id=?",id);
        var before=db.queryForMap("SELECT * FROM outbox_events WHERE id=?",id);
        trigger("fail_relay_audit","outbox_replay_audit","INSERT","");
        assertThatThrownBy(() -> control().replay(id,"operator","reason")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForMap("SELECT * FROM outbox_events WHERE id=?",id)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_replay_audit",Integer.class)).isZero();
    }
    Process process(String phase,Path signal) throws Exception {
        Files.createDirectories(signal.getParent());
        Files.deleteIfExists(signal);Files.deleteIfExists(signal.resolveSibling(signal.getFileName()+".killed"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),RelayCrashProcess.class.getName(),phase,signal.toAbsolutePath().toString());
        builder.environment().put("CONTENT_DB_URL",MYSQL.getJdbcUrl());builder.environment().put("CONTENT_MYSQL_USER",MYSQL.getUsername());builder.environment().put("CONTENT_MYSQL_PASSWORD",MYSQL.getPassword());
        builder.environment().put("RELAY_TEST_NAMESRV",RelayMqFixture.namesrv());
        builder.redirectErrorStream(true).redirectOutput(signal.resolveSibling(signal.getFileName()+".log").toFile());return builder.start();
    }
    void kill(Process child,Path signal) throws Exception {
        await().atMost(Duration.ofSeconds(60)).until(() -> {assertThat(child.isAlive()).as("child log: "+signal+".log").isTrue();return Files.exists(signal);});
        child.destroyForcibly();assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();
        Files.writeString(signal.resolveSibling(signal.getFileName()+".killed"),"eventId="+id+" attempt="+attempt()+" state="+state()+" exit="+child.exitValue());
    }
    void awaitExpiry() {await().atMost(Duration.ofSeconds(15)).until(() -> db.queryForObject("SELECT lease_until<=CURRENT_TIMESTAMP(6) FROM outbox_events WHERE id=?",Boolean.class,id));}
    @Test void killAfterClaimBeforeSendRecoversAfterLease() throws Exception {
        control();var signal=Path.of("target","relay-evidence","claim-"+id);var child=process("CLAIM",signal);
        try {kill(child,signal);}finally {if(child.isAlive())child.destroyForcibly();}
        assertThat(attempt()).isEqualTo(1);assertThat(RelayMqFixture.received(Long.toString(id))).isEmpty();awaitExpiry();tick();messages(1);assertThat(state()).isEqualTo("SENT");assertThat(attempt()).isEqualTo(2);
    }
    @Test void killAfterRealAckBeforeSentReplaysSameEvent() throws Exception {
        control();var signal=Path.of("target","relay-evidence","ack-"+id);var child=process("ACK",signal);
        try {kill(child,signal);}finally {if(child.isAlive())child.destroyForcibly();}
        messages(1);assertThat(state()).isEqualTo("RUNNING");awaitExpiry();
        var recovery=process("ONCE",Path.of("target","relay-evidence","recovery-"+id));
        try {assertThat(recovery.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(recovery.exitValue()).isZero();}finally {if(recovery.isAlive())recovery.destroyForcibly();}
        messages(2);assertThat(state()).isEqualTo("SENT");assertThat(attempt()).isEqualTo(2);
    }
    @Test void lastClaimKilledBeforeSendIsReapedDead() throws Exception {
        control();db.update("UPDATE outbox_events SET attempt=2 WHERE id=?",id);
        var signal=Path.of("target","relay-evidence","last-"+id);var child=process("CLAIM",signal);
        try {kill(child,signal);}finally {if(child.isAlive())child.destroyForcibly();}
        awaitExpiry();tick();assertThat(state()).isEqualTo("DEAD");assertThat(attempt()).isEqualTo(3);assertThat(RelayMqFixture.received(Long.toString(id))).isEmpty();
    }
    @Test void restartedScannerFindsNewEventsWithoutMemoryQueue() throws Exception {
        control();var signal=Path.of("target","relay-evidence","scanner-"+id);var child=process("SCAN",signal);
        try {await().atMost(Duration.ofSeconds(60)).until(() -> state().equals("SENT"));messages(1);}finally {child.destroyForcibly();child.waitFor(15,TimeUnit.SECONDS);}
    }
}
