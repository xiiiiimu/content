package io.github.xiiiiimu.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hardening A1: with commerce enabled (content.media.enabled=true) but neither content.sale.fault-hooks nor
 * content.sale.manual-settle set, no test hook is reachable: every hook route answers 404 to an anonymous caller.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DebugEndpointsDefaultIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  DebugEndpointsSupport.commerceProps(r,MYSQL,REDIS,S3);
 }
 @LocalServerPort int port;@Autowired ApplicationContext context;

 @ParameterizedTest(name="{0} {1}")
 @CsvSource({
  "POST,/api/sale-debug/hold-settle",
  "POST,/api/sale-debug/fail-after-commit-once",
  "GET,/api/sale-debug/inflight",
  "POST,/api/sale-debug/reset-inflight-peaks",
  "POST,/api/purchase-requests/r-1/settle",
  "POST,/api/sale-debug/giveaway-award",
  "POST,/api/sale-debug/giveaway-auto-refund",
 })
 void hookRouteIsNotRegisteredByDefault(String method,String path)throws Exception{
  assertThat(DebugEndpointsSupport.call(port,method,path)).as(method+" "+path).isEqualTo(404);
 }

 @Test void faultHooksCannotBeArmedFromOutside()throws Exception{
  DebugEndpointsSupport.call(port,"POST","/api/sale-debug/hold-settle");
  DebugEndpointsSupport.call(port,"POST","/api/sale-debug/fail-after-commit-once");
  var faults=context.getBean(io.github.xiiiiimu.content.domain.SaleService.class).faults();
  assertThat(faults.holdArmed()).isFalse();
 }

 /** Sanity: commerce itself is on, so a 404 above is the hook being absent, not the whole sale API. */
 @Test void saleApiIsRegistered()throws Exception{
  assertThat(context.getBeansOfType(io.github.xiiiiimu.content.api.SaleController.class)).hasSize(1);
 }
}

final class DebugEndpointsSupport {
 private DebugEndpointsSupport(){}
 static void commerceProps(DynamicPropertyRegistry r,MySQLContainer<?> mysql,GenericContainer<?> redis,GenericContainer<?> s3){
  r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);r.add("spring.datasource.password",mysql::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+redis.getHost()+":"+redis.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.media.enabled",()->"true");
  r.add("content.media.s3-endpoint",()->"http://"+s3.getHost()+":"+s3.getMappedPort(9090));
  r.add("content.media.bucket",()->"content-media");
  r.add("content.media.secret-key",()->"media-test-secret-key-not-for-production-01");
  r.add("content.media.ffmpeg-image",()->"");
  r.add("content.assistant.python-url",()->"");
  r.add("content.media.scan-delay-millis",()->"3600000");
  r.add("content.sale.sync-settle",()->"false");
 }
 static int call(int port,String method,String path)throws Exception{
  return callJson(port,method,path,"{\"arm\":true}");
 }
 static int callJson(int port,String method,String path,String json)throws Exception{
  var body="POST".equals(method)?HttpRequest.BodyPublishers.ofString(json):HttpRequest.BodyPublishers.noBody();
  var req=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json").method(method,body).build();
  return HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.discarding()).statusCode();
 }
}
