package io.github.xiiiiimu.content.domain;

import java.util.Optional;

/** Persistent lease protocol; implementations must commit before returning a claim. */
public interface RelayStore {
    Optional<RelayLease> claim();
    boolean sent(RelayLease lease);
    boolean failed(RelayLease lease, String error);
    int reapExhausted();
    boolean replay(long eventId, String operator, String reason);
}
