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
   // Sized for concurrent FollowingFeed merges (source+authority futures) without starving the 8-client harness.
   pool.setMaximumPoolSize(16);pool.setMinimumIdle(2);pool.setConnectionTimeout(5000);pool.setValidationTimeout(1000);pool.setInitializationFailTimeout(10000);
   pool.addDataSourceProperty("connectTimeout","5000");pool.addDataSourceProperty("socketTimeout","5000");
  }return bean;}
 };}
 @Bean org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer feedStatementBounds(){return configuration->configuration.setDefaultStatementTimeout(2);}
 @Bean(destroyMethod="shutdown") DefaultClientResources feedRedisResources(){return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();}
 @Bean(destroyMethod="shutdown") RedisClient feedRedisClient(DefaultClientResources r,@Value("${content.feed.redis-uri:redis://127.0.0.1:26379}")String uri){var u=RedisURI.create(uri);u.setTimeout(Duration.ofSeconds(5));var client=RedisClient.create(r,u);client.setOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(5)).build()).build());return client;}
 @Bean(destroyMethod="close") FeedRedis feedRedis(RedisClient client){return new FeedRedis(client);}
 // Keep AbortPolicy: FeedMerger submits nested futures on this pool; CallerRuns can deadlock workers.
 @Bean(destroyMethod="shutdownNow") ExecutorService feedExecutor(){return new ThreadPoolExecutor(16,16,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.AbortPolicy());}
 @Bean TransactionTemplate feedTransactions(PlatformTransactionManager manager){var t=new TransactionTemplate(manager);t.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);t.setTimeout(5);return t;}
 @Bean FeedTokens cursorTokens(@Value("${content.feed.cursor-key}")String key){return new FeedTokens(key,"CURSOR");}
 @Bean @org.springframework.beans.factory.annotation.Qualifier("identityTokens") FeedTokens identityTokens(@Value("${content.feed.identity-key}")String key){return new FeedTokens(key,"IDENTITY");}
 @Bean @ConditionalOnMissingBean(PipelineObserver.class) PipelineObserver pipelineObserver(){return new PipelineObserver(){};}
 @Bean NotificationService notificationService(NotificationMapper notifications,InteractionMapper interactions,VisibilityService visibility,TransactionTemplate tx){return new NotificationService(notifications,interactions,visibility,tx);}
 @Bean DistributionInbox distributionInbox(InboxMapper inbox,FeedMapper db,TransactionTemplate tx,ObjectMapper json,PipelineObserver observer,NotificationService notifications){return new DistributionInbox(inbox,db,tx,json,observer,notifications);}
 @Bean AuthService authService(UserMapper users,TransactionTemplate tx,@org.springframework.beans.factory.annotation.Qualifier("identityTokens") FeedTokens identity,@Value("${content.auth.token-ttl-ms:900000}")long ttl){return new AuthService(users,tx,identity,ttl);}
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
 @Bean InteractionService interactionService(VisibilityService visibility,InteractionMapper interactions,TransactionTemplate tx,TrendingService trending,RelationshipService relationships,org.springframework.beans.factory.ObjectProvider<MaterialService> materials,org.springframework.beans.factory.ObjectProvider<AssistantService> assistant){
  return new InteractionService(visibility,interactions,tx,trending,relationships,materials.getIfAvailable(),enq->{
   var a=assistant.getIfAvailable();if(a==null)return null;
   return a.enqueueFromComment(enq.postId(),enq.commentId(),enq.actorUserId(),enq.parentCommentId(),enq.body());
  });
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 ObjectStore objectStore(@Value("${content.media.s3-endpoint}")String endpoint,@Value("${content.media.access-key:content}")String access,@Value("${content.media.secret-key:content-secret-key-32-bytes-minimum}")String secret){return new HttpS3ObjectStore(endpoint,access,secret);}
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 MediaService mediaService(FeedMapper posts,MediaMapper media,TransactionTemplate tx,ObjectStore store,VisibilityService visibility,@Value("${content.media.bucket:content-media}")String bucket,@Value("${content.media.ffmpeg-image:}")String ffmpeg,PipelineObserver observer){return new MediaService(posts,media,tx,store,visibility,bucket,ffmpeg,observer);}
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 MaterialGrants materialGrants(@Value("${content.feed.identity-key}")String key){return new MaterialGrants(key);}
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 FeedbackWatchService feedbackWatchService(AssistantMapper assistant,InteractionMapper comments,MaterialMapper materials,FeedMapper posts,NotificationMapper notifications,VisibilityService visibility,TransactionTemplate tx){
  return new FeedbackWatchService(assistant,comments,materials,posts,notifications,visibility,tx);
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 EligibilityRegistry eligibilityRegistry(InteractionMapper interactions,GiveawayMapper giveaways,FeedMapper posts,ObjectMapper json){
  return new EligibilityRegistry(interactions,giveaways,posts,json);
 }
 @Bean @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
 GiveawayService giveawayService(GiveawayMapper giveaways,InteractionMapper comments,FeedMapper posts,MaterialMapper materials,NotificationMapper notifications,UserMapper users,EligibilityRegistry eligibility,VisibilityService visibility,TransactionTemplate tx,ObjectMapper json){
  return new GiveawayService(giveaways,comments,posts,materials,notifications,users,eligibility,visibility,tx,json);
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
 MaterialService materialService(FeedMapper posts,PostAuthoringMapper authoring,UserMapper users,MaterialMapper materials,TransactionTemplate tx,ObjectStore store,VisibilityService visibility,@Value("${content.media.bucket:content-media}")String bucket,@Value("${content.media.ffmpeg-image:}")String ffmpeg,MaterialGrants grants,PipelineObserver observer,org.springframework.beans.factory.ObjectProvider<FeedbackWatchService> feedback,org.springframework.beans.factory.ObjectProvider<GiveawayService> giveaways){
  return new MaterialService(posts,authoring,users,materials,tx,store,visibility,bucket,ffmpeg,grants,observer,
   id->{var f=feedback.getIfAvailable();if(f!=null)f.onVersionReady(id);},
   q->{var g=giveaways.getIfAvailable();return g!=null && g.hasEntitlement(q[0],q[1],q[2]);});
 }
 @Bean(name="mediaRunner") @ConditionalOnProperty(name="content.media.enabled",havingValue="true") Runnable mediaRunner(MediaService media,MaterialService materials,org.springframework.beans.factory.ObjectProvider<AssistantService> assistant,org.springframework.beans.factory.ObjectProvider<GiveawayService> giveaways){
  return ()->{media.tick();materials.tick();var a=assistant.getIfAvailable();if(a!=null)a.tick();var g=giveaways.getIfAvailable();if(g!=null)g.tick();};
 }
 @Bean PipelineReconcile pipelineReconcile(org.springframework.jdbc.core.JdbcTemplate jdbc,DistributionInbox inbox,InboxMapper inboxMapper,TransactionTemplate tx,ObjectMapper json,PipelineObserver observer){return new PipelineReconcile(jdbc,inbox,inboxMapper,tx,json,observer);}
}
