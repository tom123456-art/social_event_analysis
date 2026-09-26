package com.social.hotspot.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
/** 数据库结构初始化器：启动时补齐分析表、字段和必要索引。 */
public class DatabaseSchemaInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);
    private static final String TABLE = "ads_content_hot_rank";
    private static final String OVERVIEW_TABLE = "ads_event_overview";
    private static final String CONTENT_UNIQUE_INDEX = "uk_content_rank";
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();
    private static final Map<String, String> HEAT_TREND_COLUMNS = new LinkedHashMap<>();
    private static final Map<String, String> PLATFORM_TIMELINE_COLUMNS = new LinkedHashMap<>();

    static {
        REQUIRED_COLUMNS.put("crawl_time", "datetime null after publish_time");
        REQUIRED_COLUMNS.put("keywords", "varchar(1000) null after crawl_time");
        REQUIRED_COLUMNS.put("category", "varchar(100) null after keywords");
        REQUIRED_COLUMNS.put("like_count", "bigint null after category");
        REQUIRED_COLUMNS.put("favorite_count", "bigint null after like_count");
        REQUIRED_COLUMNS.put("comment_count", "bigint null after favorite_count");
        REQUIRED_COLUMNS.put("forward_count", "bigint null after comment_count");
        REQUIRED_COLUMNS.put("parent_content_id", "varchar(128) null after content_id");
        REQUIRED_COLUMNS.put("author_id", "varchar(128) null after clean_text");
        REQUIRED_COLUMNS.put("repost_count", "bigint null after forward_count");
        REQUIRED_COLUMNS.put("view_count", "bigint null after repost_count");
        REQUIRED_COLUMNS.put("platform_heat_index", "decimal(8,2) null after hot_score");
        HEAT_TREND_COLUMNS.put("avg_heat_index", "decimal(8,2) null after hot_score");
        PLATFORM_TIMELINE_COLUMNS.put("avg_heat_index", "decimal(8,2) null after hot_score");
        PLATFORM_TIMELINE_COLUMNS.put("max_heat_index", "decimal(8,2) null after avg_heat_index");
        PLATFORM_TIMELINE_COLUMNS.put("peak_time", "datetime null after max_heat_index");
        PLATFORM_TIMELINE_COLUMNS.put("high_heat_count", "bigint null after peak_time");
    }

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureTopicTables();
            ensureWeiboSentimentResultTable();
            ensurePlatformCategoryHeatTable();
            ensurePlatformDailyHeatTable();
            ensureColumns(TABLE, REQUIRED_COLUMNS);
            ensureColumns("ads_event_heat_trend", HEAT_TREND_COLUMNS);
            ensureColumns("ads_platform_spread_timeline", PLATFORM_TIMELINE_COLUMNS);
            dropColumnIfExists(TABLE, "share_count");
            dropColumnIfExists(TABLE, "location");
            dropColumnIfExists(TABLE, "user_age_group");
            dropColumnIfExists(TABLE, "user_gender");
            dropColumnIfExists(OVERVIEW_TABLE, "share_count");
            dropColumnIfExists("ads_interaction_summary", "share_count");
            Integer uniqueIndexCount = jdbcTemplate.queryForObject("""
                    select count(*)
                    from information_schema.statistics
                    where table_schema = database() and table_name = ? and index_name = ?
                    """, Integer.class, TABLE, CONTENT_UNIQUE_INDEX);
            if (uniqueIndexCount != null && uniqueIndexCount > 0) {
                jdbcTemplate.execute("alter table " + TABLE + " drop index " + CONTENT_UNIQUE_INDEX);
            }
        } catch (DataAccessException ex) {
            log.warn("业务数据库尚未初始化，跳过启动阶段字段检查；首次 CSV 上传将通过 VM ETL 接口初始化 schema。原因：{}",
                    ex.getMostSpecificCause() == null ? ex.getMessage() : ex.getMostSpecificCause().getMessage());
        }
    }

    private void ensureTopicTables() {
        jdbcTemplate.execute("""
                create table if not exists ads_topic_trend (
                  id bigint primary key auto_increment,
                  event_id varchar(64) not null,
                  topic_id varchar(64) not null,
                  topic_name varchar(300),
                  category varchar(100),
                  time_bucket date not null,
                  content_count bigint not null,
                  platform_count bigint not null,
                  burst_index decimal(8,2),
                  unique key uk_topic_trend (event_id, topic_id, time_bucket),
                  key idx_topic_trend_event_time (event_id, time_bucket)
                ) engine=InnoDB default charset=utf8mb4
                """);
        jdbcTemplate.execute("""
                create table if not exists ads_topic_summary (
                  id bigint primary key auto_increment,
                  event_id varchar(64) not null,
                  topic_id varchar(64) not null,
                  topic_name varchar(300),
                  category varchar(100),
                  first_publish_time datetime,
                  peak_time datetime,
                  latest_publish_time datetime,
                  content_count bigint not null,
                  platform_count bigint not null,
                  duration_days int not null,
                  peak_daily_count bigint not null,
                  peak_burst_index decimal(8,2),
                  current_stage varchar(32),
                  unique key uk_topic_summary (event_id, topic_id),
                  key idx_topic_summary_event_peak (event_id, peak_burst_index)
                ) engine=InnoDB default charset=utf8mb4
                """);
        jdbcTemplate.execute("""
                create table if not exists ads_topic_key_content (
                  id bigint primary key auto_increment,
                  event_id varchar(64) not null,
                  topic_id varchar(64) not null,
                  event_role varchar(32) not null,
                  publish_time datetime,
                  platform varchar(50),
                  content_id varchar(128),
                  title varchar(300),
                  clean_text varchar(1000),
                  source_url varchar(800),
                  unique key uk_topic_key_content (event_id, topic_id, event_role),
                  key idx_topic_key_content_event_topic (event_id, topic_id)
                ) engine=InnoDB default charset=utf8mb4
                """);
    }

    private void ensureWeiboSentimentResultTable() {
        jdbcTemplate.execute("""
                create table if not exists dwd_weibo_sentiment_result (
                  id bigint primary key auto_increment,
                  event_id varchar(64) not null,
                  content_id varchar(128) not null,
                  parent_content_id varchar(128),
                  platform varchar(50) not null,
                  publish_time datetime,
                  content_text varchar(1000) not null,
                  sentiment_label varchar(20) not null,
                  sentiment_positive_score decimal(10,6) not null,
                  sentiment_neutral_score decimal(10,6) not null,
                  sentiment_negative_score decimal(10,6) not null,
                  confidence decimal(10,6) not null,
                  analysis_method varchar(64) not null,
                  model_version varchar(128) not null,
                  batch_id varchar(64) not null,
                  analyzed_at datetime not null,
                  unique key uk_weibo_sentiment_result (event_id, content_id),
                  key idx_weibo_sentiment_event_time (event_id, publish_time),
                  key idx_weibo_sentiment_label (event_id, sentiment_label)
                ) engine=InnoDB default charset=utf8mb4
                """);
    }

    private void ensurePlatformDailyHeatTable() {
        jdbcTemplate.execute("""
                create table if not exists ads_platform_daily_heat (
                  id bigint primary key auto_increment,
                  event_id varchar(64) not null,
                  platform varchar(50) not null,
                  time_bucket date not null,
                  content_count bigint not null,
                  average_heat_index decimal(8,2),
                  peak_content_heat_index decimal(8,2),
                  high_heat_content_count bigint not null,
                  heat_algorithm varchar(32) not null,
                  unique key uk_platform_daily_heat (event_id, platform, time_bucket),
                  key idx_platform_daily_heat_event_time (event_id, time_bucket)
                ) engine=InnoDB default charset=utf8mb4
                """);
    }
    private void ensurePlatformCategoryHeatTable() {
        jdbcTemplate.execute("""
                create table if not exists ads_platform_category_heat (
                  id bigint primary key auto_increment,
                  event_id varchar(64) not null,
                  platform varchar(50) not null,
                  time_bucket date not null,
                  category varchar(100) not null,
                  content_count bigint not null,
                  platform_content_count bigint not null,
                  coverage_share decimal(8,2) not null,
                  average_relative_heat_index decimal(8,2),
                  composite_attention_index decimal(8,2) not null,
                  signal_mode varchar(32) not null,
                  unique key uk_platform_category_heat (event_id, platform, time_bucket, category),
                  key idx_platform_category_heat_event_time (event_id, time_bucket)
                ) engine=InnoDB default charset=utf8mb4
                """);
    }
    private void ensureColumns(String table, Map<String, String> columns) {
        columns.forEach((column, definition) -> {
            Integer count = jdbcTemplate.queryForObject("""
                    select count(*)
                    from information_schema.columns
                    where table_schema = database() and table_name = ? and column_name = ?
                    """, Integer.class, table, column);
            if (count == null || count == 0) {
                jdbcTemplate.execute("alter table " + table + " add column " + column + " " + definition);
            }
        });
    }
    private void dropColumnIfExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = database() and table_name = ? and column_name = ?
                """, Integer.class, table, column);
        if (count != null && count > 0) {
            jdbcTemplate.execute("alter table " + table + " drop column " + column);
        }
    }
}
