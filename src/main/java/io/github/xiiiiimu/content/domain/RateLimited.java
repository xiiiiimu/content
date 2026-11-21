package io.github.xiiiiimu.content.domain;

/** A request over its token-bucket limit (hardening A2): HTTP 429, {@code Retry-After} = {@link #retryAfterSeconds}. */
public final class RateLimited extends RuntimeException {
 public static final String CODE="RATE_LIMITED";
 public final String rule;public final long retryAfterSeconds;
 public RateLimited(String rule,long retryAfterSeconds){super(CODE+":"+rule);this.rule=rule;this.retryAfterSeconds=Math.max(1,retryAfterSeconds);}
}
