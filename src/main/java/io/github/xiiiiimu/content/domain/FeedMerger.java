package io.github.xiiiiimu.content.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** Internal continuation only: not an authenticated client cursor. */
public final class FeedMerger {
    public enum PageState { HAS_MORE, UNKNOWN, EXHAUSTED }
    public record Continuation(long snapshotTime, FeedCandidate globalBoundary, FeedCandidate consumedBoundary) {}
    public record Batch(List<FeedCandidate> items, boolean exhausted) {
        public Batch { items=List.copyOf(items); }
    }
    public record Decision(FeedCandidate canonical, boolean visible) {}
    @FunctionalInterface public interface Source {
        Batch read(long snapshotTime, FeedCandidate exclusiveBoundary, int limit) throws Exception;
    }
    @FunctionalInterface public interface Authority {
        Decision check(FeedCandidate candidate) throws Exception;
    }
    public record Budget(int batchSize, int maxScanned, int maxReads, int maxChecks, Duration timeout) {
        public static Budget defaults() { return new Budget(32,256,64,256,Duration.ofSeconds(2)); }
    }
    public record Page(List<FeedCandidate> items, Continuation next, PageState pageState,
                       int scanned, int fetched, int sourceReads, int checks,
                       Map<String,FeedCandidate> sourcePositions) {}
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(String message, Throwable cause) { super(message,cause); }
    }
    public FeedMerger(ExecutorService executor) {}
    public Page read(Map<String,Source> sources, Authority authority, Continuation input, int pageSize, Budget budget) {
        throw new UnsupportedOperationException("Ordered merge behavior is not implemented");
    }
}
