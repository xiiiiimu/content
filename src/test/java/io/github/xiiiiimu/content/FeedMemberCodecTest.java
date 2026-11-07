package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Locale;
import static org.assertj.core.api.Assertions.*;

class FeedMemberCodecTest {
    @Test void canonicalAsciiEncodingAndLongLimits() {
        assertThat(FeedMemberCodec.encode(new FeedCandidate(0,1))).isEqualTo("0000000000000:00000000000000000001");
        String max="9999999999999:09223372036854775807";
        assertThat(FeedMemberCodec.encode(new FeedCandidate(FeedMemberCodec.MAX_TIME_MS,Long.MAX_VALUE))).isEqualTo(max);
        assertThat(FeedMemberCodec.decode(max)).isEqualTo(new FeedCandidate(FeedMemberCodec.MAX_TIME_MS,Long.MAX_VALUE));
    }
    @Test void localeDoesNotChangeWireDigits() {
        var previous=Locale.getDefault();
        try {Locale.setDefault(Locale.forLanguageTag("ar-EG"));assertThat(FeedMemberCodec.encode(new FeedCandidate(12,9))).isEqualTo("0000000000012:00000000000000000009");}
        finally {Locale.setDefault(previous);}
    }
    @Test void invalidNumericInputsAreRejected() {
        for(var value:new FeedCandidate[]{new FeedCandidate(-1,1),new FeedCandidate(10_000_000_000_000L,1),new FeedCandidate(1,0),new FeedCandidate(1,-1),null})
            assertThatThrownBy(() -> FeedMemberCodec.encode(value)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={""," ","000000000000:00000000000000000001","00000000000000:00000000000000000001",
            "0000000000000-00000000000000000001","0000000000000:00000000000000000000","0000000000000:09999999999999999999",
            "0000000000000:09223372036854775808","0000000000000:0000000000000000000x","0000000000000:0000000000000000000９",
            "0000000000000:00000000000000000001\n","+000000000000:00000000000000000001"})
    void rejectsNonCanonicalMembers(String member) {
        assertThatThrownBy(() -> FeedMemberCodec.decode(member)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsNullMember() {assertThatThrownBy(() -> FeedMemberCodec.decode(null)).isInstanceOf(IllegalArgumentException.class);}
}
