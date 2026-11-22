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
  return tx.execute(status->{
   var view=visible(user,post);
   lockReactions(user);
   int undisliked=db.undislike(post,user);
   boolean inserted=db.like(post,user,System.currentTimeMillis())==1;
   String topic=inserted?view.topic():null;
   if(topic!=null)trending.recordLike(post,user,topic);
   // Counter last: the hot post_stats row lock is held only for this UPDATE, the read after it and commit (B2b).
   var stats=applyCounts(post,inserted?1:0,-undisliked,0,0);
   return new LikeResult(true,stats.likes());
  });
 }
 /** @param clientTopic ignored, see {@link #like}. */
 public LikeResult unlike(long user,long post,String clientTopic){
  return tx.execute(status->{
   visible(user,post);
   lockReactions(user);
   boolean removed=db.unlike(post,user)==1;
   // Only a real true→false flip revokes the like's own topic contribution (no separate -1 event).
   if(removed)trending.revokeLike(post,user);
   var stats=applyCounts(post,removed?-1:0,0,0,0);   // counter last (B2b)
   return new LikeResult(false,stats.likes());
  });
 }
 public DislikeResult dislike(long user,long post){
  return tx.execute(status->{
   visible(user,post);
   lockReactions(user);
   // Disliking removes an existing like: that is a true→false like flip as well.
   boolean unliked=db.unlike(post,user)==1;
   if(unliked)trending.revokeLike(post,user);
   boolean disliked=db.dislike(post,user,System.currentTimeMillis())==1;
   return new DislikeResult(true,applyCounts(post,unliked?-1:0,disliked?1:0,0,0).dislikes());
  });
 }
 public DislikeResult undislike(long user,long post){
  return tx.execute(status->{
   visible(user,post);
   lockReactions(user);
   int removed=db.undislike(post,user);
   return new DislikeResult(false,applyCounts(post,0,-removed,0,0).dislikes());
  });
 }
 public ReportResult report(long user,long post,String reason,String detail,String contextJson){
  // Hardening A3: one report per (post, reporter). A concurrent repeat waits on the unique key until the first commits
  // (READ COMMITTED), then inserts nothing and reads the committed first row; only a new row moves the counter.
  return tx.execute(status->{
   visible(user,post);   // 404 before 400, as before B3
   if(reason==null || reason.isBlank() || reason.length()>64)throw new FeedFailure(400,"INVALID_REQUEST");
   if(detail!=null && detail.length()>1000)throw new FeedFailure(400,"INVALID_REQUEST");
   boolean inserted;
   // A duplicate key fails only this statement (MySQL statement rollback); the transaction continues.
   try{inserted=db.report(post,user,reason,detail==null?"":detail,contextJson,System.currentTimeMillis())==1;}
   catch(org.springframework.dao.DuplicateKeyException repeat){inserted=false;}
   long count=applyCounts(post,0,0,0,inserted?1:0).reports();
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
  try{
   return createComment(user,post,body,key,parentId,materialVersionId,offsetMs);
  }catch(org.springframework.dao.DuplicateKeyException conflict){
   // Hardening A5 (same as PublishService): a concurrent request with this key committed first and our insert hit
   // uq_comment_idem. The template rolled back every write of this attempt; read the winner in a new transaction,
   // never inside the failed one.
   var replay=tx.execute(status->{var winner=db.existingComment(user,key);return winner==null?null:toResult(winner,true);});
   if(replay==null)throw conflict;
   return replay;
  }
 }
 private CommentResult createComment(long user,long post,String body,String key,Long parentId,Long materialVersionId,Long offsetMs){
  return tx.execute(status->{
   var view=visible(user,post);   // checked before the replay lookup, as before B3
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
   var created=db.existingComment(user,key);
   String topic=view.topic();
   // Hardening A10: one ledger row per created comment. The idempotency key is only unique per user, so it cannot be
   // the event id; a replay returns before this point and records nothing.
   if(topic!=null)trending.record("comment:"+created.id(),topic,post,1,"COMMENT");
   Map<String,Object> fields=new LinkedHashMap<>();
   fields.put("postId",post);fields.put("commentId",created.id());fields.put("actorUserId",user);
   fields.put("postAuthorId",view.authorId());fields.put("occurredAtMs",created.createdAtMs());
   if(parentId!=null){fields.put("parentCommentId",parentId);fields.put("parentAuthorId",parentAuthor);}
   if(version!=null){fields.put("materialVersionId",version);if(offset!=null)fields.put("offsetMs",offset);}
   events.emit("CommentCreated",post,fields);
   // Ordinary comment commits first; assistant work is enqueued asynchronously in the same TX.
   if(assistantEnqueue!=null)assistantEnqueue.apply(new AssistantEnqueue(post,created.id(),user,parentId,body));
   applyCounts(post,0,0,1,0);   // counter last (B2b): ledger, outbox and assistant rows are written before the hot row lock
   return toResult(created,false);
  });
 }
 public CommentResult accept(long author,long post,long commentId){
  return tx.execute(status->{
   var view=visible(author,post);
   if(view.authorId()!=author)throw new FeedFailure(403,"POST_FORBIDDEN");
   var comment=db.commentById(commentId);
   if(comment==null || comment.postId()!=post)throw new FeedFailure(404,"COMMENT_NOT_FOUND");
   db.accept(post,commentId,System.currentTimeMillis());
   return toResult(db.commentById(commentId),false);
  });
 }
 /**
  * Hardening B3: the visibility check of a write runs inside the write's transaction, on its connection, so an interaction
  * borrows one pooled connection instead of three (two autocommit reads before the transaction). The two reads are
  * plain consistent reads under READ COMMITTED and come first, before any row lock, so lock hold times do not grow.
  * This is the authoritative check (AuthoritativeVisibility, MySQL only), not the cached PostDetailService.
  * A rejection throws inside the transaction, which rolls back with nothing written.
  */
 /**
  * Hardening B10: like and dislike write the same two rows in opposite orders (like: delete post_dislikes, insert
  * post_likes; dislike: delete post_likes, insert post_dislikes). Under READ COMMITTED a DELETE that finds no row takes
  * no lock, so concurrent like and dislike by one user on one post could both commit (the user likes and dislikes the
  * post), and from that state the next pair each held one row and waited for the other's: an InnoDB deadlock.
  * <p>Every reaction write therefore takes one lock first, the user's own {@code users} row, and holds nothing before it.
  * Reactions of the same user are serialized (on every post: a person's own requests, so the wait is negligible), and
  * since the lock is taken before any other, no cycle can form through it. Other users never touch this row lock, and
  * nothing else locks {@code users} rows in a transaction that also writes reactions. A user id with no row (only in
  * tests that skip registration) locks nothing.
  */
 private void lockReactions(long user){db.lockReactor(user);}
 private VisibilityService.View visible(long user,long post){
  return visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
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
 /**
  * Hardening B2 (R3): post_stats moves by this transaction's own changes (the affected rows of its INSERT IGNORE / DELETE)
  * instead of four COUNT(*) per interaction. Every writer changes the counted rows first and post_stats last (B2b: after its ledger/outbox rows too), in the
  * same transaction, so the post_stats row lock order is unchanged and a transaction's changes become visible together
  * with its counter change ({@link StatsReconciler} relies on this). A post without a stats row (never counted yet, or
  * cleared while OFFLINE by PipelineReconcile) gets one built from COUNT(*) once, see {@code InteractionMapper.createStats}.
  * Returns the row after the change.
  */
 private InteractionMapper.Stats applyCounts(long post,int likes,int dislikes,int comments,int reports){
  boolean changed=likes!=0 || dislikes!=0 || comments!=0 || reports!=0;
  if(changed && db.addStats(post,likes,dislikes,comments,reports)==1)return db.stats(post);
  var s=changed?null:db.stats(post);
  if(s!=null)return s;
  db.createStats(post,likes,dislikes,comments,reports);
  return db.stats(post);
 }
 /**
  * Recounts one post under its post_stats row lock and stores the counts (operators and tests). Online interactions never
  * call this; drift is found by the {@link StatsReconciler} job.
  */
 public InteractionMapper.Stats reconcile(long post){
  return tx.execute(status->{
   if(db.statsForUpdate(post)==null)db.createStats(post,0,0,0,0);
   long likes=db.likeCount(post),comments=db.commentCount(post),dislikes=db.dislikeCount(post),reports=db.reportCount(post);
   db.upsertStats(post,likes,dislikes,comments,reports);return db.stats(post);
  });
 }
 private CommentResult toResult(InteractionMapper.Comment c,boolean replay){
  var s=db.stats(c.postId());
  long count=s==null?db.commentCount(c.postId()):s.comments();
  return new CommentResult(c.id(),count,replay,c.parentId(),c.materialVersionId(),c.offsetMs(),c.acceptedAtMs());
 }
}
