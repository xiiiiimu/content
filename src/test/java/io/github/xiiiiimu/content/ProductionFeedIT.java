package io.github.xiiiiimu.content;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class ProductionFeedIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired MockMvc http; @Autowired JdbcTemplate db;
 @Test void followPublishAndReadThroughProductionHttpAndAuthority() throws Exception {
  assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()) {assertThat(c.sync().ping()).isEqualTo("PONG");}
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(101,'PUSH')");
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(10101,101,'DRAFT','production chain')");
  http.perform(put("/api/follows/101").principal(()->"201")).andExpect(status().isOk());
  http.perform(post("/api/posts/10101/publish").principal(()->"101").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expectedVersion\":0}")).andExpect(status().isOk());
  http.perform(get("/api/feed/following?limit=20").principal(()->"201")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].postId").value(10101)).andExpect(jsonPath("$.pageState").value("EXHAUSTED"));
  http.perform(delete("/api/follows/101").principal(()->"201")).andExpect(status().isOk());
  http.perform(get("/api/feed/following").principal(()->"201")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
 }
}
