package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.FeedTokens;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Hardening A12: POST /api/materials/{post}/feedback/{comment}/resolve with a missing or non-numeric versionId (or a
 * non-numeric offsetMs) is a client error, 400 INVALID_REQUEST, not a 500. The identity check still comes first.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class FeedbackResolveValidationIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){DebugEndpointsSupport.commerceProps(r,MYSQL,REDIS,S3);}
 @Autowired MockMvc http;

 String bearer(long user){long now=System.currentTimeMillis();
  return "Bearer "+new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY").sign(new FeedTokens.Identity(1,user,now,now+300_000));}
 org.springframework.test.web.servlet.MvcResult resolve(String auth,String body)throws Exception{
  var req=post("/api/materials/1/feedback/1/resolve").contentType(MediaType.APPLICATION_JSON).content(body);
  if(auth!=null)req=req.header("Authorization",auth);
  return http.perform(req).andReturn();
 }

 @ParameterizedTest
 @ValueSource(strings={"{}","{\"versionId\":null}","{\"versionId\":\"abc\"}","{\"versionId\":true}","{\"versionId\":1,\"offsetMs\":\"x\"}"})
 void badVersionOrOffsetIs400(String body)throws Exception{
  var r=resolve(bearer(4101),body);
  assertThat(r.getResponse().getStatus()).as(body).isEqualTo(400);
  assertThat(r.getResponse().getContentAsString()).contains("INVALID_REQUEST");
 }

 @Test void identityIsCheckedBeforeTheBody()throws Exception{
  assertThat(resolve(null,"{}").getResponse().getStatus()).isEqualTo(401);
 }
}
