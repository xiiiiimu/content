package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import io.lettuce.core.*;
import java.util.*;

/** Controlled maintenance recovery. No SQL transaction spans Redis I/O. */
public final class FeedRecovery implements RecoveryRunner {
 public record Ownership(long generation,long token,long since,long snapshot) {
  public String marker(){return "REBUILD:"+generation+":"+token;}
  public String fence(){return String.format(java.util.Locale.ROOT,"%020d:%020d",generation,token);}
 }
 private final RecoveryMapper db;private final FeedMapper feed;private final TransactionTemplate tx;private final FeedRedis redis;private final PipelineObserver observer;private final long window;
 public FeedRecovery(RecoveryMapper db,FeedMapper feed,TransactionTemplate tx,FeedRedis redis,PipelineObserver observer,long window){this.db=db;this.feed=feed;this.tx=tx;this.redis=redis;this.observer=observer;this.window=window;}
 private static final String BEGIN="""
 local old=redis.call('GET',KEYS[1])
 if old and old>ARGV[1] then return 0 end
 if old==ARGV[1] and redis.call('GET',KEYS[2])~=ARGV[2] then return 0 end
 redis.call('SET',KEYS[1],ARGV[1]);redis.call('SET',KEYS[2],ARGV[2]);redis.call('DEL',KEYS[3]);return 1
 """;
 private static final String DELETE="""
 if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end
 for i=2,#KEYS do redis.call('UNLINK',KEYS[i]) end
 return 1
 """;
 private static final String PUBLISH="""
 if redis.call('GET',KEYS[1])~=ARGV[1] or redis.call('GET',KEYS[2])~=ARGV[2] then return 0 end
 redis.call('SET',KEYS[1],ARGV[3]);redis.call('SET',KEYS[3],ARGV[3]);return 1
 """;
 public Ownership begin(String operator,String reason){
  if(operator==null || operator.isBlank() || operator.length()>255 || reason==null || reason.isBlank() || reason.length()>512)throw new IllegalArgumentException("OS operator and bounded reason required");
  return tx.execute(status->{var c=db.lock();if(c==null || c.generation()<=0)throw new FeedFailure(503,"INVALID_RECOVERY_CONTROL");
   boolean resume=Set.of("MAINTENANCE","REBUILDING").contains(c.state());
   if(!resume && !Set.of("PRIMARY","READY").contains(c.state()))throw new FeedFailure(503,"INVALID_RECOVERY_CONTROL");
   if(resume && db.leased())throw new FeedFailure(409,"RECOVERY_BUSY");
   long generation=resume?c.generation():Math.addExact(c.generation(),1);long token=Math.addExact(c.token(),1);long snapshot=resume&&c.snapshot()!=null?c.snapshot():feed.now();long since=resume&&c.since()!=null?c.since():Math.max(0,snapshot-window);
   if(resume)db.expired(c.generation(),c.token());db.begin(generation,token,since,snapshot,reason);db.audit(generation,token,operator,reason);return new Ownership(generation,token,since,snapshot);
  });
 }
 public boolean renew(Ownership owner){return Boolean.TRUE.equals(tx.execute(status->{db.lock();return db.renew(owner.generation(),owner.token())==1;}));}
 private void requireOwner(Ownership owner){if(!renew(owner))throw new FeedFailure(409,"RECOVERY_FENCED");}
 private void script(String code,String[] keys,String... args){Long result=redis.connection().sync().eval(code,ScriptOutputType.INTEGER,keys,args);if(!Long.valueOf(1).equals(result))throw new FeedFailure(409,"RECOVERY_FENCED");}
 @Override public Report rebuild(String operator,String reason){
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Recovery cannot span a caller SQL transaction");
  long started=System.nanoTime();var owner=begin(operator,reason);long readerCount=0,memberCount=0;
  try{
   if(!"noeviction".equals(redis.connection().sync().configGet("maxmemory-policy").get("maxmemory-policy")))throw new IllegalStateException("Recovery requires Redis noeviction; configure explicitly");
   requireOwner(owner);script(BEGIN,new String[]{"feed:recovery-fence","feed:write-generation","feed:ready-generation"},owner.fence(),owner.marker());
   ScanCursor cursor=ScanCursor.INITIAL;
   do{
    requireOwner(owner);var scanned=redis.connection().sync().scan(cursor,ScanArgs.Builder.matches("feed:v2:*").limit(100));
    var keys=new ArrayList<String>();keys.add("feed:write-generation");
    for(String key:scanned.getKeys())if(key.matches("feed:v2:\\{[1-9][0-9]*\\}:inbox(?::untrusted)?"))keys.add(key);
    script(DELETE,keys.toArray(String[]::new),owner.marker());cursor=scanned;
   }while(!cursor.isFinished());
   observer.afterRecoveryClear(owner.generation(),owner.token());
   var store=new RedisFeedCandidateStore(redis.connection());long lastReader=0;
   while(true){
    requireOwner(owner);var readers=db.readers(lastReader);if(readers.isEmpty())break;
    for(long reader:readers){
     requireOwner(owner);if(db.invalid(reader,owner.since(),owner.snapshot())!=0)throw new FeedFailure(503,"INVALID_PUBLICATION");
     FeedCandidate boundary=null;
     while(true){requireOwner(owner);var rows=candidates(owner,reader,boundary);if(rows.isEmpty())break;
      store.addRecoveryFenced(reader,rows,owner.generation(),owner.token());memberCount+=rows.size();boundary=rows.get(rows.size()-1);observer.afterRecoveryBatch(owner.generation(),owner.token());
     }
     // Compare complete bounded pages, including an empty tail, rather than trusting counts.
     boundary=null;
     while(true){requireOwner(owner);var expected=candidates(owner,reader,boundary);var actual=store.readRange(reader,owner.snapshot(),boundary==null?null:FeedMemberCodec.encode(boundary),100,owner.since()).items();
      if(!expected.equals(actual))throw new IllegalStateException("Recovery coverage comparison failed for reader "+reader);
      if(expected.isEmpty())break;boundary=expected.get(expected.size()-1);
     }
     readerCount++;lastReader=reader;
    }
   }
   requireOwner(owner);observer.beforeRecoveryReady(owner.generation(),owner.token());
   script(PUBLISH,new String[]{"feed:write-generation","feed:recovery-fence","feed:ready-generation"},owner.marker(),owner.fence(),Long.toString(owner.generation()));
   final long readers=readerCount,members=memberCount,duration=(System.nanoTime()-started)/1_000_000;
   tx.executeWithoutResult(status->{db.lock();if(db.ready(owner.generation(),owner.token())!=1)throw new FeedFailure(409,"RECOVERY_FENCED");db.finishAudit(owner.generation(),owner.token(),"DONE",readers,members,duration,null);});
   return new Report(owner.generation(),readers,members,duration);
  }catch(RuntimeException failure){
   final long readers=readerCount,members=memberCount,duration=(System.nanoTime()-started)/1_000_000;String detail=failure.getClass().getSimpleName()+": "+failure.getMessage();String error=detail.substring(0,Math.min(512,detail.length()));
   try{tx.executeWithoutResult(status->{db.lock();if(db.release(owner.generation(),owner.token())==1)db.finishAudit(owner.generation(),owner.token(),"FAILED",readers,members,duration,error);});}catch(RuntimeException audit){failure.addSuppressed(audit);}
   throw failure;
  }
 }
 private List<FeedCandidate> candidates(Ownership owner,long reader,FeedCandidate boundary){return db.candidates(reader,owner.since(),owner.snapshot(),boundary==null?null:boundary.publishedAtMs(),boundary==null?null:boundary.postId());}
}
