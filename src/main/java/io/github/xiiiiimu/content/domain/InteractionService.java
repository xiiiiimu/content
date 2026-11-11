package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

public final class InteractionService {
 public record LikeResult(boolean liked,long likeCount) {}
 public record CommentResult(long commentId,long commentCount,boolean replay,Long parentId) {}
 private final VisibilityService visibility;private final InteractionMapper db;private final TransactionTemplate tx;private final TrendingService trending;private final RelationshipService events;
 public InteractionService(VisibilityService visibility,InteractionMapper db,TransactionTemplate tx,TrendingService trending,RelationshipService events){
  this.visibility=visibility;this.db=db;this.tx=tx;this.trending=trending;this.events=events;
 }
 public LikeResult like(long user,long post,String topic){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
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
 public CommentResult comment(long user,long post,String body,String key,String topic){return comment(user,post,body,key,topic,null);}
 public CommentResult comment(long user,long post,String body,String key,String topic,Long parentId){
  if(body==null || body.isBlank() || body.length()>4000 || key==null || key.isBlank() || key.length()>128)throw new FeedFailure(400,"INVALID_REQUEST");
  var view=visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   var existing=db.existingComment(user,key);
   if(existing!=null)return new CommentResult(existing.id(),db.stats(post)==null?db.commentCount(post):db.stats(post).comments(),true,existing.parentId());
   Long parentAuthor=null;
   if(parentId!=null){
    if(parentId<=0)throw new FeedFailure(400,"INVALID_REQUEST");
    var parent=db.commentById(parentId);
    if(parent==null || parent.postId()!=post)throw new FeedFailure(404,"PARENT_COMMENT_NOT_FOUND");
    // Parent must be on a post the current user can see/reply to (already checked).
    parentAuthor=parent.userId();
   }
   if(db.comment(post,user,parentId,body,key,System.currentTimeMillis())!=1)throw new IllegalStateException("comment insert failed");
   var created=db.existingComment(user,key);reconcile(post);
   trending.record("comment:"+key,topic==null?"general":topic,post,1,"COMMENT");
   Map<String,Object> fields=new LinkedHashMap<>();
   fields.put("postId",post);fields.put("commentId",created.id());fields.put("actorUserId",user);
   fields.put("postAuthorId",view.authorId());fields.put("occurredAtMs",created.createdAtMs());
   if(parentId!=null){fields.put("parentCommentId",parentId);fields.put("parentAuthorId",parentAuthor);}
   events.emit("CommentCreated",post,fields);
   return new CommentResult(created.id(),db.stats(post).comments(),false,parentId);
  });
 }
 public InteractionMapper.Stats stats(long post){var s=db.stats(post);return s==null?new InteractionMapper.Stats(post,0,0):s;}
 public InteractionMapper.Stats reconcile(long post){
  long likes=db.likeCount(post),comments=db.commentCount(post);db.upsertStats(post,likes,comments);return db.stats(post);
 }
}
