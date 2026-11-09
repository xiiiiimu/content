package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.ObjectStore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * Backend S3 put/get with AWS SigV4 (path-style).
 * Client access remains HMAC gateway URLs only — never raw bucket URLs.
 * Works with real MinIO and permissive S3Mock.
 */
public final class HttpS3ObjectStore implements ObjectStore {
 private static final DateTimeFormatter AMZ_DATE=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
 private static final DateTimeFormatter DATE_STAMP=DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 private final String endpoint;private final String access;private final String secret;private final String region;
 public HttpS3ObjectStore(String endpoint,String access,String secret){this(endpoint,access,secret,"us-east-1");}
 public HttpS3ObjectStore(String endpoint,String access,String secret,String region){
  this.endpoint=endpoint.endsWith("/")?endpoint.substring(0,endpoint.length()-1):endpoint;this.access=access;this.secret=secret;
  this.region=(region==null||region.isBlank())?"us-east-1":region;
  if(access==null || access.isBlank())throw new IllegalArgumentException("media access-key required");
  if(secret==null || secret.isBlank())throw new IllegalArgumentException("media secret-key required");
 }
 private URI uri(String bucket,String key){return URI.create(endpoint+"/"+bucket+"/"+encodeKey(key));}
 private static String encodeKey(String key){
  String[] parts=key.split("/",-1);StringBuilder b=new StringBuilder();
  for(int i=0;i<parts.length;i++){if(i>0)b.append('/');b.append(URLEncoder.encode(parts[i],StandardCharsets.UTF_8).replace("+","%20"));}
  return b.toString();
 }
 @Override public void put(String bucket,String key,byte[] body,String contentType){
  try{
   ensureBucket(bucket);
   byte[] bytes=body==null?new byte[0]:body;
   String type=contentType==null?"application/octet-stream":contentType;
   var req=signed(uri(bucket,key),"PUT",bytes,type);
   var res=http.send(req,HttpResponse.BodyHandlers.ofByteArray());
   if(res.statusCode()>=300)throw new IllegalStateException("S3 put failed status="+res.statusCode()+" body="+new String(res.body(),StandardCharsets.UTF_8));
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 @Override public byte[] get(String bucket,String key){
  try{
   var res=http.send(signed(uri(bucket,key),"GET",new byte[0],null),HttpResponse.BodyHandlers.ofByteArray());
   if(res.statusCode()==404)throw new IllegalStateException("missing");
   if(res.statusCode()>=300)throw new IllegalStateException("S3 get failed status="+res.statusCode());
   return res.body();
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 @Override public boolean exists(String bucket,String key){
  try{
   var res=http.send(signed(uri(bucket,key),"HEAD",new byte[0],null),HttpResponse.BodyHandlers.discarding());
   return res.statusCode()>=200 && res.statusCode()<300;
  }catch(Exception e){return false;}
 }
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
  var res=http.send(signed(URI.create(endpoint+"/"+bucket),"PUT",new byte[0],null),HttpResponse.BodyHandlers.ofByteArray());
  int code=res.statusCode();
  if(code>=300 && code!=409 && code!=200){
   // MinIO returns 409 BucketAlreadyOwnedByYou; some stacks 200 on recreate.
   String body=new String(res.body(),StandardCharsets.UTF_8);
   if(code==500 && body.contains("BucketAlreadyOwnedByYou"))return;
   if(code!=409)throw new IllegalStateException("bucket create failed "+code+" "+body);
  }
 }
 private HttpRequest signed(URI uri,String method,byte[] payload,String contentType)throws Exception{
  ZonedDateTime now=ZonedDateTime.now(ZoneOffset.UTC);
  String amzDate=AMZ_DATE.format(now);String dateStamp=DATE_STAMP.format(now);
  String payloadHash=sha256Hex(payload);
  String host=uri.getHost()+(uri.getPort()>0?":"+uri.getPort():"");
  String canonicalUri=uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath();
  String canonicalQuery=uri.getRawQuery()==null?"":uri.getRawQuery();
  var headers=new TreeMap<String,String>();
  headers.put("host",host);
  headers.put("x-amz-content-sha256",payloadHash);
  headers.put("x-amz-date",amzDate);
  if(contentType!=null && !contentType.isBlank())headers.put("content-type",contentType);
  StringBuilder canonicalHeaders=new StringBuilder();StringBuilder signedHeaders=new StringBuilder();
  for(var e:headers.entrySet()){
   canonicalHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
   if(signedHeaders.length()>0)signedHeaders.append(';');
   signedHeaders.append(e.getKey());
  }
  String canonicalRequest=method+"\n"+canonicalUri+"\n"+canonicalQuery+"\n"+canonicalHeaders+"\n"+signedHeaders+"\n"+payloadHash;
  String credentialScope=dateStamp+"/"+region+"/s3/aws4_request";
  String stringToSign="AWS4-HMAC-SHA256\n"+amzDate+"\n"+credentialScope+"\n"+sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
  byte[] signingKey=hmac(hmac(hmac(hmac(("AWS4"+secret).getBytes(StandardCharsets.UTF_8),dateStamp.getBytes(StandardCharsets.UTF_8)),region.getBytes(StandardCharsets.UTF_8)),"s3".getBytes(StandardCharsets.UTF_8)),"aws4_request".getBytes(StandardCharsets.UTF_8));
  String signature=HexFormat.of().formatHex(hmac(signingKey,stringToSign.getBytes(StandardCharsets.UTF_8)));
  String authorization="AWS4-HMAC-SHA256 Credential="+access+"/"+credentialScope+", SignedHeaders="+signedHeaders+", Signature="+signature;
  var b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Authorization",authorization).header("x-amz-content-sha256",payloadHash).header("x-amz-date",amzDate);
  if(contentType!=null && !contentType.isBlank())b.header("Content-Type",contentType);
  return switch(method){
   case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
   case "GET" -> b.GET().build();
   case "HEAD" -> b.method("HEAD",HttpRequest.BodyPublishers.noBody()).build();
   default -> throw new IllegalArgumentException("unsupported "+method);
  };
 }
 private static String sha256Hex(byte[] data)throws Exception{
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
 }
 private static byte[] hmac(byte[] key,byte[] data)throws Exception{
  Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(data);
 }
}
