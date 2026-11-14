package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface NotificationMapper {
 record Row(long id,long recipientUserId,long sourceEventId,String kind,long actorUserId,long postId,long commentId,Long parentCommentId,Long refVersionId,String dedupeKey,long createdAtMs,Long readAtMs) {}
 @Insert("""
  INSERT IGNORE INTO notifications(recipient_user_id,source_event_id,kind,actor_user_id,post_id,comment_id,parent_comment_id,created_at_ms)
  VALUES(#{recipient},#{event},#{kind},#{actor},#{post},#{comment},#{parent},#{at})
  """)
 int insert(@Param("recipient")long recipient,@Param("event")long event,@Param("kind")String kind,
            @Param("actor")long actor,@Param("post")long post,@Param("comment")long comment,
            @Param("parent")Long parent,@Param("at")long at);
 @Insert("""
  INSERT IGNORE INTO notifications(recipient_user_id,source_event_id,kind,actor_user_id,post_id,comment_id,parent_comment_id,ref_version_id,dedupe_key,created_at_ms)
  VALUES(#{recipient},#{event},#{kind},#{actor},#{post},#{comment},#{parent},#{version},#{dedupe},#{at})
  """)
 int insertWithMeta(@Param("recipient")long recipient,@Param("event")long event,@Param("kind")String kind,
                    @Param("actor")long actor,@Param("post")long post,@Param("comment")long comment,
                    @Param("parent")Long parent,@Param("version")Long version,@Param("dedupe")String dedupe,@Param("at")long at);
 @Select("""
  SELECT id,recipient_user_id AS recipientUserId,source_event_id AS sourceEventId,kind,actor_user_id AS actorUserId,
         post_id AS postId,comment_id AS commentId,parent_comment_id AS parentCommentId,ref_version_id AS refVersionId,
         dedupe_key AS dedupeKey,created_at_ms AS createdAtMs,read_at_ms AS readAtMs
  FROM notifications WHERE recipient_user_id=#{user} ORDER BY id DESC LIMIT #{limit}
  """)
 List<Row> list(@Param("user")long user,@Param("limit")int limit);
 @Select("""
  SELECT id,recipient_user_id AS recipientUserId,source_event_id AS sourceEventId,kind,actor_user_id AS actorUserId,
         post_id AS postId,comment_id AS commentId,parent_comment_id AS parentCommentId,ref_version_id AS refVersionId,
         dedupe_key AS dedupeKey,created_at_ms AS createdAtMs,read_at_ms AS readAtMs
  FROM notifications WHERE id=#{id}
  """)
 Row get(long id);
 @Update("UPDATE notifications SET read_at_ms=#{at} WHERE id=#{id} AND recipient_user_id=#{user} AND read_at_ms IS NULL")
 int markRead(@Param("id")long id,@Param("user")long user,@Param("at")long at);
}
