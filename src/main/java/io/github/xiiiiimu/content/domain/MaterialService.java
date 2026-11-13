package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Material posts: versions, discovery metadata, share modes; reuses ObjectStore + FFmpeg. */
public final class MaterialService {
 public record Created(long postId,long versionId,int revision,String state) {}
 public record VersionView(long versionId,int revision,String state,Long durationMs,boolean isDefault) {}
 public record MaterialDetail(long postId,String title,String description,String shareMode,boolean licenseDownload,boolean licenseCommercial,
                              Long defaultVersionId,List<String> tags,List<VersionView> versions) {}
 public record DiscoverItem(long postId,long authorId,long publishedAtMs,String title,String description,List<String> tags,
                            String shareMode,boolean coverAvailable,String coverUrl,Long coverExpiresAtMs) {}
 public record DiscoverPage(List<DiscoverItem> items,Long nextBeforeMs,Long nextBeforeId) {}
 public record Signed(String url,long expiresAtMs,String purpose) {}
 public record Unlock(String grant,long expiresAtMs,long shareEpoch) {}

 private final FeedMapper posts;private final PostAuthoringMapper authoring;private final UserMapper users;private final MaterialMapper db;private final TransactionTemplate tx;
 private final ObjectStore store;private final VisibilityService visibility;private final String bucket;private final String ffmpegImage;
 private final MaterialGrants grants;private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
 private final PipelineObserver observer;private final java.util.function.Consumer<Long> onVersionReady;
 /** Optional giveaway/entitlement gate: args are viewer, postId, versionId. */
 private final java.util.function.Predicate<long[]> entitlementAccess;

 public MaterialService(FeedMapper posts,PostAuthoringMapper authoring,UserMapper users,MaterialMapper db,TransactionTemplate tx,ObjectStore store,
                        VisibilityService visibility,String bucket,String ffmpegImage,MaterialGrants grants,PipelineObserver observer){
  this(posts,authoring,users,db,tx,store,visibility,bucket,ffmpegImage,grants,observer,id->{},q->false);
 }
 public MaterialService(FeedMapper posts,PostAuthoringMapper authoring,UserMapper users,MaterialMapper db,TransactionTemplate tx,ObjectStore store,
                        VisibilityService visibility,String bucket,String ffmpegImage,MaterialGrants grants,PipelineObserver observer,
                        java.util.function.Consumer<Long> onVersionReady,java.util.function.Predicate<long[]> entitlementAccess){
  this.posts=posts;this.authoring=authoring;this.users=users;this.db=db;this.tx=tx;this.store=store;this.visibility=visibility;
  this.bucket=bucket;this.ffmpegImage=ffmpegImage;this.grants=grants;this.observer=observer;
  this.onVersionReady=onVersionReady==null?id->{}:onVersionReady;
  this.entitlementAccess=entitlementAccess==null?q->false:entitlementAccess;
 }

 public Created createDraft(long author,String title,String description,List<String> tags,String shareMode,String password,
                            boolean licenseDownload,boolean licenseCommercial,byte[] bytes,String contentType){
  return createDraft(author,title,description,tags,shareMode,password,licenseDownload,licenseCommercial,bytes,contentType,"UNKNOWN");
 }
 public Created createDraft(long author,String title,String description,List<String> tags,String shareMode,String password,
                            boolean licenseDownload,boolean licenseCommercial,byte[] bytes,String contentType,String aspectRatio){
  validateMeta(title,description,tags,shareMode,password);
  if(bytes==null || bytes.length==0 || bytes.length>8_000_000)throw new FeedFailure(400,"INVALID_MEDIA");
  String mode=shareMode==null?"PUBLIC_PREVIEW":shareMode;
  String aspect=normalizeAspect(aspectRatio);
  String hash="PASSWORD_ORIGINAL".equals(mode)?passwords.encode(password):null;
  return tx.execute(status->{
   RelationshipService.requireWritable(posts.gate());
   users.ensureAuthor(author);
   if(authoring.insertDraft(author,bodyOf(title,description))!=1)throw new IllegalStateException("draft");
   long postId=authoring.lastId();
   if(db.markMaterial(postId,author)!=1)throw new IllegalStateException("kind");
   db.insertProfile(postId,title,description==null?"":description,mode,hash,licenseDownload,licenseCommercial,aspect,System.currentTimeMillis());
   replaceTags(postId,tags);
   return attachVersion(author,postId,bytes,contentType);
  });
 }

