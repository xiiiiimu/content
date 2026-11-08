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
   pool.setMaximumPoolSize(8);pool.setMinimumIdle(1);pool.setConnectionTimeout(5000);pool.setValidationTimeout(1000);pool.setInitializationFailTimeout(10000);
   pool.addDataSourceProperty("connectTimeout","5000");pool.addDataSourceProperty("socketTimeout","5000");
  }return bean;}
 };}
 @Bean org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer feedStatementBounds(){return configuration->configuration.setDefaultStatementTimeout(2);}
 @Bean(destroyMethod="shutdown") DefaultClientResources feedRedisResources(){return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();}
 @Bean(destroyMethod="shutdown") RedisClient feedRedisClient(DefaultClientResources r,@Value("${content.feed.redis-uri:redis://127.0.0.1:26379}")String uri){var u=RedisURI.create(uri);u.setTimeout(Duration.ofSeconds(5));var client=RedisClient.create(r,u);client.setOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(5)).build()).build());return client;}
 @Bean(destroyMethod="close") FeedRedis feedRedis(RedisClient client){return new FeedRedis(client);}
 @Bean(destroyMethod="shutdownNow") ExecutorService feedExecutor(){return new ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());}
 @Bean TransactionTemplate feedTransactions(PlatformTransactionManager manager){var t=new TransactionTemplate(manager);t.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);t.setTimeout(5);return t;}
 @Bean FeedTokens cursorTokens(@Value("${content.feed.cursor-key}")String key){return new FeedTokens(key,"CURSOR");}
 @Bean @ConditionalOnMissingBean(PipelineObserver.class) PipelineObserver pipelineObserver(){return new PipelineObserver(){};}
 @Bean DistributionInbox distributionInbox(InboxMapper inbox,FeedMapper db,TransactionTemplate tx,ObjectMapper json,PipelineObserver observer){return new DistributionInbox(inbox,db,tx,json,observer);}
 @Bean DeliveryStore deliveryStore(FeedMapper db,DeliveryMapper tasks,TransactionTemplate tx,@Value("${content.pipeline.max-attempts:5}")int max,@Value("${content.pipeline.lease-millis:30000}")long lease){return new DeliveryStore(db,tasks,tx,max,lease);}
 @Bean DistributionWorker distributionWorker(DeliveryStore store,DeliveryMapper tasks,FeedMapper db,FeedRedis redis,ObjectMapper json,PipelineObserver observer){return new DistributionWorker(store,tasks,db,redis,json,observer);}
 @Bean(name="deliveryRunner") Runnable deliveryRunner(DistributionWorker worker){return worker::tick;}
 @Bean RelationshipService relationships(FeedMapper db,TransactionTemplate tx,ObjectMapper json,@Value("${content.feed.history-limit:20}")int n,@Value("${content.feed.window-millis:2592000000}")long window){return new RelationshipService(db,tx,json,n,window);}
 @Bean FeedIdentityFilter feedIdentityFilter(@Value("${content.feed.identity-key}")String key,@Value("${content.feed.cursor-key}")String cursorKey){if(key.equals(cursorKey))throw new IllegalArgumentException("Identity and cursor keys must differ");return new FeedIdentityFilter(key);}
 @Bean FeedMerger.Budget feedBudget(@Value("${content.feed.batch-size:32}")int batch,@Value("${content.feed.max-scanned:256}")int scans,@Value("${content.feed.max-reads:64}")int reads){return new FeedMerger.Budget(batch,scans,reads,256,Duration.ofSeconds(2));}
 @Bean FollowingFeed followingFeed(FeedMapper db,ExecutorService executor,FeedTokens tokens,@Value("${content.feed.window-millis:2592000000}")long window,FeedRedis redis,FeedMerger.Budget budget,PipelineObserver observer){return new FollowingFeed(db,executor,tokens,window,redis,budget,observer);}
 @Bean FeedModes feedModes(FeedMapper db,ModeMapper modes,TransactionTemplate tx,RelationshipService events,@Value("${content.feed.window-millis:2592000000}")long window){return new FeedModes(db,modes,tx,events,window);}
 @Bean FeedRecovery feedRecovery(RecoveryMapper recovery,FeedMapper feed,TransactionTemplate tx,FeedRedis redis,PipelineObserver observer,@Value("${content.feed.window-millis:2592000000}")long window){return new FeedRecovery(recovery,feed,tx,redis,observer,window);}
 @Bean FeedTrimmer feedTrimmer(FeedMapper db,FeedRedis redis,@Value("${content.feed.window-millis:2592000000}")long window){return new FeedTrimmer(db,redis,window);}
}
