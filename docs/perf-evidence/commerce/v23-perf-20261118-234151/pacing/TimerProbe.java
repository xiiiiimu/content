import java.util.concurrent.locks.LockSupport;
public class TimerProbe {
 public static void main(String[] a) {
  long start=System.nanoTime(),next=start;long[] gaps=new long[399];long last=start;
  for(int i=0;i<400;i++) {
   long due=next;
   while(System.nanoTime()<due){long remain=due-System.nanoTime();if(remain>1000000L)LockSupport.parkNanos(remain-500000L);else Thread.onSpinWait();}
   long n=System.nanoTime();if(i>0)gaps[i-1]=n-last;last=n;next=n+2500000L;
  }
  java.util.Arrays.sort(gaps);
  System.out.printf("{\"permits\":400,\"spanMs\":%.3f,\"rate\":%.3f,\"gapP50Ms\":%.3f,\"gapP95Ms\":%.3f}%n",(last-start)/1e6,399e9/(last-start),gaps[199]/1e6,gaps[379]/1e6);
 }
}
