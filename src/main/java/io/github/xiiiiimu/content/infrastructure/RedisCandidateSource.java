package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;

/** Source coverage/readiness must be established by the caller, independently of an empty Redis range. */
public final class RedisCandidateSource implements FeedMerger.Source {
    public RedisCandidateSource(RedisFeedCandidateStore store, long readerId) {}
    @Override public FeedMerger.Batch read(long snapshotTime, FeedCandidate boundary, int limit) {
        throw new UnsupportedOperationException("Redis merge source behavior is not implemented");
    }
}
