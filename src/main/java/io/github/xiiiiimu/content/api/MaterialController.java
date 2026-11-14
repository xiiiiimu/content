package io.github.xiiiiimu.content.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.util.*;

@RestController @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
public class MaterialController {
 private final MaterialService materials;private final InteractionService interactions;private final PublishService publish;private final ObjectMapper json;
 public MaterialController(MaterialService materials,InteractionService interactions,PublishService publish,ObjectMapper json){
  this.materials=materials;this.interactions=interactions;this.publish=publish;this.json=json;
 }
 private long user(Principal p){if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");try{long id=Long.parseLong(p.getName());if(id<=0)throw new FeedFailure(401,"UNAUTHENTICATED");return id;}catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}}

 @PostMapping(value="/api/materials",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 public MaterialService.Created create(@RequestPart("meta") String metaJson,@RequestPart("file") MultipartFile file,Principal p)throws Exception{
  Map<String,Object> meta=readMeta(metaJson);
  List<String> tags=tagsOf(meta.get("tags"));
  boolean dl=bool(meta.get("licenseDownload"),false);boolean com=bool(meta.get("licenseCommercial"),false);
  String aspect=str(meta.get("aspectRatio"));
  return materials.createDraft(user(p),str(meta.get("title")),str(meta.get("description")),tags,str(meta.get("shareMode")),str(meta.get("password")),dl,com,file.getBytes(),file.getContentType(),aspect);
}

 @PostMapping(value="/api/materials/{post}/versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 public MaterialService.Created uploadVersion(@PathVariable long post,@RequestPart("file") MultipartFile file,Principal p)throws Exception{
  return materials.uploadVersion(user(p),post,file.getBytes(),file.getContentType());
 }

 @GetMapping("/api/materials/discover")
 public MaterialService.DiscoverPage discover(@RequestParam(required=false)String q,@RequestParam(required=false)String tag,
                                             @RequestParam(required=false)Long beforeMs,@RequestParam(required=false)Long beforeId,
                                             @RequestParam(defaultValue="20")int limit,Principal p){
  return materials.discover(user(p),q,tag,beforeMs,beforeId,limit);
 }

 @GetMapping("/api/materials/{post}")
 public MaterialService.MaterialDetail detail(@PathVariable long post,Principal p){return materials.detail(user(p),post);}

 @PostMapping("/api/materials/{post}/unlock")
 public MaterialService.Unlock unlock(@PathVariable long post,@RequestBody Map<String,String> body,Principal p){
  return materials.unlock(user(p),post,body.get("password"));
 }

 @PatchMapping("/api/materials/{post}/share")
 public MaterialService.MaterialDetail share(@PathVariable long post,@RequestBody Map<String,Object> body,Principal p){
  Boolean revoke=body.get("revoke") instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(body.getOrDefault("revoke","false")));
  Boolean dl=body.containsKey("licenseDownload")?bool(body.get("licenseDownload"),false):null;
  Boolean com=body.containsKey("licenseCommercial")?bool(body.get("licenseCommercial"),false):null;
  return materials.updateShare(user(p),post,str(body.get("shareMode")),str(body.get("password")),Boolean.TRUE.equals(revoke),dl,com);
 }

 @PostMapping("/api/materials/{post}/preview/sign")
 public MaterialService.Signed preview(@PathVariable long post,@RequestBody Map<String,Object> body,Principal p){
  return materials.signPreview(user(p),post,longOrNull(body.get("versionId")),longOr(body.get("ttlMillis"),60_000L),str(body.get("grant")));
 }

 @PostMapping("/api/materials/{post}/original/sign")
 public MaterialService.Signed original(@PathVariable long post,@RequestBody Map<String,Object> body,Principal p){
  return materials.signOriginal(user(p),post,longOrNull(body.get("versionId")),longOr(body.get("ttlMillis"),60_000L),str(body.get("grant")));
 }

 @PostMapping("/api/materials/{post}/publish")
 public Publication publish(@PathVariable long post,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> body,Principal p){
  long expected=longOr(body.get("expectedVersion"),0L);
  return publish.publish(user(p),post,key,expected);
 }

 @PostMapping("/api/posts/{post}/comments/{comment}/accept")
 public InteractionService.CommentResult accept(@PathVariable long post,@PathVariable long comment,Principal p){
  return interactions.accept(user(p),post,comment);
 }

 @SuppressWarnings("unchecked")
 private Map<String,Object> readMeta(String metaJson)throws Exception{
  if(metaJson==null || metaJson.isBlank())throw new FeedFailure(400,"INVALID_REQUEST");
  return json.readValue(metaJson,Map.class);
 }
 @SuppressWarnings("unchecked")
 private static List<String> tagsOf(Object raw){
  if(raw==null)return List.of();
  if(raw instanceof List<?> list)return list.stream().map(String::valueOf).toList();
  if(raw instanceof String s && !s.isBlank())return Arrays.stream(s.split(",")).map(String::trim).toList();
  return List.of();
 }
 private static String str(Object o){return o==null?null:String.valueOf(o);}
 private static boolean bool(Object o,boolean d){if(o==null)return d;if(o instanceof Boolean b)return b;return Boolean.parseBoolean(String.valueOf(o));}
 private static Long longOrNull(Object o){if(o==null)return null;if(o instanceof Number n)return n.longValue();if(String.valueOf(o).isBlank())return null;return Long.parseLong(String.valueOf(o));}
 private static long longOr(Object o,long d){Long v=longOrNull(o);return v==null?d:v;}
}
