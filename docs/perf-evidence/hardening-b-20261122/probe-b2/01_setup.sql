-- Hardening B2 probe (same method as risk list measurement A). Isolated schema, structure copied, no data copied.
-- The schema name is substituted by run.sh. Both procedures write the B1 ledger row (pending, no bucket) so the only
-- difference between them is how post_stats is maintained: B2 before = reconcile (4x COUNT + upsert), after = +1.
CREATE DATABASE @@DB@@;
USE @@DB@@;
CREATE TABLE post_likes LIKE content.post_likes;
CREATE TABLE post_dislikes LIKE content.post_dislikes;
CREATE TABLE post_comments LIKE content.post_comments;
CREATE TABLE post_reports LIKE content.post_reports;
CREATE TABLE post_stats LIKE content.post_stats;
CREATE TABLE topic_event_ledger LIKE content.topic_event_ledger;
ALTER TABLE topic_event_ledger ADD COLUMN aggregated_at_ms BIGINT NULL DEFAULT 0, ADD COLUMN revoked_at_ms BIGINT NULL, ADD KEY ix_ledger_pending(aggregated_at_ms,occurred_at_ms);
CREATE TABLE probe_timings(id BIGINT AUTO_INCREMENT PRIMARY KEY, scenario VARCHAR(64) NOT NULL, t_total_us BIGINT, t_stats_us BIGINT, t_commit_us BIGINT, KEY(scenario));
DELIMITER $$
CREATE PROCEDURE like_tx(IN p_scenario VARCHAR(64), IN p_mode VARCHAR(8), IN p_post BIGINT, IN p_user BIGINT)
BEGIN
  DECLARE t0,t1,t2,t3 DATETIME(6);
  DECLARE v_ins,v_undis INT DEFAULT 0;
  DECLARE v_likes,v_comments,v_dislikes,v_reports BIGINT DEFAULT 0;
  DECLARE v_now BIGINT;
  SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
  SET t0=SYSDATE(6);
  START TRANSACTION;
  DELETE FROM post_dislikes WHERE post_id=p_post AND user_id=p_user; SET v_undis=ROW_COUNT();
  SET v_now=CAST(UNIX_TIMESTAMP(SYSDATE(3))*1000 AS UNSIGNED);
  INSERT IGNORE INTO post_likes(post_id,user_id,created_at_ms) VALUES(p_post,p_user,v_now); SET v_ins=ROW_COUNT();
  SET t1=SYSDATE(6);
  IF p_mode='before' THEN
    SELECT COUNT(*) INTO v_likes FROM post_likes WHERE post_id=p_post;
    SELECT COUNT(*) INTO v_comments FROM post_comments WHERE post_id=p_post;
    SELECT COUNT(*) INTO v_dislikes FROM post_dislikes WHERE post_id=p_post;
    SELECT COUNT(*) INTO v_reports FROM post_reports WHERE post_id=p_post;
    INSERT INTO post_stats(post_id,like_count,dislike_count,comment_count,report_count) VALUES(p_post,v_likes,v_dislikes,v_comments,v_reports)
     ON DUPLICATE KEY UPDATE like_count=v_likes,dislike_count=v_dislikes,comment_count=v_comments,report_count=v_reports;
  ELSE
    UPDATE post_stats SET like_count=GREATEST(like_count+v_ins,0),dislike_count=GREATEST(dislike_count-v_undis,0),comment_count=GREATEST(comment_count+0,0),report_count=GREATEST(report_count+0,0) WHERE post_id=p_post;
  END IF;
  SELECT like_count INTO v_likes FROM post_stats WHERE post_id=p_post;
  SET t2=SYSDATE(6);
  IF v_ins=1 THEN
    INSERT IGNORE INTO topic_event_ledger(event_id,topic,post_id,occurred_at_ms,delta,kind,aggregated_at_ms) VALUES(CONCAT('like:',p_post,':',p_user),'probeB2',p_post,v_now,1,'LIKE',NULL);
  END IF;
  SET t3=SYSDATE(6);
  COMMIT;
  INSERT INTO probe_timings(scenario,t_total_us,t_stats_us,t_commit_us) VALUES(p_scenario,TIMESTAMPDIFF(MICROSECOND,t0,SYSDATE(6)),TIMESTAMPDIFF(MICROSECOND,t1,t2),TIMESTAMPDIFF(MICROSECOND,t3,SYSDATE(6)));
END$$
CREATE PROCEDURE like_loop(IN p_scenario VARCHAR(64), IN p_mode VARCHAR(8), IN p_post BIGINT, IN p_user_start BIGINT, IN p_n INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  WHILE i<p_n DO CALL like_tx(p_scenario,p_mode,p_post,p_user_start+i); SET i=i+1; END WHILE;
END$$
CREATE PROCEDURE seed_likes(IN p_post BIGINT, IN p_base BIGINT, IN p_n INT)
BEGIN
  DECLARE done INT DEFAULT 0;
  WHILE done<p_n DO
    INSERT INTO post_likes(post_id,user_id,created_at_ms)
      WITH RECURSIVE s(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM s WHERE n<LEAST(50000,p_n-done)-1)
      SELECT p_post,p_base+done+n,0 FROM s;
    SET done=done+LEAST(50000,p_n-done);
  END WHILE;
END$$
DELIMITER ;
