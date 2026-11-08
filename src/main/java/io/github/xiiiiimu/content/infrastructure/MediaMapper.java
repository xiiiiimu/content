package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MediaMapper {
 record Task(long id,long postId,long expectedVersion,String objectKey,String state,int attempt,long token,String outputKey,String coverKey,String metaJson) {}
 @Insert("INSERT INTO media_tasks(post_id,expected_post_version,object_key,state) VALUES(#{post},#{version},#{key},'NEW')")
 int create(@Param("post")long post,@Param("version")long version,@Param("key")String key);
 @Select("SELECT id,post_id AS postId,expected_post_version AS expectedVersion,object_key AS objectKey,state,attempt,lease_token AS token,output_key AS outputKey,cover_key AS coverKey,CAST(meta_json AS CHAR) AS metaJson FROM media_tasks WHERE post_id=#{post}")
 Task byPost(long post);
 @Select("SELECT id,post_id AS postId,expected_post_version AS expectedVersion,object_key AS objectKey,state,attempt,lease_token AS token,output_key AS outputKey,cover_key AS coverKey,CAST(meta_json AS CHAR) AS metaJson FROM media_tasks WHERE state IN('NEW','RUNNING') AND next_retry_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY id LIMIT 1 FOR UPDATE")
 Task claimable();
 @Update("UPDATE media_tasks SET state='RUNNING',attempt=attempt+1,lease_token=lease_token+1,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=#{id} AND state IN('NEW','RUNNING') AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6))")
 int claim(long id);
 @Select("SELECT lease_token FROM media_tasks WHERE id=#{id}") Long token(long id);
 @Update("UPDATE media_tasks SET state='DONE',output_key=#{output},cover_key=#{cover},meta_json=#{meta},lease_until=NULL,last_error=NULL WHERE id=#{id} AND lease_token=#{token} AND state='RUNNING'")
 int complete(@Param("id")long id,@Param("token")long token,@Param("output")String output,@Param("cover")String cover,@Param("meta")String meta);
 @Update("UPDATE media_tasks SET state=IF(attempt>=5,'DEAD','NEW'),next_retry_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(6)),lease_until=NULL,last_error=#{error} WHERE id=#{id} AND lease_token=#{token} AND state='RUNNING'")
 int fail(@Param("id")long id,@Param("token")long token,@Param("delay")int delay,@Param("error")String error);
}
