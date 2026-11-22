package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.RetentionMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hardening C2 R-FOLLOW (C-3): follow_history rows of follow epochs that can never authorise a read again. Not time based:
 * rows of the current FOLLOWING epoch are visibility data and are never deleted.
 *
 * <p>An epoch is dead when the relationship has a later epoch or is no longer FOLLOWING; re-following always bumps the epoch
 * ({@link RelationshipService#change}), and every reader joins follows on the current epoch and FOLLOWING, so dead rows are
 * already invisible.
 *
 * <p>The table has no time column, so the job walks the primary key in windows of {@code batchSize}, keeping its position in
 * memory between runs (like {@link FeedTrimmer}; the position is not a watermark) and wrapping at the end. Each window is one
 * READ COMMITTED transaction: the multi-table DELETE record-locks the deleted history rows and share-locks the matching follows
 * rows for that one batch, so a concurrent follow/unfollow of the same pair waits at most one batch.
 */
public final class FollowHistoryRetention {
 private static final RetentionMapper.HistoryKey START=new RetentionMapper.HistoryKey(0,0,0,0);
 private final RetentionMapper db;private final TransactionTemplate tx;private final int batchSize,maxBatches;
 private RetentionMapper.HistoryKey position=START;
 public FollowHistoryRetention(RetentionMapper db,TransactionTemplate tx,int batchSize,int maxBatches){
  new RetentionBounds(1,batchSize,maxBatches);
  this.db=db;this.tx=tx;this.batchSize=batchSize;this.maxBatches=maxBatches;
 }
 public synchronized long purge(){
  long deleted=0;
  for(int batch=0;batch<maxBatches;batch++){
   var window=db.historyWindow(position,batchSize);
   if(window.isEmpty()){position=START;break;}
   Integer n=tx.execute(status->db.deleteStaleHistory(window));
   deleted+=n==null?0:n;
   position=window.get(window.size()-1);
   if(window.size()<batchSize){position=START;break;}
  }
  return deleted;
 }
}