 public Created uploadVersion(long author,long postId,byte[] bytes,String contentType){
  if(bytes==null || bytes.length==0 || bytes.length>8_000_000)throw new FeedFailure(400,"INVALID_MEDIA");
  return tx.execute(status->{
   RelationshipService.requireWritable(posts.gate());
   var post=posts.post(postId);if(post==null)throw new FeedFailure(404,"POST_NOT_FOUND");
   if(post.authorId()!=author)throw new FeedFailure(403,"POST_FORBIDDEN");
   if(db.profile(postId)==null)throw new FeedFailure(409,"NOT_MATERIAL");
   if(!Set.of("DRAFT","PUBLISHED","PROCESSING").contains(post.status()))throw new FeedFailure(409,"POST_STATE_CONFLICT");
   return attachVersion(author,postId,bytes,contentType);
  });
 }

 private Created attachVersion(long author,long postId,byte[] bytes,String contentType){
  int revision=db.maxRevision(postId)+1;
  String raw="material/"+postId+"/v"+revision+"/raw";
  store.put(bucket,raw,bytes,contentType==null?"application/octet-stream":contentType);
  var row=new MaterialMapper.VersionInsert();row.post=postId;row.revision=revision;row.raw=raw;row.at=System.currentTimeMillis();
  if(db.insertVersion(row)!=1)throw new IllegalStateException("version");
  return new Created(postId,row.id,revision,"PENDING");
 }

 public void tick(){
  var claimed=tx.execute(status->{
   var task=db.claimable();if(task==null)return null;
   // Pin the post-claim token from the locked row; do not re-read lease_token outside this TX.
   long nextToken=Math.addExact(task.token(),1);
   int nextAttempt=Math.addExact(task.attempt(),1);
   if(db.claim(task.id())!=1)return null;
   return new MaterialMapper.Version(task.id(),task.postId(),task.revision(),"PROCESSING",task.rawObjectKey(),
    null,null,task.durationMs(),task.metaJson(),nextAttempt,nextToken,task.createdAtMs());
  });
  if(claimed==null)return;
  long token=claimed.token();
  try{
   observer.beforeMediaProcess(claimed.id());
   var post=posts.post(claimed.postId());
   if(post==null || "OFFLINE".equals(post.status())){tx.executeWithoutResult(s->db.fail(claimed.id(),token,1,"POST_GONE"));return;}
   byte[] raw=store.get(bucket,claimed.rawObjectKey());
   // Per-claim object keys so a late/stale worker cannot overwrite a newer lease's derived artifacts.
   String preview="material/"+claimed.postId()+"/v"+claimed.revision()+"/t"+token+"/preview.mp4";
   String cover="material/"+claimed.postId()+"/v"+claimed.revision()+"/t"+token+"/cover.jpg";
   Long duration=null;String meta;
   if(ffmpegImage!=null && !ffmpegImage.isBlank()){
    var result=new FfmpegTranscoder(ffmpegImage).transcode(raw);
    store.put(bucket,preview,result.video(),"video/mp4");
    store.put(bucket,cover,result.cover(),"image/jpeg");
    meta=result.metadataJson();
    duration=parseDurationMs(meta);
   }else{
    byte[] placeholder=("placeholder:"+raw.length).getBytes(StandardCharsets.UTF_8);
    store.put(bucket,preview,placeholder,"application/octet-stream");
    store.put(bucket,cover,placeholder,"application/octet-stream");
    meta="{\"format\":{\"duration\":\"8.0\"},\"streams\":[]}";
    duration=8000L;
   }
   Long finalDuration=duration;String finalMeta=meta;
   long readyId=claimed.id();
   tx.executeWithoutResult(s->{
    if(db.complete(claimed.id(),token,preview,cover,finalDuration,finalMeta)!=1)throw new FeedFailure(409,"MEDIA_FENCED");
    // Monotonic default: higher revision only; late READY of an older revision cannot regress.
    db.setDefaultIfNewer(claimed.postId(),claimed.id(),System.currentTimeMillis());
   });
   onVersionReady.accept(readyId);
  }catch(FeedFailure e){
   if("MEDIA_FENCED".equals(e.getMessage()))return; // lost lease/token; do not schedule retry as this claim
   String msg=e.getMessage()==null?"error":e.getMessage();
   tx.executeWithoutResult(s->db.fail(claimed.id(),token,Math.min(60,1<<Math.min(5,Math.max(0,claimed.attempt()))),msg.substring(0,Math.min(500,msg.length()))));
  }catch(Exception e){
   String msg=e.getMessage()==null?"error":e.getMessage();
   tx.executeWithoutResult(s->db.fail(claimed.id(),token,Math.min(60,1<<Math.min(5,Math.max(0,claimed.attempt()))),msg.substring(0,Math.min(500,msg.length()))));
  }
 }

