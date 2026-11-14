package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

public final class InteractionService {
 public record LikeResult(boolean liked,long likeCount) {}
 public record DislikeResult(boolean disliked,long dislikeCount) {}
 public record ReportResult(long reportId,long reportCount) {}
 public record CommentResult(long commentId,long commentCount,boolean replay,Long parentId,Long materialVersionId,Long offsetMs,Long acceptedAtMs) {}
 private final VisibilityService visibility;private final InteractionMapper db;private final TransactionTemplate tx;private final TrendingService trending;private final RelationshipService events;
 private final MaterialService materials; // optional null when media disabled
 public InteractionService(VisibilityService visibility,InteractionMapper db,TransactionTemplate tx,TrendingService trending,RelationshipService events,MaterialService materials){
  this.visibility=visibility;this.db=db;this.tx=tx;this.trending=trending;this.events=events;this.materials=materials;
 }
 public LikeResult like(long user,long post,String topic){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   db.undislike(post,user);
   boolean inserted=db.like(post,user,System.currentTimeMillis())==1;
   reconcile(post);
   if(inserted)trending.record("like:"+post+":"+user,topic==null?"general":topic,post,1,"LIKE");
   return new LikeResult(true,db.stats(post).likes());
  });
 }
 public LikeResult unlike(long user,long post,String topic){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   boolean removed=db.unlike(post,user)==1;reconcile(post);
   if(removed)trending.record("unlike:"+post+":"+user+":"+System.currentTimeMillis(),topic==null?"general":topic,post,-1,"UNLIKE");
   return new LikeResult(false,db.stats(post).likes());
  });
 }
 public DislikeResult dislike(long user,long post){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   db.unlike(post,user);
   db.dislike(post,user,System.currentTimeMillis());
   reconcile(post);
   return new DislikeResult(true,db.stats(post).dislikes());
  });
 }
 public DislikeResult undislike(long user,long post){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   db.undislike(post,user);reconcile(post);
   return new DislikeResult(false,db.stats(post)==null?0:db.stats(post).dislikes());
  });
 }
 public ReportResult report(long user,long post,String reason,String detail,String contextJson){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  if(reason==null || reason.isBlank() || reason.length()>64)throw new FeedFailure(400,"INVALID_REQUEST");
  if(detail!=null && detail.length()>1000)throw new FeedFailure(400,"INVALID_REQUEST");
  return tx.execute(status->{
   db.report(post,user,reason,detail==null?"":detail,contextJson,System.currentTimeMillis());
   reconcile(post);
   return new ReportResult(0,db.stats(post).reports());
  });
 }
 public CommentResult comment(long user,long post,String body,String key,String topic){return comment(user,post,body,key,topic,null,null,null);}
 public CommentResult comment(long user,long post,String body,String key,String topic,Long parentId){return comment(user,post,body,key,topic,parentId,null,null);}
 public CommentResult comment(long user,long post,String body,String key,String topic,Long parentId,Long materialVersionId,Long offsetMs){
  if(body==null || body.isBlank() || body.length()>4000 || key==null || key.isBlank() || key.length()>128)throw new FeedFailure(400,"INVALID_REQUEST");
  var view=visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   var existing=db.existingComment(user,key);
   if(existing!=null)return toResult(existing,true);
   Long parentAuthor=null;
   if(parentId!=null){
    if(parentId<=0)throw new FeedFailure(400,"INVALID_REQUEST");
    var parent=db.commentById(parentId);
    if(parent==null || parent.postId()!=post)throw new FeedFailure(404,"PARENT_COMMENT_NOT_FOUND");
    parentAuthor=parent.userId();
   }
   Long version=materialVersionId;Long offset=offsetMs;
   if(version!=null || offset!=null){
    if(materials==null)throw new FeedFailure(409,"MATERIAL_UNAVAILABLE");
    if(version==null || version<=0)throw new FeedFailure(400,"INVALID_REQUEST");
    var mv=materials.requireAccessibleVersion(user,post,version);
    if(offset!=null){
     if(offset<0)throw new FeedFailure(400,"INVALID_OFFSET");
     if(mv.durationMs()!=null && offset>mv.durationMs())throw new FeedFailure(400,"INVALID_OFFSET");
    }
   }
   if(db.comment(post,user,parentId,version,offset,body,key,System.currentTimeMillis())!=1)throw new IllegalStateException("comment insert failed");
   var created=db.existingComment(user,key);reconcile(post);
   trending.record("comment:"+key,topic==null?"general":topic,post,1,"COMMENT");
   Map<String,Object> fields=new LinkedHashMap<>();
   fields.put("postId",post);fields.put("commentId",created.id());fields.put("actorUserId",user);
   fields.put("postAuthorId",view.authorId());fields.put("occurredAtMs",created.createdAtMs());
   if(parentId!=null){fields.put("parentCommentId",parentId);fields.put("parentAuthorId",parentAuthor);}
   if(version!=null){fields.put("materialVersionId",version);if(offset!=null)fields.put("offsetMs",offset);}
   events.emit("CommentCreated",post,fields);
   return toResult(created,false);
  });
 }
 public CommentResult accept(long author,long post,long commentId){
  return tx.execute(status->{
   var view=visibility.read(author,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
   if(view.authorId()!=author)throw new FeedFailure(403,"POST_FORBIDDEN");
   var comment=db.commentById(commentId);
   if(comment==null || comment.postId()!=post)throw new FeedFailure(404,"COMMENT_NOT_FOUND");
   db.accept(post,commentId,System.currentTimeMillis());
   return toResult(db.commentById(commentId),false);
  });
 }
 public InteractionMapper.Stats stats(long post){
  var s=db.stats(post);return s==null?new InteractionMapper.Stats(post,0,0,0,0):s;
 }
 public InteractionMapper.Stats reconcile(long post){
  long likes=db.likeCount(post),comments=db.commentCount(post),dislikes=db.dislikeCount(post),reports=db.reportCount(post);
  db.upsertStats(post,likes,dislikes,comments,reports);return db.stats(post);
 }
 private CommentResult toResult(InteractionMapper.Comment c,boolean replay){
  var s=db.stats(c.postId());
  long count=s==null?db.commentCount(c.postId()):s.comments();
  return new CommentResult(c.id(),count,replay,c.parentId(),c.materialVersionId(),c.offsetMs(),c.acceptedAtMs());
 }
}
