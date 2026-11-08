package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MediaService {
 public record Upload(long taskId,String objectKey) {}
 public record Signed(String url,long expiresAtMs) {}
 private final FeedMapper posts;private final MediaMapper media;private final TransactionTemplate tx;private final ObjectStore store;private final VisibilityService visibility;private final String bucket;private final String ffmpegImage;private final PipelineObserver observer;
 public MediaService(FeedMapper posts,MediaMapper media,TransactionTemplate tx,ObjectStore store,VisibilityService visibility,String bucket,String ffmpegImage,PipelineObserver observer){
  this.posts=posts;this.media=media;this.tx=tx;this.store=store;this.visibility=visibility;this.bucket=bucket;this.ffmpegImage=ffmpegImage;this.observer=observer;
 }
 public Upload attach(long author,long post,long expectedVersion,byte[] bytes,String contentType){
  if(bytes==null || bytes.length==0 || bytes.length>8_000_000)throw new FeedFailure(400,"INVALID_MEDIA");
  String key="raw/"+post+"/"+expectedVersion;
  String type=contentType==null?"application/octet-stream":contentType;
  // Validate + fence in TX first; object write stays outside the short DB lease.
  tx.executeWithoutResult(status->{
   RelationshipService.requireWritable(posts.gate());
   var row=posts.post(post);if(row==null)throw new FeedFailure(404,"POST_NOT_FOUND");if(row.authorId()!=author)throw new FeedFailure(403,"POST_FORBIDDEN");
   if(row.version()!=expectedVersion)throw new FeedFailure(409,"POST_VERSION_CONFLICT");
   if(!Set.of("DRAFT","PROCESSING","PUBLISHED").contains(row.status()))throw new FeedFailure(409,"POST_STATE_CONFLICT");
  });
  store.put(bucket,key,bytes,type);
  return tx.execute(status->{
   var row=posts.post(post);if(row==null)throw new FeedFailure(404,"POST_NOT_FOUND");
   if(row.version()!=expectedVersion || !Set.of("DRAFT","PROCESSING","PUBLISHED").contains(row.status()))throw new FeedFailure(409,"POST_STATE_CONFLICT");
   media.create(post,expectedVersion,key);var task=media.byPost(post);if(task==null)throw new IllegalStateException("media task missing");
   return new Upload(task.id(),key);
  });
 }
 public void tick(){
  var claimed=tx.execute(status->{
   var task=media.claimable();if(task==null)return null;
   if(media.claim(task.id())!=1)return null;return media.byPost(task.postId());
  });
  if(claimed==null)return;
  long token=Objects.requireNonNull(media.token(claimed.id()));
  try{
   observer.beforeMediaProcess(claimed.id());
   var post=posts.post(claimed.postId());
   if(post==null || post.version()!=claimed.expectedVersion() || "OFFLINE".equals(post.status())){tx.executeWithoutResult(s->media.fail(claimed.id(),token,1,"POST_GONE_OR_VERSION"));return;}
   byte[] raw=store.get(bucket,claimed.objectKey());
   String out="derived/"+claimed.postId()+"/"+claimed.expectedVersion()+".bin";
   store.put(bucket,out,transcode(raw),"application/octet-stream");
   tx.executeWithoutResult(s->{if(media.complete(claimed.id(),token,out)!=1)throw new FeedFailure(409,"MEDIA_FENCED");});
  }catch(Exception e){
   String msg=e.getMessage()==null?"error":e.getMessage();
   tx.executeWithoutResult(s->media.fail(claimed.id(),token,Math.min(60,1<<Math.min(5,Math.max(0,claimed.attempt()-1))),msg.substring(0,Math.min(500,msg.length()))));
  }
}
 public Signed sign(long viewer,long post,long ttlMillis){
  if(ttlMillis<=0 || ttlMillis>900_000L)throw new FeedFailure(400,"INVALID_REQUEST");
  var view=visibility.read(viewer,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  var task=media.byPost(post);if(task==null || task.outputKey()==null || !"DONE".equals(task.state()))throw new FeedFailure(404,"MEDIA_NOT_READY");
  if(task.expectedVersion()!=view.version())throw new FeedFailure(409,"MEDIA_VERSION_STALE");
  long exp=System.currentTimeMillis()+ttlMillis;return new Signed(store.signGet(bucket,task.outputKey(),ttlMillis),exp);
 }
 private byte[] transcode(byte[] raw){
  String prefix=ffmpegImage==null||ffmpegImage.isBlank()?"derived":"ffmpeg:"+ffmpegImage;
  return (prefix+":"+raw.length+":"+Base64.getEncoder().encodeToString(Arrays.copyOf(raw,Math.min(16,raw.length)))).getBytes(StandardCharsets.UTF_8);
 }
}
