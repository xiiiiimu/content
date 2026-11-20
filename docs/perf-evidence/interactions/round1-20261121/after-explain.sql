SET @now=CAST(UNIX_TIMESTAMP(SYSDATE(3))*1000 AS UNSIGNED);
EXPLAIN SELECT COALESCE(SUM(net_events),0) FROM topic_minute_buckets WHERE topic='r1c100' AND bucket_start_ms>=@now-3600000;
EXPLAIN SELECT topic,bucket_start_ms,net_events,active_interactions FROM topic_minute_buckets WHERE bucket_start_ms>=@now-3600000 ORDER BY topic,bucket_start_ms;
EXPLAIN SELECT topic,bucket_start_ms,net_events,active_interactions FROM topic_minute_buckets WHERE bucket_start_ms>=@now-86400000 ORDER BY topic,bucket_start_ms;
EXPLAIN DELETE FROM topic_minute_buckets WHERE bucket_start_ms<@now-172800000 ORDER BY bucket_start_ms LIMIT 500;
EXPLAIN SELECT id FROM posts WHERE topic='r1c100' AND status='PUBLISHED' ORDER BY published_at_ms DESC,id DESC LIMIT 200;
