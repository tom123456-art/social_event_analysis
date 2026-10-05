-- Restore the propagation parent reference for databases that already ran
-- the 20260929 cleanup migration.
SET @parent_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ads_content_hot_rank'
    AND column_name = 'parent_content_id'
);
SET @restore_parent_sql := IF(
  @parent_column_exists = 0,
  'ALTER TABLE ads_content_hot_rank ADD COLUMN parent_content_id VARCHAR(128) AFTER content_id',
  'SELECT 1'
);
PREPARE restore_parent_stmt FROM @restore_parent_sql;
EXECUTE restore_parent_stmt;
DEALLOCATE PREPARE restore_parent_stmt;
