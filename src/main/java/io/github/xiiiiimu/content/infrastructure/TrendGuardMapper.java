package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface TrendGuardMapper {
 record Risk(String eventId,int schemaVersion,String topic,String kind,long occurredAtMs,Long postId,String detail) {}
 @Insert("INSERT IGNORE INTO trend_risk_events(event_id,schema_version,topic,kind,window_start_ms,occurred_at_ms,post_id,detail,created_at_ms) VALUES(#{id},#{ver},#{topic},#{kind},#{window},#{at},#{post},#{detail},#{created})")
 int insert(@Param("id")String id,@Param("ver")int ver,@Param("topic")String topic,@Param("kind")String kind,@Param("window")long window,@Param("at")long at,@Param("post")Long post,@Param("detail")String detail,@Param("created")long created);
 @Select("SELECT event_id AS eventId,schema_version AS schemaVersion,topic,kind,occurred_at_ms AS occurredAtMs,post_id AS postId,detail FROM trend_risk_events WHERE occurred_at_ms>=#{since} AND (#{beforeMs} IS NULL OR occurred_at_ms<#{beforeMs} OR (occurred_at_ms=#{beforeMs} AND event_id<#{beforeId})) ORDER BY occurred_at_ms DESC,event_id DESC LIMIT #{limit}")
 List<Risk> sinceAll(@Param("since")long since,@Param("beforeMs")Long beforeMs,@Param("beforeId")String beforeId,@Param("limit")int limit);
 @Select("SELECT event_id AS eventId,schema_version AS schemaVersion,topic,kind,occurred_at_ms AS occurredAtMs,post_id AS postId,detail FROM trend_risk_events WHERE topic=#{topic} AND occurred_at_ms>=#{since} AND (#{beforeMs} IS NULL OR occurred_at_ms<#{beforeMs} OR (occurred_at_ms=#{beforeMs} AND event_id<#{beforeId})) ORDER BY occurred_at_ms DESC,event_id DESC LIMIT #{limit}")
 List<Risk> sinceTopic(@Param("topic")String topic,@Param("since")long since,@Param("beforeMs")Long beforeMs,@Param("beforeId")String beforeId,@Param("limit")int limit);
}
