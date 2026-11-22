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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Hardening A13: a login for a username that does not exist still runs one BCrypt check (against a fixed dummy hash, on
 * the A4 pool), so it costs the same as a wrong password for an existing user and the response time does not reveal
 * whether the account exists. Both answer 401 INVALID_CREDENTIALS with the same body.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LoginUnknownUserBcryptIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired AuthService auth;@Autowired PasswordHasher hasher;@Autowired MockMvc http;

 @Test void unknownUserAndWrongPasswordBothRunOneBcryptCheck(){
  auth.register("a13_known","password-k1");
  long before=hasher.checks();
  assertThatThrownBy(()->auth.login("a13_known","wrong-pass1")).isInstanceOf(FeedFailure.class).hasMessage("INVALID_CREDENTIALS");
  assertThat(hasher.checks()-before).as("wrong password for an existing user").isEqualTo(1);
  before=hasher.checks();
  assertThatThrownBy(()->auth.login("a13_nobody","wrong-pass1")).isInstanceOf(FeedFailure.class).hasMessage("INVALID_CREDENTIALS");
  assertThat(hasher.checks()-before).as("unknown user still runs one BCrypt check").isEqualTo(1);
 }

 @Test void bothAnswerTheSame401()throws Exception{
  auth.register("a13_http","password-k1");
  var known=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr("10.13.0.1");return r;}).contentType(MediaType.APPLICATION_JSON)
   .content("{\"username\":\"a13_http\",\"password\":\"wrong-pass1\"}")).andReturn().getResponse();
  var unknown=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr("10.13.0.2");return r;}).contentType(MediaType.APPLICATION_JSON)
   .content("{\"username\":\"a13_nobody2\",\"password\":\"wrong-pass1\"}")).andReturn().getResponse();
  assertThat(known.getStatus()).isEqualTo(401);assertThat(unknown.getStatus()).isEqualTo(401);
  assertThat(unknown.getContentAsString()).isEqualTo(known.getContentAsString());
 }
}
