package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.*;
import java.time.Duration;

@Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class FeedConfiguration {
 @Bean(destroyMethod="shutdown") DefaultClientResources feedRedisResources(){return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();}
 @Bean(destroyMethod="shutdown") RedisClient feedRedisClient(DefaultClientResources r,@Value("${content.feed.redis-uri:redis://127.0.0.1:26379}")String uri){var u=RedisURI.create(uri);u.setTimeout(Duration.ofSeconds(2));return RedisClient.create(r,u);}
 @Bean(destroyMethod="close") StatefulRedisConnection<String,String> feedRedis(RedisClient client){return client.connect();}
 @Bean(destroyMethod="shutdownNow") ExecutorService feedExecutor(){return new ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());}
 @Bean TransactionTemplate feedTransactions(PlatformTransactionManager manager){var t=new TransactionTemplate(manager);t.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);t.setTimeout(5);return t;}
 @Bean FeedTokens cursorTokens(@Value("${content.feed.cursor-key}")String key){return new FeedTokens(key,"CURSOR");}
 @Bean RelationshipService relationships(FeedMapper db,TransactionTemplate tx,ObjectMapper json,@Value("${content.feed.history-limit:20}")int n,@Value("${content.feed.window-millis:2592000000}")long window){return new RelationshipService(db,tx,json,n,window);}
 @Bean FollowingFeed followingFeed(FeedMapper db,ExecutorService executor,FeedTokens tokens,@Value("${content.feed.window-millis:2592000000}")long window,StatefulRedisConnection<String,String> redis){return new FollowingFeed(db,executor,tokens,window,redis);}
}
