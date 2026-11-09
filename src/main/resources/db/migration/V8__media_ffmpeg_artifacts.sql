ALTER TABLE media_tasks
    ADD COLUMN cover_key VARCHAR(512) NULL AFTER output_key,
    ADD COLUMN meta_json JSON NULL AFTER cover_key;
