package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;

/** Adapter only. Composition and connection lifecycle belong to its caller. */
public final class RedisFeedCandidateStore {
    public static final int MAX_READ_SIZE=200;
    public static final int MAX_WRITE_SIZE=200;
    public RedisFeedCandidateStore(StatefulRedisConnection<String,String> connection) {}
    public void add(long readerId,List<FeedCandidate> candidates) { throw new UnsupportedOperationException("Feed candidate storage is not implemented"); }
    public CandidateSlice read(long readerId,long snapshotMs,String exclusiveBoundary,int limit) { throw new UnsupportedOperationException("Feed candidate reading is not implemented"); }
}
