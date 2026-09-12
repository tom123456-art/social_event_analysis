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
  share_count STRING,
  favorite_count STRING,
  view_count STRING,
  hot_rank STRING,
  location STRING,
  user_age_group STRING,
  user_gender STRING,
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
  share_count BIGINT,
  favorite_count BIGINT,
  view_count BIGINT,
  hot_rank INT,
  location STRING,
  user_age_group STRING,
  user_gender STRING,
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
