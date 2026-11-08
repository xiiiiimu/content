package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.ObjectStore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Backend S3 put/get. Client access is only via HMAC gateway URLs, never raw bucket URLs. */
public final class HttpS3ObjectStore implements ObjectStore {
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 private final String endpoint;private final String access;private final String secret;
 public HttpS3ObjectStore(String endpoint,String access,String secret){
  this.endpoint=endpoint.endsWith("/")?endpoint.substring(0,endpoint.length()-1):endpoint;this.access=access;this.secret=secret;
  if(secret==null || secret.isBlank())throw new IllegalArgumentException("media secret-key required");
 }
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
  long exp=System.currentTimeMillis()+ttlMillis;String sig=mac(bucket,key,exp);
  return "/api/media/objects?bucket="+enc(bucket)+"&key="+enc(key)+"&exp="+exp+"&sig="+enc(sig)+"&ak="+enc(access);
 }
 @Override public boolean verifyGet(String bucket,String key,long exp,String sig){
  if(bucket==null || key==null || sig==null || sig.isBlank())return false;
  if(exp<=0 || exp<System.currentTimeMillis())return false;
  return constantTimeEquals(sig,mac(bucket,key,exp));
 }
 private String mac(String bucket,String key,long exp){
  try{String body=bucket+"\n"+key+"\n"+exp;var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
   return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 private static String enc(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
 private static boolean constantTimeEquals(String a,String b){
  byte[] x=a.getBytes(StandardCharsets.UTF_8),y=b.getBytes(StandardCharsets.UTF_8);if(x.length!=y.length)return false;
  int r=0;for(int i=0;i<x.length;i++)r|=x[i]^y[i];return r==0;
 }
 private void ensureBucket(String bucket)throws Exception{
  var res=http.send(HttpRequest.newBuilder(URI.create(endpoint+"/"+bucket)).timeout(Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
  if(res.statusCode()>=300 && res.statusCode()!=409 && res.statusCode()!=500)throw new IllegalStateException("bucket create failed "+res.statusCode());
 }
}
