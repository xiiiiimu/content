package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;

@Mapper
public interface PostAuthoringMapper {
 record Locked(long id,long authorId,String status,String body,long version,Long publishedAtMs,String topic) {}
 String COLS="id,author_id AS authorId,status,body,version,published_at_ms AS publishedAtMs,topic";
 @Insert("INSERT INTO posts(author_id,status,body,version) VALUES(#{author},'DRAFT',#{body},0)")
 int insertDraft(@Param("author")long author,@Param("body")String body);
 @Insert("INSERT INTO posts(author_id,status,body,version,topic) VALUES(#{author},'DRAFT',#{body},0,#{topic})")
 int insertDraftWithTopic(@Param("author")long author,@Param("body")String body,@Param("topic")String topic);
 @Select("SELECT LAST_INSERT_ID()") long lastId();
 @Select("SELECT "+COLS+" FROM posts WHERE id=#{id} FOR UPDATE")
 Locked lock(long id);
 @Update("UPDATE posts SET body=#{body},version=version+1 WHERE id=#{id} AND author_id=#{author} AND version=#{expected} AND status IN('DRAFT','PUBLISHED','PROCESSING')")
 int updateBody(@Param("id")long id,@Param("author")long author,@Param("expected")long expected,@Param("body")String body);
 @Update("UPDATE posts SET body=#{body},topic=#{topic},version=version+1 WHERE id=#{id} AND author_id=#{author} AND version=#{expected} AND status IN('DRAFT','PUBLISHED','PROCESSING')")
 int updateBodyAndTopic(@Param("id")long id,@Param("author")long author,@Param("expected")long expected,@Param("body")String body,@Param("topic")String topic);
 @Select("SELECT "+COLS+" FROM posts WHERE id=#{id}")
 Locked get(long id);
}
