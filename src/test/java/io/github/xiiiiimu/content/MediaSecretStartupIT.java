package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.ObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Hardening A7: content.media.secret-key signs download links (HMAC) and has no public default any more.
 * Without the dev profile a missing key, or the old public value, stops startup; with the dev profile a random per-process
 * key is generated with a WARN, so a link signed with the old public value no longer verifies.
 */
@Testcontainers @ExtendWith(OutputCaptureExtension.class)
class MediaSecretStartupIT {
 static final String OLD_PUBLIC_DEFAULT="content-secret-key-32-bytes-minimum";
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);

 static Map<String,Object> props(String secret){
  var m=new LinkedHashMap<String,Object>();
  m.put("server.port","0");
  m.put("spring.datasource.url",MYSQL.getJdbcUrl());m.put("spring.datasource.username",MYSQL.getUsername());m.put("spring.datasource.password",MYSQL.getPassword());
  m.put("content.feed.enabled","true");m.put("content.feed.redis-uri","redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  m.put("content.feed.cursor-key","cursor-test-key-32-bytes-minimum-00001");m.put("content.feed.identity-key","identity-test-key-32-bytes-minimum-0001");
  m.put("content.media.enabled","true");m.put("content.media.s3-endpoint","http://"+S3.getHost()+":"+S3.getMappedPort(9090));
  m.put("content.media.bucket","content-media");m.put("content.media.ffmpeg-image","");m.put("content.assistant.python-url","");
  m.put("content.media.scan-delay-millis","3600000");m.put("content.sale.sync-settle","false");
  // null = not configured at all (application.yml / CONTENT_S3_SECRET_KEY decide, as in a real deployment).
  if(secret!=null)m.put("content.media.secret-key",secret);
  return m;
 }
 /** As command-line arguments: builder default properties rank below application.yml and would be overridden. */
 static ConfigurableApplicationContext start(String secret,String... profiles){
  var args=props(secret).entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new);
  return new SpringApplicationBuilder(ContentApplication.class).profiles(profiles).run(args);
 }
 static String hmac(String key,String bucket,String objectKey,long exp)throws Exception{
  var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
  return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((bucket+"\n"+objectKey+"\n"+exp).getBytes(StandardCharsets.UTF_8)));
 }

 @Test void missingSecretOutsideDevStopsStartup(){
  assertThat(System.getenv("CONTENT_S3_SECRET_KEY")).as("test JVM must not carry a media secret").isNull();
  assertThatThrownBy(()->start(null).close()).hasStackTraceContaining("content.media.secret-key");
 }

 @Test void theOldPublicDefaultCountsAsMissing(){
  assertThatThrownBy(()->start(OLD_PUBLIC_DEFAULT).close()).hasStackTraceContaining("content.media.secret-key");
 }

 @Test void devProfileGeneratesARandomKeyAndWarns(CapturedOutput output)throws Exception{
  long exp=System.currentTimeMillis()+600_000;
  String link;
  try(var ctx=start(null,"dev")){
   var store=ctx.getBean(ObjectStore.class);
   assertThat(store.verifyGet("content-media","k",exp,hmac(OLD_PUBLIC_DEFAULT,"content-media","k",exp))).as("old public key no longer verifies").isFalse();
   link=store.signGet("content-media","k",600_000);
   assertThat(output.getOut()+output.getErr()).contains("WARN").contains("content.media.secret-key is not set");
  }
  long linkExp=Long.parseLong(link.replaceAll(".*[?&]exp=([0-9]+).*","$1"));
  String linkSig=java.net.URLDecoder.decode(link.replaceAll(".*[?&]sig=([^&]+).*","$1"),StandardCharsets.UTF_8);
  try(var ctx=start(null,"dev")){
   assertThat(ctx.getBean(ObjectStore.class).verifyGet("content-media","k",linkExp,linkSig))
    .as("a link from one dev process does not verify in the next: a fresh random key per process").isFalse();
  }
 }

 @Test void anExplicitSecretIsUsedAsIs()throws Exception{
  try(var ctx=start("explicit-media-secret-0123456789")){
   long exp=System.currentTimeMillis()+600_000;
   assertThat(ctx.getBean(ObjectStore.class).verifyGet("content-media","k",exp,hmac("explicit-media-secret-0123456789","content-media","k",exp))).isTrue();
  }
 }
}
