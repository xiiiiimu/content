package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.AuthService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.ServerSocket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Hardening A2, Redis failure: the limiter's Redis is unreachable (closed port) while the rest of the app works.
 * Decisions fall back to a per-instance in-memory token bucket with the same limits: neither all-allow nor all-deny.
 * Each fallback decision is counted in content.ratelimit.fallback and a WARN is logged.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers @ExtendWith(OutputCaptureExtension.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitRedisDownIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 static int closedPort(){try(var s=new ServerSocket(0)){return s.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  int dead=closedPort();
  r.add("content.ratelimit.redis-uri",()->"redis://127.0.0.1:"+dead);
 }
 @Autowired MockMvc http;@Autowired AuthService auth;@Autowired MeterRegistry meters;

 @Test void unreachableRedisFallsBackToLocalBucketsWithTheSameLimits(CapturedOutput output)throws Exception{
  String t=auth.register("rl_down_u1","password-x1").token();
  int allowed=0;int limited=0;String retryAfter=null;
  for(int i=0;i<7;i++){
   var r=http.perform(post("/api/posts/"+(910_000+i)+"/reports").header("Authorization","Bearer "+t)
    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"spam\"}")).andReturn();
   if(r.getResponse().getStatus()==429){limited++;retryAfter=r.getResponse().getHeader("Retry-After");}else allowed++;
  }
  assertThat(allowed).as("not all-deny").isEqualTo(5);
  assertThat(limited).as("not all-allow").isEqualTo(2);
  assertThat(retryAfter).isNotNull();
  assertThat(Long.parseLong(retryAfter)).isBetween(11L,12L);
  var fallback=meters.find("content.ratelimit.fallback").counter();
  assertThat(fallback).as("fallback counter").isNotNull();
  assertThat(fallback.count()).isGreaterThanOrEqualTo(7);
  assertThat(output.getOut()+output.getErr()).contains("WARN").contains("Rate limit Redis unavailable");
 }
}
