package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;

@Mapper
public interface InboxMapper {
 record Event(String type,long aggregate,String payload) {}
 record Receipt(String type,String hash,String decision) {}
 record Task(String kind,long author,long epoch,Long post,Long follower,Long followEpoch,String payload) {}
 @Select("SELECT event_type AS type,aggregate_id AS aggregate,payload FROM outbox_events WHERE id=#{id}") Event event(long id);
 @Insert("INSERT IGNORE INTO consumer_inbox(consumer_name,event_id,event_type,payload_hash,decision) VALUES('feed-v1',#{id},#{type},#{hash},#{decision})")
 int accept(@Param("id")long id,@Param("type")String type,@Param("hash")String hash,@Param("decision")String decision);
 @Select("SELECT event_type AS type,payload_hash AS hash,decision FROM consumer_inbox WHERE consumer_name='feed-v1' AND event_id=#{id}") Receipt receipt(long id);
 @Insert("INSERT INTO feed_delivery_tasks(event_id,author_id,delivery_epoch,state,kind,post_id,follower_id,follow_epoch,payload) VALUES(#{event},#{author},#{epoch},'NEW',#{kind},#{post},#{follower},#{followEpoch},#{payload})")
 void createTask(@Param("event")long event,@Param("author")long author,@Param("epoch")long epoch,@Param("kind")String kind,@Param("post")Long post,@Param("follower")Long follower,@Param("followEpoch")Long followEpoch,@Param("payload")String payload);
 /** Concurrent-safe repair insert; unique event_id makes repeats a no-op. */
 @Insert("INSERT IGNORE INTO feed_delivery_tasks(event_id,author_id,delivery_epoch,state,kind,post_id,follower_id,follow_epoch,payload) VALUES(#{event},#{author},#{epoch},'NEW',#{kind},#{post},#{follower},#{followEpoch},#{payload})")
 int createTaskIgnore(@Param("event")long event,@Param("author")long author,@Param("epoch")long epoch,@Param("kind")String kind,@Param("post")Long post,@Param("follower")Long follower,@Param("followEpoch")Long followEpoch,@Param("payload")String payload);
 @Select("SELECT kind,author_id AS author,delivery_epoch AS epoch,post_id AS post,follower_id AS follower,follow_epoch AS followEpoch,payload FROM feed_delivery_tasks WHERE event_id=#{id}") Task task(long id);
 @Insert("INSERT IGNORE INTO feed_event_conflicts(consumer_name,event_id,received_hash,reason,payload) VALUES('feed-v1',#{id},#{hash},#{reason},#{payload})")
 void conflict(@Param("id")long id,@Param("hash")String hash,@Param("reason")String reason,@Param("payload")String payload);
}
