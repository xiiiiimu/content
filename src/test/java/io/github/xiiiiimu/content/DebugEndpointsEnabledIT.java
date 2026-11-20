package io.github.xiiiiimu.content;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;

/** Hardening A1: an explicit test configuration (fault-hooks + manual-settle) still registers every hook the scripts use. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DebugEndpointsEnabledIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  DebugEndpointsSupport.commerceProps(r,MYSQL,REDIS,S3);
  r.add("content.sale.fault-hooks",()->"true");
  r.add("content.sale.manual-settle",()->"true");
 }
 @LocalServerPort int port;

 @Test void explicitlyEnabledHooksAreReachable()throws Exception{
  assertThat(DebugEndpointsSupport.call(port,"GET","/api/sale-debug/inflight")).isEqualTo(200);
  assertThat(DebugEndpointsSupport.call(port,"POST","/api/sale-debug/reset-inflight-peaks")).isEqualTo(200);
  assertThat(DebugEndpointsSupport.call(port,"POST","/api/sale-debug/fail-after-commit-once")).isEqualTo(200);
  // arm then release so later work in this context is not held
  assertThat(DebugEndpointsSupport.call(port,"POST","/api/sale-debug/hold-settle")).isEqualTo(200);
  assertThat(DebugEndpointsSupport.callJson(port,"POST","/api/sale-debug/hold-settle","{\"arm\":false}")).isEqualTo(200);
  // manual settle requires an authenticated owner: registered, so not 404
  assertThat(DebugEndpointsSupport.call(port,"POST","/api/purchase-requests/r-1/settle")).isNotEqualTo(404);
  assertThat(DebugEndpointsSupport.call(port,"POST","/api/sale-debug/giveaway-award")).isNotEqualTo(404);
 }
}
