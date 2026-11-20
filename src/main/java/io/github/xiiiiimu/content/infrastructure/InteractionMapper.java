package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface InteractionMapper {
 record Stats(long postId,long likes,long comments,long dislikes,long reports) {}
 record Comment(long id,long postId,long userId,Long parentId,Long materialVersionId,Long offsetMs,Long acceptedAtMs,String body,long createdAtMs) {}
 record Bucket(String topic,long bucket,long net,long active) {}
 @Insert("INSERT IGNORE INTO post_likes(post_id,user_id,created_at_ms) VALUES(#{post},#{user},#{at})") int like(@Param("post")long post,@Param("user")long user,@Param("at")long at);
 @Delete("DELETE FROM post_likes WHERE post_id=#{post} AND user_id=#{user}") int unlike(@Param("post")long post,@Param("user")long user);
 @Select("SELECT COUNT(*) FROM post_likes WHERE post_id=#{post}") long likeCount(long post);
 @Select("SELECT user_id FROM post_likes WHERE post_id=#{post}") List<Long> likedUsers(long post);
 @Select("""
  SELECT DISTINCT user_id FROM post_comments WHERE post_id=#{post}
   AND (#{start} IS NULL OR created_at_ms>=#{start})
   AND (#{end} IS NULL OR created_at_ms<=#{end})
  """)
 List<Long> commentedUsers(@Param("post")long post,@Param("start")Long start,@Param("end")Long end);
 @Insert("INSERT IGNORE INTO post_dislikes(post_id,user_id,created_at_ms) VALUES(#{post},#{user},#{at})") int dislike(@Param("post")long post,@Param("user")long user,@Param("at")long at);
 @Delete("DELETE FROM post_dislikes WHERE post_id=#{post} AND user_id=#{user}") int undislike(@Param("post")long post,@Param("user")long user);
 @Select("SELECT COUNT(*) FROM post_dislikes WHERE post_id=#{post}") long dislikeCount(long post);
 /**
  * One report per (post, reporter) (V29 uq_report_post_reporter): 1 = inserted, 0 = the reporter already reported this post.
  * ON DUPLICATE KEY UPDATE id=id instead of INSERT IGNORE so every other error (bad JSON, truncation) still fails the statement.
  */
 @Insert("INSERT INTO post_reports(post_id,reporter_user_id,reason,detail,context_json,created_at_ms) VALUES(#{post},#{user},#{reason},#{detail},#{context},#{at}) ON DUPLICATE KEY UPDATE id=id")
 int report(@Param("post")long post,@Param("user")long user,@Param("reason")String reason,@Param("detail")String detail,@Param("context")String context,@Param("at")long at);
 @Select("SELECT COUNT(*) FROM post_reports WHERE post_id=#{post}") long reportCount(long post);
 @Select("SELECT id FROM post_reports WHERE post_id=#{post} AND reporter_user_id=#{user}") Long reportId(@Param("post")long post,@Param("user")long user);
 @Insert("""
  INSERT INTO post_comments(post_id,user_id,parent_id,material_version_id,offset_ms,body,idempotency_key,created_at_ms)
  VALUES(#{post},#{user},#{parent},#{version},#{offset},#{body},#{key},#{at})
  """)
 int comment(@Param("post")long post,@Param("user")long user,@Param("parent")Long parent,@Param("version")Long version,@Param("offset")Long offset,
             @Param("body")String body,@Param("key")String key,@Param("at")long at);
 String COMMENT_COLS="id,post_id AS postId,user_id AS userId,parent_id AS parentId,material_version_id AS materialVersionId,offset_ms AS offsetMs,accepted_at_ms AS acceptedAtMs,body,created_at_ms AS createdAtMs";
 @Select("SELECT "+COMMENT_COLS+" FROM post_comments WHERE user_id=#{user} AND idempotency_key=#{key}")
 Comment existingComment(@Param("user")long user,@Param("key")String key);
 @Select("SELECT "+COMMENT_COLS+" FROM post_comments WHERE id=#{id}")
 Comment commentById(long id);
 @Select("SELECT "+COMMENT_COLS+" FROM post_comments WHERE post_id=#{post} ORDER BY id ASC LIMIT #{limit}")
 List<Comment> commentsForPost(@Param("post")long post,@Param("limit")int limit);
 @Update("UPDATE post_comments SET accepted_at_ms=#{at} WHERE id=#{id} AND post_id=#{post} AND accepted_at_ms IS NULL")
 int accept(@Param("post")long post,@Param("id")long id,@Param("at")long at);
 @Select("SELECT COUNT(*) FROM post_comments WHERE post_id=#{post}") long commentCount(long post);
 @Insert("""
  INSERT INTO post_stats(post_id,like_count,dislike_count,comment_count,report_count)
  VALUES(#{post},#{likes},#{dislikes},#{comments},#{reports})
  ON DUPLICATE KEY UPDATE like_count=#{likes},dislike_count=#{dislikes},comment_count=#{comments},report_count=#{reports}
  """)
 void upsertStats(@Param("post")long post,@Param("likes")long likes,@Param("dislikes")long dislikes,@Param("comments")long comments,@Param("reports")long reports);
 @Select("SELECT post_id AS postId,like_count AS likes,comment_count AS comments,dislike_count AS dislikes,report_count AS reports FROM post_stats WHERE post_id=#{post}")
 Stats stats(long post);
 @Insert("INSERT IGNORE INTO topic_event_ledger(event_id,topic,post_id,occurred_at_ms,delta,kind) VALUES(#{id},#{topic},#{post},#{at},#{delta},#{kind})")
 int ledger(@Param("id")String id,@Param("topic")String topic,@Param("post")long post,@Param("at")long at,@Param("delta")int delta,@Param("kind")String kind);
 @Insert("INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions) VALUES(#{topic},#{bucket},#{net},#{active}) ON DUPLICATE KEY UPDATE net_events=net_events+#{net},active_interactions=active_interactions+#{active}")
 void bucket(@Param("topic")String topic,@Param("bucket")long bucket,@Param("net")long net,@Param("active")long active);
 @Select("SELECT topic,bucket_start_ms AS bucket,net_events AS net,active_interactions AS active FROM topic_minute_buckets WHERE bucket_start_ms>=#{since} ORDER BY topic,bucket_start_ms")
 List<Bucket> bucketsSince(long since);
 record LikeEvent(String topic,long at) {}
 /** The ledger row of a still-counted like (event id like:{post}:{user}), or null. */
 @Select("SELECT topic,occurred_at_ms AS at FROM topic_event_ledger WHERE event_id=#{id}")
 LikeEvent likeEvent(String id);
 @Delete("DELETE FROM topic_event_ledger WHERE event_id=#{id}")
 int deleteLedger(String id);
 /** Takes back one counted like from the bucket of the minute it was recorded in; never below zero. Missing (purged) bucket → 0 rows. */
 @Update("UPDATE topic_minute_buckets SET net_events=GREATEST(net_events-1,0),active_interactions=GREATEST(active_interactions-1,0) WHERE topic=#{topic} AND bucket_start_ms=#{bucket}")
 int revokeBucket(@Param("topic")String topic,@Param("bucket")long bucket);
 /** Spike check: net of one topic since a time, a primary-key prefix range (topic, bucket_start_ms). */
 @Select("SELECT COALESCE(SUM(net_events),0) FROM topic_minute_buckets WHERE topic=#{topic} AND bucket_start_ms>=#{since}")
 long topicNetSince(@Param("topic")String topic,@Param("since")long since);
 /** Retention: delete at most {@code limit} expired buckets, oldest first, via ix_bucket_start. */
 @Delete("DELETE FROM topic_minute_buckets WHERE bucket_start_ms<#{cutoff} ORDER BY bucket_start_ms LIMIT #{limit}")
 int purgeBuckets(@Param("cutoff")long cutoff,@Param("limit")int limit);
 /** Retention: delete at most {@code limit} expired ledger rows, oldest first, via ix_ledger_time. */
 @Delete("DELETE FROM topic_event_ledger WHERE occurred_at_ms<#{cutoff} ORDER BY occurred_at_ms LIMIT #{limit}")
 int purgeLedger(@Param("cutoff")long cutoff,@Param("limit")int limit);
 @Delete("DELETE FROM topic_minute_buckets") void clearBuckets();
 @Select("SELECT event_id AS id,topic,post_id AS post,occurred_at_ms AS at,delta,kind FROM topic_event_ledger WHERE occurred_at_ms>=#{since}")
 List<Ledger> ledgerSince(long since);
 /** The post's own topic (author-set); null means the post takes no part in topic statistics. */
 @Select("SELECT topic FROM posts WHERE id=#{post}")
 String postTopic(long post);
 /** Topic page source: published posts carrying the topic attribute, newest first (ix_posts_topic). */
 @Select("SELECT id FROM posts WHERE topic=#{topic} AND status='PUBLISHED' ORDER BY published_at_ms DESC,id DESC LIMIT #{limit}")
 List<Long> postsWithTopic(@Param("topic")String topic,@Param("limit")int limit);
 record Ledger(String id,String topic,long post,long at,int delta,String kind) {}
}
