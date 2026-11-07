package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;

/** Source coverage/readiness must be established by the caller, independently of an empty Redis range. */
public final class RedisCandidateSource implements FeedMerger.Source {
    private final RedisFeedCandidateStore store;
    private final long readerId;
    public RedisCandidateSource(RedisFeedCandidateStore store, long readerId) {
        this.store=java.util.Objects.requireNonNull(store);
        if(readerId<=0)throw new IllegalArgumentException("Reader id must be positive");
        this.readerId=readerId;
    }
    @Override public FeedMerger.Batch read(long snapshotTime, FeedCandidate boundary, int limit) {
        var slice=store.read(readerId,snapshotTime,boundary==null?null:FeedMemberCodec.encode(boundary),limit);
        return new FeedMerger.Batch(slice.items(),slice.items().size()<limit);
    }
}
