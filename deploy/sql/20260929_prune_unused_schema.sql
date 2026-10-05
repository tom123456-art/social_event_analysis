-- September 29, 2026: remove obsolete analytics structures after code-path cleanup.
DROP TABLE IF EXISTS dwd_weibo_sentiment_result;
DROP TABLE IF EXISTS ads_topic_key_content;
DROP TABLE IF EXISTS ads_topic_summary;
DROP TABLE IF EXISTS ads_topic_trend;

ALTER TABLE ads_content_hot_rank
  DROP COLUMN author_id,
  DROP COLUMN crawl_time,
  DROP COLUMN forward_count,
  DROP COLUMN view_count,
  DROP COLUMN image_url;

ALTER TABLE ads_event_overview
  DROP COLUMN event_name,
  DROP COLUMN user_count,
  DROP COLUMN comment_count,
  DROP COLUMN repost_count,
  DROP COLUMN like_count,
  DROP COLUMN view_count,
  DROP COLUMN positive_count,
  DROP COLUMN neutral_count,
  DROP COLUMN negative_count,
  DROP COLUMN peak_time;

ALTER TABLE ads_event_heat_trend
  DROP COLUMN interaction_count,
  DROP COLUMN avg_heat_index;

ALTER TABLE ads_platform_spread_timeline
  DROP COLUMN avg_heat_index,
  DROP COLUMN max_heat_index,
  DROP COLUMN peak_time,
  DROP COLUMN high_heat_count;

ALTER TABLE ads_interaction_summary
  DROP COLUMN comment_count,
  DROP COLUMN repost_count,
  DROP COLUMN like_count,
  DROP COLUMN favorite_count,
  DROP COLUMN view_count;

ALTER TABLE ads_noise_summary
  DROP COLUMN high_freq_user_count;

ALTER TABLE ads_platform_daily_heat
  DROP COLUMN peak_content_heat_index,
  DROP COLUMN heat_algorithm;

ALTER TABLE ads_platform_category_heat
  DROP COLUMN platform_content_count,
  DROP COLUMN coverage_share,
  DROP COLUMN average_relative_heat_index,
  DROP COLUMN signal_mode;
