package io.github.xiiiiimu.content.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/** Short-lived share unlock grants. share_epoch bump invalidates outstanding grants immediately. */
public final class MaterialGrants {
 private final byte[] key;
 public MaterialGrants(String secret){
  if(secret==null || secret.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("grant key");
  this.key=secret.getBytes(StandardCharsets.UTF_8);
 }
 public String sign(long userId,long postId,long shareEpoch,String purpose,long expiresAtMs){
  String body=userId+"."+postId+"."+shareEpoch+"."+purpose+"."+expiresAtMs;
  return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8))+"."+mac(body);
 }
 public void verify(String grant,long userId,long postId,long shareEpoch,String purpose){
  if(grant==null || grant.isBlank())throw new FeedFailure(403,"GRANT_REQUIRED");
  int dot=grant.lastIndexOf('.');if(dot<=0)throw new FeedFailure(403,"GRANT_INVALID");
  String body;try{body=new String(Base64.getUrlDecoder().decode(grant.substring(0,dot)),StandardCharsets.UTF_8);}
  catch(Exception e){throw new FeedFailure(403,"GRANT_INVALID");}
  String sig=grant.substring(dot+1);
  if(!mac(body).equals(sig))throw new FeedFailure(403,"GRANT_INVALID");
  String[] p=body.split("\\.");
  if(p.length!=5)throw new FeedFailure(403,"GRANT_INVALID");
  try{
   if(Long.parseLong(p[0])!=userId || Long.parseLong(p[1])!=postId || Long.parseLong(p[2])!=shareEpoch)throw new FeedFailure(403,"GRANT_INVALID");
   if(!purpose.equals(p[3]))throw new FeedFailure(403,"GRANT_INVALID");
   if(Long.parseLong(p[4])<=System.currentTimeMillis())throw new FeedFailure(403,"GRANT_EXPIRED");
  }catch(FeedFailure f){throw f;}catch(Exception e){throw new FeedFailure(403,"GRANT_INVALID");}
 }
 private String mac(String body){
  try{
   Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
   return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }catch(Exception e){throw new IllegalStateException(e);}
 }
}
