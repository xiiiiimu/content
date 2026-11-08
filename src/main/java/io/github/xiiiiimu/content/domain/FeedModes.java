package io.github.xiiiiimu.content.domain;
import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

public final class FeedModes {
 public record Result(String mode,long epoch,String transitionId) {}
 private final FeedMapper db;private final ModeMapper modes;private final TransactionTemplate tx;private final RelationshipService events;private final long window;
 public FeedModes(FeedMapper db,ModeMapper modes,TransactionTemplate tx,RelationshipService events,long window){this.db=db;this.modes=modes;this.tx=tx;this.events=events;this.window=window;}
 public Result change(long author,String target,long expected){
  if(author<=0 || target==null || !Set.of("PUSH","PULL").contains(target) || expected<0)throw new FeedFailure(400,"INVALID_REQUEST");
  var result=tx.execute(status->{RelationshipService.requireWritable(db.gate());var row=db.author(author);if(row==null)throw new FeedFailure(404,"AUTHOR_NOT_FOUND");
   if(row.mode().equals(target))return new Result(target,row.epoch(),author+":"+row.epoch());
   if(row.epoch()!=expected)throw new FeedFailure(409,"MODE_EPOCH_CONFLICT");long next=Math.addExact(row.epoch(),1);
   modes.transition(author,row.epoch(),next,row.mode(),target,Math.max(0,db.now()-window));
   if(modes.change(author,row.epoch(),next,target)!=1)throw new IllegalStateException("Mode row changed while locked");
   String id=author+":"+next;events.emit("FeedModeChanged",author,Map.of("authorId",author,"fromEpoch",row.epoch(),"toEpoch",next,"oldMode",row.mode(),"newMode",target,"transitionId",id));
   return new Result(target,next,id);
  });
  verify(author,result.epoch());return result;
 }
 public void verify(long author,long epoch){tx.executeWithoutResult(status->{RelationshipService.requireWritable(db.gate());db.author(author);Long since=modes.lockTransition(author,epoch);if(since==null)return;
  // SQL PULL and PUSH sources are always retained. This capability does not depend on an inbox watermark.
  if(modes.invalid(author,epoch,since)!=0)throw new FeedFailure(503,"INVALID_HISTORICAL_PUBLICATION");
  if(modes.verified(author,epoch)!=1)throw new IllegalStateException("Coverage verification lost its transition");
 });}
 public void scan(){for(var row:modes.pending())verify(row.author(),row.epoch());}
}
