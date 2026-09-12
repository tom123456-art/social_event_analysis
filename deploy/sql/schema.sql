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
  share_count BIGINT,
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
  share_count BIGINT,
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
  share_count BIGINT,
  view_count BIGINT,
  location VARCHAR(100),
  user_age_group VARCHAR(50),
  user_gender VARCHAR(30),
  sentiment_label VARCHAR(20),
  hot_score DECIMAL(18,2),
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
