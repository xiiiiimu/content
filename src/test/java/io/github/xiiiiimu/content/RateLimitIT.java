package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.AuthService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Hardening A2: per-user / per-IP / per-username token buckets with the default limits, enforced in Redis.
 * Over the limit → 429, {"code":"RATE_LIMITED"}, Retry-After in whole seconds (rounded up).
 * Users and tokens come from AuthService directly so only the requests under test touch the HTTP limits.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired MockMvc http;@Autowired AuthService auth;
 static final AtomicInteger SEQ=new AtomicInteger();
 static final AtomicInteger IP=new AtomicInteger(1);

 String token(){return auth.register("rl_u"+SEQ.incrementAndGet(),"password-x1").token();}
 static String freshIp(){int n=IP.incrementAndGet();return "10.9."+(n/250)+"."+(n%250+1);}
 MvcResult send(MockHttpServletRequestBuilder req,String ip)throws Exception{
  return http.perform(req.with(r->{r.setRemoteAddr(ip);return r;})).andReturn();
 }
 static int status(MvcResult r){return r.getResponse().getStatus();}
 static void assertLimited(MvcResult r,long minRetry,long maxRetry)throws Exception{
  assertThat(status(r)).isEqualTo(429);
  assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
  String ra=r.getResponse().getHeader("Retry-After");
  assertThat(ra).as("Retry-After").isNotNull();
  assertThat(Long.parseLong(ra)).isBetween(minRetry,maxRetry);
 }

 /** like/unlike/dislike/undislike share one per-user bucket: 10/s, burst 20. */
 @Test void interactionsShareAPerUserBucketOfBurst20At10PerSecond()throws Exception{
  String t=token();String ip=freshIp();
  long start=System.nanoTime();int allowed=0;MvcResult limited=null;
  for(int i=0;i<40 && limited==null;i++){
   var req=switch(i%4){
    case 0->put("/api/posts/"+(900_000+i)+"/likes");
    case 1->delete("/api/posts/"+(900_000+i)+"/likes");
    case 2->put("/api/posts/"+(900_000+i)+"/dislikes");
    default->delete("/api/posts/"+(900_000+i)+"/dislikes");
   };
   var r=send(req.header("Authorization","Bearer "+t),ip);
   if(status(r)==429)limited=r;else allowed++;
  }
  double elapsed=(System.nanoTime()-start)/1e9;
  assertThat(limited).as("a 429 within 40 rapid interactions").isNotNull();
  assertThat(allowed).isBetween(20,20+(int)Math.ceil(elapsed*10)+1);
  assertLimited(limited,1,1);
  // another user is unaffected
  assertThat(status(send(put("/api/posts/900001/likes").header("Authorization","Bearer "+token()),ip))).isNotEqualTo(429);
  // recovery: one token refills in 100 ms
  Thread.sleep(250);
  assertThat(status(send(put("/api/posts/900002/likes").header("Authorization","Bearer "+t),ip))).isNotEqualTo(429);
 }

 /** comment: 1/s, burst 5 per user. */
 @Test void commentsAre1PerSecondWithBurst5()throws Exception{
  String t=token();String ip=freshIp();
  long start=System.nanoTime();int allowed=0;MvcResult limited=null;
  for(int i=0;i<12 && limited==null;i++){
   var r=send(post("/api/posts/900100/comments").header("Authorization","Bearer "+t).header("Idempotency-Key","rl-c-"+i)
    .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"),ip);
   if(status(r)==429)limited=r;else allowed++;
  }
  double elapsed=(System.nanoTime()-start)/1e9;
  assertThat(limited).isNotNull();
  assertThat(allowed).isBetween(5,5+(int)Math.ceil(elapsed)+1);
  assertLimited(limited,1,1);
 }

 /** report: 5 per minute per user; the 6th waits ~12 s for one token. */
 @Test void reportsAre5PerMinutePerUser()throws Exception{
  String t=token();String ip=freshIp();
  for(int i=0;i<5;i++)assertThat(status(send(post("/api/posts/"+(900_200+i)+"/reports").header("Authorization","Bearer "+t)
   .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"spam\"}"),ip))).isNotEqualTo(429);
  assertLimited(send(post("/api/posts/900299/reports").header("Authorization","Bearer "+t)
   .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"spam\"}"),ip),11,12);
 }

 /** login: 5 per minute per username (any outcome counts), independent of IP. */
 @Test void loginIs5PerMinutePerUsername()throws Exception{
  String name="rl_login_"+SEQ.incrementAndGet();auth.register(name,"password-ok1");
  for(int i=0;i<5;i++){
   int s=status(send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+name+"\",\"password\":\"wrong-pass1\"}"),freshIp()));
   assertThat(s).isNotEqualTo(429);
  }
  // the right password from yet another IP is still refused: the username bucket is empty
  assertLimited(send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+name+"\",\"password\":\"password-ok1\"}"),freshIp()),11,12);
  // a different username is unaffected
  String other="rl_login_"+SEQ.incrementAndGet();auth.register(other,"password-ok1");
  assertThat(status(send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+other+"\",\"password\":\"password-ok1\"}"),freshIp()))).isEqualTo(200);
 }

 /** login: 20 per minute per IP across usernames. */
 @Test void loginIs20PerMinutePerIp()throws Exception{
  String ip=freshIp();
  for(int i=0;i<20;i++){
   int s=status(send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nobody_"+SEQ.incrementAndGet()+"\",\"password\":\"x-password1\"}"),ip));
   assertThat(s).isNotEqualTo(429);
  }
  assertLimited(send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nobody_"+SEQ.incrementAndGet()+"\",\"password\":\"x-password1\"}"),ip),1,3);
  assertThat(status(send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nobody_"+SEQ.incrementAndGet()+"\",\"password\":\"x-password1\"}"),freshIp()))).isNotEqualTo(429);
 }

 /** register: 10 per hour per IP; the 11th waits ~6 minutes. */
 @Test void registerIs10PerHourPerIp()throws Exception{
  String ip=freshIp();
  for(int i=0;i<10;i++){
   var r=send(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"rl_reg_"+SEQ.incrementAndGet()+"\",\"password\":\"password-r1\"}"),ip);
   assertThat(status(r)).isEqualTo(200);
  }
  assertLimited(send(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"rl_reg_"+SEQ.incrementAndGet()+"\",\"password\":\"password-r1\"}"),ip),359,360);
 }

 /** The limit lives in Redis (shared by instances): a bucket key exists for the user after a like. */
 @Test void bucketStateIsKeptInRedis()throws Exception{
  String t=token();
  send(put("/api/posts/900400/likes").header("Authorization","Bearer "+t),freshIp());
  try(var c=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379)).connect()){
   assertThat(c.sync().keys("rl:v1:interact:*")).isNotEmpty();
  }
 }
}
