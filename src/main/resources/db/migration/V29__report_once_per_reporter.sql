-- Hardening A3: one report per (post, reporter).
-- 1) Existing duplicates keep only the earliest report per (post_id, reporter_user_id): lowest created_at_ms, then lowest id.
DELETE r FROM post_reports r
JOIN (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY post_id, reporter_user_id ORDER BY created_at_ms, id) AS rn
  FROM post_reports
) ranked ON ranked.id = r.id
WHERE ranked.rn > 1;

-- 2) Cached counts follow the surviving rows.
UPDATE post_stats s
SET s.report_count = (SELECT COUNT(*) FROM post_reports r WHERE r.post_id = s.post_id);

-- 3) Enforce it.
ALTER TABLE post_reports ADD UNIQUE KEY uq_report_post_reporter (post_id, reporter_user_id);
