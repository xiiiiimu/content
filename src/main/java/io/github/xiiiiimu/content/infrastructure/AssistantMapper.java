package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AssistantMapper {
 record Task(long id,long triggerCommentId,long postId,long actorUserId,Long parentCommentId,String body,String intent,
             String state,int attempt,long token,Long replyCommentId,long createdAtMs) {}
 record Subscription(long id,long accountId,long feedbackCommentId,long postId,String state,long createdAtMs) {}
 record Resolution(long id,long feedbackCommentId,long postId,long newVersionId,Long offsetMs,long authorId,long createdAtMs) {}

 @Insert("""
  INSERT INTO assistant_tasks(trigger_comment_id,post_id,actor_user_id,parent_comment_id,body,state,created_at_ms)
  VALUES(#{trigger},#{post},#{actor},#{parent},#{body},'NEW',#{at})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insertTask(TaskInsert row);
 class TaskInsert { public long id; public long trigger; public long post; public long actor; public Long parent; public String body; public long at; }

 @Select("""
  SELECT id,trigger_comment_id AS triggerCommentId,post_id AS postId,actor_user_id AS actorUserId,parent_comment_id AS parentCommentId,
         body,intent,state,attempt,lease_token AS token,reply_comment_id AS replyCommentId,created_at_ms AS createdAtMs
  FROM assistant_tasks WHERE id=#{id}
  """)
 Task task(long id);

 @Select("""
  SELECT id,trigger_comment_id AS triggerCommentId,post_id AS postId,actor_user_id AS actorUserId,parent_comment_id AS parentCommentId,
         body,intent,state,attempt,lease_token AS token,reply_comment_id AS replyCommentId,created_at_ms AS createdAtMs
  FROM assistant_tasks WHERE trigger_comment_id=#{comment}
  """)
 Task byTrigger(long comment);

 @Select("""
  SELECT id,trigger_comment_id AS triggerCommentId,post_id AS postId,actor_user_id AS actorUserId,parent_comment_id AS parentCommentId,
         body,intent,state,attempt,lease_token AS token,reply_comment_id AS replyCommentId,created_at_ms AS createdAtMs
  FROM assistant_tasks WHERE state IN('NEW','RUNNING') AND next_retry_at<=CURRENT_TIMESTAMP(6)
    AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY id LIMIT 1 FOR UPDATE
  """)
 Task claimable();

 @Update("""
  UPDATE assistant_tasks SET state='RUNNING',attempt=attempt+1,lease_token=lease_token+1,
  lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE id=#{id} AND state IN('NEW','RUNNING')
    AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6))
  """)
 int claim(long id);

 @Update("""
  UPDATE assistant_tasks SET state=#{state},intent=#{intent},result_json=#{result},reply_comment_id=#{reply},lease_until=NULL,last_error=NULL
  WHERE id=#{id} AND lease_token=#{token} AND state='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)
  """)
 int complete(@Param("id")long id,@Param("token")long token,@Param("state")String state,@Param("intent")String intent,
              @Param("result")String result,@Param("reply")Long reply);

 @Update("""
  UPDATE assistant_tasks SET state=IF(attempt>=5,'FAILED','NEW'),
  next_retry_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(6)),lease_until=NULL,last_error=#{error}
  WHERE id=#{id} AND lease_token=#{token} AND state='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)
  """)
 int fail(@Param("id")long id,@Param("token")long token,@Param("delay")int delay,@Param("error")String error);

 @Insert("""
  INSERT INTO feedback_subscriptions(account_id,feedback_comment_id,post_id,state,created_at_ms)
  VALUES(#{account},#{feedback},#{post},'ACTIVE',#{at})
  ON DUPLICATE KEY UPDATE state=IF(state='CANCELLED','ACTIVE',state),cancelled_at_ms=NULL
  """)
 int upsertSubscription(@Param("account")long account,@Param("feedback")long feedback,@Param("post")long post,@Param("at")long at);

 @Update("UPDATE feedback_subscriptions SET state='CANCELLED',cancelled_at_ms=#{at} WHERE account_id=#{account} AND feedback_comment_id=#{feedback} AND state='ACTIVE'")
 int cancelSubscription(@Param("account")long account,@Param("feedback")long feedback,@Param("at")long at);

 @Select("""
  SELECT id,account_id AS accountId,feedback_comment_id AS feedbackCommentId,post_id AS postId,state,created_at_ms AS createdAtMs
  FROM feedback_subscriptions WHERE account_id=#{account} AND feedback_comment_id=#{feedback}
  """)
 Subscription subscription(@Param("account")long account,@Param("feedback")long feedback);

 @Select("""
  SELECT id,account_id AS accountId,feedback_comment_id AS feedbackCommentId,post_id AS postId,state,created_at_ms AS createdAtMs
  FROM feedback_subscriptions WHERE feedback_comment_id=#{feedback} AND state='ACTIVE'
  """)
 List<Subscription> activeSubs(long feedback);

 @Insert("""
  INSERT INTO feedback_resolutions(feedback_comment_id,post_id,new_version_id,offset_ms,author_id,created_at_ms)
  VALUES(#{feedback},#{post},#{version},#{offset},#{author},#{at})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insertResolution(ResolutionInsert row);
 class ResolutionInsert { public long id; public long feedback; public long post; public long version; public Long offset; public long author; public long at; }

 @Select("""
  SELECT id,feedback_comment_id AS feedbackCommentId,post_id AS postId,new_version_id AS newVersionId,offset_ms AS offsetMs,
         author_id AS authorId,created_at_ms AS createdAtMs
  FROM feedback_resolutions WHERE new_version_id=#{version}
  """)
 List<Resolution> resolutionsForVersion(long version);

 @Select("""
  SELECT id,feedback_comment_id AS feedbackCommentId,post_id AS postId,new_version_id AS newVersionId,offset_ms AS offsetMs,
         author_id AS authorId,created_at_ms AS createdAtMs
  FROM feedback_resolutions WHERE feedback_comment_id=#{feedback}
  """)
 List<Resolution> resolutionsForFeedback(long feedback);

 @Insert("INSERT IGNORE INTO feedback_notify_log(account_id,feedback_comment_id,new_version_id,notification_id,created_at_ms) VALUES(#{account},#{feedback},#{version},#{notif},#{at})")
 int notifyLog(@Param("account")long account,@Param("feedback")long feedback,@Param("version")long version,@Param("notif")Long notif,@Param("at")long at);
}
