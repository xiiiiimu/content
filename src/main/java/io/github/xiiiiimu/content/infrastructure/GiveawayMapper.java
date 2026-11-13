package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface GiveawayMapper {
 record Giveaway(long id,long sourceCommentId,long postId,long authorId,String state,String ruleTreeJson,String toolVersionsJson,
                 String timezone,Long windowStartMs,Long deadlineMs,int winnerCount,Long rewardVersionId,String rewardLicense,
                 String summaryText,Long announceCommentId,Long frozenAtMs,Long drawnAtMs,long createdAtMs) {}
 record Candidate(long giveawayId,long accountId,String evidenceJson) {}
 record Winner(long giveawayId,long accountId,int drawRank,Long entitlementId) {}
 record Entitlement(long id,long accountId,long postId,long versionId,String license,String sourceKind,long sourceId,String state) {}

 @Insert("""
  INSERT INTO giveaways(source_comment_id,post_id,author_id,state,rule_tree_json,tool_versions_json,timezone,window_start_ms,deadline_ms,
    winner_count,reward_version_id,reward_license,summary_text,created_at_ms,updated_at_ms)
  VALUES(#{source},#{post},#{author},#{state},#{rules},#{tools},#{tz},#{windowStart},#{deadline},#{winners},#{reward},#{license},#{summary},#{at},#{at})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insert(GiveawayInsert row);
 class GiveawayInsert {
  public long id; public long source; public long post; public long author; public String state; public String rules; public String tools;
  public String tz; public Long windowStart; public Long deadline; public int winners; public Long reward; public String license; public String summary; public long at;
 }

 @Select("""
  SELECT id,source_comment_id AS sourceCommentId,post_id AS postId,author_id AS authorId,state,CAST(rule_tree_json AS CHAR) AS ruleTreeJson,
         CAST(tool_versions_json AS CHAR) AS toolVersionsJson,timezone,window_start_ms AS windowStartMs,deadline_ms AS deadlineMs,
         winner_count AS winnerCount,reward_version_id AS rewardVersionId,reward_license AS rewardLicense,summary_text AS summaryText,
         announce_comment_id AS announceCommentId,frozen_at_ms AS frozenAtMs,drawn_at_ms AS drawnAtMs,created_at_ms AS createdAtMs
  FROM giveaways WHERE id=#{id} FOR UPDATE
  """)
 Giveaway lock(long id);

 @Select("""
  SELECT id,source_comment_id AS sourceCommentId,post_id AS postId,author_id AS authorId,state,CAST(rule_tree_json AS CHAR) AS ruleTreeJson,
         CAST(tool_versions_json AS CHAR) AS toolVersionsJson,timezone,window_start_ms AS windowStartMs,deadline_ms AS deadlineMs,
         winner_count AS winnerCount,reward_version_id AS rewardVersionId,reward_license AS rewardLicense,summary_text AS summaryText,
         announce_comment_id AS announceCommentId,frozen_at_ms AS frozenAtMs,drawn_at_ms AS drawnAtMs,created_at_ms AS createdAtMs
  FROM giveaways WHERE id=#{id}
  """)
 Giveaway get(long id);

 @Select("""
  SELECT id,source_comment_id AS sourceCommentId,post_id AS postId,author_id AS authorId,state,CAST(rule_tree_json AS CHAR) AS ruleTreeJson,
         CAST(tool_versions_json AS CHAR) AS toolVersionsJson,timezone,window_start_ms AS windowStartMs,deadline_ms AS deadlineMs,
         winner_count AS winnerCount,reward_version_id AS rewardVersionId,reward_license AS rewardLicense,summary_text AS summaryText,
         announce_comment_id AS announceCommentId,frozen_at_ms AS frozenAtMs,drawn_at_ms AS drawnAtMs,created_at_ms AS createdAtMs
  FROM giveaways WHERE source_comment_id=#{comment}
  """)
 Giveaway bySource(long comment);

 @Select("""
  SELECT id FROM giveaways WHERE state='OPEN' AND deadline_ms IS NOT NULL AND deadline_ms<=#{now} ORDER BY id LIMIT 20
  """)
 List<Long> dueOpen(long now);

 @Update("""
  UPDATE giveaways SET state=#{state},announce_comment_id=COALESCE(#{announce},announce_comment_id),summary_text=COALESCE(#{summary},summary_text),
  frozen_at_ms=COALESCE(#{frozen},frozen_at_ms),drawn_at_ms=COALESCE(#{drawn},drawn_at_ms),last_error=#{error},updated_at_ms=#{at}
  WHERE id=#{id} AND state=#{from}
  """)
 int transition(@Param("id")long id,@Param("from")String from,@Param("state")String state,@Param("announce")Long announce,
                @Param("summary")String summary,@Param("frozen")Long frozen,@Param("drawn")Long drawn,@Param("error")String error,@Param("at")long at);

 @Update("UPDATE giveaways SET state='CANCELLED',updated_at_ms=#{at},last_error=#{error} WHERE id=#{id} AND state IN('PENDING_CLARIFY','OPEN')")
 int cancel(@Param("id")long id,@Param("error")String error,@Param("at")long at);

 @Insert("INSERT IGNORE INTO giveaway_candidates(giveaway_id,account_id,evidence_json,created_at_ms) VALUES(#{g},#{account},#{evidence},#{at})")
 int addCandidate(@Param("g")long g,@Param("account")long account,@Param("evidence")String evidence,@Param("at")long at);

 @Select("SELECT giveaway_id AS giveawayId,account_id AS accountId,CAST(evidence_json AS CHAR) AS evidenceJson FROM giveaway_candidates WHERE giveaway_id=#{g} ORDER BY account_id")
 List<Candidate> candidates(long g);

 @Insert("INSERT IGNORE INTO giveaway_winners(giveaway_id,account_id,draw_rank,entitlement_id,created_at_ms) VALUES(#{g},#{account},#{rank},#{ent},#{at})")
 int addWinner(@Param("g")long g,@Param("account")long account,@Param("rank")int rank,@Param("ent")Long ent,@Param("at")long at);
 @Update("UPDATE giveaway_winners SET entitlement_id=#{ent},notified_at_ms=#{at} WHERE giveaway_id=#{g} AND account_id=#{account} AND (entitlement_id IS NULL OR entitlement_id=#{ent})")
 int setWinnerEntitlement(@Param("g")long g,@Param("account")long account,@Param("ent")long ent,@Param("at")long at);

 @Select("SELECT giveaway_id AS giveawayId,account_id AS accountId,draw_rank AS drawRank,entitlement_id AS entitlementId FROM giveaway_winners WHERE giveaway_id=#{g} ORDER BY draw_rank")
 List<Winner> winners(long g);

 @Insert("""
  INSERT INTO material_entitlements(account_id,post_id,version_id,license,source_kind,source_id,state,created_at_ms)
  VALUES(#{account},#{post},#{version},#{license},#{kind},#{source},'ACTIVE',#{at})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insertEntitlement(EntitlementInsert row);
 class EntitlementInsert { public long id; public long account; public long post; public long version; public String license; public String kind; public long source; public long at; }

 @Select("""
  SELECT id,account_id AS accountId,post_id AS postId,version_id AS versionId,license,source_kind AS sourceKind,source_id AS sourceId,state
  FROM material_entitlements WHERE account_id=#{account} AND post_id=#{post} AND version_id=#{version} AND state='ACTIVE' LIMIT 1
  """)
 Entitlement activeEntitlement(@Param("account")long account,@Param("post")long post,@Param("version")long version);

 @Insert("INSERT IGNORE INTO post_favorites(post_id,user_id,created_at_ms) VALUES(#{post},#{user},#{at})")
 int favorite(@Param("post")long post,@Param("user")long user,@Param("at")long at);
 @Delete("DELETE FROM post_favorites WHERE post_id=#{post} AND user_id=#{user}")
 int unfavorite(@Param("post")long post,@Param("user")long user);
 @Select("SELECT COUNT(*) FROM post_favorites WHERE post_id=#{post} AND user_id=#{user}")
 int hasFavorite(@Param("post")long post,@Param("user")long user);
 @Select("SELECT user_id FROM post_favorites WHERE post_id=#{post}")
 List<Long> favoritedUsers(long post);
 @Select("SELECT COUNT(*) FROM post_favorites WHERE post_id=#{post}")
 long favoriteCount(long post);
}
