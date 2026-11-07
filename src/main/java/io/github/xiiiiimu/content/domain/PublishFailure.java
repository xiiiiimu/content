package io.github.xiiiiimu.content.domain;

public class PublishFailure extends RuntimeException {
    public enum Reason {
        AUTHENTICATION_REQUIRED, INVALID_REQUEST, POST_NOT_FOUND, POST_FORBIDDEN,
        IDEMPOTENCY_CONFLICT, POST_STATE_CONFLICT, POST_VERSION_CONFLICT
    }
    private final Reason reason;
    public PublishFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
