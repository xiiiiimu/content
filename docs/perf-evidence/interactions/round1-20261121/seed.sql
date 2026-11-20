SET SESSION cte_max_recursion_depth=100000;
INSERT INTO author_feed_mode(author_id,mode,mode_epoch,publish_seq) VALUES(500000,'PULL',0,100000);
INSERT INTO follows(follower_id,followee_id,status,follow_epoch,follow_cutoff_seq,history_since_ms,history_limit)
 WITH RECURSIVE s(n) AS (SELECT 600001 UNION ALL SELECT n+1 FROM s WHERE n<604000) SELECT n,500000,'FOLLOWING',1,0,0,0 FROM s;
-- r=0 warmup(4) r=1..3 (a, 100 posts) r=4..9 (b/c, 4 posts)
INSERT INTO posts(id,author_id,status,body,version,published_at_ms,publish_seq,delivery_mode,delivery_epoch)
 WITH RECURSIVE r(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM r WHERE n<9), k(m) AS (SELECT 0 UNION ALL SELECT m+1 FROM k WHERE m<99)
 SELECT 9200000000+r.n*1000+k.m,500000,'PUBLISHED','perf-r1',1,CAST(UNIX_TIMESTAMP(SYSDATE(3))*1000 AS UNSIGNED),r.n*1000+k.m+1,'PULL',0
 FROM r CROSS JOIN k WHERE (r.n BETWEEN 1 AND 3) OR k.m<4;
SELECT 'posts',COUNT(*) FROM posts; SELECT 'follows',COUNT(*) FROM follows;
