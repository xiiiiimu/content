package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.RedisFeedCandidateStore;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.resource.DefaultClientResources;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.*;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class RedisFeedCandidateStoreIT {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    static DefaultClientResources resources;
    static RedisClient client;
    static StatefulRedisConnection<String,String> connection;
    static RedisCommands<String,String> raw;
    RedisFeedCandidateStore store;
    static final long UID=7;
    static final long TIME=1_794_000_000_000L;
    static final String KEY="feed:v2:{7}:inbox";
    static final String TRUST=KEY+":untrusted";
    @BeforeAll static void preflight() {
        resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        client=RedisClient.create(resources,RedisURI.Builder.redis(REDIS.getHost(),REDIS.getMappedPort(6379)).withTimeout(Duration.ofSeconds(2)).build());
        connection=client.connect();raw=connection.sync();
        assertThat(raw.ping()).isEqualTo("PONG");
        raw.zadd("preflight",0,"0000000000000:00000000000000000001");
        assertThat(raw.zrange("preflight",0,0)).containsExactly("0000000000000:00000000000000000001");raw.del("preflight");
    }
    @AfterAll static void close() throws Exception {
        var folder=java.nio.file.Path.of("target","redis-candidate-evidence");java.nio.file.Files.createDirectories(folder);
        java.nio.file.Files.writeString(folder.resolve("redis.log"),REDIS.getLogs());
        if(connection!=null)connection.close();if(client!=null)client.shutdown();if(resources!=null)resources.shutdown();
    }
    @BeforeEach void fixture() {raw.flushdb();store=new RedisFeedCandidateStore(connection);}
    @Test void t01SameMillisecondIdsNineTenElevenOneAtATime() {
        assertThatCode(() -> store.add(UID,List.of(new FeedCandidate(TIME,9),new FeedCandidate(TIME,10),new FeedCandidate(TIME,11))))
                .as("Redis PING and raw ZSET preflight passed; candidate adapter behavior is required").doesNotThrowAnyException();
        String boundary=null;
        for(long id:new long[]{11,10,9}) {
            var page=store.read(UID,TIME,boundary,1);
            assertThat(page.items()).containsExactly(new FeedCandidate(TIME,id));
            assertThat(page.lastReadBoundary()).isEqualTo(FeedMemberCodec.encode(new FeedCandidate(TIME,id)));
            if(boundary!=null)assertThat(page.lastReadBoundary()).isLessThan(boundary);
            boundary=page.lastReadBoundary();
        }
        assertThat(store.read(UID,TIME,boundary,1).items()).isEmpty();
        assertThat(raw.zrangeWithScores(KEY,0,2)).allSatisfy(v -> assertThat(v.getScore()).isZero());
    }
    @Test void t02Traverses501SameMillisecondCandidatesWithoutDuplicatesOrStalling() {
        var shuffled=new ArrayList<>(LongStream.rangeClosed(1,501).mapToObj(id -> new FeedCandidate(TIME,id)).toList());
        Collections.shuffle(shuffled,new Random(401));
        for(int start=0;start<shuffled.size();start+=100)store.add(UID,shuffled.subList(start,Math.min(start+100,shuffled.size())));
        var expected=LongStream.iterate(501,id -> id>0,id -> id-1).mapToObj(id -> new FeedCandidate(TIME,id)).toList();
        var actual=new ArrayList<FeedCandidate>();String boundary=null;int calls=0;
        while(calls++<100) {
            var page=store.read(UID,TIME,boundary,7);assertThat(page.items().size()).isLessThanOrEqualTo(7);
            if(page.items().isEmpty())break;
            if(boundary!=null)assertThat(page.lastReadBoundary()).isLessThan(boundary);
            actual.addAll(page.items());boundary=page.lastReadBoundary();
        }
        assertThat(calls).isLessThan(100);assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(raw.zcard(KEY)).isEqualTo(501);
    }
    @Test void ordersAcrossMillisecondsAndHonorsSnapshot() {
        store.add(UID,List.of(new FeedCandidate(1000,Long.MAX_VALUE),new FeedCandidate(1001,1),new FeedCandidate(1001,9),new FeedCandidate(1002,3)));
        assertThat(store.read(UID,1001,null,10).items()).containsExactly(new FeedCandidate(1001,9),new FeedCandidate(1001,1),new FeedCandidate(1000,Long.MAX_VALUE));
    }
    @Test void boundaryIsExclusiveAndClampedToSnapshot() {
        store.add(UID,List.of(new FeedCandidate(1000,1),new FeedCandidate(1000,2),new FeedCandidate(1000,3),new FeedCandidate(1001,4)));
        assertThat(store.read(UID,1001,FeedMemberCodec.encode(new FeedCandidate(1000,2)),10).items()).containsExactly(new FeedCandidate(1000,1));
        assertThat(store.read(UID,1000,FeedMemberCodec.encode(new FeedCandidate(1001,4)),10).items()).containsExactly(new FeedCandidate(1000,3),new FeedCandidate(1000,2),new FeedCandidate(1000,1));
        assertThat(store.read(UID,1000,FeedMemberCodec.encode(new FeedCandidate(999,9)),10).items()).isEmpty();
    }
    @Test void successfulMissingOrEmptyKeyIsOnlyAnEmptySourceRead() {
        var result=store.read(UID,TIME,null,5);assertThat(result.items()).isEmpty();assertThat(result.lastReadBoundary()).isNull();
        store.add(UID,List.of(new FeedCandidate(TIME,1)));raw.zrem(KEY,FeedMemberCodec.encode(new FeedCandidate(TIME,1)));
        assertThat(store.read(UID,TIME,null,5).items()).isEmpty();
    }
    @Test void fixedPublicationReplayIsIdempotentAndReaderKeysAreIsolated() {
        var value=new FeedCandidate(TIME,10);store.add(UID,List.of(value));store.add(UID,List.of(value,value));
        assertThat(raw.zcard(KEY)).isEqualTo(1);assertThat(store.read(UID,TIME,null,5).items()).containsExactly(value);
        assertThat(store.read(8,TIME,null,5).items()).isEmpty();
    }
    @Test void legalExtremesAreReadableAndExactlyAtSnapshotBoundary() {
        var max=new FeedCandidate(FeedMemberCodec.MAX_TIME_MS,Long.MAX_VALUE);var min=new FeedCandidate(0,1);
        store.add(UID,List.of(min,max));assertThat(store.read(UID,FeedMemberCodec.MAX_TIME_MS,null,2).items()).containsExactly(max,min);
        assertThat(store.read(UID,FeedMemberCodec.MAX_TIME_MS,FeedMemberCodec.encode(max),2).items()).containsExactly(min);
        assertThat(store.read(UID,0,null,1).items()).containsExactly(min);
    }
    @Test void validatesLimitsIdsSnapshotAndBoundaryBeforeRedis() {
        for(int n:new int[]{0,-1,RedisFeedCandidateStore.MAX_READ_SIZE+1,Integer.MAX_VALUE})
            assertThatThrownBy(() -> store.read(UID,TIME,null,n)).isInstanceOf(IllegalArgumentException.class);
        for(long id:new long[]{0,-1})assertThatThrownBy(() -> store.read(id,TIME,null,1)).isInstanceOf(IllegalArgumentException.class);
        for(long time:new long[]{-1,FeedMemberCodec.MAX_TIME_MS+1})assertThatThrownBy(() -> store.read(UID,time,null,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read(UID,TIME,"bad",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add(UID,Collections.nCopies(RedisFeedCandidateStore.MAX_WRITE_SIZE+1,new FeedCandidate(TIME,1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add(0,List.of(new FeedCandidate(TIME,1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add(UID,List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add(UID,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add(UID,Arrays.asList(new FeedCandidate(TIME,1),null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add(UID,List.of(new FeedCandidate(TIME,1),new FeedCandidate(-1,2)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(raw.exists(KEY)).isZero();
    }
    @Test void maximumReadAndWriteBatchAreBoundedAndAccepted() {
        var values=LongStream.rangeClosed(1,200).mapToObj(id -> new FeedCandidate(TIME,id)).toList();store.add(UID,values);
        var page=store.read(UID,TIME,null,RedisFeedCandidateStore.MAX_READ_SIZE);assertThat(page.items()).hasSize(200);
        assertThat(store.read(UID,TIME,page.lastReadBoundary(),1).items()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"0000000000000:00000000000000000000","0000000000000:09999999999999999999","0000000000000:0000000000000000000x","0000000000000:0000000000000000001"})
    void corruptReturnedMemberFailsWholeReadAndPersistsUntrustedMarker(String member) {
        store.add(UID,List.of(new FeedCandidate(TIME,1)));raw.zadd(KEY,0,member);
        assertThatThrownBy(() -> store.read(UID,TIME,null,10)).isInstanceOf(CandidateIndexUnavailableException.class)
                .satisfies(e -> assertThat(((CandidateIndexUnavailableException)e).reason()).isEqualTo(CandidateIndexUnavailableException.Reason.UNTRUSTED_INDEX));
        assertThat(raw.get(TRUST)).isNotBlank();raw.zrem(KEY,member);
        assertThatThrownBy(() -> store.read(UID,TIME,null,10)).isInstanceOf(CandidateIndexUnavailableException.class);
        assertThatThrownBy(() -> store.add(UID,List.of(new FeedCandidate(TIME,2)))).isInstanceOf(CandidateIndexUnavailableException.class);
    }
    @Test void wrongKeyTypeFailsReadAndWriteWithoutOverwritingIt() {
        raw.set(KEY,"wrong-type");assertThatThrownBy(() -> store.read(UID,TIME,null,1)).isInstanceOf(CandidateIndexUnavailableException.class);
        assertThatThrownBy(() -> store.add(UID,List.of(new FeedCandidate(TIME,1)))).isInstanceOf(CandidateIndexUnavailableException.class);
        assertThat(raw.get(KEY)).isEqualTo("wrong-type");assertThat(raw.get(TRUST)).isNotBlank();
    }
    @ParameterizedTest @ValueSource(doubles={-1,1})
    void anyNonZeroScoreFailsEvenWhenMemberIsOutsideRequestedWindow(double score) {
        raw.zadd(KEY,score,FeedMemberCodec.encode(new FeedCandidate(TIME+1,1)));
        assertThatThrownBy(() -> store.read(UID,TIME,null,1)).isInstanceOf(CandidateIndexUnavailableException.class);
        assertThat(raw.get(TRUST)).isNotBlank();
    }
    @Test void actualRedisTimeoutIsNotExhaustionAndCanRecover() {
        store.add(UID,List.of(new FeedCandidate(TIME,1)));var docker=REDIS.getDockerClient();var container=REDIS.getContainerId();docker.pauseContainerCmd(container).exec();
        try {assertThatThrownBy(() -> store.read(UID,TIME,null,1)).isInstanceOf(CandidateIndexUnavailableException.class)
                .satisfies(e -> assertThat(((CandidateIndexUnavailableException)e).reason()).isEqualTo(CandidateIndexUnavailableException.Reason.REDIS_UNAVAILABLE));}
        finally {docker.unpauseContainerCmd(container).exec();}
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(() -> assertThat(store.read(UID,TIME,null,1).items()).containsExactly(new FeedCandidate(TIME,1)));
    }
    @Test void closedConnectionIsNotTranslatedToAnEmptySource() {
        try(var other=client.connect()) {
            var adapter=new RedisFeedCandidateStore(other);other.close();
            assertThatThrownBy(() -> adapter.read(UID,TIME,null,1)).isInstanceOf(CandidateIndexUnavailableException.class)
                    .satisfies(e -> assertThat(((CandidateIndexUnavailableException)e).reason()).isEqualTo(CandidateIndexUnavailableException.Reason.REDIS_UNAVAILABLE));
        }
    }
}
