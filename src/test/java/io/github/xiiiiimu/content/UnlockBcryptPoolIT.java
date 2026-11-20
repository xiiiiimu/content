package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Hardening A9: share-password checks run on the A4 bounded BCrypt pool, not on request threads. With one pool thread and
 * no queue, a burst of unlocks from different users and IPs (so the A9 per-user/per-IP limits never decide) is partly
 * refused with 429 RATE_LIMITED + Retry-After: 1; every other attempt succeeds; no 5xx.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class UnlockBcryptPoolIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  DebugEndpointsSupport.commerceProps(r,MYSQL,REDIS,S3);
  r.add("content.auth.bcrypt-threads",()->"1");r.add("content.auth.bcrypt-queue",()->"0");
 }
 @Autowired MockMvc http;@Autowired AuthService auth;@Autowired MaterialService materials;@Autowired PublishService publish;@Autowired RelationshipService follows;

 @Test void unlocksBeyondThePoolAreRefusedWith429()throws Exception{
  long author=auth.register("ubp_author","password-a1").userId();
  long postId=materials.createDraft(author,"locked","d",List.of("x"),"PASSWORD_ORIGINAL","share-secret",true,false,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4").postId();
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,postId,UUID.randomUUID().toString(),0);
  int n=16;var tokens=new ArrayList<String>();
  for(int i=0;i<n;i++){var s=auth.register("ubp_v"+i,"password-v1");follows.change(s.userId(),author,true);tokens.add(s.token());}

  var barrier=new CyclicBarrier(n);var pool=Executors.newFixedThreadPool(n);
  try{
   var fs=new ArrayList<Future<String>>();
   for(int i=0;i<n;i++){int k=i;fs.add(pool.submit(()->{
    barrier.await(10,TimeUnit.SECONDS);
    var r=http.perform(post("/api/materials/"+postId+"/unlock").header("Authorization","Bearer "+tokens.get(k))
      .with(q->{q.setRemoteAddr("10.9.0."+(k+1));return q;})
      .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"share-secret\"}")).andReturn();
    String ra=r.getResponse().getHeader("Retry-After");
    return r.getResponse().getStatus()+(ra==null?"":" retry="+ra)+" "+(r.getResponse().getStatus()==200?"":r.getResponse().getContentAsString());
   }));}
   var out=new ArrayList<String>();for(var f:fs)out.add(f.get(60,TimeUnit.SECONDS));
   long ok=out.stream().filter(s->s.startsWith("200")).count();
   var refused=out.stream().filter(s->s.startsWith("429")).toList();
   assertThat(refused).as(out.toString()).isNotEmpty().allMatch(s->s.equals("429 retry=1 {\"code\":\"RATE_LIMITED\"}"));
   assertThat(ok+refused.size()).as("only 200 or 429: "+out).isEqualTo(n);
   assertThat(ok).isGreaterThanOrEqualTo(1);
  }finally{pool.shutdownNow();}
 }
}
