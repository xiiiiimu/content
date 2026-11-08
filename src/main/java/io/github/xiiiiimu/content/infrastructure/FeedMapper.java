package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.FeedCandidate;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface FeedMapper {
 record Control(String state,long generation) {}
 record Author(String mode,long epoch,long sequence) {}
 record Relation(String status,long epoch,long cutoff,long sinceMs,int historyLimit) {}
 record Post(long id,long authorId,Long time,Long sequence,String body,String status,long version,String mode,Long epoch) {}
 record Authority(long id,Long time,String body,boolean allowed) {}
 String POST="id,author_id AS authorId,published_at_ms AS time,publish_seq AS sequence,body,status,version,delivery_mode AS mode,delivery_epoch AS epoch";
 @Select("SELECT state,generation FROM feed_control WHERE id=1 FOR SHARE") Control gate();
 @Select("SELECT state,generation FROM feed_control WHERE id=1") Control control();
 @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)") long now();
 @Select("SELECT mode,mode_epoch AS epoch,publish_seq AS sequence FROM author_feed_mode WHERE author_id=#{id} FOR UPDATE") Author author(long id);
 @Insert("INSERT IGNORE INTO follow_graph_version(follower_id,version) VALUES(#{reader},0)") void initGraph(long reader);
 @Select("SELECT version FROM follow_graph_version WHERE follower_id=#{reader} FOR UPDATE") long lockGraph(long reader);
 @Select("SELECT version FROM follow_graph_version WHERE follower_id=#{reader}") Long graph(long reader);
 @Select("SELECT follower_id FROM follow_graph_version WHERE follower_id>#{last} ORDER BY follower_id LIMIT 100") List<Long> readerBatch(long last);
 @Update("UPDATE follow_graph_version SET version=version+1 WHERE follower_id=#{reader}") void bumpGraph(long reader);
 @Select("SELECT status,follow_epoch AS epoch,follow_cutoff_seq AS cutoff,history_since_ms AS sinceMs,history_limit AS historyLimit FROM follows WHERE follower_id=#{reader} AND followee_id=#{author} FOR UPDATE")
 Relation relation(@Param("reader") long reader,@Param("author") long author);
 @Insert("INSERT INTO follows(follower_id,followee_id,status,follow_epoch,follow_cutoff_seq,history_since_ms,history_limit) VALUES(#{reader},#{author},'FOLLOWING',#{epoch},#{cutoff},#{since},#{n}) ON DUPLICATE KEY UPDATE status='FOLLOWING',follow_epoch=#{epoch},follow_cutoff_seq=#{cutoff},history_since_ms=#{since},history_limit=#{n}")
 void follow(@Param("reader") long reader,@Param("author") long author,@Param("epoch") long epoch,@Param("cutoff") long cutoff,@Param("since") long since,@Param("n") int n);
 @Update("UPDATE follows SET status='UNFOLLOWED' WHERE follower_id=#{reader} AND followee_id=#{author}") void unfollow(@Param("reader") long reader,@Param("author") long author);
 @Select("SELECT id FROM posts WHERE author_id=#{author} AND status='PUBLISHED' AND publish_seq<=#{cutoff} AND published_at_ms>=#{since} ORDER BY published_at_ms DESC,id DESC LIMIT #{n}")
 List<Long> history(@Param("author") long author,@Param("cutoff") long cutoff,@Param("since") long since,@Param("n") int n);
 @Insert("INSERT INTO follow_history(follower_id,followee_id,follow_epoch,post_id) VALUES(#{reader},#{author},#{epoch},#{post})")
 void saveHistory(@Param("reader") long reader,@Param("author") long author,@Param("epoch") long epoch,@Param("post") long post);
 @Insert("INSERT INTO outbox_events(event_type,aggregate_id,payload) VALUES(#{type},#{aggregate},'{}')") void event(@Param("type") String type,@Param("aggregate") long aggregate);
 @Select("SELECT LAST_INSERT_ID()") long insertedId();
 @Update("UPDATE outbox_events SET payload=#{payload} WHERE id=#{id}") void eventPayload(@Param("id") long id,@Param("payload") String payload);
 @Select("SELECT "+POST+" FROM posts WHERE id=#{id}") Post post(long id);
 @Select("SELECT p.published_at_ms AS publishedAtMs,p.id AS postId FROM posts p JOIN follows f ON f.followee_id=p.author_id AND f.follower_id=#{reader} AND f.status='FOLLOWING' WHERE p.published_at_ms BETWEEN #{since} AND #{snapshot} AND (#{mode}='ALL' OR p.delivery_mode=#{mode}) AND (#{time} IS NULL OR p.published_at_ms<#{time} OR (p.published_at_ms=#{time} AND p.id<#{id})) ORDER BY p.published_at_ms DESC,p.id DESC LIMIT #{limit}")
 List<FeedCandidate> candidates(@Param("reader") long reader,@Param("mode") String mode,@Param("since") long since,@Param("snapshot") long snapshot,@Param("time") Long time,@Param("id") Long id,@Param("limit") int limit);
 @Select("SELECT p.id,p.published_at_ms AS time,p.body,CASE WHEN p.status='PUBLISHED' AND f.status='FOLLOWING' AND (p.publish_seq>f.follow_cutoff_seq OR EXISTS(SELECT 1 FROM follow_history h WHERE h.follower_id=#{reader} AND h.followee_id=p.author_id AND h.follow_epoch=f.follow_epoch AND h.post_id=p.id)) THEN TRUE ELSE FALSE END AS allowed FROM posts p LEFT JOIN follows f ON f.follower_id=#{reader} AND f.followee_id=p.author_id WHERE p.id=#{id}")
 Authority authority(@Param("reader") long reader,@Param("id") long id);
 @Select("SELECT "+POST+" FROM posts WHERE id=#{id}") Post visible(long id);
 @Update("UPDATE posts SET status='OFFLINE',version=version+1 WHERE id=#{id} AND author_id=#{author} AND version=#{version} AND status IN('PUBLISHED','PROCESSING')")
 int offline(@Param("author") long author,@Param("id") long id,@Param("version") long version);
}
