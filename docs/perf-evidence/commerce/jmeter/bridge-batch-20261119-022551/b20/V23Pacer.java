import java.util.concurrent.locks.LockSupport;
/** Windows-safe bounded pacing. No catch-up bursts or global timer changes. */
public final class V23Pacer {
  public static void waitUntil(long due) {
    for(long remaining;(remaining=due-System.nanoTime())>0;) {
      // Default Windows park can take a full 15.6ms tick. Leave that much
      // headroom, then spin on ONE serialized coordinator (not every worker).
      if(remaining>16_500_000L) LockSupport.parkNanos(remaining-16_000_000L);
      else Thread.onSpinWait();
    }
  }
}
