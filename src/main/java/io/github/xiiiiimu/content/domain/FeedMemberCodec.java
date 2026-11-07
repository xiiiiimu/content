package io.github.xiiiiimu.content.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class FeedMemberCodec {
    public static final long MAX_TIME_MS=9_999_999_999_999L;
    private static final Pattern FORMAT=Pattern.compile("[0-9]{13}:[0-9]{20}");
    private FeedMemberCodec() {}
    public static String encode(FeedCandidate candidate) {
        if(candidate==null || candidate.publishedAtMs()<0 || candidate.publishedAtMs()>MAX_TIME_MS || candidate.postId()<=0)
            throw new IllegalArgumentException("Candidate requires a UTC millisecond in range and a positive BIGINT id");
        return String.format(Locale.ROOT,"%013d:%020d",candidate.publishedAtMs(),candidate.postId());
    }
    public static FeedCandidate decode(String member) {
        if(member==null || member.length()!=34 || !FORMAT.matcher(member).matches())
            throw new IllegalArgumentException("Feed member must be 34 canonical ASCII characters");
        try {
            long time=Long.parseLong(member.substring(0,13));
            long id=Long.parseLong(member.substring(14));
            if(id<=0)throw new IllegalArgumentException("Post id must be positive");
            return new FeedCandidate(time,id);
        }catch(NumberFormatException e){throw new IllegalArgumentException("Feed member id exceeds positive BIGINT",e);}
    }
}
