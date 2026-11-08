package io.github.xiiiiimu.content.domain;
/** Monitoring seams. Default production implementation does nothing; tests use deterministic barriers. */
public interface PipelineObserver {
 default void beforeInboxCommit(long eventId) {}
 default void afterInboxCommit(long eventId) {}
 default void afterClaim(DeliveryLease lease) {}
 default void afterRelations(DeliveryLease lease,java.util.List<DistributionWorker.Write> writes) {}
 default void beforeRedis(DeliveryLease lease) {}
 default void afterRedis(DeliveryLease lease) {}
 default void beforeProgress(DeliveryLease lease) {}
 default void afterRecoveryClear(long generation,long token) {}
 default void afterRecoveryBatch(long generation,long token) {}
 default void beforeRecoveryReady(long generation,long token) {}
 default void beforeFeedAuthority(long reader,FeedCandidate candidate) {}
 default void beforeFeedReturn(long reader) {}
 default void beforeCacheInvalidate(long postId) {}
}
