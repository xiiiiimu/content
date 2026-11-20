public class PrecisionProbe {
 public static void main(String[] a){ long start=System.nanoTime(),next=start,last=start; for(int i=0;i<400;i++){V23Pacer.waitUntil(next);last=System.nanoTime();next=last+2500000L;} System.out.printf("{\"permits\":400,\"spanMs\":%.3f,\"rate\":%.3f}%n",(last-start)/1e6,399e9/(last-start));}
}
