package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MaterialMapper {
 record Profile(long postId,String title,String description,String shareMode,String passwordHash,long shareEpoch,
                boolean licenseDownload,boolean licenseCommercial,Long defaultVersionId,long createdAtMs,long updatedAtMs) {}
 record Version(long id,long postId,int revision,String state,String rawObjectKey,String previewObjectKey,String coverObjectKey,
                Long durationMs,String metaJson,int attempt,long token,long createdAtMs) {}
 record DiscoverRow(long postId,long authorId,long publishedAtMs,String title,String description,Long defaultVersionId,String coverKey,String shareMode) {}

 @Insert("""
  INSERT INTO material_profiles(post_id,title,description,share_mode,password_hash,share_epoch,license_download,license_commercial,default_version_id,created_at_ms,updated_at_ms)
  VALUES(#{post},#{title},#{description},#{share},#{password},0,#{download},#{commercial},NULL,#{at},#{at})
  """)
 int insertProfile(@Param("post")long post,@Param("title")String title,@Param("description")String description,@Param("share")String share,
                   @Param("password")String password,@Param("download")boolean download,@Param("commercial")boolean commercial,@Param("at")long at);

 @Select("""
  SELECT post_id AS postId,title,description,share_mode AS shareMode,password_hash AS passwordHash,share_epoch AS shareEpoch,
         license_download AS licenseDownload,license_commercial AS licenseCommercial,default_version_id AS defaultVersionId,
         created_at_ms AS createdAtMs,updated_at_ms AS updatedAtMs
  FROM material_profiles WHERE post_id=#{post}
  """)
 Profile profile(long post);

 @Update("""
  UPDATE material_profiles SET title=#{title},description=#{description},share_mode=#{share},password_hash=#{password},
  share_epoch=share_epoch+#{bump},license_download=#{download},license_commercial=#{commercial},updated_at_ms=#{at}
  WHERE post_id=#{post}
  """)
 int updateProfile(@Param("post")long post,@Param("title")String title,@Param("description")String description,@Param("share")String share,
                   @Param("password")String password,@Param("bump")int bump,@Param("download")boolean download,@Param("commercial")boolean commercial,@Param("at")long at);

 /** Only advance default when candidate revision is strictly greater than the current default. */
 @Update("""
  UPDATE material_profiles mp
  LEFT JOIN material_versions cur ON cur.id=mp.default_version_id
  INNER JOIN material_versions cand ON cand.id=#{version} AND cand.post_id=mp.post_id
  SET mp.default_version_id=#{version},mp.updated_at_ms=#{at}
  WHERE mp.post_id=#{post}
    AND (mp.default_version_id IS NULL OR cand.revision>COALESCE(cur.revision,-1))
  """)
 int setDefaultIfNewer(@Param("post")long post,@Param("version")long version,@Param("at")long at);

 @Insert("""
  INSERT INTO material_versions(post_id,revision,state,raw_object_key,created_at_ms)
  VALUES(#{post},#{revision},'PENDING',#{raw},#{at})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insertVersion(VersionInsert row);

 class VersionInsert { public long id; public long post; public int revision; public String raw; public long at; }

 @Select("SELECT COALESCE(MAX(revision),0) FROM material_versions WHERE post_id=#{post}")
 int maxRevision(long post);

 @Select("""
  SELECT id,post_id AS postId,revision,state,raw_object_key AS rawObjectKey,preview_object_key AS previewObjectKey,
         cover_object_key AS coverObjectKey,duration_ms AS durationMs,CAST(meta_json AS CHAR) AS metaJson,attempt,
         lease_token AS token,created_at_ms AS createdAtMs
  FROM material_versions WHERE id=#{id}
  """)
 Version version(long id);

 @Select("""
  SELECT id,post_id AS postId,revision,state,raw_object_key AS rawObjectKey,preview_object_key AS previewObjectKey,
         cover_object_key AS coverObjectKey,duration_ms AS durationMs,CAST(meta_json AS CHAR) AS metaJson,attempt,
         lease_token AS token,created_at_ms AS createdAtMs
  FROM material_versions WHERE post_id=#{post} ORDER BY revision
  """)
 List<Version> versions(long post);

 @Select("""
  SELECT id,post_id AS postId,revision,state,raw_object_key AS rawObjectKey,preview_object_key AS previewObjectKey,
         cover_object_key AS coverObjectKey,duration_ms AS durationMs,CAST(meta_json AS CHAR) AS metaJson,attempt,
         lease_token AS token,created_at_ms AS createdAtMs
  FROM material_versions WHERE state IN('PENDING','PROCESSING') AND next_retry_at<=CURRENT_TIMESTAMP(6)
    AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY id LIMIT 1 FOR UPDATE
  """)
 Version claimable();

 /** Lease covers FFmpeg wall clock (2m) plus store I/O margin; takeover uses distinct derived object keys. */
 @Update("""
  UPDATE material_versions SET state='PROCESSING',attempt=attempt+1,lease_token=lease_token+1,
  lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP(6)) WHERE id=#{id} AND state IN('PENDING','PROCESSING')
    AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6))
  """)
 int claim(long id);

 /** Expired lease loses authority even with a matching token (DB clock). */
 @Update("""
  UPDATE material_versions SET state='READY',preview_object_key=#{preview},cover_object_key=#{cover},duration_ms=#{duration},
  meta_json=#{meta},lease_until=NULL,last_error=NULL
  WHERE id=#{id} AND lease_token=#{token} AND state='PROCESSING' AND lease_until>CURRENT_TIMESTAMP(6)
  """)
 int complete(@Param("id")long id,@Param("token")long token,@Param("preview")String preview,@Param("cover")String cover,
              @Param("duration")Long duration,@Param("meta")String meta);

 @Update("""
  UPDATE material_versions SET state=IF(attempt>=5,'FAILED','PENDING'),
  next_retry_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(6)),lease_until=NULL,last_error=#{error}
  WHERE id=#{id} AND lease_token=#{token} AND state='PROCESSING' AND lease_until>CURRENT_TIMESTAMP(6)
  """)
 int fail(@Param("id")long id,@Param("token")long token,@Param("delay")int delay,@Param("error")String error);

 @Insert("INSERT IGNORE INTO material_tags(post_id,tag) VALUES(#{post},#{tag})")
 int addTag(@Param("post")long post,@Param("tag")String tag);

 @Delete("DELETE FROM material_tags WHERE post_id=#{post}")
 int clearTags(long post);

 @Select("SELECT tag FROM material_tags WHERE post_id=#{post} ORDER BY tag")
 List<String> tags(long post);

 @Select("""
  SELECT p.id AS postId,p.author_id AS authorId,p.published_at_ms AS publishedAtMs,mp.title,mp.description,
         mp.default_version_id AS defaultVersionId,v.cover_object_key AS coverKey,mp.share_mode AS shareMode
  FROM posts p
  JOIN material_profiles mp ON mp.post_id=p.id
  LEFT JOIN material_versions v ON v.id=mp.default_version_id
  WHERE p.post_kind='MATERIAL' AND p.status IN('PUBLISHED','PROCESSING')
    AND (#{q} IS NULL OR mp.title LIKE CONCAT('%',#{q},'%') OR mp.description LIKE CONCAT('%',#{q},'%'))
    AND (#{tag} IS NULL OR EXISTS (SELECT 1 FROM material_tags t WHERE t.post_id=p.id AND t.tag=#{tag}))
    AND (#{beforeMs} IS NULL OR p.published_at_ms < #{beforeMs} OR (p.published_at_ms=#{beforeMs} AND p.id < #{beforeId}))
  ORDER BY p.published_at_ms DESC,p.id DESC
  LIMIT #{limit}
  """)
 List<DiscoverRow> discover(@Param("q")String q,@Param("tag")String tag,@Param("beforeMs")Long beforeMs,@Param("beforeId")Long beforeId,@Param("limit")int limit);

 @Update("UPDATE posts SET post_kind='MATERIAL' WHERE id=#{id} AND author_id=#{author} AND post_kind='TEXT'")
 int markMaterial(@Param("id")long id,@Param("author")long author);
}
