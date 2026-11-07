package io.github.xiiiiimu.content.domain;

/** Lifecycle observations after committed claim and after a successful external ACK. */
public interface RelayObserver {
    default void afterClaim(RelayLease lease) {}
    default void afterAck(RelayLease lease) {}
}
