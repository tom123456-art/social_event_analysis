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
public class DatabaseSchemaInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);
    private static final String TABLE = "ads_content_hot_rank";
    private static final String OVERVIEW_TABLE = "ads_event_overview";
    private static final String CONTENT_UNIQUE_INDEX = "uk_content_rank";
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();

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
        REQUIRED_COLUMNS.put("share_count", "bigint null after repost_count");
        REQUIRED_COLUMNS.put("view_count", "bigint null after share_count");
        REQUIRED_COLUMNS.put("location", "varchar(100) null after view_count");
        REQUIRED_COLUMNS.put("user_age_group", "varchar(50) null after location");
        REQUIRED_COLUMNS.put("user_gender", "varchar(30) null after user_age_group");
    }

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            REQUIRED_COLUMNS.forEach((column, definition) -> {
                Integer count = jdbcTemplate.queryForObject("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = database() and table_name = ? and column_name = ?
                        """, Integer.class, TABLE, column);
                if (count == null || count == 0) {
                    jdbcTemplate.execute("alter table " + TABLE + " add column " + column + " " + definition);
                }
            });
            ensureColumn(OVERVIEW_TABLE, "share_count", "bigint null after repost_count");
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

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = database() and table_name = ? and column_name = ?
                """, Integer.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("alter table " + table + " add column " + column + " " + definition);
        }
    }
}
