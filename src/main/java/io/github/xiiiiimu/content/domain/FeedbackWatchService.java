package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Subscribe to a feedback comment; notify once when author links a READY version. */
public final class FeedbackWatchService {
 private final AssistantMapper db;private final InteractionMapper comments;private final MaterialMapper materials;
 private final FeedMapper posts;private final NotificationMapper notifications;private final VisibilityService visibility;
 private final TransactionTemplate tx;

 public FeedbackWatchService(AssistantMapper db,InteractionMapper comments,MaterialMapper materials,FeedMapper posts,
                             NotificationMapper notifications,VisibilityService visibility,TransactionTemplate tx){
  this.db=db;this.comments=comments;this.materials=materials;this.posts=posts;this.notifications=notifications;
  this.visibility=visibility;this.tx=tx;
 }

 public String subscribe(long accountId,long feedbackCommentId){
  var c=comments.commentById(feedbackCommentId);
  if(c==null)throw new FeedFailure(404,"FEEDBACK_NOT_FOUND");
  visibility.read(accountId,c.postId()).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  tx.executeWithoutResult(s->db.upsertSubscription(accountId,feedbackCommentId,c.postId(),System.currentTimeMillis()));
  for(var res:db.resolutionsForFeedback(feedbackCommentId))maybeNotify(res);
  return "已订阅反馈 #"+feedbackCommentId+" 的改进结果。可在原反馈下取消订阅。";
 }

 public void cancel(long accountId,long feedbackCommentId){
  if(db.cancelSubscription(accountId,feedbackCommentId,System.currentTimeMillis())!=1)
   throw new FeedFailure(404,"SUBSCRIPTION_NOT_FOUND");
 }

 public void linkResolution(long authorId,long postId,long feedbackCommentId,long newVersionId,Long offsetMs){
  var post=posts.post(postId);if(post==null)throw new FeedFailure(404,"POST_NOT_FOUND");
  if(post.authorId()!=authorId)throw new FeedFailure(403,"POST_FORBIDDEN");
  var feedback=comments.commentById(feedbackCommentId);
  if(feedback==null || feedback.postId()!=postId)throw new FeedFailure(404,"FEEDBACK_NOT_FOUND");
  var version=materials.version(newVersionId);
  if(version==null || version.postId()!=postId)throw new FeedFailure(404,"VERSION_NOT_FOUND");
  var row=new AssistantMapper.ResolutionInsert();
  row.feedback=feedbackCommentId;row.post=postId;row.version=newVersionId;row.offset=offsetMs;row.author=authorId;row.at=System.currentTimeMillis();
  tx.executeWithoutResult(s->{
   try{db.insertResolution(row);}catch(org.springframework.dao.DuplicateKeyException ignore){/* idempotent link */}
  });
  for(var res:db.resolutionsForFeedback(feedbackCommentId))if(res.newVersionId()==newVersionId)maybeNotify(res);
 }

 /** Called when a material version becomes READY. */
 public void onVersionReady(long versionId){
  for(var res:db.resolutionsForVersion(versionId))maybeNotify(res);
 }

 private void maybeNotify(AssistantMapper.Resolution res){
  var version=materials.version(res.newVersionId());
  if(version==null || !"READY".equals(version.state()))return;
  for(var sub:db.activeSubs(res.feedbackCommentId())){
   tx.executeWithoutResult(status->{
    if(db.notifyLog(sub.accountId(),res.feedbackCommentId(),res.newVersionId(),null,System.currentTimeMillis())!=1)return;
    String dedupe="feedback-ready:"+sub.accountId()+":"+res.feedbackCommentId()+":"+res.newVersionId();
    long synthetic=Math.floorMod(java.util.Objects.hash(sub.accountId(),res.feedbackCommentId(),res.newVersionId()),1_000_000_000)+2_000_000_000L;
    notifications.insertWithMeta(sub.accountId(),synthetic,"FEEDBACK_READY",res.authorId(),res.postId(),res.feedbackCommentId(),
     null,res.newVersionId(),dedupe,System.currentTimeMillis());
   });
  }
 }
}
