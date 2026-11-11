package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
 record User(long id,String username,String passwordHash,String status,long createdAtMs) {}
 @Insert("INSERT INTO users(username,password_hash,status,created_at_ms) VALUES(#{username},#{hash},'ACTIVE',#{at})")
 int insert(@Param("username")String username,@Param("hash")String hash,@Param("at")long at);
 @Select("SELECT LAST_INSERT_ID()") long lastId();
 @Select("SELECT id,username,password_hash AS passwordHash,status,created_at_ms AS createdAtMs FROM users WHERE username=#{username}")
 User byUsername(String username);
 @Select("SELECT id,username,password_hash AS passwordHash,status,created_at_ms AS createdAtMs FROM users WHERE id=#{id}")
 User byId(long id);
 @Insert("INSERT IGNORE INTO author_feed_mode(author_id,mode) VALUES(#{id},'PULL')")
 int ensureAuthor(long id);
}
