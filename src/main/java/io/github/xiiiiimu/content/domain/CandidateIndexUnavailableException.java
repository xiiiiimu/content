package io.github.xiiiiimu.content.domain;

/** Caller must retry or use an authoritative source; never translate to exhaustion. */
public class CandidateIndexUnavailableException extends RuntimeException {
    public enum Reason { REDIS_UNAVAILABLE, UNTRUSTED_INDEX }
    private final Reason reason;
    public CandidateIndexUnavailableException(Reason reason, String message, Throwable cause) {
        super(message,cause); this.reason=reason;
    }
    public Reason reason() { return reason; }
}
