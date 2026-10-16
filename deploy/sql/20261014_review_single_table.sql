-- Stop the backend before this migration, then deploy the single-table version.
-- Keep data_quality_review and all its existing records unchanged.
DROP TABLE IF EXISTS data_quality_review_log;
DROP TABLE IF EXISTS data_quality_review_job;
DROP TABLE IF EXISTS data_quality_review_lock;
