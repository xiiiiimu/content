package io.github.xiiiiimu.content;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hardening A1 follow-up: every route registered with feed + commerce enabled (default hook configuration) is called
 * without credentials. A route that does not answer 401 is anonymous-reachable and must be on {@link #ANONYMOUS}, the
 * reviewed list of routes meant to be public or authorized by something other than the identity token.
 * The full table is written to target/anonymous-access/routes.tsv.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AnonymousAccessIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){DebugEndpointsSupport.commerceProps(r,MYSQL,REDIS,S3);}

 /** Reviewed anonymous routes: "METHOD pattern" → why it may answer without an identity token. */
 static final Map<String,String> ANONYMOUS=Map.of(
  "POST /api/auth/register","account creation (rate limited in A2)",
  "POST /api/auth/login","credential check (rate limited in A2)",
  "GET /api/media/objects","authorized by HMAC signature + expiry, not identity"
 );
 /**
  * Anonymous-reachable routes found by this probe and reported in docs/hardening-plan.md (A1 inventory), not yet fixed.
  * Kept separate from ANONYMOUS so the list cannot silently grow: a new entry here needs a reviewed decision.
  */
 static final Map<String,String> KNOWN_FINDINGS=Map.of(
  "GET /api/assistant/tasks/{id}","task state/intent/reply comment of any task id, no identity or owner check"
 );
 /** Bodies that pass request parsing, so the probe reaches the handler's identity check instead of a 400/415/500. */
 static final Map<String,String> BODIES=Map.of(
  "PATCH /api/posts/{id}","{\"expectedVersion\":0,\"body\":\"x\"}",
  "POST /api/materials/{post}/feedback/{comment}/resolve","{\"versionId\":1}",
  "POST /api/posts/{postId}/publish","{\"expectedVersion\":0}"
 );
 static final Set<String> MULTIPART=Set.of("POST /api/materials","POST /api/materials/{post}/versions");

 @LocalServerPort int port;
 @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

 @Test void everyRouteOutsideTheReviewedListRequiresAnIdentity()throws Exception{
  var rows=new ArrayList<String>();var unexpected=new ArrayList<String>();
  var client=HttpClient.newHttpClient();
  for(var e:mappings.getHandlerMethods().entrySet()){
   var info=e.getKey();
   for(String pattern:info.getPatternValues())for(var m:info.getMethodsCondition().getMethods()){
    String key=m+" "+pattern;
    String path=pattern.replaceAll("\\{[^}]+}","1");
    String query="GET /api/media/objects".equals(key)?"?bucket=b&key=k&exp=1&sig=x":"";
    var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path+query)).header("Idempotency-Key","anon-probe");
    if(MULTIPART.contains(key)){
     String boundary="anonprobe";
     String mp="--"+boundary+"\r\nContent-Disposition: form-data; name=\"meta\"\r\n\r\n{\"title\":\"t\"}\r\n"
      +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.mp4\"\r\nContent-Type: video/mp4\r\n\r\nx\r\n--"+boundary+"--\r\n";
     b.header("Content-Type","multipart/form-data; boundary="+boundary).method(m.name(),HttpRequest.BodyPublishers.ofString(mp));
    }else{
     b.header("Content-Type","application/json").method(m.name(),"GET".equals(m.name())||"DELETE".equals(m.name())
      ?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(BODIES.getOrDefault(key,"{}")));
    }
    var req=b.build();
    var res=client.send(req,HttpResponse.BodyHandlers.ofString());
    String body=res.body()==null?"":res.body().replaceAll("\\s+"," ");
    if(body.length()>120)body=body.substring(0,120);
    rows.add(key+"\t"+res.statusCode()+"\t"+e.getValue().getBeanType().getSimpleName()+"#"+e.getValue().getMethod().getName()+"\t"+body);
    boolean api=pattern.startsWith("/api/");
    if(api && res.statusCode()!=401 && !ANONYMOUS.containsKey(key) && !KNOWN_FINDINGS.containsKey(key))unexpected.add(key+" -> "+res.statusCode()+" "+body);
   }
  }
  for(String a:List.of("/actuator","/actuator/health","/actuator/env","/actuator/metrics","/actuator/beans","/actuator/configprops","/actuator/heapdump")){
   var res=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+a)).GET().build(),HttpResponse.BodyHandlers.ofString());
   String body=res.body()==null?"":res.body().replaceAll("\s+"," ");if(body.length()>120)body=body.substring(0,120);
   rows.add("GET "+a+"	"+res.statusCode()+"	actuator	"+body);
   if(!"/actuator/health".equals(a) && !"/actuator".equals(a))assertThat(res.statusCode()).as("actuator "+a+" must not be exposed").isEqualTo(404);
  }
  Collections.sort(rows);
  var out=Path.of("target","anonymous-access");Files.createDirectories(out);
  Files.write(out.resolve("routes.tsv"),rows);
  assertThat(unexpected).as("routes answering an anonymous caller with something other than 401").isEmpty();
 }
}
