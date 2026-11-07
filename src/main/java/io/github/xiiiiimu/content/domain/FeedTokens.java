package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class FeedTokens {
 private final byte[] key;private final String purpose;private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
 public FeedTokens(String key,String purpose){this.key=key.getBytes(StandardCharsets.UTF_8);if(this.key.length<32)throw new IllegalArgumentException("Signing key must have at least 32 bytes");this.purpose=purpose;}
 private byte[] mac(String body)throws Exception{var h=Mac.getInstance("HmacSHA256");h.init(new SecretKeySpec(key,"HmacSHA256"));return h.doFinal((purpose+"\n"+body).getBytes(StandardCharsets.US_ASCII));}
 public String sign(Object data){try{String b=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(data));return b+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(b));}catch(Exception e){throw new IllegalStateException(e);}}
 public <T>T verify(String token,Class<T> type){
  try{if(token==null || token.length()>4096)throw new IllegalArgumentException();var parts=token.split("\\.",-1);if(parts.length!=2 || !parts[0].matches("[A-Za-z0-9_-]+") || !parts[1].matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
   var sig=Base64.getUrlDecoder().decode(parts[1]);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(sig).equals(parts[1]) || !MessageDigest.isEqual(sig,mac(parts[0])))throw new IllegalArgumentException();
   return json.readValue(Base64.getUrlDecoder().decode(parts[0]),type);
  }catch(Exception e){throw new FeedFailure(400,"INVALID_CURSOR");}
 }
 public record Identity(int version,long reader,long issuedAt,long expiresAt) {}
}
