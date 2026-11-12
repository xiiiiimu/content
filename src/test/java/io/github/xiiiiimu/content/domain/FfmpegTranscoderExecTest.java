package io.github.xiiiiimu.content.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Wall-clock kill for hung child processes (stdout may stay open). */
class FfmpegTranscoderExecTest {
 @Test @EnabledOnOs(OS.WINDOWS)
 void waitForDestroysHungWindowsProcess(){
  long start=System.nanoTime();
  assertThatThrownBy(()->FfmpegTranscoder.exec(List.of("cmd.exe","/c","ping -n 60 127.0.0.1 >nul"),Duration.ofSeconds(2)))
   .isInstanceOf(IllegalStateException.class)
   .hasMessageContaining("timeout");
  assertThat(Duration.ofNanos(System.nanoTime()-start).toSeconds()).isLessThan(15);
 }

 @Test @EnabledOnOs({OS.LINUX,OS.MAC})
 void waitForDestroysHungUnixProcess(){
  long start=System.nanoTime();
  assertThatThrownBy(()->FfmpegTranscoder.exec(List.of("sleep","60"),Duration.ofSeconds(2)))
   .isInstanceOf(IllegalStateException.class)
   .hasMessageContaining("timeout");
  assertThat(Duration.ofNanos(System.nanoTime()-start).toSeconds()).isLessThan(15);
 }
}
