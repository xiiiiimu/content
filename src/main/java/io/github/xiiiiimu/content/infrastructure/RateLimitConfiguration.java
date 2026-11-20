package io.github.xiiiiimu.content.infrastructure;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.time.Duration;
import java.util.List;

/**
 * Hardening A2 limits. Every value is configurable under {@code content.ratelimit.rules.<rule>.{limit,period,burst}}
 * (period is a Spring Duration, e.g. 1s, 1m, 1h). Defaults:
 * <pre>
 * interact    per user      10 / 1s   burst 20   (like, unlike, dislike, undislike share one bucket)
 * comment     per user       1 / 1s   burst 5
 * report      per user       5 / 1m   burst 5
 * login-ip    per IP        20 / 1m   burst 20
 * login-user  per username   5 / 1m   burst 5
 * register-ip per IP        10 / 1h   burst 10
 * unlock-user per user      10 / 1m   burst 10   (share-password unlock, A9)
 * unlock-ip   per IP        30 / 1m   burst 30
 * </pre>
 * Failure backoff {@code content.ratelimit.backoff.unlock.{threshold,base,max,window}} (A9): after 5 consecutive wrong
 * share passwords for one (user, post) the next attempt waits 1 s, 2 s, 4 s ... up to 15 min; forgotten 1 h after the
 * last failure or on success.
 * The limiter has its own Redis client ({@code content.ratelimit.redis-uri}, default the feed Redis) with a short
 * command/connect timeout, so a slow Redis costs a limiter decision at most {@code timeout-millis} before the
 * per-instance fallback takes over for {@code redis-cooldown-millis}.
 */
@Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class RateLimitConfiguration {
 static RateLimiter.Rule rule(Environment env,String name,long limit,Duration period,long burst){
  String p="content.ratelimit.rules."+name+".";
  return new RateLimiter.Rule(name,env.getProperty(p+"limit",Long.class,limit),
   env.getProperty(p+"period",Duration.class,period).toMillis(),env.getProperty(p+"burst",Long.class,burst));
 }
 static List<RateLimiter.Rule> rules(Environment env){
  return List.of(
   rule(env,"interact",10,Duration.ofSeconds(1),20),
   rule(env,"comment",1,Duration.ofSeconds(1),5),
   rule(env,"report",5,Duration.ofMinutes(1),5),
   rule(env,"login-ip",20,Duration.ofMinutes(1),20),
   rule(env,"login-user",5,Duration.ofMinutes(1),5),
   rule(env,"register-ip",10,Duration.ofHours(1),10),
   rule(env,"unlock-user",10,Duration.ofMinutes(1),10),
   rule(env,"unlock-ip",30,Duration.ofMinutes(1),30));
 }
 static List<RateLimiter.Backoff> backoffs(Environment env){
  String p="content.ratelimit.backoff.unlock.";
  return List.of(new RateLimiter.Backoff("unlock-fail",env.getProperty(p+"threshold",Integer.class,5),
   env.getProperty(p+"base",Duration.class,Duration.ofSeconds(1)).toMillis(),
   env.getProperty(p+"max",Duration.class,Duration.ofMinutes(15)).toMillis(),
   env.getProperty(p+"window",Duration.class,Duration.ofHours(1)).toMillis()));
 }
 @Bean(destroyMethod="close") RateLimiter rateLimiter(Environment env,DefaultClientResources resources,ObjectProvider<MeterRegistry> meters,
   @Value("${content.ratelimit.redis-uri:${content.feed.redis-uri:redis://127.0.0.1:26379}}")String uri,
   @Value("${content.ratelimit.timeout-millis:200}")long timeoutMillis,
   @Value("${content.ratelimit.redis-cooldown-millis:5000}")long cooldownMillis){
  if(timeoutMillis<10)throw new IllegalArgumentException("content.ratelimit.timeout-millis must be >= 10");
  var u=RedisURI.create(uri);u.setTimeout(Duration.ofMillis(timeoutMillis));
  var client=RedisClient.create(resources,u);
  client.setOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(Math.max(timeoutMillis,100))).build()).build());
  return new RateLimiter(client,rules(env),backoffs(env),cooldownMillis,meters.getIfAvailable());
 }
}
