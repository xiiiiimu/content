package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class InteractionService {
 public record LikeResult(boolean liked,long likeCount) {}
 public record CommentResult(long commentId,long commentCount,boolean replay) {}
 private final VisibilityService visibility;private final InteractionMapper db;private final TransactionTemplate tx;private final TrendingService trending;
 public InteractionService(VisibilityService visibility,InteractionMapper db,TransactionTemplate tx,TrendingService trending){this.visibility=visibility;this.db=db;this.tx=tx;this.trending=trending;}
 public LikeResult like(long user,long post,String topic){
  var view=visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
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
 public CommentResult comment(long user,long post,String body,String key,String topic){
  if(body==null || body.isBlank() || body.length()>4000 || key==null || key.isBlank() || key.length()>128)throw new FeedFailure(400,"INVALID_REQUEST");
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   var existing=db.existingComment(user,key);if(existing!=null)return new CommentResult(existing.id(),db.stats(post)==null?db.commentCount(post):db.stats(post).comments(),true);
   if(db.comment(post,user,body,key,System.currentTimeMillis())!=1)throw new IllegalStateException("comment insert failed");
   var created=db.existingComment(user,key);reconcile(post);
   trending.record("comment:"+key,topic==null?"general":topic,post,1,"COMMENT");
   return new CommentResult(created.id(),db.stats(post).comments(),false);
  });
 }
 public InteractionMapper.Stats stats(long post){var s=db.stats(post);return s==null?new InteractionMapper.Stats(post,0,0):s;}
 public InteractionMapper.Stats reconcile(long post){
  long likes=db.likeCount(post),comments=db.commentCount(post);db.upsertStats(post,likes,comments);return db.stats(post);
 }
}
