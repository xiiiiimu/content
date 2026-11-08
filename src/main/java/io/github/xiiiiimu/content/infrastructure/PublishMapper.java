package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.PublishRepository;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PublishMapper extends PublishRepository {
    @Override @Select("SELECT CASE WHEN generation>0 AND state IN('PRIMARY','READY') THEN state ELSE 'BLOCKED' END FROM feed_control WHERE id=1 FOR SHARE") String gate();
    @Override
    @Select("SELECT mode, mode_epoch AS epoch, publish_seq AS sequence FROM author_feed_mode WHERE author_id=#{authorId} FOR UPDATE")
    AuthorMode lockAuthor(long authorId);

    @Override
    @Select("SELECT author_id AS authorId, status, version FROM posts WHERE id=#{postId} FOR UPDATE")
    Draft lockPost(long postId);

    @Override
    @Select("SELECT post_id AS postId, expected_version AS expectedVersion, response_snapshot AS snapshot FROM publish_requests WHERE author_id=#{authorId} AND operation='PUBLISH' AND request_key=#{key} FOR UPDATE")
    Receipt receipt(@Param("authorId") long authorId, @Param("key") String key);

    @Override
    @Update("UPDATE author_feed_mode SET publish_seq=publish_seq+1 WHERE author_id=#{authorId}")
    int advanceSequence(long authorId);

    @Override
    @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")
    long databaseTimeMs();

    @Override
    @Update("UPDATE posts SET status='PUBLISHED',version=version+1,delivery_mode=#{mode},delivery_epoch=#{epoch},publish_seq=#{sequence},published_at_ms=#{timeMs},publish_request_id=#{key} WHERE id=#{postId} AND status='DRAFT' AND version=#{expectedVersion}")
    int publish(@Param("postId") long postId, @Param("expectedVersion") long expectedVersion,
                @Param("mode") String mode, @Param("epoch") long epoch, @Param("sequence") long sequence,
                @Param("timeMs") long timeMs, @Param("key") String key);

    @Override
    @Insert("INSERT INTO outbox_events(event_type,aggregate_id,payload) VALUES ('PostPublished',#{postId},'{}')")
    int insertOutbox(long postId);

    @Override
    @Select("SELECT LAST_INSERT_ID()")
    long insertedEventId();

    @Override
    @Update("UPDATE outbox_events SET payload=#{payload} WHERE id=#{eventId} AND status='NEW'")
    int completeOutbox(@Param("eventId") long eventId, @Param("payload") String payload);

    @Override
    @Insert("INSERT INTO publish_requests(author_id,operation,request_key,post_id,expected_version,response_snapshot) VALUES (#{authorId},'PUBLISH',#{key},#{postId},#{expectedVersion},#{snapshot})")
    int saveReceipt(@Param("authorId") long authorId, @Param("key") String key, @Param("postId") long postId,
                    @Param("expectedVersion") long expectedVersion, @Param("snapshot") String snapshot);
}
