// Invoked before the SAME HTTP sampler for warmup and measurement. No credentials logged.
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.LockSupport
import groovy.json.JsonOutput

def shared
synchronized(props) {
  shared = props.get('v23.shared')
  if (shared == null) {
    def readRows = { path ->
      new File(path).readLines('UTF-8').drop(1).findAll{ it.trim() }.collect { line ->
        def a=line.split(',').collect{it.trim().replaceAll('^"|"$', '')}
        if(a.size()!=3) throw new IllegalArgumentException('Expected userId,token,clientKey CSV')
        a
      }
    }
    int workers=Integer.parseInt(props.getProperty('workers'))
    shared=[warm:readRows(props.getProperty('warmCsv')), formal:readRows(props.getProperty('formalCsv')),
      barrier:new CyclicBarrier(workers), slot:new AtomicInteger(), lock:new Object(),
      next:new AtomicLong(), start:new AtomicLong(), startNano:new AtomicLong(),pacer:Class.forName('V23Pacer')]
    props.put('v23.shared',shared)
  }
}
vars.put('runHttp','true')
int thread=ctx.getThreadNum()
def row
if(vars.get('v23.warmed')==null) {
  row=shared.warm[thread]
  vars.put('campaign',props.getProperty('warmCampaign'))
  vars.put('phaseLabel','WARMUP-purchase')
  vars.put('v23.warmed','true')
} else {
  if(vars.get('v23.joined')==null) {
    shared.barrier.await(120,TimeUnit.SECONDS)
    synchronized(shared.lock) {
      if(shared.start.get()==0L) {
        long start=System.currentTimeMillis()+2000L
        long nano=System.nanoTime()+2_000_000_000L
        shared.start.set(start);shared.startNano.set(nano);shared.next.set(nano)
        long window=Long.parseLong(props.getProperty('windowSec','0'))*1000L
        new File(props.getProperty('windowPath')).text=JsonOutput.toJson([
          startEpochMs:start,endEpochMs:window>0?start+window:null,
          targetRate:Integer.parseInt(props.getProperty('rate')), workers:Integer.parseInt(props.getProperty('workers')),
          sameJvm:true,sameHttpSamplerAndThreads:true,deadlinePolicy:'No catch-up; serialized minimum-spaced permits'])
      }
    }
    vars.put('v23.joined','true')
  }
  int slot=shared.slot.getAndIncrement()
  if(slot>=Integer.parseInt(props.getProperty('count'))) {
    vars.put('runHttp','false');vars.put('continue','false');SampleResult.setIgnore();return
  }
  row=shared.formal[slot]
  long interval=(long)Math.ceil(1_000_000_000d/Integer.parseInt(props.getProperty('rate')))
  synchronized(shared.lock) {
    long due=shared.next.get()
    shared.pacer.waitUntil(due)
    long now=System.nanoTime()
    shared.next.set(now+interval)
    vars.put('permitEpochMs',Long.toString(System.currentTimeMillis()))
  }
  long windowMs=Long.parseLong(props.getProperty('windowSec','0'))*1000L
  if(windowMs>0 && System.currentTimeMillis()>=shared.start.get()+windowMs) {
    vars.put('runHttp','false');vars.put('continue','false');SampleResult.setIgnore();return
  }
  vars.put('phaseLabel','POST-purchase')
  vars.put('campaign',props.getProperty('formalCampaign'))
}
vars.put('userId',row[0]);vars.put('token',row[1]);vars.put('effClientKey',row[2])
vars.put('reservationId','');vars.put('businessCode','')
SampleResult.setIgnore()
