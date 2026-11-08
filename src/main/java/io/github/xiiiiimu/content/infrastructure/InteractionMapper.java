package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface InteractionMapper {
 record Stats(long postId,long likes,long comments) {}
 record Comment(long id,long postId,long userId,String body,long createdAtMs) {}
 record Bucket(String topic,long bucket,long net,long active) {}
 @Insert("INSERT IGNORE INTO post_likes(post_id,user_id,created_at_ms) VALUES(#{post},#{user},#{at})") int like(@Param("post")long post,@Param("user")long user,@Param("at")long at);
 @Delete("DELETE FROM post_likes WHERE post_id=#{post} AND user_id=#{user}") int unlike(@Param("post")long post,@Param("user")long user);
 @Select("SELECT COUNT(*) FROM post_likes WHERE post_id=#{post}") long likeCount(long post);
 @Insert("INSERT INTO post_comments(post_id,user_id,body,idempotency_key,created_at_ms) VALUES(#{post},#{user},#{body},#{key},#{at})")
 int comment(@Param("post")long post,@Param("user")long user,@Param("body")String body,@Param("key")String key,@Param("at")long at);
 @Select("SELECT id,post_id AS postId,user_id AS userId,body,created_at_ms AS createdAtMs FROM post_comments WHERE user_id=#{user} AND idempotency_key=#{key}")
 Comment existingComment(@Param("user")long user,@Param("key")String key);
 @Select("SELECT COUNT(*) FROM post_comments WHERE post_id=#{post}") long commentCount(long post);
 @Insert("INSERT INTO post_stats(post_id,like_count,comment_count) VALUES(#{post},#{likes},#{comments}) ON DUPLICATE KEY UPDATE like_count=#{likes},comment_count=#{comments}")
 void upsertStats(@Param("post")long post,@Param("likes")long likes,@Param("comments")long comments);
 @Select("SELECT post_id AS postId,like_count AS likes,comment_count AS comments FROM post_stats WHERE post_id=#{post}") Stats stats(long post);
 @Insert("INSERT IGNORE INTO topic_event_ledger(event_id,topic,post_id,occurred_at_ms,delta,kind) VALUES(#{id},#{topic},#{post},#{at},#{delta},#{kind})")
 int ledger(@Param("id")String id,@Param("topic")String topic,@Param("post")long post,@Param("at")long at,@Param("delta")int delta,@Param("kind")String kind);
 @Insert("INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions) VALUES(#{topic},#{bucket},#{net},#{active}) ON DUPLICATE KEY UPDATE net_events=net_events+#{net},active_interactions=active_interactions+#{active}")
 void bucket(@Param("topic")String topic,@Param("bucket")long bucket,@Param("net")long net,@Param("active")long active);
 @Select("SELECT topic,bucket_start_ms AS bucket,net_events AS net,active_interactions AS active FROM topic_minute_buckets WHERE bucket_start_ms>=#{since} ORDER BY topic,bucket_start_ms")
 List<Bucket> bucketsSince(long since);
 @Delete("DELETE FROM topic_minute_buckets") void clearBuckets();
 @Select("SELECT event_id AS id,topic,post_id AS post,occurred_at_ms AS at,delta,kind FROM topic_event_ledger WHERE occurred_at_ms>=#{since}")
 List<Ledger> ledgerSince(long since);
 record Ledger(String id,String topic,long post,long at,int delta,String kind) {}
}
