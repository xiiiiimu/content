package io.github.xiiiiimu.content.domain;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Runs real FFmpeg via Docker image. Host ffmpeg binary is optional and not required. */
public final class FfmpegTranscoder {
 public record Result(byte[] video,byte[] cover,String metadataJson) {}
 private static final Duration DEFAULT_TIMEOUT=Duration.ofMinutes(2);
 private static final Duration REMOVE_TIMEOUT=Duration.ofSeconds(30);
 static final String CONTAINER_LABEL="io.github.xiiiiimu.content.ffmpeg=1";
 /** The child process did not finish within its wall-clock timeout. */
 static final class TimeoutFailure extends IllegalStateException{TimeoutFailure(String m){super(m);}}
 private final String image;private final Duration timeout;
 public FfmpegTranscoder(String image){this(image,DEFAULT_TIMEOUT);}
 FfmpegTranscoder(String image,Duration timeout){if(image==null || image.isBlank())throw new IllegalArgumentException("ffmpeg image required");this.image=image;this.timeout=timeout;}
 /** Fixture helper: generate a tiny real MP4 (ftyp) for upload tests. */
 public byte[] synthesizeFixture(){
  Path dir=null;
  try{
   dir=Files.createTempDirectory("content-ffmpeg-fix-");
   Path out=dir.resolve("fixture.mp4");
   run(dir,"-y","-f","lavfi","-i","testsrc=size=160x120:rate=10","-f","lavfi","-i","sine=frequency=880:sample_rate=44100","-t","1","-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac","-shortest",out.getFileName().toString());
   byte[] bytes=Files.readAllBytes(out);if(!looksLikeMedia(bytes))throw new IllegalStateException("fixture missing ftyp");return bytes;
  }catch(Exception e){throw new IllegalStateException("ffmpeg fixture failed: "+e.getMessage(),e);}
  finally{cleanup(dir);}
 }
 /** Transcode an uploaded media object. Non-media payloads fail — they are not replaced by lavfi. */
 public Result transcode(byte[] raw){
  if(raw==null || raw.length==0)throw new IllegalStateException("empty media");
  if(!looksLikeMedia(raw))throw new IllegalStateException("input is not a recognized media container");
  Path dir=null;
  try{
   dir=Files.createTempDirectory("content-ffmpeg-");
   Path in=dir.resolve("input.bin");Path out=dir.resolve("out.mp4");Path cover=dir.resolve("cover.jpg");
   Files.write(in,raw);
   // Keep source duration (material timestamp comments need real offsets; short fixtures stay short).
   run(dir,"-y","-i",in.getFileName().toString(),"-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac","-movflags","+faststart",out.getFileName().toString());
   run(dir,"-y","-i",out.getFileName().toString(),"-frames:v","1","-q:v","2",cover.getFileName().toString());
   String json=probe(dir,out.getFileName().toString());
   byte[] video=Files.readAllBytes(out);byte[] thumb=Files.readAllBytes(cover);
   if(video.length==0 || thumb.length==0 || !json.contains("\"format\""))throw new IllegalStateException("ffmpeg artifacts incomplete");
   if(!json.contains("streams"))throw new IllegalStateException("ffprobe metadata missing streams");
   return new Result(video,thumb,json);
  }catch(Exception e){throw new IllegalStateException("ffmpeg failed: "+e.getMessage(),e);}
  finally{cleanup(dir);}
 }
 /** Re-probe derived bytes to prove the stored object is still a decodable media container. */
 public String verifyDecodable(byte[] derived){
  if(derived==null || derived.length==0)throw new IllegalStateException("empty derived media");
  Path dir=null;
  try{
   dir=Files.createTempDirectory("content-ffmpeg-verify-");
   Path in=dir.resolve("check.bin");Files.write(in,derived);
   String json=probe(dir,in.getFileName().toString());
   if(!json.contains("\"format\"") || !json.contains("streams"))throw new IllegalStateException("derived media not probeable");
   return json;
  }catch(Exception e){throw new IllegalStateException("verifyDecodable failed: "+e.getMessage(),e);}
  finally{cleanup(dir);}
 }
 static String dockerBin(){
  String override=System.getenv("DOCKER_BIN");if(override!=null && !override.isBlank())return override;
  Path win=Path.of("C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe");
  if(Files.isExecutable(win) || Files.exists(win))return win.toString();
  return "docker";
 }
 void run(Path dir,String... args)throws Exception{
  String name=containerName();
  List<String> cmd=new ArrayList<>(dockerRun(dir,"ffmpeg",name));cmd.addAll(List.of(args));
  execContainer(cmd,name);
 }
 private String probe(Path dir,String input)throws Exception{
  String name=containerName();
  List<String> cmd=new ArrayList<>(dockerRun(dir,"ffprobe",name));cmd.addAll(List.of("-v","quiet","-print_format","json","-show_format","-show_streams",input));
  return new String(execContainer(cmd,name),StandardCharsets.UTF_8);
 }
 /** Every run gets a unique name and label so a timed-out container can be stopped and removed by name. */
 private List<String> dockerRun(Path dir,String entrypoint,String name){
  return List.of(dockerBin(),"run","--rm","--name",name,"--label",CONTAINER_LABEL,"-v",dir.toAbsolutePath()+":/work","-w","/work","--entrypoint",entrypoint,image);
 }
 static String containerName(){return "content-ffmpeg-"+UUID.randomUUID();}
 /**
  * Killing the local docker CLI on timeout does not stop the container ({@code --rm} only removes it once it exits),
  * so on timeout or interrupt the container is force-removed by name. A removal failure is attached as suppressed.
  */
 private byte[] execContainer(List<String> cmd,String name)throws Exception{
  try{return exec(cmd,timeout);}
  catch(TimeoutFailure|InterruptedException e){
   boolean interrupted=Thread.interrupted();
   try{exec(List.of(dockerBin(),"rm","-f",name),REMOVE_TIMEOUT);}
   catch(Exception removal){e.addSuppressed(removal);}
   finally{if(interrupted || e instanceof InterruptedException)Thread.currentThread().interrupt();}
   throw e;
  }
 }
 /**
  * Wait with a hard wall-clock timeout. Stdout is drained on a daemon thread so a hung child that
  * keeps the pipe open cannot block forever before waitFor runs; destroyForcibly on timeout.
  */
 static byte[] exec(List<String> cmd,Duration timeout)throws Exception{
  ProcessBuilder pb=new ProcessBuilder(cmd);pb.redirectErrorStream(true);Process p=pb.start();
  ByteArrayOutputStream buf=new ByteArrayOutputStream();
  Thread reader=new Thread(()->{
   try(InputStream in=p.getInputStream()){in.transferTo(buf);}catch(Exception ignored){/* process killed or closed */}
  },"content-ffmpeg-out");
  reader.setDaemon(true);reader.start();
  boolean finished;
  try{finished=p.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);}
  catch(InterruptedException e){p.destroyForcibly();throw e;}
  if(!finished){
   p.destroyForcibly();
   reader.join(TimeUnit.SECONDS.toMillis(5));
   throw new TimeoutFailure("timeout "+cmd);
  }
  reader.join(TimeUnit.SECONDS.toMillis(5));
  if(p.exitValue()!=0)throw new IllegalStateException("exit="+p.exitValue()+" cmd="+cmd+" out="+buf.toString(StandardCharsets.UTF_8));
  return buf.toByteArray();
 }
 static boolean looksLikeMedia(byte[] raw){
  if(raw.length<12)return false;
  String head=new String(raw,4,Math.min(8,raw.length-4),StandardCharsets.ISO_8859_1);
  return head.contains("ftyp") || (raw[0]==0x1A && raw[1]==0x45) || (raw[0]=='I' && raw[1]=='D' && raw[2]=='3');
 }
 private static void cleanup(Path dir){if(dir==null)return;try{try(var s=Files.walk(dir)){s.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}catch(Exception ignored){}}
}
