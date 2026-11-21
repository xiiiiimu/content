package io.github.xiiiiimu.content;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Hardening A6: concurrent registrations of one username → exactly one 200, every other one 409 USERNAME_TAKEN, never 5xx. */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AuthRegisterRaceIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired MockMvc http;@Autowired JdbcTemplate db;

 List<String> race(String... usernames)throws Exception{
  int n=usernames.length;var barrier=new CyclicBarrier(n);var pool=Executors.newFixedThreadPool(n);
  try{
   var fs=new ArrayList<Future<String>>();
   for(int i=0;i<n;i++){int k=i;fs.add(pool.submit(()->{
    barrier.await(10,TimeUnit.SECONDS);
    var r=http.perform(post("/api/auth/register").with(q->{q.setRemoteAddr("10.6.0."+(k+1));return q;})
      .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+usernames[k]+"\",\"password\":\"password-r1\"}")).andReturn();
    return r.getResponse().getStatus()+" "+r.getResponse().getContentAsString();
   }));}
   var out=new ArrayList<String>();for(var f:fs)out.add(f.get(60,TimeUnit.SECONDS));return out;
  }finally{pool.shutdownNow();}
 }

 @Test void concurrentSameUsernameGivesOneSuccessAndConflictsOtherwise()throws Exception{
  var names=new String[8];Arrays.fill(names,"race_same_name");
  var out=race(names);
  assertThat(out.stream().filter(s->s.startsWith("200 ")).count()).as(out.toString()).isEqualTo(1);
  assertThat(out.stream().filter(s->!s.startsWith("200 ")).toList()).as(out.toString()).allMatch(s->s.equals("409 {\"code\":\"USERNAME_TAKEN\"}"));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE username='race_same_name'",Long.class)).isEqualTo(1);
 }

 /** The username key is case-insensitive (utf8mb4 default collation): a concurrent case variant is the same name. */
 @Test void concurrentCaseVariantsAreTheSameName()throws Exception{
  var out=race("Race_Case_Name","race_case_name","RACE_CASE_NAME","rAcE_cAsE_nAmE");
  assertThat(out.stream().filter(s->s.startsWith("200 ")).count()).as(out.toString()).isEqualTo(1);
  assertThat(out.stream().filter(s->!s.startsWith("200 ")).toList()).as(out.toString()).allMatch(s->s.equals("409 {\"code\":\"USERNAME_TAKEN\"}"));
 }
}
