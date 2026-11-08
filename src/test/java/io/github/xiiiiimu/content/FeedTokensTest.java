package io.github.xiiiiimu.content;
import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FeedTokensTest {
 static final String KEY="unit-secret-signing-key-at-least-32-bytes";
 @Test void roundTripAndPurposeSeparation(){
  var t=new FeedTokens(KEY,"CURSOR");var value=new FeedTokens.Identity(1,9,1,2);var token=t.sign(value);
  assertThat(t.verify(token,FeedTokens.Identity.class)).isEqualTo(value);
  assertThatThrownBy(()->new FeedTokens(KEY,"IDENTITY").verify(token,FeedTokens.Identity.class)).isInstanceOf(FeedFailure.class);
 }
 @Test void invalidEncodingAndOversizeCannotPassMac(){
  var t=new FeedTokens(KEY,"CURSOR");for(var bad:new String[]{"","a.b.c","%bad.x","a..","a.b=","x".repeat(4097)})assertThatThrownBy(()->t.verify(bad,FeedTokens.Identity.class)).isInstanceOf(FeedFailure.class);
 }
 @Test void weakKeyIsRejected(){assertThatThrownBy(()->new FeedTokens("short","CURSOR")).isInstanceOf(IllegalArgumentException.class);}
}
