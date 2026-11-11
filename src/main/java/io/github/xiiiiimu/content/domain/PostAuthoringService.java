package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.PostAuthoringMapper;
import io.github.xiiiiimu.content.infrastructure.UserMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** Create/edit posts. Edit of PUBLISHED keeps publishedAtMs and does not emit PostPublished. */
public final class PostAuthoringService {
 public record Draft(long postId,long authorId,String status,String body,long version,Long publishedAtMs) {}
 private final PostAuthoringMapper posts;private final UserMapper users;private final TransactionTemplate tx;
 public PostAuthoringService(PostAuthoringMapper posts,UserMapper users,TransactionTemplate tx){
  this.posts=posts;this.users=users;this.tx=tx;
 }
 public Draft create(long authorId,String body){
  if(authorId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  String text=requireBody(body);
  return tx.execute(status->{
   if(users.byId(authorId)==null)throw new FeedFailure(404,"USER_NOT_FOUND");
   users.ensureAuthor(authorId);
   if(posts.insertDraft(authorId,text)!=1)throw new IllegalStateException("draft insert");
   long id=posts.lastId();
   var row=posts.get(id);
   return toDraft(row);
  });
 }
 public Draft edit(long authorId,long postId,long expectedVersion,String body){
  if(authorId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  if(postId<=0 || expectedVersion<0)throw new FeedFailure(400,"INVALID_REQUEST");
  String text=requireBody(body);
  return tx.execute(status->{
   var locked=posts.lock(postId);
   if(locked==null)throw new FeedFailure(404,"POST_NOT_FOUND");
   if(locked.authorId()!=authorId)throw new FeedFailure(403,"POST_FORBIDDEN");
   if(locked.version()!=expectedVersion)throw new FeedFailure(409,"POST_VERSION_CONFLICT");
   if(!"DRAFT".equals(locked.status()) && !"PUBLISHED".equals(locked.status()) && !"PROCESSING".equals(locked.status()))
    throw new FeedFailure(409,"POST_STATE_CONFLICT");
   if(posts.updateBody(postId,authorId,expectedVersion,text)!=1)throw new FeedFailure(409,"POST_VERSION_CONFLICT");
   return toDraft(posts.get(postId));
  });
 }
 private static String requireBody(String body){
  if(body==null || body.isBlank() || body.length()>100_000)throw new FeedFailure(400,"INVALID_REQUEST");
  return body;
 }
 private static Draft toDraft(PostAuthoringMapper.Locked row){
  return new Draft(row.id(),row.authorId(),row.status(),row.body(),row.version(),row.publishedAtMs());
 }
}
