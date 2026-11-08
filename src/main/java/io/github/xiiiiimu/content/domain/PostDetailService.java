package io.github.xiiiiimu.content.domain;
/** Detail reads may use Redis as a content cache only; authority always re-checks MySQL. */
public interface PostDetailService {
 VisibilityService.View get(long viewer,long postId);
 Long cachedVersion(long postId);
 void invalidate(long postId);
}
