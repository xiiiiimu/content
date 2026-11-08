package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.ObjectStore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Minimal path-style S3 put/get for S3Mock / MinIO-compatible endpoints. */
public final class HttpS3ObjectStore implements ObjectStore {
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 private final String endpoint;private final String access;private final String secret;
 public HttpS3ObjectStore(String endpoint,String access,String secret){this.endpoint=endpoint.endsWith("/")?endpoint.substring(0,endpoint.length()-1):endpoint;this.access=access;this.secret=secret;}
 private URI uri(String bucket,String key){return URI.create(endpoint+"/"+bucket+"/"+key);}
 @Override public void put(String bucket,String key,byte[] body,String contentType){
  try{
   ensureBucket(bucket);
   var req=HttpRequest.newBuilder(uri(bucket,key)).timeout(Duration.ofSeconds(10)).header("Content-Type",contentType==null?"application/octet-stream":contentType).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
   var res=http.send(req,HttpResponse.BodyHandlers.ofByteArray());if(res.statusCode()>=300)throw new IllegalStateException("S3 put failed status="+res.statusCode());
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 @Override public byte[] get(String bucket,String key){
  try{var res=http.send(HttpRequest.newBuilder(uri(bucket,key)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());if(res.statusCode()==404)throw new IllegalStateException("missing");if(res.statusCode()>=300)throw new IllegalStateException("S3 get failed");return res.body();}catch(Exception e){throw new IllegalStateException(e);}
 }
 @Override public boolean exists(String bucket,String key){try{var res=http.send(HttpRequest.newBuilder(uri(bucket,key)).timeout(Duration.ofSeconds(5)).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());return res.statusCode()>=200 && res.statusCode()<300;}catch(Exception e){return false;}}
 @Override public String signGet(String bucket,String key,long ttlMillis){
  try{long exp=System.currentTimeMillis()+ttlMillis;String body=bucket+"\n"+key+"\n"+exp;var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
   String sig=Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
   return endpoint+"/"+bucket+"/"+key+"?exp="+exp+"&sig="+sig+"&ak="+access;
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 private void ensureBucket(String bucket)throws Exception{
  var res=http.send(HttpRequest.newBuilder(URI.create(endpoint+"/"+bucket)).timeout(Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
  if(res.statusCode()>=300 && res.statusCode()!=409 && res.statusCode()!=500)throw new IllegalStateException("bucket create failed "+res.statusCode());
 }
}
