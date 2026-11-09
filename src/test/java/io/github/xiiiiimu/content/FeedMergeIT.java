package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.domain.FeedMerger.*;
import io.github.xiiiiimu.content.infrastructure.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.DefaultClientResources;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;

/** SQL source and authority below are integration fixtures, not production follow/visibility adapters. */
@Testcontainers
class FeedMergeIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    static DefaultClientResources resources;static RedisClient client;static StatefulRedisConnection<String,String> connection;
    static JdbcTemplate sql;static HikariDataSource pool;static RedisFeedCandidateStore store;
    ExecutorService executor;FeedMerger merger;
    static final long TIME=1_794_000_000_000L;
    static final Path EVIDENCE=Path.of("target","feed-merge-evidence");
    @BeforeAll static void preflight() throws Exception {
        Files.createDirectories(EVIDENCE);
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).load().migrate();
        String url=MYSQL.getJdbcUrl();
        pool=new HikariDataSource();pool.setJdbcUrl(url+(url.contains("?")?"&":"?")+"connectTimeout=2000&socketTimeout=2000");
        pool.setUsername(MYSQL.getUsername());pool.setPassword(MYSQL.getPassword());pool.setMaximumPoolSize(4);pool.setConnectionTimeout(2000);
        sql=new JdbcTemplate(pool);sql.setQueryTimeout(1);
        assertThat(sql.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
        resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        client=RedisClient.create(resources,RedisURI.Builder.redis(REDIS.getHost(),REDIS.getMappedPort(6379)).withTimeout(Duration.ofSeconds(2)).build());
        connection=client.connect();assertThat(connection.sync().ping()).isEqualTo("PONG");store=new RedisFeedCandidateStore(connection);
        Files.writeString(EVIDENCE.resolve("preflight.txt"),"Real MySQL SELECT 1 and Flyway migrations passed; real Redis PING passed\n");
    }
    @BeforeEach void setup() {
        connection.sync().flushdb();sql.update("DELETE FROM posts");sql.update("INSERT IGNORE INTO author_feed_mode(author_id,mode) VALUES(1,'PULL')");
        executor=Executors.newFixedThreadPool(8);merger=new FeedMerger(executor);
    }
    @AfterEach void stop() {executor.shutdownNow();}
    @AfterAll static void logs() throws Exception {
        Files.writeString(EVIDENCE.resolve("redis.log"),REDIS.getLogs());Files.writeString(EVIDENCE.resolve("mysql.log"),MYSQL.getLogs());
        if(connection!=null)connection.close();if(client!=null)client.shutdown();if(resources!=null)resources.shutdown();if(pool!=null)pool.close();
    }
    /** Fixture writes only — keep merger-path queryTimeout=1s, but CI Docker MySQL can stall a single INSERT >1s. */
    void post(long time,long id) {
        int prev=sql.getQueryTimeout();
        sql.setQueryTimeout(Math.max(prev,5));
        try{sql.update("INSERT INTO posts(id,author_id,status,body,published_at_ms) VALUES(?,1,'PUBLISHED','fixture',?)",id,time);}
        finally{sql.setQueryTimeout(prev);}
    }
    Source redis() {return new RedisCandidateSource(store,7);}
    Source sqlSource(String predicate) {
        return (snapshot,boundary,limit) -> {
            var args=new ArrayList<Object>();args.add(snapshot);String where="published_at_ms<=? AND ("+predicate+")";
            if(boundary!=null) {where+=" AND (published_at_ms<? OR (published_at_ms=? AND id<?))";args.add(boundary.publishedAtMs());args.add(boundary.publishedAtMs());args.add(boundary.postId());}
            args.add(limit);
            var rows=sql.query("SELECT published_at_ms,id FROM posts WHERE "+where+" ORDER BY published_at_ms DESC,id DESC LIMIT ?",(r,n) -> new FeedCandidate(r.getLong(1),r.getLong(2)),args.toArray());
            return new Batch(rows,rows.size()<limit);
        };
    }
    Authority authority() {
        return candidate -> {
            var rows=sql.query("SELECT published_at_ms,id,status FROM posts WHERE id=?",(r,n) -> new Decision(new FeedCandidate(r.getLong(1),r.getLong(2)),r.getString(3).equals("PUBLISHED")),candidate.postId());
            return rows.isEmpty()?new Decision(null,false):rows.get(0);
        };
    }
    Continuation start() {return new Continuation(TIME,null,null);}
    List<FeedCandidate> traverse(Map<String,Source> sources,int size,Budget budget,String evidence) throws Exception {
        var all=new ArrayList<FeedCandidate>();Continuation next=start();var trace=new StringBuilder();int pages=0;
        do {
            var input=next;var p=merger.read(sources,authority(),input,size,budget);all.addAll(p.items());next=p.next();
            trace.append("input=").append(input).append(" output=").append(next).append(" state=").append(p.pageState()).append(" scanned=").append(p.scanned()).append(" fetched=").append(p.fetched()).append(" reads=").append(p.sourceReads()).append(" P=").append(p.sourcePositions()).append(" items=").append(p.items()).append('\n');
            if(next!=null && input.consumedBoundary()!=null)assertThat(FeedMergerTest.ORDER.compare(next.consumedBoundary(),input.consumedBoundary())).isNegative();
            assertThat(++pages).isLessThan(600);
        }while(next!=null);
        Files.writeString(EVIDENCE.resolve(evidence),trace);return all;
    }
    @Test void t02All501AcrossRealRedisAndSqlMatchFullSqlOrder() throws Exception {
        var shuffled=new ArrayList<FeedCandidate>();for(long i=1;i<=501;i++){post(TIME,i);if(i%2==1 || i%3==0)shuffled.add(new FeedCandidate(TIME,i));}
        Collections.shuffle(shuffled,new Random(501));for(int i=0;i<shuffled.size();i+=100)store.add(7,shuffled.subList(i,Math.min(i+100,shuffled.size())));
        var sources=Map.of("redis",redis(),"sql",sqlSource("MOD(id,2)=0 OR MOD(id,3)=0"));
        var actual=new ArrayList<FeedCandidate>();
        assertThatCode(() -> actual.addAll(traverse(sources,7,new Budget(3,11,64,256,Duration.ofSeconds(2)),"t02-501.txt")))
                .as("Real Redis and MySQL preflight passed; ordered merge behavior must be implemented").doesNotThrowAnyException();
        var expected=sql.query("SELECT published_at_ms,id FROM posts ORDER BY published_at_ms DESC,id DESC",(r,n) -> new FeedCandidate(r.getLong(1),r.getLong(2)));
        assertThat(actual).hasSize(501).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(actual.stream().map(FeedCandidate::postId)).containsExactlyElementsOf(LongStream.iterate(501,i -> i>0,i -> i-1).boxed().toList());
    }
    @Test void prefetchAndCrossMillisecondDuplicatesSurviveNewRequests() throws Exception {
        for(long i=1;i<=50;i++){post(TIME-i%3,i);store.add(7,List.of(new FeedCandidate(TIME-i%3,i)));}
        var actual=traverse(Map.of("redis",redis(),"sql",sqlSource("1=1")),2,Budget.defaults(),"prefetch.txt");
        var expected=sql.query("SELECT published_at_ms,id FROM posts ORDER BY published_at_ms DESC,id DESC",(r,n) -> new FeedCandidate(r.getLong(1),r.getLong(2)));
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }
    @Test void realAuthorityFiltersHundredHiddenRowsWithStrictlyAdvancingEmptyUnknownPages() throws Exception {
        for(long i=1;i<=105;i++) {post(TIME,i);store.add(7,List.of(new FeedCandidate(TIME,i)));}
        sql.update("UPDATE posts SET status='HIDDEN' WHERE id>5");Continuation next=start();int empty=0;var result=new ArrayList<FeedCandidate>();
        for(int request=0;next!=null && request<30;request++) {
            var p=merger.read(Map.of("redis",redis(),"sql",sqlSource("1=1")),authority(),next,5,new Budget(1,16,32,256,Duration.ofSeconds(2)));
            if(p.items().isEmpty()) {empty++;assertThat(p.pageState()).isEqualTo(PageState.UNKNOWN);assertThat(p.scanned()).isEqualTo(16);assertThat(p.next().globalBoundary()).isNull();}
            if(next.consumedBoundary()!=null && p.next()!=null)assertThat(FeedMergerTest.ORDER.compare(p.next().consumedBoundary(),next.consumedBoundary())).isNegative();
            result.addAll(p.items());next=p.next();
        }
        assertThat(next).isNull();assertThat(empty).isEqualTo(6);assertThat(result.stream().map(FeedCandidate::postId)).containsExactly(5L,4L,3L,2L,1L);
    }
    @Test void lateRowsInOtherSourceStayBelowSharedBoundary() {
        post(TIME,100);post(TIME,60);store.add(7,List.of(new FeedCandidate(TIME,100),new FeedCandidate(TIME,60)));
        var sources=Map.of("redis",redis(),"sql",sqlSource("1=1"));var first=merger.read(sources,authority(),start(),1,Budget.defaults());
        post(TIME,110);post(TIME,90);post(TIME+1,1);
        assertThat(merger.read(sources,authority(),first.next(),100,Budget.defaults()).items()).containsExactly(new FeedCandidate(TIME,90),new FeedCandidate(TIME,60));
        assertThat(merger.read(sources,authority(),start(),100,Budget.defaults()).items()).containsExactly(new FeedCandidate(TIME,110),new FeedCandidate(TIME,100),new FeedCandidate(TIME,90),new FeedCandidate(TIME,60));
    }
    @Test void redisWrongTypeCannotProduceSuccessfulExhaustion() {
        connection.sync().set("feed:v2:{7}:inbox","wrong");
        assertThatThrownBy(() -> merger.read(Map.of("redis",redis(),"sql",sqlSource("1=1")),authority(),start(),1,Budget.defaults())).isInstanceOf(Unavailable.class);
    }
    @Test void actualRedisPauseIsAnErrorAndOriginalInputRecovers() throws Exception {
        post(TIME,1);store.add(7,List.of(new FeedCandidate(TIME,1)));var docker=REDIS.getDockerClient();docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            var failure=catchThrowable(() -> merger.read(Map.of("redis",redis()),authority(),start(),1,Budget.defaults()));
            assertThat(failure).isInstanceOf(Unavailable.class);failureEvidence("redis-pause-error.txt",failure);
        }
        finally {docker.unpauseContainerCmd(REDIS.getContainerId()).exec();}
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(() -> assertThat(merger.read(Map.of("redis",redis()),authority(),start(),1,Budget.defaults()).items()).containsExactly(new FeedCandidate(TIME,1)));
        Files.writeString(EVIDENCE.resolve("redis-pause.txt"),"Real Docker pause: merge failed instead of EXHAUSTED; unpause + same continuation recovered\n");
    }
    @Test void realSqlTimeoutAndInvalidQueryCannotBecomeExhaustion() throws Exception {
        Source slow=(s,b,n) -> {sql.queryForObject("SELECT SLEEP(5)",Integer.class);return new Batch(List.of(),true);};
        Source bad=(s,b,n) -> {sql.queryForList("SELECT * FROM missing_feed_fixture");return new Batch(List.of(),true);};
        var timeout=catchThrowable(() -> merger.read(Map.of("sql",slow),authority(),start(),1,Budget.defaults()));
        assertThat(timeout).isInstanceOf(Unavailable.class);failureEvidence("sql-timeout-error.txt",timeout);
        var invalid=catchThrowable(() -> merger.read(Map.of("sql",bad),authority(),start(),1,Budget.defaults()));
        assertThat(invalid).isInstanceOf(Unavailable.class).hasRootCauseInstanceOf(java.sql.SQLSyntaxErrorException.class);failureEvidence("sql-query-error.txt",invalid);
        Files.writeString(EVIDENCE.resolve("sql-faults.txt"),"Real MySQL SLEEP with one-second JDBC query timeout and missing-table query both rejected\n");
    }
    static void failureEvidence(String name,Throwable failure) throws Exception {
        var text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));Files.writeString(EVIDENCE.resolve(name),text.toString());
    }
    @Test void canonicalTimestampMismatchIsRejectedEvenAcrossReconstructedRequests() {
        post(TIME-1,1);store.add(7,List.of(new FeedCandidate(TIME,1)));
        assertThatThrownBy(() -> merger.read(Map.of("redis",redis()),authority(),start(),1,Budget.defaults())).isInstanceOf(Unavailable.class);
    }
    @Test void redisAndSqlExtremeTupleBoundsAgree() throws Exception {
        post(0,1);post(FeedMemberCodec.MAX_TIME_MS,Long.MAX_VALUE);
        store.add(7,List.of(new FeedCandidate(0,1),new FeedCandidate(FeedMemberCodec.MAX_TIME_MS,Long.MAX_VALUE)));
        var p=merger.read(Map.of("redis",redis(),"sql",sqlSource("1=1")),authority(),new Continuation(FeedMemberCodec.MAX_TIME_MS,null,null),1,Budget.defaults());
        assertThat(p.items()).containsExactly(new FeedCandidate(FeedMemberCodec.MAX_TIME_MS,Long.MAX_VALUE));
        assertThat(merger.read(Map.of("redis",redis(),"sql",sqlSource("1=1")),authority(),p.next(),1,Budget.defaults()).items()).containsExactly(new FeedCandidate(0,1));
    }
}
