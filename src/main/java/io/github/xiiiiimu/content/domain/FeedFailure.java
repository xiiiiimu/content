package io.github.xiiiiimu.content.domain;
public final class FeedFailure extends RuntimeException {
 public final String code;public final int status;
 public FeedFailure(int status,String code){super(code);this.status=status;this.code=code;}
}
