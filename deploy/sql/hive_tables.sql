CREATE DATABASE IF NOT EXISTS social_ods;
CREATE DATABASE IF NOT EXISTS social_dwd;
CREATE DATABASE IF NOT EXISTS social_dws;
CREATE DATABASE IF NOT EXISTS social_ads;

CREATE EXTERNAL TABLE IF NOT EXISTS social_ods.ods_social_content_raw (
  event_id STRING,
  event_name STRING,
  platform STRING,
  content_id STRING,
  parent_content_id STRING,
  content_type STRING,
  title STRING,
  content_text STRING,
  author_id STRING,
  author_name STRING,
  publish_time STRING,
  crawl_time STRING,
  like_count STRING,
  comment_count STRING,
  repost_count STRING,
  favorite_count STRING,
  view_count STRING,
  hot_rank STRING,
  keywords STRING
)
PARTITIONED BY (batch_id STRING)
ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.OpenCSVSerde'
WITH SERDEPROPERTIES (
  "separatorChar" = ",",
  "quoteChar" = "\""
)
STORED AS TEXTFILE
LOCATION '/social-hotspot-analytics/raw/social_event';

CREATE EXTERNAL TABLE IF NOT EXISTS social_dwd.dwd_social_content_detail (
  event_id STRING,
  event_name STRING,
  platform STRING,
  content_id STRING,
  parent_content_id STRING,
  content_type STRING,
  title STRING,
  clean_text STRING,
  author_id STRING,
  author_name STRING,
  publish_time TIMESTAMP,
  crawl_time TIMESTAMP,
  like_count BIGINT,
  comment_count BIGINT,
  repost_count BIGINT,
  favorite_count BIGINT,
  view_count BIGINT,
  hot_rank INT,
  keywords STRING,
  time_bucket TIMESTAMP,
  interaction_count BIGINT,
  sentiment_label STRING,
  is_noise BOOLEAN,
  hot_score DOUBLE
)
PARTITIONED BY (batch_id STRING)
STORED AS PARQUET
LOCATION '/social-hotspot-analytics/warehouse/dwd/dwd_social_content_detail';

CREATE EXTERNAL TABLE IF NOT EXISTS social_ads.ads_event_heat_trend (
  event_id STRING,
  time_bucket TIMESTAMP,
  platform STRING,
  content_count BIGINT,
  interaction_count BIGINT,
  hot_score DOUBLE
)
STORED AS PARQUET
LOCATION '/social-hotspot-analytics/warehouse/ads/ads_event_heat_trend';

CREATE EXTERNAL TABLE IF NOT EXISTS social_ads.ads_platform_daily_heat (
  event_id STRING,
  platform STRING,
  time_bucket DATE,
  content_count BIGINT,
  average_heat_index DOUBLE,
  peak_content_heat_index DOUBLE,
  high_heat_content_count BIGINT,
  heat_algorithm STRING
)
STORED AS PARQUET
LOCATION '/social-hotspot-analytics/warehouse/ads/ads_platform_daily_heat';
CREATE EXTERNAL TABLE IF NOT EXISTS social_ads.ads_platform_category_heat (
  event_id STRING,
  platform STRING,
  time_bucket DATE,
  category STRING,
  content_count BIGINT,
  platform_content_count BIGINT,
  coverage_share DOUBLE,
  average_relative_heat_index DOUBLE,
  composite_attention_index DOUBLE,
  signal_mode STRING
)
STORED AS PARQUET
LOCATION '/social-hotspot-analytics/warehouse/ads/ads_platform_category_heat';
CREATE EXTERNAL TABLE IF NOT EXISTS social_ads.ads_topic_trend (
  event_id STRING,
  topic_id STRING,
  topic_name STRING,
  category STRING,
  time_bucket DATE,
  content_count BIGINT,
  platform_count BIGINT,
  burst_index DOUBLE
)
STORED AS PARQUET
LOCATION '/social-hotspot-analytics/warehouse/ads/ads_topic_trend';

CREATE EXTERNAL TABLE IF NOT EXISTS social_ads.ads_topic_summary (
  event_id STRING,
  topic_id STRING,
  topic_name STRING,
  category STRING,
  first_publish_time TIMESTAMP,
  peak_time TIMESTAMP,
  latest_publish_time TIMESTAMP,
  content_count BIGINT,
  platform_count BIGINT,
  duration_days INT,
  peak_daily_count BIGINT,
  peak_burst_index DOUBLE,
  current_stage STRING
)
STORED AS PARQUET
LOCATION '/social-hotspot-analytics/warehouse/ads/ads_topic_summary';

CREATE EXTERNAL TABLE IF NOT EXISTS social_ads.ads_topic_key_content (
  event_id STRING,
  topic_id STRING,
  event_role STRING,
  publish_time TIMESTAMP,
  platform STRING,
  content_id STRING,
  title STRING,
  clean_text STRING,
  source_url STRING
)
STORED AS PARQUET
LOCATION '/social-hotspot-analytics/warehouse/ads/ads_topic_key_content';
