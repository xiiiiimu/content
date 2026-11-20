package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.api.FeedIdentityFilter;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.*;
import java.time.Duration;

@Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class FeedConfiguration {
 @Bean static org.springframework.beans.factory.config.BeanPostProcessor feedDataSourceBounds(){return new org.springframework.beans.factory.config.BeanPostProcessor(){
  @Override public Object postProcessBeforeInitialization(Object bean,String name){if(bean instanceof com.zaxxer.hikari.HikariDataSource pool && !pool.isRunning()){
   // Default 16 suits FollowingFeed harness; commerce load sets CONTENT_HIKARI_MAX_POOL / -Dcontent.hikari.max-pool.
   int maxPool=parsePositive(firstNonBlank(System.getProperty("content.hikari.max-pool"),System.getenv("CONTENT_HIKARI_MAX_POOL")),16);
   int minIdle=Math.min(Math.max(2,maxPool/4),maxPool);
   int connTimeoutMs=parsePositive(firstNonBlank(System.getProperty("content.hikari.connection-timeout-ms"),System.getenv("CONTENT_HIKARI_CONNECTION_TIMEOUT_MS")),5000);
   // JDBC driver timeouts (ms). Hard-coded 5000 previously killed settle under load (~5.4s Communications link failure).
   int jdbcConnectMs=parsePositive(firstNonBlank(System.getProperty("content.hikari.jdbc-connect-timeout-ms"),System.getenv("CONTENT_HIKARI_JDBC_CONNECT_TIMEOUT_MS")),5000);
   int jdbcSocketMs=parsePositive(firstNonBlank(System.getProperty("content.hikari.jdbc-socket-timeout-ms"),System.getenv("CONTENT_HIKARI_JDBC_SOCKET_TIMEOUT_MS")),5000);
   pool.setMaximumPoolSize(maxPool);pool.setMinimumIdle(minIdle);pool.setConnectionTimeout(connTimeoutMs);pool.setValidationTimeout(1000);pool.setInitializationFailTimeout(10000);
   pool.addDataSourceProperty("connectTimeout",String.valueOf(jdbcConnectMs));
   pool.addDataSourceProperty("socketTimeout",String.valueOf(jdbcSocketMs));
  }return bean;}
 };}
 @Bean org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer feedStatementBounds(){
  int timeoutSec=parsePositive(firstNonBlank(System.getProperty("content.mybatis.default-statement-timeout-seconds"),System.getenv("CONTENT_MYBATIS_STATEMENT_TIMEOUT_SECONDS")),2);
  return configuration->configuration.setDefaultStatementTimeout(timeoutSec);
 }
 private static String firstNonBlank(String a,String b){if(a!=null&&!a.isBlank())return a;if(b!=null&&!b.isBlank())return b;return null;}
 private static int parsePositive(String raw,int fallback){if(raw==null||raw.isBlank())return fallback;try{return Math.max(1,Integer.parseInt(raw.trim()));}catch(NumberFormatException e){return fallback;}}
 @Bean(destroyMethod="shutdown") DefaultClientResources feedRedisResources(){
  int io=parsePositive(firstNonBlank(System.getProperty("content.redis.io-threads"),System.getenv("CONTENT_REDIS_IO_THREADS")),2);
  int cpu=parsePositive(firstNonBlank(System.getProperty("content.redis.computation-threads"),System.getenv("CONTENT_REDIS_COMPUTATION_THREADS")),2);
  return DefaultClientResources.builder().ioThreadPoolSize(io).computationThreadPoolSize(cpu).build();
 }
 @Bean(destroyMethod="shutdown") RedisClient feedRedisClient(DefaultClientResources r,@Value("${content.feed.redis-uri:redis://127.0.0.1:26379}")String uri){var u=RedisURI.create(uri);u.setTimeout(Duration.ofSeconds(5));var client=RedisClient.create(r,u);client.setOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(5)).build()).build());return client;}
 @Bean(destroyMethod="close") FeedRedis feedRedis(RedisClient client){return new FeedRedis(client);}
 // Keep AbortPolicy: FeedMerger submits nested futures on this pool; CallerRuns can deadlock workers.
 @Bean(destroyMethod="shutdownNow") ExecutorService feedExecutor(){return new ThreadPoolExecutor(16,16,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.AbortPolicy());}
 @Bean TransactionTemplate feedTransactions(PlatformTransactionManager manager,@Value("${content.feed.tx-timeout-seconds:15}")int txTimeoutSeconds){var t=new TransactionTemplate(manager);t.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);t.setTimeout(Math.max(5,txTimeoutSeconds));return t;}
 @Bean FeedTokens cursorTokens(@Value("${content.feed.cursor-key}")String key){return new FeedTokens(key,"CURSOR");}
 @Bean @org.springframework.beans.factory.annotation.Qualifier("identityTokens") FeedTokens identityTokens(@Value("${content.feed.identity-key}")String key){return new FeedTokens(key,"IDENTITY");}
 @Bean @ConditionalOnMissingBean(PipelineObserver.class) PipelineObserver pipelineObserver(){return new PipelineObserver(){};}
 @Bean NotificationService notificationService(NotificationMapper notifications,InteractionMapper interactions,VisibilityService visibility,TransactionTemplate tx){return new NotificationService(notifications,interactions,visibility,tx);}
 @Bean DistributionInbox distributionInbox(InboxMapper inbox,FeedMapper db,TransactionTemplate tx,ObjectMapper json,PipelineObserver observer,NotificationService notifications,org.springframework.beans.factory.ObjectProvider<AssistantService> assistant){
  return new DistributionInbox(inbox,db,tx,json,observer,notifications,()->{var a=assistant.getIfAvailable();if(a!=null)a.tick();});
 }
 /** Hardening A4: bounded BCrypt pool for login/register; threads default max(1, cores/2), queue default 4 x threads. */
 @Bean(destroyMethod="close") PasswordHasher passwordHasher(@Value("${content.auth.bcrypt-threads:0}")int threads,@Value("${content.auth.bcrypt-queue:-1}")int queue,
   @Value("${content.auth.bcrypt-wait-millis:5000}")long waitMillis){
  int t=threads>0?threads:PasswordHasher.defaultThreads();
  return new PasswordHasher(t,queue>=0?queue:4*t,waitMillis);
 }
 @Bean AuthService authService(UserMapper users,TransactionTemplate tx,@org.springframework.beans.factory.annotation.Qualifier("identityTokens") FeedTokens identity,@Value("${content.auth.token-ttl-ms:900000}")long ttl,PasswordHasher passwords){return new AuthService(users,tx,identity,ttl,passwords);}
 @Bean PostAuthoringService postAuthoringService(PostAuthoringMapper posts,UserMapper users,TransactionTemplate tx){return new PostAuthoringService(posts,users,tx);}
 @Bean DeliveryStore deliveryStore(FeedMapper db,DeliveryMapper tasks,TransactionTemplate tx,@Value("${content.pipeline.max-attempts:5}")int max,@Value("${content.pipeline.lease-millis:30000}")long lease){return new DeliveryStore(db,tasks,tx,max,lease);}
 @Bean DistributionWorker distributionWorker(DeliveryStore store,DeliveryMapper tasks,FeedMapper db,FeedRedis redis,ObjectMapper json,PipelineObserver observer){return new DistributionWorker(store,tasks,db,redis,json,observer);}
 @Bean(name="deliveryRunner") Runnable deliveryRunner(DistributionWorker worker){return worker::tick;}
 @Bean VisibilityService visibilityService(FeedMapper db){return new AuthoritativeVisibility(db);}
 @Bean PostDetailService postDetailService(VisibilityService visibility,FeedRedis redis,ObjectMapper json,PipelineObserver observer){return new CachedPostDetails(visibility,redis,json,observer);}
 @Bean RelationshipService relationships(FeedMapper db,TransactionTemplate tx,ObjectMapper json,@Value("${content.feed.history-limit:20}")int n,@Value("${content.feed.window-millis:2592000000}")long window,PostDetailService details){return new RelationshipService(db,tx,json,n,window,details);}
 @Bean FeedIdentityFilter feedIdentityFilter(@Value("${content.feed.identity-key}")String key,@Value("${content.feed.cursor-key}")String cursorKey){if(key.equals(cursorKey))throw new IllegalArgumentException("Identity and cursor keys must differ");return new FeedIdentityFilter(key);}
 @Bean FeedMerger.Budget feedBudget(@Value("${content.feed.batch-size:32}")int batch,@Value("${content.feed.max-scanned:256}")int scans,@Value("${content.feed.max-reads:64}")int reads){return new FeedMerger.Budget(batch,scans,reads,256,Duration.ofSeconds(2));}
 @Bean FollowingFeed followingFeed(FeedMapper db,ExecutorService executor,@org.springframework.beans.factory.annotation.Qualifier("cursorTokens") FeedTokens tokens,@Value("${content.feed.window-millis:2592000000}")long window,FeedRedis redis,FeedMerger.Budget budget,PipelineObserver observer,VisibilityService visibility){return new FollowingFeed(db,executor,tokens,window,redis,budget,observer,visibility);}
 @Bean FeedModes feedModes(FeedMapper db,ModeMapper modes,TransactionTemplate tx,RelationshipService events,@Value("${content.feed.window-millis:2592000000}")long window){return new FeedModes(db,modes,tx,events,window);}
 @Bean FeedRecovery feedRecovery(RecoveryMapper recovery,FeedMapper feed,TransactionTemplate tx,FeedRedis redis,PipelineObserver observer,@Value("${content.feed.window-millis:2592000000}")long window){return new FeedRecovery(recovery,feed,tx,redis,observer,window);}
 @Bean FeedTrimmer feedTrimmer(FeedMapper db,FeedRedis redis,@Value("${content.feed.window-millis:2592000000}")long window){return new FeedTrimmer(db,redis,window);}
 @Bean TrendGuardService trendGuardService(TrendGuardMapper risks,TransactionTemplate tx){return new TrendGuardService(risks,tx);}
 @Bean TrendingService trendingService(InteractionMapper interactions,TransactionTemplate tx,FeedRedis redis,VisibilityService visibility,TrendGuardService risks,@Value("${content.trendguard.spike-threshold:3}")int spikeThreshold){return new TrendingService(interactions,tx,redis,visibility,risks,spikeThreshold);}
 @Bean TopicRetention topicRetention(InteractionMapper interactions,TransactionTemplate tx,
   @Value("${content.trending.retention-millis:"+TopicRetention.DEFAULT_RETENTION_MS+"}")long retention,@Value("${content.trending.retention-batch:500}")int batch){
  return new TopicRetention(interactions,tx,retention,batch);
 }
 /** Periodic purge on its own daemon thread (no @EnableScheduling dependency). Disable with content.trending.retention-enabled=false. */
 @Bean(destroyMethod="close") @ConditionalOnProperty(name="content.trending.retention-enabled",havingValue="true",matchIfMissing=true)
 TopicRetentionJob topicRetentionJob(TopicRetention retention,@Value("${content.trending.retention-interval-millis:600000}")long interval){
  return new TopicRetentionJob(retention,interval);
 }
 /** Own daemon thread so the purge never competes with request or pipeline executors; not an ExecutorService bean. */
 public static final class TopicRetentionJob implements AutoCloseable {
  private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"topic-retention");t.setDaemon(true);return t;});
  TopicRetentionJob(TopicRetention retention,long interval){
   long every=Math.max(60_000L,interval);
   scheduler.scheduleWithFixedDelay(()->{
    try{var r=retention.purge(System.currentTimeMillis());
     if(r.buckets()+r.ledger()>0)org.slf4j.LoggerFactory.getLogger(TopicRetention.class).info("Topic retention purged buckets={} ledger={}",r.buckets(),r.ledger());}
    catch(Exception e){org.slf4j.LoggerFactory.getLogger(TopicRetention.class).warn("Topic retention purge failed; will retry",e);}
   },every,every,TimeUnit.MILLISECONDS);
  }
  @Override public void close(){scheduler.shutdownNow();}
 }
 @Bean InteractionService interactionService(VisibilityService visibility,InteractionMapper interactions,TransactionTemplate tx,TrendingService trending,RelationshipService relationships,org.springframework.beans.factory.ObjectProvider<MaterialService> materials,org.springframework.beans.factory.ObjectProvider<AssistantService> assistant){
  return new InteractionService(visibility,interactions,tx,trending,relationships,materials.getIfAvailable(),enq->{
   var a=assistant.getIfAvailable();if(a==null)return null;
   return a.enqueueFromComment(enq.postId(),enq.commentId(),enq.actorUserId(),enq.parentCommentId(),enq.body());
  });
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 ObjectStore objectStore(@Value("${content.media.s3-endpoint}")String endpoint,@Value("${content.media.access-key:content}")String access,@Value("${content.media.secret-key:}")String secret,
   org.springframework.core.env.Environment env){
  boolean dev=env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"));
  return new HttpS3ObjectStore(endpoint,access,MediaSecret.resolve(secret,dev));
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 MediaService mediaService(FeedMapper posts,MediaMapper media,TransactionTemplate tx,ObjectStore store,VisibilityService visibility,@Value("${content.media.bucket:content-media}")String bucket,@Value("${content.media.ffmpeg-image:}")String ffmpeg,PipelineObserver observer){return new MediaService(posts,media,tx,store,visibility,bucket,ffmpeg,observer);}
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 MaterialGrants materialGrants(@Value("${content.feed.identity-key}")String key){return new MaterialGrants(key);}
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 FeedbackWatchService feedbackWatchService(AssistantMapper assistant,InteractionMapper comments,MaterialMapper materials,FeedMapper posts,NotificationMapper notifications,VisibilityService visibility,TransactionTemplate tx){
  return new FeedbackWatchService(assistant,comments,materials,posts,notifications,visibility,tx);
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 EligibilityRegistry eligibilityRegistry(InteractionMapper interactions,GiveawayMapper giveaways,FeedMapper posts,ObjectMapper json,org.springframework.beans.factory.ObjectProvider<SaleMapper> sales){
  return new EligibilityRegistry(interactions,giveaways,posts,json,sales.getIfAvailable());
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 GiveawayService giveawayService(GiveawayMapper giveaways,InteractionMapper comments,FeedMapper posts,MaterialMapper materials,NotificationMapper notifications,UserMapper users,EligibilityRegistry eligibility,VisibilityService visibility,TransactionTemplate tx,ObjectMapper json,org.springframework.beans.factory.ObjectProvider<SaleService> sales){
  var g=new GiveawayService(giveaways,comments,posts,materials,notifications,users,eligibility,visibility,tx,json);
  g.setSaleBridgeProvider(sales::getIfAvailable);
  return g;
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 AssistantParser assistantParser(@Value("${content.assistant.python-url:}")String url,@Value("${content.assistant.internal-token:}")String token,ObjectMapper json){
  if(url==null || url.isBlank())return new StubAssistantParser();
  return new HttpAssistantParser(url,token,json);
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 AssistantService assistantService(AssistantMapper assistant,InteractionMapper comments,FeedMapper posts,UserMapper users,MaterialMapper materials,MaterialService materialService,VisibilityService visibility,TransactionTemplate tx,RelationshipService events,AssistantParser parser,FeedbackWatchService feedback,GiveawayService giveaways,ObjectMapper json){
  return new AssistantService(assistant,comments,posts,users,materials,materialService,visibility,tx,events,parser,feedback,giveaways,json);
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 MaterialService materialService(FeedMapper posts,PostAuthoringMapper authoring,UserMapper users,MaterialMapper materials,TransactionTemplate tx,ObjectStore store,VisibilityService visibility,@Value("${content.media.bucket:content-media}")String bucket,@Value("${content.media.ffmpeg-image:}")String ffmpeg,MaterialGrants grants,PipelineObserver observer,org.springframework.beans.factory.ObjectProvider<FeedbackWatchService> feedback,org.springframework.beans.factory.ObjectProvider<GiveawayService> giveaways,PasswordHasher passwordHasher){
  return new MaterialService(posts,authoring,users,materials,tx,store,visibility,bucket,ffmpeg,grants,observer,passwordHasher,
   id->{var f=feedback.getIfAvailable();if(f!=null)f.onVersionReady(id);},
   q->{var g=giveaways.getIfAvailable();return g!=null && g.hasEntitlement(q[0],q[1],q[2]);});
 }
 @Bean(name="mediaRunner") @ConditionalOnProperty(name="content.media.enabled",havingValue="true") Runnable mediaRunner(MediaService media,MaterialService materials,org.springframework.beans.factory.ObjectProvider<AssistantService> assistant,org.springframework.beans.factory.ObjectProvider<GiveawayService> giveaways){
  return ()->{media.tick();materials.tick();var a=assistant.getIfAvailable();if(a!=null)a.tick();var g=giveaways.getIfAvailable();if(g!=null)g.tick();};
 }
 @Bean PipelineReconcile pipelineReconcile(org.springframework.jdbc.core.JdbcTemplate jdbc,DistributionInbox inbox,InboxMapper inboxMapper,TransactionTemplate tx,ObjectMapper json,PipelineObserver observer){return new PipelineReconcile(jdbc,inbox,inboxMapper,tx,json,observer);}
}
