package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.FeedMapper;
import java.util.Optional;

/** MySQL is the only authorizer. Redis/detail caches never grant access. */
public final class AuthoritativeVisibility implements VisibilityService {
 private final FeedMapper db;
 public AuthoritativeVisibility(FeedMapper db){this.db=db;}
 @Override public Optional<View> read(long viewer,long postId){
  if(viewer<=0 || postId<=0)throw new FeedFailure(400,"INVALID_REQUEST");
  var row=db.visible(postId);if(row==null || !"PUBLISHED".equals(row.status()) || row.time()==null)return Optional.empty();
  if(row.authorId()==viewer)return Optional.of(new View(row.id(),row.authorId(),row.time(),row.body(),row.version(),row.status()));
  var auth=db.authority(viewer,postId);if(auth==null || !auth.allowed())return Optional.empty();
  return Optional.of(new View(row.id(),row.authorId(),row.time(),row.body(),row.version(),row.status()));
 }
 @Override public boolean visibleInFeed(long viewer,long postId){
  if(viewer<=0 || postId<=0)return false;
  var row=db.authority(viewer,postId);return row!=null && row.allowed() && row.time()!=null;
 }
}
