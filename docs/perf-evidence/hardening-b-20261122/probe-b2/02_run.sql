USE @@DB@@;
SET SESSION cte_max_recursion_depth=100000;
-- one post per (mode, size) so the two modes never share a post; stats rows start exact (as after the first interaction)
CALL seed_likes(9300000002, 1000000000, 100000); CALL seed_likes(9300000003, 1000000000, 1000000);
CALL seed_likes(9300000012, 1000000000, 100000); CALL seed_likes(9300000013, 1000000000, 1000000);
INSERT INTO post_stats(post_id,like_count,dislike_count,comment_count,report_count)
 SELECT p, (SELECT COUNT(*) FROM post_likes WHERE post_id=p),0,0,0 FROM (SELECT 9300000001 p UNION ALL SELECT 9300000002 UNION ALL SELECT 9300000003 UNION ALL SELECT 9300000011 UNION ALL SELECT 9300000012 UNION ALL SELECT 9300000013) x;
SELECT post_id,like_count FROM post_stats ORDER BY post_id;
CALL like_loop('warmup','before',9300000001,7000000000,5); CALL like_loop('warmup','before',9300000002,7000000000,5); CALL like_loop('warmup','before',9300000003,7000000000,5);
CALL like_loop('warmup','after',9300000011,7000000000,5); CALL like_loop('warmup','after',9300000012,7000000000,5); CALL like_loop('warmup','after',9300000013,7000000000,5);
CALL like_loop('before_0','before',9300000001,8000000000,100);
CALL like_loop('before_100k','before',9300000002,8000000000,100);
CALL like_loop('before_1M','before',9300000003,8000000000,100);
CALL like_loop('after_0','after',9300000011,8000000000,100);
CALL like_loop('after_100k','after',9300000012,8000000000,100);
CALL like_loop('after_1M','after',9300000013,8000000000,100);
SELECT post_id,like_count,(SELECT COUNT(*) FROM post_likes l WHERE l.post_id=s.post_id) counted FROM post_stats s ORDER BY post_id;
WITH r AS (SELECT scenario,t_total_us,t_stats_us,t_commit_us,PERCENT_RANK() OVER (PARTITION BY scenario ORDER BY t_total_us) pr FROM probe_timings WHERE scenario<>'warmup')
SELECT scenario,COUNT(*) n,ROUND(AVG(t_total_us)/1000,2) avg_ms,ROUND(MAX(IF(pr<=0.5,t_total_us,NULL))/1000,2) p50_ms,ROUND(MAX(IF(pr<=0.95,t_total_us,NULL))/1000,2) p95_ms,
 ROUND(MAX(t_total_us)/1000,2) max_ms,ROUND(AVG(t_stats_us)/1000,2) stats_avg_ms,ROUND(AVG(t_commit_us)/1000,2) commit_avg_ms FROM r GROUP BY scenario ORDER BY scenario;
