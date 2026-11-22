package io.github.xiiiiimu.content.domain;
import java.util.Optional;
/** Authoritative visibility for every application read entry. Cache never authorizes. */
public interface VisibilityService {
 /** topic is public post metadata (the topic page lists it), returned to every reader who can see the post. */
 record View(long postId,long authorId,long publishedAtMs,String body,long version,String status,String topic) {}
 Optional<View> read(long viewer,long postId);
 boolean visibleInFeed(long viewer,long postId);
}
