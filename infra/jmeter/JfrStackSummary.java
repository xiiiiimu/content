import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
/** Whitelisted frame/class names and durations only; original recording stays private. */
public class JfrStackSummary {
 static final class Stats {long count;double total,max;void add(double d){count++;total+=d;max=Math.max(max,d);}}
 public static void main(String[] args)throws Exception {
  double from=Double.parseDouble(args[1]),until=Double.parseDouble(args[2]);
  Map<String,Stats> stacks=new HashMap<>(),classes=new HashMap<>();long events=0;
  Set<String> types=Set.of("jdk.ExecutionSample","jdk.NativeMethodSample","jdk.JavaMonitorEnter","jdk.ClassLoad","jdk.ThreadPark","jdk.SocketRead","jdk.SafepointBegin","jdk.ExecuteVMOperation","jdk.GCPhasePause");
  try(var timeline=Files.newBufferedWriter(Path.of(args[3]+"-events.tsv"));var input=new RecordingFile(Path.of(args[0]))){
   timeline.write("thread\tstartMs\tendMs\tdurationMs\tstack\n");
   while(input.hasMoreEvents()){
   var event=input.readEvent();String type=event.getEventType().getName();if(!types.contains(type))continue;
   double start=event.getStartTime().toEpochMilli(),duration=event.getDuration().toNanos()/1e6;
   if(start>until || start+duration<from)continue;events++;
   var trace=event.getStackTrace();var names=new ArrayList<String>();
   if(trace!=null)for(var frame:trace.getFrames()){if(names.size()==12)break;var method=frame.getMethod();names.add(method.getType().getName()+"."+method.getName());}
   String key=type+"|"+String.join(" > ",names);
   if(type.equals("jdk.JavaMonitorEnter"))key+="|monitor="+event.getClass("monitorClass").getName();
   if(type.equals("jdk.ExecuteVMOperation"))key+="|"+event.getString("operation");
   stacks.computeIfAbsent(key,k->new Stats()).add(duration);
   var thread=event.getThread();
   if(duration>=100 && thread!=null && thread.getJavaName()!=null && thread.getJavaName().startsWith("commerce-threads"))
    timeline.write(thread.getJavaName()+"\t"+event.getStartTime().toEpochMilli()+"\t"+event.getEndTime().toEpochMilli()+"\t"+duration+"\t"+key+"\n");
   if(type.equals("jdk.ClassLoad")){String name=event.getClass("loadedClass").getName();classes.computeIfAbsent(name,k->new Stats()).add(duration);}
  }}
  write(Path.of(args[3]+"-stacks.tsv"),stacks);write(Path.of(args[3]+"-classes.tsv"),classes);
  System.out.println("events="+events+" stackGroups="+stacks.size()+" loadedClasses="+classes.size());
 }
 static void write(Path path,Map<String,Stats> values)throws Exception {
  var rows=new ArrayList<>(values.entrySet());rows.sort((a,b)->Double.compare(b.getValue().total,a.getValue().total));
  try(var out=Files.newBufferedWriter(path)){out.write("count\ttotalMs\tmaxMs\tstackOrClass\n");for(var row:rows){var s=row.getValue();out.write(s.count+"\t"+s.total+"\t"+s.max+"\t"+row.getKey()+"\n");}}
 }
}
