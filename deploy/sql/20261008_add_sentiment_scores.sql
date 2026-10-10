-- Add model probability fields required by multi-platform sentiment analysis.
SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ads_content_hot_rank ADD COLUMN sentiment_positive_score DOUBLE DEFAULT 0 AFTER sentiment_label',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'ads_content_hot_rank'
      AND column_name = 'sentiment_positive_score'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ads_content_hot_rank ADD COLUMN sentiment_neutral_score DOUBLE DEFAULT 1 AFTER sentiment_positive_score',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'ads_content_hot_rank'
      AND column_name = 'sentiment_neutral_score'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ads_content_hot_rank ADD COLUMN sentiment_negative_score DOUBLE DEFAULT 0 AFTER sentiment_neutral_score',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'ads_content_hot_rank'
      AND column_name = 'sentiment_negative_score'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;
