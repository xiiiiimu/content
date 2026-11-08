package io.github.xiiiiimu.content.domain;
import io.github.xiiiiimu.content.infrastructure.*;
/** Low-priority idempotent cleanup. Its in-memory position is not a delivery or recovery watermark. */
public final class FeedTrimmer {
 private final FeedMapper db;private final FeedRedis redis;private final long window;private long reader;
 public FeedTrimmer(FeedMapper db,FeedRedis redis,long window){this.db=db;this.redis=redis;this.window=window;}
 public synchronized void tick(){var c=db.control();if(c==null || !c.state().equals("READY"))return;long since=Math.max(0,db.now()-window);var readers=db.readerBatch(reader);
  if(readers.isEmpty()){reader=0;return;}
  var index=new RedisFeedCandidateStore(redis.connection());for(long id:readers){index.trimFenced(id,since,100,c.generation());reader=id;}
 }
}
