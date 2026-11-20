package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.FeedTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Hardening A8: GET /api/assistant/tasks/{id} needs an identity; only the task's requester (actor) or the author of the
 * post it belongs to can read it. Anyone else gets 404, the same as a task that does not exist.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantTaskAccessIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){DebugEndpointsSupport.commerceProps(r,MYSQL,REDIS,S3);}
 @Autowired MockMvc http;@Autowired JdbcTemplate db;

 String bearer(long user){long now=System.currentTimeMillis();
  return "Bearer "+new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY").sign(new FeedTokens.Identity(1,user,now,now+300_000));}
 int status(Long user,long task)throws Exception{
  var req=get("/api/assistant/tasks/"+task);
  if(user!=null)req=req.header("Authorization",bearer(user));
  return http.perform(req).andReturn().getResponse().getStatus();
 }

 @Test void onlyTheRequesterOrThePostAuthorCanReadATask()throws Exception{
  long author=1801,actor=2801,outsider=3801;
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,'PULL')",author);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(180101,?,'PUBLISHED','p')",author);
  db.update("INSERT INTO post_comments(id,post_id,user_id,body,idempotency_key,created_at_ms) VALUES(555,180101,?,'@助手 找','a8-k',0)",actor);
  db.update("INSERT INTO assistant_tasks(id,trigger_comment_id,post_id,actor_user_id,body,state,created_at_ms) VALUES(9101,555,180101,?,'@助手 找','DONE',0)",actor);

  assertThat(status(null,9101)).as("anonymous").isEqualTo(401);
  assertThat(status(outsider,9101)).as("someone else").isEqualTo(404);
  assertThat(status(outsider,9999)).as("missing task, same answer").isEqualTo(404);
  assertThat(status(actor,9101)).as("requester").isEqualTo(200);
  assertThat(status(author,9101)).as("post author").isEqualTo(200);
 }
}
