package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.xiiiiimu.content.infrastructure.InteractionMapper;
import io.github.xiiiiimu.content.infrastructure.NotificationMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** In-app notifications; created from CommentCreated outbox consumption; recipient-only access. */
public final class NotificationService {
 public record Item(long id,String kind,long actorUserId,long postId,long commentId,Long parentCommentId,Long refVersionId,long createdAtMs,Long readAtMs) {}
 public record Target(long postId,long commentId,Long parentCommentId,Long refVersionId,VisibilityService.View post) {}
 private final NotificationMapper db;private final InteractionMapper comments;private final VisibilityService visibility;private final TransactionTemplate tx;
 public NotificationService(NotificationMapper db,InteractionMapper comments,VisibilityService visibility,TransactionTemplate tx){
  this.db=db;this.comments=comments;this.visibility=visibility;this.tx=tx;
 }
 /** Idempotent materialization keyed by (source_event_id, recipient). */
 public void acceptCommentEvent(long eventId,JsonNode body){
  long postId=req(body,"postId");long commentId=req(body,"commentId");long actor=req(body,"actorUserId");
  long postAuthor=req(body,"postAuthorId");
  Long parentId=body.has("parentCommentId") && !body.get("parentCommentId").isNull()?body.get("parentCommentId").asLong():null;
  Long parentAuthor=body.has("parentAuthorId") && !body.get("parentAuthorId").isNull()?body.get("parentAuthorId").asLong():null;
  long now=body.has("occurredAtMs")?body.get("occurredAtMs").asLong():System.currentTimeMillis();
  tx.executeWithoutResult(status->{
   if(parentId==null){
    if(actor!=postAuthor)db.insert(postAuthor,eventId,"COMMENT_ON_POST",actor,postId,commentId,null,now);
   }else if(parentAuthor!=null && actor!=parentAuthor){
    db.insert(parentAuthor,eventId,"REPLY_TO_COMMENT",actor,postId,commentId,parentId,now);
   }
  });
 }
 public List<Item> list(long userId,int limit){
  if(userId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  int n=limit<=0?50:Math.min(limit,100);
  return db.list(userId,n).stream().map(this::item).toList();
 }
 public Item markRead(long userId,long id){
  if(userId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  var row=db.get(id);if(row==null || row.recipientUserId()!=userId)throw new FeedFailure(404,"NOTIFICATION_NOT_FOUND");
  db.markRead(id,userId,System.currentTimeMillis());
  return item(db.get(id));
 }
 /** Re-check post visibility; never returns post body when unauthorized. */
 public Target target(long userId,long id){
  if(userId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  var row=db.get(id);if(row==null || row.recipientUserId()!=userId)throw new FeedFailure(404,"NOTIFICATION_NOT_FOUND");
  var view=visibility.read(userId,row.postId()).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  // Comment may still exist; absence does not leak body.
  var comment=comments.commentById(row.commentId());
  if(comment==null || comment.postId()!=row.postId())throw new FeedFailure(404,"COMMENT_NOT_FOUND");
  return new Target(row.postId(),row.commentId(),row.parentCommentId(),row.refVersionId(),view);
 }
 private Item item(NotificationMapper.Row r){
  return new Item(r.id(),r.kind(),r.actorUserId(),r.postId(),r.commentId(),r.parentCommentId(),r.refVersionId(),r.createdAtMs(),r.readAtMs());
 }
 private static long req(JsonNode body,String name){
  var v=body.get(name);if(v==null || !v.isIntegralNumber() || v.asLong()<=0)throw new FeedFailure(409,"INVALID_EVENT");
  return v.asLong();
 }
}
