package io.github.xiiiiimu.content.domain;
/** Local operator boundary; implementing service must persist maintenance and fence old writers. */
public interface RecoveryRunner {
 record Report(long generation,long readers,long members,long durationMillis) {}
 Report rebuild(String operator,String reason);
}
