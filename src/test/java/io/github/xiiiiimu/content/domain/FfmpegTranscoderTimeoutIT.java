package io.github.xiiiiimu.content.domain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * D2: a timed-out FFmpeg run must not leave its container behind. Killing the local docker CLI does not stop the
 * container ({@code --rm} only removes it after it exits), so the transcoder has to stop and remove it itself.
 */
class FfmpegTranscoderTimeoutIT {
 static final String IMAGE="linuxserver/ffmpeg:version-7.1-cli";

 @BeforeAll static void pullImage()throws Exception{
  // Pull outside the measured run so the short timeout below cannot fire during an image pull.
  FfmpegTranscoder.exec(List.of(FfmpegTranscoder.dockerBin(),"pull","-q",IMAGE),Duration.ofMinutes(10));
 }

 @Test void timeoutStopsAndRemovesTheContainer()throws Exception{
  Set<String> before=containers();
  Path dir=Files.createTempDirectory("content-ffmpeg-timeout-");
  var transcoder=new FfmpegTranscoder(IMAGE,Duration.ofSeconds(20));
  ExecutorService pool=Executors.newSingleThreadExecutor();
  try{
   // -re reads the synthetic input in real time, so this run lasts ~600 s unless something stops it.
   Future<?> hung=pool.submit(()->{transcoder.run(dir,"-re","-f","lavfi","-i","testsrc=size=64x64:rate=5","-t","600","-f","null","-");return null;});
   Set<String> started=Set.of();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(18);
   while(started.isEmpty() && System.nanoTime()<deadline){started=diff(containers(),before);if(started.isEmpty())Thread.sleep(250);}
   assertThat(started).as("the hung ffmpeg container must be observed running before the timeout").isNotEmpty();

   assertThatThrownBy(()->hung.get(60,TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class).rootCause().hasMessageContaining("timeout");

   Set<String> left=diff(containers(),before);
   long settle=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(!left.isEmpty() && System.nanoTime()<settle){Thread.sleep(500);left=diff(containers(),before);}
   assertThat(left).as("containers left after timeout (docker ps -a, ancestor="+IMAGE+")").isEmpty();
  }finally{
   pool.shutdownNow();
   for(String id:diff(containers(),before))FfmpegTranscoder.exec(List.of(FfmpegTranscoder.dockerBin(),"rm","-f",id),Duration.ofSeconds(30));
   try(var s=Files.walk(dir)){s.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());}
  }
 }

 /** Written together with the fix (no separate RED): an interrupted worker must not leave the container either. */
 @Test void interruptStopsAndRemovesTheContainer()throws Exception{
  Set<String> before=containers();
  Path dir=Files.createTempDirectory("content-ffmpeg-interrupt-");
  var transcoder=new FfmpegTranscoder(IMAGE,Duration.ofMinutes(5));
  ExecutorService pool=Executors.newSingleThreadExecutor();
  try{
   Future<?> hung=pool.submit(()->{transcoder.run(dir,"-re","-f","lavfi","-i","testsrc=size=64x64:rate=5","-t","600","-f","null","-");return null;});
   Set<String> started=Set.of();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
   while(started.isEmpty() && System.nanoTime()<deadline){started=diff(containers(),before);if(started.isEmpty())Thread.sleep(250);}
   assertThat(started).as("the hung ffmpeg container must be observed running before the interrupt").isNotEmpty();
   pool.shutdownNow();
   assertThat(pool.awaitTermination(60,TimeUnit.SECONDS)).as("worker finished after interrupt").isTrue();
   assertThat(hung.isDone()).isTrue();
   Set<String> left=diff(containers(),before);
   long settle=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(!left.isEmpty() && System.nanoTime()<settle){Thread.sleep(500);left=diff(containers(),before);}
   assertThat(left).as("containers left after interrupt").isEmpty();
  }finally{
   pool.shutdownNow();
   for(String id:diff(containers(),before))FfmpegTranscoder.exec(List.of(FfmpegTranscoder.dockerBin(),"rm","-f",id),Duration.ofSeconds(30));
   try(var s=Files.walk(dir)){s.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());}
  }
 }

 static Set<String> containers()throws Exception{
  String out=new String(FfmpegTranscoder.exec(List.of(FfmpegTranscoder.dockerBin(),"ps","-a","--no-trunc","--filter","ancestor="+IMAGE,"--format","{{.ID}}"),Duration.ofSeconds(30)),StandardCharsets.UTF_8);
  Set<String> ids=new TreeSet<>();
  for(String line:out.split("\\R"))if(!line.isBlank())ids.add(line.trim());
  return ids;
 }
 static Set<String> diff(Set<String> now,Set<String> before){Set<String> d=new TreeSet<>(now);d.removeAll(before);return d;}
}
