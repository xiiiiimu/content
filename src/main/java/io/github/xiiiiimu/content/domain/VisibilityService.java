package io.github.xiiiiimu.content.domain;
import java.util.Optional;
/** Authoritative visibility for every application read entry. Cache never authorizes. */
public interface VisibilityService {
 record View(long postId,long authorId,long publishedAtMs,String body,long version,String status) {}
 Optional<View> read(long viewer,long postId);
 boolean visibleInFeed(long viewer,long postId);
}
