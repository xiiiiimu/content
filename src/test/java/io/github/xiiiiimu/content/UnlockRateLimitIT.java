package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Hardening A9: POST /api/materials/{post}/unlock (share password) is limited per user (10/min, burst 10) and per IP
 * (30/min, burst 30) with the A2 limiter, and backs off after repeated wrong passwords for one (user, post): after 5
 * consecutive failures the next attempt waits 1 s, then 2 s, 4 s ... (cap 15 min); a success clears the failures.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class UnlockRateLimitIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){DebugEndpointsSupport.commerceProps(r,MYSQL,REDIS,S3);}
 @Autowired MockMvc http;@Autowired AuthService auth;@Autowired MaterialService materials;@Autowired PublishService publish;@Autowired RelationshipService follows;
 static final AtomicInteger SEQ=new AtomicInteger();static final AtomicInteger IP=new AtomicInteger();
 static long author;static long postId;

 /** Once per class (lazily: containers must be up before the Spring context, so no PER_CLASS @BeforeAll). */
 @BeforeEach void material(){
  if(postId!=0)return;
  author=auth.register("unl_author","password-a1").userId();
  var created=materials.createDraft(author,"locked","d",List.of("x"),"PASSWORD_ORIGINAL","share-secret",true,false,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4");
  postId=created.postId();
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,postId,UUID.randomUUID().toString(),0);
 }
 String viewer(){var s=auth.register("unl_v"+SEQ.incrementAndGet(),"password-v1");follows.change(s.userId(),author,true);return s.token();}
 static String freshIp(){return "10.8.0."+(IP.incrementAndGet()%250+1);}
 MvcResult unlock(String token,String ip,String password)throws Exception{
  return http.perform(post("/api/materials/"+postId+"/unlock").header("Authorization","Bearer "+token).with(r->{r.setRemoteAddr(ip);return r;})
   .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\""+password+"\"}")).andReturn();
 }
 static int status(MvcResult r){return r.getResponse().getStatus();}
 static long retryAfter(MvcResult r){var h=r.getResponse().getHeader("Retry-After");return h==null?-1:Long.parseLong(h);}

 @Test void repeatedWrongPasswordsBackOffExponentiallyAndASuccessClearsThem()throws Exception{
  String t=viewer();String ip=freshIp();
  for(int i=0;i<5;i++)assertThat(status(unlock(t,ip,"wrong-"+i))).as("failure %d",i+1).isEqualTo(403);
  var held=unlock(t,ip,"share-secret");
  assertThat(status(held)).as("6th attempt right after 5 failures, even with the right password").isEqualTo(429);
  assertThat(retryAfter(held)).isEqualTo(1);
  Thread.sleep(1_100);
  assertThat(status(unlock(t,ip,"wrong-6"))).isEqualTo(403);
  var held2=unlock(t,ip,"share-secret");
  assertThat(status(held2)).isEqualTo(429);
  assertThat(retryAfter(held2)).as("doubled").isBetween(1L,2L);
  Thread.sleep(2_100);
  assertThat(status(unlock(t,ip,"share-secret"))).as("right password after the wait").isEqualTo(200);
  assertThat(status(unlock(t,ip,"wrong-again"))).as("success cleared the failures").isEqualTo(403);
 }

 @Test void attemptsAre10PerMinutePerUser()throws Exception{
  String t=viewer();String ip=freshIp();
  for(int i=0;i<10;i++)assertThat(status(unlock(t,ip,"share-secret"))).isEqualTo(200);
  var r=unlock(t,ip,"share-secret");
  assertThat(status(r)).isEqualTo(429);
  assertThat(r.getResponse().getContentAsString()).contains("RATE_LIMITED");
  assertThat(retryAfter(r)).isBetween(5L,6L);
 }

 @Test void attemptsAre30PerMinutePerIp()throws Exception{
  String ip=freshIp();
  // Users are created up front; each sends at most 10 so the per-user bucket never decides. The IP bucket refills at
  // 0.5/s while the loop runs, so the allowed count is 30 plus what refilled in the elapsed time.
  var users=new ArrayList<String>();for(int u=0;u<4;u++)users.add(viewer());
  long start=System.nanoTime();int allowed=0;MvcResult limited=null;
  outer:for(String t:users)for(int i=0;i<10;i++){
   var r=unlock(t,ip,"share-secret");
   if(status(r)==429){limited=r;break outer;}
   assertThat(status(r)).isEqualTo(200);allowed++;
  }
  double elapsedS=(System.nanoTime()-start)/1e9;
  assertThat(limited).as("IP limit reached within 40 attempts").isNotNull();
  assertThat(allowed).isBetween(30,30+(int)Math.ceil(elapsedS*0.5));
  assertThat(limited.getResponse().getContentAsString()).contains("RATE_LIMITED");
  assertThat(retryAfter(limited)).isBetween(1L,2L);
 }
}
