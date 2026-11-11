package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;

@Mapper
public interface PostAuthoringMapper {
 record Locked(long id,long authorId,String status,String body,long version,Long publishedAtMs) {}
 @Insert("INSERT INTO posts(author_id,status,body,version) VALUES(#{author},'DRAFT',#{body},0)")
 int insertDraft(@Param("author")long author,@Param("body")String body);
 @Select("SELECT LAST_INSERT_ID()") long lastId();
 @Select("SELECT id,author_id AS authorId,status,body,version,published_at_ms AS publishedAtMs FROM posts WHERE id=#{id} FOR UPDATE")
 Locked lock(long id);
 @Update("UPDATE posts SET body=#{body},version=version+1 WHERE id=#{id} AND author_id=#{author} AND version=#{expected} AND status IN('DRAFT','PUBLISHED','PROCESSING')")
 int updateBody(@Param("id")long id,@Param("author")long author,@Param("expected")long expected,@Param("body")String body);
 @Select("SELECT id,author_id AS authorId,status,body,version,published_at_ms AS publishedAtMs FROM posts WHERE id=#{id}")
 Locked get(long id);
}
