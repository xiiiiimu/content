package io.github.xiiiiimu.content.domain;

public final class FeedMemberCodec {
    public static final long MAX_TIME_MS=9_999_999_999_999L;
    private FeedMemberCodec() {}
    public static String encode(FeedCandidate candidate) { throw new UnsupportedOperationException("Feed member encoding is not implemented"); }
    public static FeedCandidate decode(String member) { throw new UnsupportedOperationException("Feed member decoding is not implemented"); }
}
