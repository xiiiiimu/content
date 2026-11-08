package io.github.xiiiiimu.content.infrastructure;
import io.github.xiiiiimu.content.domain.FeedCandidate;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface RecoveryMapper {
 record Control(String state,long generation,long token,Long since,Long snapshot) {}
 @Select("SELECT state,generation,recovery_token AS token,recovery_since_ms AS since,recovery_snapshot_ms AS snapshot FROM feed_control WHERE id=1 FOR UPDATE") Control lock();
 @Select("SELECT COALESCE(recovery_lease_until>CURRENT_TIMESTAMP(6),FALSE) FROM feed_control WHERE id=1") boolean leased();
 @Update("UPDATE feed_control SET state='REBUILDING',generation=#{generation},recovery_token=#{token},recovery_since_ms=#{since},recovery_snapshot_ms=#{snapshot},recovery_lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),reason=#{reason},updated_at=CURRENT_TIMESTAMP(6) WHERE id=1")
 void begin(@Param("generation")long generation,@Param("token")long token,@Param("since")long since,@Param("snapshot")long snapshot,@Param("reason")String reason);
 @Update("UPDATE feed_recovery_audit SET state='EXPIRED',error='Recovery lease expired; ownership taken over',completed_at=CURRENT_TIMESTAMP(6) WHERE generation=#{generation} AND recovery_token=#{token} AND state='RUNNING'")
 void expired(@Param("generation")long generation,@Param("token")long token);
 @Insert("INSERT INTO feed_recovery_audit(generation,recovery_token,operator,reason,state) VALUES(#{generation},#{token},#{operator},#{reason},'RUNNING')")
 void audit(@Param("generation")long generation,@Param("token")long token,@Param("operator")String operator,@Param("reason")String reason);
 @Update("UPDATE feed_control SET recovery_lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=1 AND state='REBUILDING' AND generation=#{generation} AND recovery_token=#{token} AND recovery_lease_until>CURRENT_TIMESTAMP(6)")
 int renew(@Param("generation")long generation,@Param("token")long token);
 @Update("UPDATE feed_control SET state='READY',recovery_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND state='REBUILDING' AND generation=#{generation} AND recovery_token=#{token} AND recovery_lease_until>CURRENT_TIMESTAMP(6)")
 int ready(@Param("generation")long generation,@Param("token")long token);
 @Update("UPDATE feed_control SET recovery_lease_until=CURRENT_TIMESTAMP(6) WHERE id=1 AND state='REBUILDING' AND generation=#{generation} AND recovery_token=#{token} AND recovery_lease_until>CURRENT_TIMESTAMP(6)")
 int release(@Param("generation")long generation,@Param("token")long token);
 @Update("UPDATE feed_recovery_audit SET state=#{state},readers=#{readers},members=#{members},duration_ms=#{duration},error=#{error},completed_at=CURRENT_TIMESTAMP(6) WHERE generation=#{generation} AND recovery_token=#{token} AND state='RUNNING'")
 void finishAudit(@Param("generation")long generation,@Param("token")long token,@Param("state")String state,@Param("readers")long readers,@Param("members")long members,@Param("duration")long duration,@Param("error")String error);
 @Select("SELECT DISTINCT follower_id FROM follows WHERE status='FOLLOWING' AND follower_id>#{last} ORDER BY follower_id LIMIT 100") List<Long> readers(long last);
 String ELIGIBLE=" FROM posts p JOIN follows f ON f.followee_id=p.author_id AND f.follower_id=#{reader} AND f.status='FOLLOWING' WHERE p.status='PUBLISHED' AND p.published_at_ms BETWEEN #{since} AND #{snapshot} AND (p.publish_seq>f.follow_cutoff_seq OR EXISTS(SELECT 1 FROM follow_history h WHERE h.follower_id=f.follower_id AND h.followee_id=f.followee_id AND h.follow_epoch=f.follow_epoch AND h.post_id=p.id))";
 @Select("SELECT p.published_at_ms AS publishedAtMs,p.id AS postId"+ELIGIBLE+" AND (#{time} IS NULL OR p.published_at_ms<#{time} OR (p.published_at_ms=#{time} AND p.id<#{id})) ORDER BY p.published_at_ms DESC,p.id DESC LIMIT 100")
 List<FeedCandidate> candidates(@Param("reader")long reader,@Param("since")long since,@Param("snapshot")long snapshot,@Param("time")Long time,@Param("id")Long id);
 @Select("SELECT COUNT(*)"+ELIGIBLE+" AND (p.delivery_mode IS NULL OR p.delivery_mode NOT IN('PUSH','PULL') OR p.delivery_epoch IS NULL OR p.delivery_epoch<0 OR p.publish_seq IS NULL OR p.publish_seq<=0)")
 long invalid(@Param("reader")long reader,@Param("since")long since,@Param("snapshot")long snapshot);
}
