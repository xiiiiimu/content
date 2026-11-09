package io.github.xiiiiimu.content.infrastructure;
import io.github.xiiiiimu.content.domain.DeliveryLease;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface DeliveryMapper {
 String COLS="id AS taskId,event_id AS eventId,kind,author_id AS authorId,post_id AS postId,follower_id AS followerId,follow_epoch AS followEpoch,payload,lease_token AS token,attempt,lease_generation AS generation,scan_upper_follower_id AS upper,last_follower_id AS lastFollower,last_post_id AS lastPost,delivery_epoch AS deliveryEpoch";
 /** Single-row claim/fail readiness (id already known); OR is safe here. */
 String READY="((state='NEW' AND next_retry_at<=CURRENT_TIMESTAMP(6)) OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)))";
 String READY_NEW="state='NEW' AND next_retry_at<=CURRENT_TIMESTAMP(6)";
 String READY_RUNNING="state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)";
 @Select("SELECT "+COLS+" FROM feed_delivery_tasks WHERE "+READY_NEW+" AND attempt<#{max} ORDER BY next_retry_at,id LIMIT 1 FOR UPDATE SKIP LOCKED") DeliveryLease candidateNew(int max);
 @Select("SELECT "+COLS+" FROM feed_delivery_tasks WHERE "+READY_RUNNING+" AND attempt<#{max} ORDER BY lease_until,id LIMIT 1 FOR UPDATE SKIP LOCKED") DeliveryLease candidateRunning(int max);
 @Select("SELECT "+COLS+" FROM feed_delivery_tasks WHERE id=#{id} FOR UPDATE") DeliveryLease lock(long id);
 @Update("UPDATE feed_delivery_tasks SET state='RUNNING',lease_token=lease_token+1,attempt=attempt+1,lease_generation=#{generation},lease_until=TIMESTAMPADD(MICROSECOND,#{lease}*1000,CURRENT_TIMESTAMP(6)),last_attempt_at=CURRENT_TIMESTAMP(6),scan_upper_follower_id=COALESCE(scan_upper_follower_id,(SELECT COALESCE(MAX(follower_id),0) FROM follows WHERE followee_id=#{author} AND status='FOLLOWING')) WHERE id=#{id} AND lease_token=#{token} AND "+READY)
 int claim(@Param("id")long id,@Param("token")long token,@Param("author")long author,@Param("generation")long generation,@Param("lease")long lease);
 @Select("SELECT COUNT(*) FROM feed_delivery_tasks t JOIN feed_control c ON c.id=1 WHERE t.id=#{id} AND t.state='RUNNING' AND t.lease_token=#{token} AND t.lease_until>CURRENT_TIMESTAMP(6) AND t.lease_generation=c.generation AND c.state IN('PRIMARY','READY')")
 boolean valid(@Param("id")long id,@Param("token")long token);
 @Update("UPDATE feed_delivery_tasks t SET t.last_follower_id=#{follower},t.last_post_id=#{post},t.state=IF(#{done},'DONE','RUNNING'),t.lease_until=IF(#{done},NULL,TIMESTAMPADD(MICROSECOND,#{lease}*1000,CURRENT_TIMESTAMP(6))),t.last_error=NULL WHERE t.id=#{id} AND t.state='RUNNING' AND t.lease_token=#{token} AND t.lease_until>CURRENT_TIMESTAMP(6) AND t.lease_generation=#{generation} AND #{follower}>=t.last_follower_id AND #{post}>=t.last_post_id AND (t.kind<>'PUBLISH' OR #{follower}<=t.scan_upper_follower_id) AND (#{done} OR #{follower}>t.last_follower_id OR #{post}>t.last_post_id)")
 int progress(@Param("id")long id,@Param("token")long token,@Param("follower")long follower,@Param("post")long post,@Param("done")boolean done,@Param("lease")long lease,@Param("generation")long generation);
 @Update("UPDATE feed_delivery_tasks t SET t.state=#{state},t.lease_until=NULL,t.last_error=#{error},t.next_retry_at=TIMESTAMPADD(MICROSECOND,#{delay}*1000,CURRENT_TIMESTAMP(6)) WHERE t.id=#{id} AND t.state='RUNNING' AND t.lease_token=#{token} AND t.lease_until>CURRENT_TIMESTAMP(6) AND t.lease_generation=#{generation}")
 int fail(@Param("id")long id,@Param("token")long token,@Param("state")String state,@Param("error")String error,@Param("delay")long delay,@Param("generation")long generation);
 @Select("SELECT "+COLS+" FROM feed_delivery_tasks WHERE "+READY_NEW+" AND attempt>=#{max} ORDER BY next_retry_at,id LIMIT 100 FOR UPDATE SKIP LOCKED") List<DeliveryLease> exhaustedNew(int max);
 @Select("SELECT "+COLS+" FROM feed_delivery_tasks WHERE "+READY_RUNNING+" AND attempt>=#{max} ORDER BY lease_until,id LIMIT 100 FOR UPDATE SKIP LOCKED") List<DeliveryLease> exhaustedRunning(int max);
 @Update("UPDATE feed_delivery_tasks SET state='DEAD',lease_until=NULL,last_error='Attempt limit after lease expiry' WHERE id=#{id} AND lease_token=#{token} AND attempt>=#{max} AND "+READY)
 int dead(@Param("id")long id,@Param("token")long token,@Param("max")int max);
 @Select("SELECT follower_id FROM follows WHERE followee_id=#{author} AND status='FOLLOWING' AND follower_id>#{last} AND follower_id<=#{upper} ORDER BY follower_id LIMIT #{n}")
 List<Long> followers(@Param("author")long author,@Param("last")long last,@Param("upper")long upper,@Param("n")int n);
 @Select("SELECT COUNT(*) FROM follows WHERE follower_id=#{reader} AND followee_id=#{author} AND status='FOLLOWING' AND #{sequence}>follow_cutoff_seq")
 boolean eligible(@Param("reader")long reader,@Param("author")long author,@Param("sequence")long sequence);
 @Select("SELECT h.post_id FROM follow_history h JOIN follows f ON f.follower_id=h.follower_id AND f.followee_id=h.followee_id AND f.follow_epoch=h.follow_epoch AND f.status='FOLLOWING' WHERE h.follower_id=#{reader} AND h.followee_id=#{author} AND h.follow_epoch=#{epoch} AND h.post_id>#{last} ORDER BY h.post_id LIMIT #{n}")
 List<Long> history(@Param("reader")long reader,@Param("author")long author,@Param("epoch")long epoch,@Param("last")long last,@Param("n")int n);
 @Select("SELECT COUNT(*) FROM follows f JOIN follow_history h ON h.follower_id=f.follower_id AND h.followee_id=f.followee_id AND h.follow_epoch=f.follow_epoch WHERE f.follower_id=#{reader} AND f.followee_id=#{author} AND f.follow_epoch=#{epoch} AND f.status='FOLLOWING' AND h.post_id=#{post}")
 boolean historyEligible(@Param("reader")long reader,@Param("author")long author,@Param("epoch")long epoch,@Param("post")long post);
 @Update("UPDATE feed_delivery_tasks SET state='NEW',attempt=0,lease_token=lease_token+1,lease_until=NULL,next_retry_at=CURRENT_TIMESTAMP(6),last_error=NULL WHERE id=#{id} AND state='DEAD' AND lease_token=#{token}")
 int replay(@Param("id")long id,@Param("token")long token);
 @Insert("INSERT INTO delivery_replay_audit(task_id,event_id,old_token,new_token,previous_attempt,operator,reason) VALUES(#{id},#{event},#{token},#{next},#{attempt},#{operator},#{reason})")
 void audit(@Param("id")long id,@Param("event")long event,@Param("token")long token,@Param("next")long next,@Param("attempt")int attempt,@Param("operator")String operator,@Param("reason")String reason);
}
