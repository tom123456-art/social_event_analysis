CREATE DATABASE IF NOT EXISTS social_hotspot_analytics
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE social_hotspot_analytics;

CREATE TABLE IF NOT EXISTS event_info (
  event_id VARCHAR(64) PRIMARY KEY,
  event_name VARCHAR(200) NOT NULL,
  description VARCHAR(500),
  start_time DATETIME,
  end_time DATETIME,
  status VARCHAR(32) DEFAULT 'ACTIVE',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS etl_batch (
  batch_id VARCHAR(32) PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  source_path VARCHAR(500) NOT NULL,
  source_count BIGINT DEFAULT 0,
  valid_count BIGINT DEFAULT 0,
  dirty_count BIGINT DEFAULT 0,
  duplicate_count BIGINT DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  current_stage VARCHAR(32) NOT NULL,
  started_at DATETIME,
  finished_at DATETIME,
  error_message VARCHAR(1000),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS etl_task_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id VARCHAR(32) NOT NULL,
  task_name VARCHAR(100) NOT NULL,
  task_stage VARCHAR(32) NOT NULL,
  input_count BIGINT DEFAULT 0,
  output_count BIGINT DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  started_at DATETIME,
  finished_at DATETIME,
  error_message VARCHAR(1000),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_batch_id (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_event_overview (
  event_id VARCHAR(64) PRIMARY KEY,
  event_name VARCHAR(200),
  content_count BIGINT,
  user_count BIGINT,
  platform_count BIGINT,
  comment_count BIGINT,
  repost_count BIGINT,
  like_count BIGINT,
  view_count BIGINT,
  positive_count BIGINT,
  neutral_count BIGINT,
  negative_count BIGINT,
  hot_score DECIMAL(18,2),
  peak_time DATETIME,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_event_heat_trend (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64),
  time_bucket DATETIME,
  platform VARCHAR(50),
  content_count BIGINT,
  interaction_count BIGINT,
  hot_score DECIMAL(18,2),
  avg_heat_index DECIMAL(8,2),
  UNIQUE KEY uk_heat (event_id, time_bucket, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_platform_spread_timeline (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64),
  platform VARCHAR(50),
  first_publish_time DATETIME,
  delay_minutes BIGINT,
  content_count BIGINT,
  hot_score DECIMAL(18,2),
  avg_heat_index DECIMAL(8,2),
  max_heat_index DECIMAL(8,2),
  peak_time DATETIME,
  high_heat_count BIGINT,
  UNIQUE KEY uk_platform_timeline (event_id, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_interaction_summary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64),
  platform VARCHAR(50),
  content_count BIGINT,
  comment_count BIGINT,
  repost_count BIGINT,
  like_count BIGINT,
  favorite_count BIGINT,
  view_count BIGINT,
  hot_score DECIMAL(18,2),
  UNIQUE KEY uk_interaction (event_id, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_content_hot_rank (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64),
  rank_no INT,
  platform VARCHAR(50),
  content_id VARCHAR(128),
  parent_content_id VARCHAR(128),
  content_type VARCHAR(50),
  title VARCHAR(300),
  clean_text VARCHAR(1000),
  author_id VARCHAR(128),
  author_name VARCHAR(100),
  publish_time DATETIME,
  crawl_time DATETIME,
  keywords VARCHAR(1000),
  category VARCHAR(100),
  like_count BIGINT,
  favorite_count BIGINT,
  comment_count BIGINT,
  forward_count BIGINT,
  repost_count BIGINT,
  view_count BIGINT,
  sentiment_label VARCHAR(20),
  hot_score DECIMAL(18,2),
  platform_heat_index DECIMAL(8,2),
  source_url VARCHAR(800),
  image_url VARCHAR(800)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_keyword_rank (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64),
  keyword VARCHAR(100),
  word_count BIGINT,
  platform_top VARCHAR(50),
  sentiment_top VARCHAR(20),
  UNIQUE KEY uk_keyword_rank (event_id, keyword)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_sentiment_trend (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64),
  time_bucket DATETIME,
  platform VARCHAR(50),
  sentiment_label VARCHAR(20),
  sentiment_count BIGINT,
  UNIQUE KEY uk_sentiment_trend (event_id, time_bucket, platform, sentiment_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dwd_weibo_sentiment_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  content_id VARCHAR(128) NOT NULL,
  parent_content_id VARCHAR(128),
  platform VARCHAR(50) NOT NULL,
  publish_time DATETIME,
  content_text VARCHAR(1000) NOT NULL,
  sentiment_label VARCHAR(20) NOT NULL,
  sentiment_positive_score DECIMAL(10,6) NOT NULL,
  sentiment_neutral_score DECIMAL(10,6) NOT NULL,
  sentiment_negative_score DECIMAL(10,6) NOT NULL,
  confidence DECIMAL(10,6) NOT NULL,
  analysis_method VARCHAR(64) NOT NULL,
  model_version VARCHAR(128) NOT NULL,
  batch_id VARCHAR(64) NOT NULL,
  analyzed_at DATETIME NOT NULL,
  UNIQUE KEY uk_weibo_sentiment_result (event_id, content_id),
  KEY idx_weibo_sentiment_event_time (event_id, publish_time),
  KEY idx_weibo_sentiment_label (event_id, sentiment_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_noise_summary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64),
  time_bucket DATETIME,
  platform VARCHAR(50),
  content_count BIGINT,
  duplicate_count BIGINT,
  high_freq_user_count BIGINT,
  noise_count BIGINT,
  UNIQUE KEY uk_noise (event_id, time_bucket, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_platform_daily_heat (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  platform VARCHAR(50) NOT NULL,
  time_bucket DATE NOT NULL,
  content_count BIGINT NOT NULL,
  average_heat_index DECIMAL(8,2),
  peak_content_heat_index DECIMAL(8,2),
  high_heat_content_count BIGINT NOT NULL,
  heat_algorithm VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_platform_daily_heat (event_id, platform, time_bucket),
  KEY idx_platform_daily_heat_event_time (event_id, time_bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ads_platform_category_heat (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  platform VARCHAR(50) NOT NULL,
  time_bucket DATE NOT NULL,
  category VARCHAR(100) NOT NULL,
  content_count BIGINT NOT NULL,
  platform_content_count BIGINT NOT NULL,
  coverage_share DECIMAL(8,2) NOT NULL,
  average_relative_heat_index DECIMAL(8,2),
  composite_attention_index DECIMAL(8,2) NOT NULL,
  signal_mode VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_platform_category_heat (event_id, platform, time_bucket, category),
  KEY idx_platform_category_heat_event_time (event_id, time_bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ads_topic_trend (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  topic_id VARCHAR(64) NOT NULL,
  topic_name VARCHAR(300),
  category VARCHAR(100),
  time_bucket DATE NOT NULL,
  content_count BIGINT NOT NULL,
  platform_count BIGINT NOT NULL,
  burst_index DECIMAL(8,2),
  UNIQUE KEY uk_topic_trend (event_id, topic_id, time_bucket),
  KEY idx_topic_trend_event_time (event_id, time_bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_topic_summary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  topic_id VARCHAR(64) NOT NULL,
  topic_name VARCHAR(300),
  category VARCHAR(100),
  first_publish_time DATETIME,
  peak_time DATETIME,
  latest_publish_time DATETIME,
  content_count BIGINT NOT NULL,
  platform_count BIGINT NOT NULL,
  duration_days INT NOT NULL,
  peak_daily_count BIGINT NOT NULL,
  peak_burst_index DECIMAL(8,2),
  current_stage VARCHAR(32),
  UNIQUE KEY uk_topic_summary (event_id, topic_id),
  KEY idx_topic_summary_event_peak (event_id, peak_burst_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ads_topic_key_content (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  topic_id VARCHAR(64) NOT NULL,
  event_role VARCHAR(32) NOT NULL,
  publish_time DATETIME,
  platform VARCHAR(50),
  content_id VARCHAR(128),
  title VARCHAR(300),
  clean_text VARCHAR(1000),
  source_url VARCHAR(800),
  UNIQUE KEY uk_topic_key_content (event_id, topic_id, event_role),
  KEY idx_topic_key_content_event_topic (event_id, topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  nickname VARCHAR(100) NOT NULL,
  phone VARCHAR(32),
  email VARCHAR(120),
  role VARCHAR(32) DEFAULT 'USER',
  status VARCHAR(32) DEFAULT 'ENABLED',
  bio VARCHAR(300),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_interaction (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  username VARCHAR(64),
  event_id VARCHAR(64) NOT NULL,
  content_id VARCHAR(128),
  platform VARCHAR(50),
  action_type VARCHAR(32) NOT NULL,
  comment_text VARCHAR(1000),
  sentiment_label VARCHAR(20) DEFAULT 'neutral',
  status VARCHAR(32) DEFAULT 'NORMAL',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_interaction_event (event_id),
  KEY idx_interaction_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_submission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  username VARCHAR(64),
  event_id VARCHAR(64) NOT NULL,
  platform VARCHAR(50) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content_text VARCHAR(1000) NOT NULL,
  location VARCHAR(100),
  status VARCHAR(32) DEFAULT 'PENDING',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_submission_event (event_id),
  KEY idx_submission_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO app_user(id, username, nickname, phone, email, role, status, bio) VALUES
  (1, 'admin', '系统管理员', '13800000000', 'admin@example.com', 'ADMIN', 'ENABLED', '负责后台数据、ETL和用户管理'),
  (2, 'student', '演示用户', '13900000000', 'student@example.com', 'USER', 'ENABLED', '前台普通用户，用于产生互动数据');
