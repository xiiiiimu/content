package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

public final class InteractionService {
 public record LikeResult(boolean liked,long likeCount) {}
 public record DislikeResult(boolean disliked,long dislikeCount) {}
 /** {@code replay} = this reporter had already reported the post; reportId is that first report. */
 public record ReportResult(long reportId,long reportCount,boolean replay) {}
 public record CommentResult(long commentId,long commentCount,boolean replay,Long parentId,Long materialVersionId,Long offsetMs,Long acceptedAtMs) {}
 public record CommentView(long commentId,long userId,Long parentId,Long materialVersionId,Long offsetMs,Long acceptedAtMs,String body,long createdAtMs) {}
 private final VisibilityService visibility;private final InteractionMapper db;private final TransactionTemplate tx;private final TrendingService trending;private final RelationshipService events;
 private final MaterialService materials; // optional null when media disabled
 private final java.util.function.Function<AssistantEnqueue,Long> assistantEnqueue; // optional
 public record AssistantEnqueue(long postId,long commentId,long actorUserId,Long parentCommentId,String body) {}
 public InteractionService(VisibilityService visibility,InteractionMapper db,TransactionTemplate tx,TrendingService trending,RelationshipService events,MaterialService materials){
  this(visibility,db,tx,trending,events,materials,null);
 }
 public InteractionService(VisibilityService visibility,InteractionMapper db,TransactionTemplate tx,TrendingService trending,RelationshipService events,MaterialService materials,java.util.function.Function<AssistantEnqueue,Long> assistantEnqueue){
  this.visibility=visibility;this.db=db;this.tx=tx;this.trending=trending;this.events=events;this.materials=materials;this.assistantEnqueue=assistantEnqueue;
 }
 /**
  * @param clientTopic ignored. Kept for API compatibility; topic statistics use the post's own topic attribute
  *                    ({@code posts.topic}) and a post without a topic writes no topic statistics.
  */
 public LikeResult like(long user,long post,String clientTopic){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   db.undislike(post,user);
   boolean inserted=db.like(post,user,System.currentTimeMillis())==1;
   reconcile(post);
   String topic=inserted?db.postTopic(post):null;
   if(topic!=null)trending.recordLike(post,user,topic);
   return new LikeResult(true,db.stats(post).likes());
  });
 }
 /** @param clientTopic ignored, see {@link #like}. */
 public LikeResult unlike(long user,long post,String clientTopic){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   boolean removed=db.unlike(post,user)==1;reconcile(post);
   // Only a real true→false flip revokes the like's own topic contribution (no separate -1 event).
   if(removed)trending.revokeLike(post,user);
   return new LikeResult(false,db.stats(post).likes());
  });
 }
 public DislikeResult dislike(long user,long post){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   // Disliking removes an existing like: that is a true→false like flip as well.
   if(db.unlike(post,user)==1)trending.revokeLike(post,user);
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
  // Hardening A3: one report per (post, reporter). A concurrent repeat waits on the unique key until the first commits
  // (READ COMMITTED), then inserts nothing and reads the committed first row; counts are recomputed only on a new row.
  return tx.execute(status->{
   boolean inserted=db.report(post,user,reason,detail==null?"":detail,contextJson,System.currentTimeMillis())==1;
   long count=inserted?reconcile(post).reports():currentReports(post);
   Long id=db.reportId(post,user);
   if(id==null)throw new IllegalStateException("report row missing after insert for post "+post);
   return new ReportResult(id,count,!inserted);
  });
 }
 public CommentResult comment(long user,long post,String body,String key,String topic){return comment(user,post,body,key,topic,null,null,null);}
 public CommentResult comment(long user,long post,String body,String key,String topic,Long parentId){return comment(user,post,body,key,topic,parentId,null,null);}
 /** @param clientTopic ignored, see {@link #like}. */
 public CommentResult comment(long user,long post,String body,String key,String clientTopic,Long parentId,Long materialVersionId,Long offsetMs){
  if(body==null || body.isBlank() || body.length()>4000 || key==null || key.isBlank() || key.length()>128)throw new FeedFailure(400,"INVALID_REQUEST");
  var view=visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  try{
   return createComment(user,post,body,key,parentId,materialVersionId,offsetMs,view);
  }catch(org.springframework.dao.DuplicateKeyException conflict){
   // Hardening A5 (same as PublishService): a concurrent request with this key committed first and our insert hit
   // uq_comment_idem. The template rolled back every write of this attempt; read the winner in a new transaction,
   // never inside the failed one.
   var replay=tx.execute(status->{var winner=db.existingComment(user,key);return winner==null?null:toResult(winner,true);});
   if(replay==null)throw conflict;
   return replay;
  }
 }
 private CommentResult createComment(long user,long post,String body,String key,Long parentId,Long materialVersionId,Long offsetMs,VisibilityService.View view){
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
   String topic=db.postTopic(post);
   if(topic!=null)trending.record("comment:"+key,topic,post,1,"COMMENT");
   Map<String,Object> fields=new LinkedHashMap<>();
   fields.put("postId",post);fields.put("commentId",created.id());fields.put("actorUserId",user);
   fields.put("postAuthorId",view.authorId());fields.put("occurredAtMs",created.createdAtMs());
   if(parentId!=null){fields.put("parentCommentId",parentId);fields.put("parentAuthorId",parentAuthor);}
   if(version!=null){fields.put("materialVersionId",version);if(offset!=null)fields.put("offsetMs",offset);}
   events.emit("CommentCreated",post,fields);
   // Ordinary comment commits first; assistant work is enqueued asynchronously in the same TX.
   if(assistantEnqueue!=null)assistantEnqueue.apply(new AssistantEnqueue(post,created.id(),user,parentId,body));
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
 /** Visible comments for a post the viewer can read (oldest first, capped). */
 public List<CommentView> listComments(long viewer,long post,int limit){
  visibility.read(viewer,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  int n=limit<=0?50:Math.min(limit,200);
  return db.commentsForPost(post,n).stream()
   .map(c->new CommentView(c.id(),c.userId(),c.parentId(),c.materialVersionId(),c.offsetMs(),c.acceptedAtMs(),c.body(),c.createdAtMs()))
   .toList();
 }
 private long currentReports(long post){var s=db.stats(post);return s==null?db.reportCount(post):s.reports();}
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
