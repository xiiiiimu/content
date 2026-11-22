package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Hardening A4: BCrypt runs on a bounded pool (content.auth.bcrypt-threads + content.auth.bcrypt-queue). When pool and
 * queue are full a login is refused at once with 429 RATE_LIMITED + Retry-After instead of queueing on request threads.
 * Pool 1 + queue 1 here: of 16 simultaneous logins (distinct IPs and usernames, so A2 limits do not apply) some must be
 * refused with 429 and every other one must succeed; no 5xx.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AuthBcryptPoolIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.auth.bcrypt-threads",()->"1");r.add("content.auth.bcrypt-queue",()->"1");
 }
 @Autowired MockMvc http;@Autowired AuthService auth;

 @Test void loginsBeyondPoolAndQueueAreRefusedWith429NotQueued()throws Exception{
  int n=16;
  for(int i=0;i<n;i++)auth.register("pool_user_"+i,"password-p1");
  var barrier=new CyclicBarrier(n);var pool=Executors.newFixedThreadPool(n);
  try{
   var fs=new ArrayList<Future<String>>();
   for(int i=0;i<n;i++){int k=i;fs.add(pool.submit(()->{
    barrier.await(10,TimeUnit.SECONDS);
    var r=http.perform(post("/api/auth/login").with(q->{q.setRemoteAddr("10.7.0."+(k+1));return q;})
      .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"pool_user_"+k+"\",\"password\":\"password-p1\"}")).andReturn();
    String ra=r.getResponse().getHeader("Retry-After");
    return r.getResponse().getStatus()+(ra==null?"":" retry="+ra)+" "+(r.getResponse().getStatus()==200?"":r.getResponse().getContentAsString());
   }));}
   var out=new ArrayList<String>();for(var f:fs)out.add(f.get(60,TimeUnit.SECONDS));
   long ok=out.stream().filter(s->s.startsWith("200")).count();
   var refused=out.stream().filter(s->s.startsWith("429")).toList();
   assertThat(refused).as(out.toString()).isNotEmpty().allMatch(s->s.equals("429 retry=1 {\"code\":\"RATE_LIMITED\"}"));
   assertThat(ok+refused.size()).as("only 200 or 429: "+out).isEqualTo(n);
   assertThat(ok).as("pool + queue still serve logins").isGreaterThanOrEqualTo(2);
  }finally{pool.shutdownNow();}
  // once the burst is over, logins work again
  var r=http.perform(post("/api/auth/login").with(q->{q.setRemoteAddr("10.7.1.1");return q;})
   .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"pool_user_0\",\"password\":\"password-p1\"}")).andReturn();
  assertThat(r.getResponse().getStatus()).isEqualTo(200);
 }
}