 public MaterialDetail detail(long viewer,long postId){
  var post=posts.post(postId);if(post==null)throw new FeedFailure(404,"POST_NOT_FOUND");
  boolean author=post.authorId()==viewer;
  var view=visibility.read(viewer,postId);
  // Authors may inspect DRAFT/PROCESSING material versions before first publish.
  if(view.isEmpty() && !(author && Set.of("DRAFT","PROCESSING").contains(post.status())))
   throw new FeedFailure(404,"POST_NOT_VISIBLE");
  var profile=db.profile(postId);if(profile==null)throw new FeedFailure(404,"NOT_MATERIAL");
  if("AUTHOR_ONLY".equals(profile.shareMode()) && !author)throw new FeedFailure(404,"POST_NOT_VISIBLE");
  Long def=profile.defaultVersionId();
  List<VersionView> versions=db.versions(postId).stream()
   .filter(v->"READY".equals(v.state()) || author)
   .map(v->new VersionView(v.id(),v.revision(),v.state(),v.durationMs(),def!=null && def==v.id()))
   .toList();
  return new MaterialDetail(postId,profile.title(),profile.description(),profile.shareMode(),profile.licenseDownload(),profile.licenseCommercial(),
   def,db.tags(postId),versions);
 }

 public DiscoverPage discover(long viewer,String q,String tag,Long beforeMs,Long beforeId,int limit){
  int n=limit<=0?20:Math.min(limit,50);
  var rows=db.discover(blankToNull(q),blankToNull(tag),beforeMs,beforeId,n+5);
  List<DiscoverItem> items=new ArrayList<>();
  for(var row:rows){
   if(items.size()>=n)break;
   var view=visibility.read(viewer,row.postId());
   if(view.isEmpty())continue;
   var profile=db.profile(row.postId());
   if(profile==null)continue;
   boolean author=view.get().authorId()==viewer;
   if("AUTHOR_ONLY".equals(profile.shareMode()) && !author)continue;
   boolean cover=row.coverKey()!=null;
   String coverUrl=null;Long exp=null;
   if(cover && metadataPublic(profile,author)){
    long ttl=60_000L;coverUrl=store.signGet(bucket,row.coverKey(),ttl);exp=System.currentTimeMillis()+ttl;
   }
   items.add(new DiscoverItem(row.postId(),row.authorId(),row.publishedAtMs(),row.title(),row.description(),db.tags(row.postId()),
    row.shareMode(),cover && metadataPublic(profile,author),coverUrl,exp));
  }
  Long nextMs=null,nextId=null;
  if(items.size()==n){var last=items.get(items.size()-1);nextMs=last.publishedAtMs();nextId=last.postId();}
  return new DiscoverPage(items,nextMs,nextId);
 }

