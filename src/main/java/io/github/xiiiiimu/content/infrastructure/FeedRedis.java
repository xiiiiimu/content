package io.github.xiiiiimu.content.infrastructure;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
/** Connecting is lazy: a Redis outage must not prevent the authoritative SQL API from starting. */
public final class FeedRedis implements AutoCloseable {
 private final RedisClient client;private StatefulRedisConnection<String,String> connection;
 public FeedRedis(RedisClient client){this.client=client;}
 public synchronized StatefulRedisConnection<String,String> connection(){if(connection==null || !connection.isOpen()){connection=client.connect();connection.setTimeout(java.time.Duration.ofSeconds(2));}return connection;}
 @Override public synchronized void close(){if(connection!=null){connection.close();connection=null;}}
}
