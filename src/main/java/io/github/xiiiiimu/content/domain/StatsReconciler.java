package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.InteractionMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hardening B2 (R3): offline reconciliation of post_stats against COUNT(*) of the counted tables.
 * <p>The online path moves post_stats by ±1 from affected rows; this job finds and repairs drift (legacy rows, manual
 * edits, a bug). Each post is checked in its own short READ COMMITTED transaction that first takes the post_stats row
 * lock ({@code FOR UPDATE}) and only then counts: every online writer changes its counted row first and post_stats
 * second, in one transaction, so while we hold the lock each committed change is already in the stored value and each
 * uncommitted one is in neither (READ COMMITTED gives each COUNT a fresh snapshot). Stored and counted values are
 * therefore comparable, and a concurrent like is never mistaken for drift. The lock is held for the four COUNTs: on a
 * post with 1M likes about 66 ms (risk list measurement A), during which likes on that one post wait.
 * <p>Posts are visited in post_id order from a cursor, {@code batchSize} per run; the cursor wraps to the start at the end.
 */
public final class StatsReconciler {
 public record Result(int checked,int corrected,long likeDrift,long dislikeDrift,long commentDrift,long reportDrift) {
  Result plus(Result o){return new Result(checked+o.checked,corrected+o.corrected,likeDrift+o.likeDrift,dislikeDrift+o.dislikeDrift,commentDrift+o.commentDrift,reportDrift+o.reportDrift);}
  public long totalDrift(){return Math.abs(likeDrift)+Math.abs(dislikeDrift)+Math.abs(commentDrift)+Math.abs(reportDrift);}
 }
 static final Result NONE=new Result(0,0,0,0,0,0);
 private final InteractionMapper db;private final TransactionTemplate tx;private final int batchSize;
 private long cursor=0;
 public StatsReconciler(InteractionMapper db,TransactionTemplate tx,int batchSize){
  if(batchSize<1 || batchSize>10_000)throw new IllegalArgumentException("Stats reconcile batch must be in [1,10000]");
  this.db=db;this.tx=tx;this.batchSize=batchSize;
 }
 /** Checks the next {@code batchSize} posts after the cursor. Drift values are stored minus counted, summed. */
 public synchronized Result runBatch(){
  var ids=db.statsPostIdsAfter(cursor,batchSize);
  if(ids.isEmpty()){cursor=0;return NONE;}
  var total=NONE;
  for(long post:ids){var r=tx.execute(status->check(post));if(r!=null)total=total.plus(r);}
  cursor=ids.get(ids.size()-1);
  if(ids.size()<batchSize)cursor=0;
  return total;
 }
 /** One full pass from the first post (for operators and tests). */
 public synchronized Result reconcileAll(){
  cursor=0;var total=NONE;
  while(true){var r=runBatch();total=total.plus(r);if(cursor==0)return total;}
 }
 private Result check(long post){
  var stored=db.statsForUpdate(post);
  if(stored==null)return NONE;
  long likes=db.likeCount(post),comments=db.commentCount(post),dislikes=db.dislikeCount(post),reports=db.reportCount(post);
  long dl=stored.likes()-likes,dd=stored.dislikes()-dislikes,dc=stored.comments()-comments,dr=stored.reports()-reports;
  if(dl==0 && dd==0 && dc==0 && dr==0)return new Result(1,0,0,0,0,0);
  db.upsertStats(post,likes,dislikes,comments,reports);
  return new Result(1,1,dl,dd,dc,dr);
 }
}
