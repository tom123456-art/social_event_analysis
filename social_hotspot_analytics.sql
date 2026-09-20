/*
 Navicat Premium Dump SQL

 Source Server         : 数据库密码123456
 Source Server Type    : MySQL
 Source Server Version : 80022 (8.0.22)
 Source Host           : localhost:3306
 Source Schema         : social_hotspot_analytics

 Target Server Type    : MySQL
 Target Server Version : 80022 (8.0.22)
 File Encoding         : 65001

 Date: 20/09/2026 21:59:25
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for ads_content_hot_rank
-- ----------------------------
DROP TABLE IF EXISTS `ads_content_hot_rank`;
CREATE TABLE `ads_content_hot_rank`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `rank_no` int NULL DEFAULT NULL,
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `parent_content_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `title` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `clean_text` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `author_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `author_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `publish_time` datetime NULL DEFAULT NULL,
  `crawl_time` datetime NULL DEFAULT NULL,
  `keywords` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `category` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `like_count` bigint NULL DEFAULT NULL,
  `favorite_count` bigint NULL DEFAULT NULL,
  `comment_count` bigint NULL DEFAULT NULL,
  `forward_count` bigint NULL DEFAULT NULL,
  `repost_count` bigint NULL DEFAULT NULL,
  `share_count` bigint NULL DEFAULT NULL,
  `view_count` bigint NULL DEFAULT NULL,
  `location` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `user_age_group` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `user_gender` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sentiment_label` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `hot_score` decimal(18, 2) NULL DEFAULT NULL,
  `source_url` varchar(800) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `image_url` varchar(800) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2236542 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for ads_event_heat_trend
-- ----------------------------
DROP TABLE IF EXISTS `ads_event_heat_trend`;
CREATE TABLE `ads_event_heat_trend`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `time_bucket` datetime NULL DEFAULT NULL,
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content_count` bigint NULL DEFAULT NULL,
  `interaction_count` bigint NULL DEFAULT NULL,
  `hot_score` decimal(18, 2) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_heat`(`event_id` ASC, `time_bucket` ASC, `platform` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 387864 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for ads_event_overview
-- ----------------------------
DROP TABLE IF EXISTS `ads_event_overview`;
CREATE TABLE `ads_event_overview`  (
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `event_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content_count` bigint NULL DEFAULT NULL,
  `user_count` bigint NULL DEFAULT NULL,
  `platform_count` bigint NULL DEFAULT NULL,
  `comment_count` bigint NULL DEFAULT NULL,
  `repost_count` bigint NULL DEFAULT NULL,
  `share_count` bigint NULL DEFAULT NULL,
  `like_count` bigint NULL DEFAULT NULL,
  `view_count` bigint NULL DEFAULT NULL,
  `positive_count` bigint NULL DEFAULT NULL,
  `neutral_count` bigint NULL DEFAULT NULL,
  `negative_count` bigint NULL DEFAULT NULL,
  `hot_score` decimal(18, 2) NULL DEFAULT NULL,
  `peak_time` datetime NULL DEFAULT NULL,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for ads_interaction_summary
-- ----------------------------
DROP TABLE IF EXISTS `ads_interaction_summary`;
CREATE TABLE `ads_interaction_summary`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content_count` bigint NULL DEFAULT NULL,
  `comment_count` bigint NULL DEFAULT NULL,
  `repost_count` bigint NULL DEFAULT NULL,
  `like_count` bigint NULL DEFAULT NULL,
  `share_count` bigint NULL DEFAULT NULL,
  `favorite_count` bigint NULL DEFAULT NULL,
  `view_count` bigint NULL DEFAULT NULL,
  `hot_score` decimal(18, 2) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_interaction`(`event_id` ASC, `platform` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 390 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for ads_keyword_rank
-- ----------------------------
DROP TABLE IF EXISTS `ads_keyword_rank`;
CREATE TABLE `ads_keyword_rank`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `keyword` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `word_count` bigint NULL DEFAULT NULL,
  `platform_top` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sentiment_top` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_keyword_rank`(`event_id` ASC, `keyword` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5560 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for ads_noise_summary
-- ----------------------------
DROP TABLE IF EXISTS `ads_noise_summary`;
CREATE TABLE `ads_noise_summary`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `time_bucket` datetime NULL DEFAULT NULL,
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content_count` bigint NULL DEFAULT NULL,
  `duplicate_count` bigint NULL DEFAULT NULL,
  `high_freq_user_count` bigint NULL DEFAULT NULL,
  `noise_count` bigint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_noise`(`event_id` ASC, `time_bucket` ASC, `platform` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 341260 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for ads_platform_spread_timeline
-- ----------------------------
DROP TABLE IF EXISTS `ads_platform_spread_timeline`;
CREATE TABLE `ads_platform_spread_timeline`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `first_publish_time` datetime NULL DEFAULT NULL,
  `delay_minutes` bigint NULL DEFAULT NULL,
  `content_count` bigint NULL DEFAULT NULL,
  `hot_score` decimal(18, 2) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_platform_timeline`(`event_id` ASC, `platform` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 394 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for ads_sentiment_trend
-- ----------------------------
DROP TABLE IF EXISTS `ads_sentiment_trend`;
CREATE TABLE `ads_sentiment_trend`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `time_bucket` datetime NULL DEFAULT NULL,
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sentiment_label` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sentiment_count` bigint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_sentiment_trend`(`event_id` ASC, `time_bucket` ASC, `platform` ASC, `sentiment_label` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 462849 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for app_user
-- ----------------------------
DROP TABLE IF EXISTS `app_user`;
CREATE TABLE `app_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `nickname` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `phone` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `email` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'USER',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'ENABLED',
  `bio` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for etl_batch
-- ----------------------------
DROP TABLE IF EXISTS `etl_batch`;
CREATE TABLE `etl_batch`  (
  `batch_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source_count` bigint NULL DEFAULT 0,
  `valid_count` bigint NULL DEFAULT 0,
  `dirty_count` bigint NULL DEFAULT 0,
  `duplicate_count` bigint NULL DEFAULT 0,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `current_stage` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `started_at` datetime NULL DEFAULT NULL,
  `finished_at` datetime NULL DEFAULT NULL,
  `error_message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`batch_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for etl_task_log
-- ----------------------------
DROP TABLE IF EXISTS `etl_task_log`;
CREATE TABLE `etl_task_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_stage` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `input_count` bigint NULL DEFAULT 0,
  `output_count` bigint NULL DEFAULT 0,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `started_at` datetime NULL DEFAULT NULL,
  `finished_at` datetime NULL DEFAULT NULL,
  `error_message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_batch_id`(`batch_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 294 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for event_info
-- ----------------------------
DROP TABLE IF EXISTS `event_info`;
CREATE TABLE `event_info`  (
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `event_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `start_time` datetime NULL DEFAULT NULL,
  `end_time` datetime NULL DEFAULT NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'ACTIVE',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for user_interaction
-- ----------------------------
DROP TABLE IF EXISTS `user_interaction`;
CREATE TABLE `user_interaction`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NULL DEFAULT NULL,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `content_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `action_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `comment_text` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sentiment_label` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'neutral',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'NORMAL',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_interaction_event`(`event_id` ASC) USING BTREE,
  INDEX `idx_interaction_user`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for user_submission
-- ----------------------------
DROP TABLE IF EXISTS `user_submission`;
CREATE TABLE `user_submission`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NULL DEFAULT NULL,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `content_text` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `location` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'PENDING',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_submission_event`(`event_id` ASC) USING BTREE,
  INDEX `idx_submission_user`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

SET FOREIGN_KEY_CHECKS = 1;