 public Unlock unlock(long viewer,long postId,String password){
  visibility.read(viewer,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  var profile=db.profile(postId);if(profile==null)throw new FeedFailure(404,"NOT_MATERIAL");
  if(!"PASSWORD_ORIGINAL".equals(profile.shareMode()))throw new FeedFailure(409,"UNLOCK_NOT_REQUIRED");
  if(profile.passwordHash()==null || password==null || !passwords.matches(password,profile.passwordHash()))
   throw new FeedFailure(403,"INVALID_SHARE_PASSWORD");
  long ttl=900_000L;long exp=System.currentTimeMillis()+ttl;
  return new Unlock(grants.sign(viewer,postId,profile.shareEpoch(),"ORIGINAL",exp),exp,profile.shareEpoch());
 }

 public MaterialDetail updateShare(long author,long postId,String shareMode,String password,boolean revoke,Boolean licenseDownload,Boolean licenseCommercial){
  return tx.execute(status->{
   var post=posts.post(postId);if(post==null)throw new FeedFailure(404,"POST_NOT_FOUND");
   if(post.authorId()!=author)throw new FeedFailure(403,"POST_FORBIDDEN");
   var profile=db.profile(postId);if(profile==null)throw new FeedFailure(404,"NOT_MATERIAL");
   String mode=shareMode==null?profile.shareMode():shareMode;
   if(!Set.of("AUTHOR_ONLY","PUBLIC_PREVIEW","PASSWORD_ORIGINAL").contains(mode))throw new FeedFailure(400,"INVALID_REQUEST");
   int bump=(revoke || (password!=null && !password.isBlank()) || !mode.equals(profile.shareMode()))?1:0;
   String hash=profile.passwordHash();
   if("PASSWORD_ORIGINAL".equals(mode)){
    if(password!=null && !password.isBlank())hash=passwords.encode(password);
    if(hash==null)throw new FeedFailure(400,"PASSWORD_REQUIRED");
   }else hash=null;
   boolean dl=licenseDownload==null?profile.licenseDownload():licenseDownload;
   boolean com=licenseCommercial==null?profile.licenseCommercial():licenseCommercial;
   db.updateProfile(postId,profile.title(),profile.description(),mode,hash,bump,dl,com,null,System.currentTimeMillis());
   return detail(author,postId);
  });
 }

 public Signed signPreview(long viewer,long postId,Long versionId,long ttlMillis,String grant){
  if(ttlMillis<=0 || ttlMillis>900_000L)throw new FeedFailure(400,"INVALID_REQUEST");
  var view=visibility.read(viewer,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  var profile=db.profile(postId);if(profile==null)throw new FeedFailure(404,"NOT_MATERIAL");
  boolean author=view.authorId()==viewer;
  if("AUTHOR_ONLY".equals(profile.shareMode()) && !author)throw new FeedFailure(403,"PREVIEW_FORBIDDEN");
  if(!"AUTHOR_ONLY".equals(profile.shareMode()) && !"PUBLIC_PREVIEW".equals(profile.shareMode()) && !"PASSWORD_ORIGINAL".equals(profile.shareMode()))
   throw new FeedFailure(403,"PREVIEW_FORBIDDEN");
  // PUBLIC_PREVIEW and PASSWORD_ORIGINAL allow preview without grant; AUTHOR_ONLY author-only above.
  var version=resolveVersion(postId,versionId,profile);
  if(!"READY".equals(version.state()) || version.previewObjectKey()==null)throw new FeedFailure(404,"MEDIA_NOT_READY");
  return new Signed(store.signGet(bucket,version.previewObjectKey(),ttlMillis),System.currentTimeMillis()+ttlMillis,"PREVIEW");
 }

 public Signed signOriginal(long viewer,long postId,Long versionId,long ttlMillis,String grant){
  if(ttlMillis<=0 || ttlMillis>900_000L)throw new FeedFailure(400,"INVALID_REQUEST");
  var view=visibility.read(viewer,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  var profile=db.profile(postId);if(profile==null)throw new FeedFailure(404,"NOT_MATERIAL");
  boolean author=view.authorId()==viewer;
  var version=resolveVersion(postId,versionId,profile);
  boolean entitled=!author && entitlementAccess.test(new long[]{viewer,postId,version.id()});
  if(!author && !entitled){
   if(!"PASSWORD_ORIGINAL".equals(profile.shareMode()))throw new FeedFailure(403,"ORIGINAL_FORBIDDEN");
   grants.verify(grant,viewer,postId,profile.shareEpoch(),"ORIGINAL");
  }
  if(!profile.licenseDownload() && !author && !entitled)throw new FeedFailure(403,"DOWNLOAD_NOT_LICENSED");
  if(!"READY".equals(version.state()) || version.rawObjectKey()==null)throw new FeedFailure(404,"MEDIA_NOT_READY");
  // Note: object-store signed URLs remain usable until TTL even after revoke; share_epoch invalidates new grants immediately.
  return new Signed(store.signGet(bucket,version.rawObjectKey(),ttlMillis),System.currentTimeMillis()+ttlMillis,"ORIGINAL");
 }

 public MaterialMapper.Version requireAccessibleVersion(long viewer,long postId,long versionId){
  visibility.read(viewer,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  var profile=db.profile(postId);if(profile==null)throw new FeedFailure(404,"NOT_MATERIAL");
  var version=db.version(versionId);
  if(version==null || version.postId()!=postId)throw new FeedFailure(404,"VERSION_NOT_FOUND");
  var post=posts.post(postId);boolean author=post!=null && post.authorId()==viewer;
  if("AUTHOR_ONLY".equals(profile.shareMode()) && !author)throw new FeedFailure(403,"VERSION_FORBIDDEN");
  if(!"READY".equals(version.state()) && !author)throw new FeedFailure(404,"MEDIA_NOT_READY");
  return version;
 }

 private MaterialMapper.Version resolveVersion(long postId,Long versionId,MaterialMapper.Profile profile){
  long id=versionId==null?(profile.defaultVersionId()==null?0:profile.defaultVersionId()):versionId;
  if(id<=0)throw new FeedFailure(404,"VERSION_NOT_FOUND");
  var version=db.version(id);
  if(version==null || version.postId()!=postId)throw new FeedFailure(404,"VERSION_NOT_FOUND");
  return version;
 }

 private void replaceTags(long postId,List<String> tags){
  db.clearTags(postId);
  if(tags==null)return;
  for(String t:tags){
   String tag=normalizeTag(t);if(tag!=null)db.addTag(postId,tag);
  }
 }

 private static void validateMeta(String title,String description,List<String> tags,String shareMode,String password){
  if(title==null || title.isBlank() || title.length()>200)throw new FeedFailure(400,"INVALID_REQUEST");
  if(description!=null && description.length()>2000)throw new FeedFailure(400,"INVALID_REQUEST");
  if(shareMode!=null && !Set.of("AUTHOR_ONLY","PUBLIC_PREVIEW","PASSWORD_ORIGINAL").contains(shareMode))throw new FeedFailure(400,"INVALID_REQUEST");
  if("PASSWORD_ORIGINAL".equals(shareMode) && (password==null || password.length()<4 || password.length()>72))throw new FeedFailure(400,"PASSWORD_REQUIRED");
  if(tags!=null && tags.size()>20)throw new FeedFailure(400,"INVALID_REQUEST");
 }
 private static String normalizeAspect(String aspect){
  if(aspect==null || aspect.isBlank())return "UNKNOWN";
  String a=aspect.trim().toUpperCase(Locale.ROOT);
  if(!Set.of("PORTRAIT","LANDSCAPE","SQUARE","UNKNOWN").contains(a))throw new FeedFailure(400,"INVALID_ASPECT");
  return a;
 }

 private static String normalizeTag(String t){
  if(t==null)return null;String s=t.trim().toLowerCase(Locale.ROOT);
  if(s.isEmpty() || s.length()>64 || !s.matches("[a-z0-9_\\-]{1,64}"))return null;return s;
 }
 private static String blankToNull(String s){return s==null || s.isBlank()?null:s.trim();}
 private static String bodyOf(String title,String description){
  String d=description==null?"":description;return (title+"\n"+d).substring(0,Math.min(4000,(title+"\n"+d).length()));
 }
 private static boolean metadataPublic(MaterialMapper.Profile profile,boolean author){
  if(author)return true;return !"AUTHOR_ONLY".equals(profile.shareMode());
 }
 private static Long parseDurationMs(String meta){
  try{
   int i=meta.indexOf("\"duration\"");if(i<0)return null;
   int c=meta.indexOf(':',i);int q1=meta.indexOf('"',c+1);int q2=meta.indexOf('"',q1+1);
   if(q1<0||q2<0)return null;double sec=Double.parseDouble(meta.substring(q1+1,q2));return Math.round(sec*1000);
  }catch(Exception e){return null;}
 }
}
