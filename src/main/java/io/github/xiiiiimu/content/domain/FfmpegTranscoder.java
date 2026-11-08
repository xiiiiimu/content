package io.github.xiiiiimu.content.domain;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs real FFmpeg via Docker image. Host ffmpeg binary is optional and not required. */
public final class FfmpegTranscoder {
 public record Result(byte[] video,byte[] cover,String metadataJson) {}
 private final String image;
 public FfmpegTranscoder(String image){if(image==null || image.isBlank())throw new IllegalArgumentException("ffmpeg image required");this.image=image;}
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
   run(dir,"-y","-i",in.getFileName().toString(),"-t","3","-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac","-movflags","+faststart",out.getFileName().toString());
   run(dir,"-y","-i",out.getFileName().toString(),"-frames:v","1","-q:v","2",cover.getFileName().toString());
   String json=probe(dir,out.getFileName().toString());
   byte[] video=Files.readAllBytes(out);byte[] thumb=Files.readAllBytes(cover);
   if(video.length==0 || thumb.length==0 || !json.contains("\"format\""))throw new IllegalStateException("ffmpeg artifacts incomplete");
   if(!json.contains("streams"))throw new IllegalStateException("ffprobe metadata missing streams");
   return new Result(video,thumb,json);
  }catch(Exception e){throw new IllegalStateException("ffmpeg failed: "+e.getMessage(),e);}
  finally{cleanup(dir);}
 }
 private static String dockerBin(){
  String override=System.getenv("DOCKER_BIN");if(override!=null && !override.isBlank())return override;
  Path win=Path.of("C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe");
  if(Files.isExecutable(win) || Files.exists(win))return win.toString();
  return "docker";
 }
 private void run(Path dir,String... args)throws Exception{
  List<String> cmd=new ArrayList<>();cmd.add(dockerBin());cmd.add("run");cmd.add("--rm");cmd.add("-v");cmd.add(dir.toAbsolutePath()+":/work");cmd.add("-w");cmd.add("/work");cmd.add("--entrypoint");cmd.add("ffmpeg");cmd.add(image);cmd.addAll(List.of(args));
  exec(cmd,Duration.ofMinutes(2));
 }
 private String probe(Path dir,String input)throws Exception{
  List<String> cmd=List.of(dockerBin(),"run","--rm","-v",dir.toAbsolutePath()+":/work","-w","/work","--entrypoint","ffprobe",image,"-v","quiet","-print_format","json","-show_format","-show_streams",input);
  ProcessBuilder pb=new ProcessBuilder(cmd);pb.redirectErrorStream(true);Process p=pb.start();
  byte[] out=p.getInputStream().readAllBytes();
  if(!p.waitFor(2,TimeUnit.MINUTES)){p.destroyForcibly();throw new IllegalStateException("ffprobe timeout");}
  if(p.exitValue()!=0)throw new IllegalStateException("ffprobe exit="+p.exitValue()+" "+new String(out,StandardCharsets.UTF_8));
  return new String(out,StandardCharsets.UTF_8);
 }
 private static void exec(List<String> cmd,Duration timeout)throws Exception{
  ProcessBuilder pb=new ProcessBuilder(cmd);pb.redirectErrorStream(true);Process p=pb.start();
  byte[] out=p.getInputStream().readAllBytes();
  if(!p.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){p.destroyForcibly();throw new IllegalStateException("timeout "+cmd);}
  if(p.exitValue()!=0)throw new IllegalStateException("exit="+p.exitValue()+" cmd="+cmd+" out="+new String(out,StandardCharsets.UTF_8));
 }
 static boolean looksLikeMedia(byte[] raw){
  if(raw.length<12)return false;
  String head=new String(raw,4,Math.min(8,raw.length-4),StandardCharsets.ISO_8859_1);
  return head.contains("ftyp") || (raw[0]==0x1A && raw[1]==0x45) || (raw[0]=='I' && raw[1]=='D' && raw[2]=='3');
 }
 private static void cleanup(Path dir){if(dir==null)return;try{try(var s=Files.walk(dir)){s.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}catch(Exception ignored){}}
}
