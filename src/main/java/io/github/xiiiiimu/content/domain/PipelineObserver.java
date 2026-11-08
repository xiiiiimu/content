package io.github.xiiiiimu.content.domain;
/** Monitoring seams. Default production implementation does nothing; tests use deterministic barriers. */
public interface PipelineObserver {
 default void beforeInboxCommit(long eventId) {}
 default void afterInboxCommit(long eventId) {}
}
