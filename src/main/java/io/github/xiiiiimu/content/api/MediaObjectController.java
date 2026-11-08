package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** HMAC gateway for private media. Raw object-store endpoints are never client-facing. */
@RestController @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
public class MediaObjectController {
 private final ObjectStore store;
 public MediaObjectController(ObjectStore store){this.store=store;}
 @GetMapping("/api/media/objects")
 public ResponseEntity<byte[]> get(@RequestParam String bucket,@RequestParam String key,@RequestParam long exp,@RequestParam String sig){
  if(!store.verifyGet(bucket,key,exp,sig))throw new FeedFailure(403,"MEDIA_SIGNATURE_INVALID");
  byte[] body=store.get(bucket,key);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
 }
}
