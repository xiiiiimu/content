package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.PostAuthoringMapper;
import io.github.xiiiiimu.content.infrastructure.UserMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** Create/edit posts. Edit of PUBLISHED keeps publishedAtMs and does not emit PostPublished. */
public final class PostAuthoringService {
 public static final int MAX_TOPIC_LENGTH=128;
 public record Draft(long postId,long authorId,String status,String body,long version,Long publishedAtMs,String topic) {}
 private final PostAuthoringMapper posts;private final UserMapper users;private final TransactionTemplate tx;
 public PostAuthoringService(PostAuthoringMapper posts,UserMapper users,TransactionTemplate tx){
  this.posts=posts;this.users=users;this.tx=tx;
 }
 public Draft create(long authorId,String body){return create(authorId,body,null);}
 /** @param topic optional author-set topic; trimmed, blank means none, at most {@value #MAX_TOPIC_LENGTH} characters. */
 public Draft create(long authorId,String body,String topic){
  if(authorId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  String text=requireBody(body);String normalized=normalizeTopic(topic);
  return tx.execute(status->{
   if(users.byId(authorId)==null)throw new FeedFailure(404,"USER_NOT_FOUND");
   users.ensureAuthor(authorId);
   if(posts.insertDraftWithTopic(authorId,text,normalized)!=1)throw new IllegalStateException("draft insert");
   long id=posts.lastId();
   var row=posts.get(id);
   return toDraft(row);
  });
 }
 public Draft edit(long authorId,long postId,long expectedVersion,String body){return edit(authorId,postId,expectedVersion,body,false,null);}
 /** @param changeTopic when false the stored topic is kept; when true {@code topic} replaces it (blank/null clears it). */
 public Draft edit(long authorId,long postId,long expectedVersion,String body,boolean changeTopic,String topic){
  if(authorId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  if(postId<=0 || expectedVersion<0)throw new FeedFailure(400,"INVALID_REQUEST");
  String text=requireBody(body);String normalized=changeTopic?normalizeTopic(topic):null;
  return tx.execute(status->{
   var locked=posts.lock(postId);
   if(locked==null)throw new FeedFailure(404,"POST_NOT_FOUND");
   if(locked.authorId()!=authorId)throw new FeedFailure(403,"POST_FORBIDDEN");
   if(locked.version()!=expectedVersion)throw new FeedFailure(409,"POST_VERSION_CONFLICT");
   if(!"DRAFT".equals(locked.status()) && !"PUBLISHED".equals(locked.status()) && !"PROCESSING".equals(locked.status()))
    throw new FeedFailure(409,"POST_STATE_CONFLICT");
   int updated=changeTopic?posts.updateBodyAndTopic(postId,authorId,expectedVersion,text,normalized):posts.updateBody(postId,authorId,expectedVersion,text);
   if(updated!=1)throw new FeedFailure(409,"POST_VERSION_CONFLICT");
   return toDraft(posts.get(postId));
  });
 }
 public static String normalizeTopic(String topic){
  if(topic==null)return null;
  String t=topic.strip();
  if(t.isEmpty())return null;
  if(t.length()>MAX_TOPIC_LENGTH || t.chars().anyMatch(Character::isISOControl))throw new FeedFailure(400,"INVALID_TOPIC");
  return t;
 }
 private static String requireBody(String body){
  if(body==null || body.isBlank() || body.length()>100_000)throw new FeedFailure(400,"INVALID_REQUEST");
  return body;
 }
 private static Draft toDraft(PostAuthoringMapper.Locked row){
  return new Draft(row.id(),row.authorId(),row.status(),row.body(),row.version(),row.publishedAtMs(),row.topic());
 }
}
