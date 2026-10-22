package com.social.hotspot.service.ai;

import com.social.hotspot.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import com.social.hotspot.config.ProjectPaths;

@Service
public class AiQueryTools {
    private static final Map<String, Set<String>> SCHEMA = Map.of(
        "ads_content_hot_rank", Set.of("event_id", "platform", "title", "publish_time", "category", "sentiment_label", "hot_score", "source_url", "keywords"),
        "ads_keyword_rank", Set.of("event_id", "keyword", "word_count", "platform_top", "sentiment_top"),
        "ads_sentiment_trend", Set.of("event_id", "time_bucket", "platform", "sentiment_label", "sentiment_count"),
        "ads_event_heat_trend", Set.of("event_id", "time_bucket", "platform", "content_count", "hot_score"),
        "ads_platform_spread_timeline", Set.of("event_id", "platform", "first_publish_time", "delay_minutes", "content_count", "hot_score"));
    private final JdbcTemplate jdbc;
    private final String url, user, password;
    public AiQueryTools(JdbcTemplate jdbc, @Value("${ai.query.url:}") String url,
                        @Value("${ai.query.username:}") String user, @Value("${ai.query.password:}") String password) {
        Properties local = new Properties();
        Path file = ProjectPaths.root().resolve("runtime/config/ai/query.properties");
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) { local.load(reader); }
            catch (IOException ex) { throw new IllegalStateException("Cannot read local AI database configuration", ex); }
        }
        this.jdbc = jdbc;
        this.url = url.isBlank() ? local.getProperty("url", "") : url;
        this.user = user.isBlank() ? local.getProperty("username", "") : user;
        this.password = password.isBlank() ? local.getProperty("password", "") : password;
    }
    public boolean sqlEnabled() { return !url.isBlank() && !user.isBlank() && !user.equalsIgnoreCase("root"); }
    public Map<String, Set<String>> schema() { return SCHEMA; }
    public Object query(Map<String, Object> args) {
        String table = Objects.toString(args.get("table"), "");
        if (!SCHEMA.containsKey(table)) throw new BusinessException("不允许查询此数据表。");
        List<Object> parameters = new ArrayList<>(List.of("public_rss_latest"));
        StringBuilder where = new StringBuilder(" WHERE event_id=?");
        for (String field : List.of("platform", "sentiment_label", "category")) {
            String value = Objects.toString(args.get(field), "").trim();
            if (!value.isEmpty()) {
                if (!SCHEMA.get(table).contains(field) || value.length() > 100) throw new BusinessException("查询条件不适用于当前表。");
                where.append(" AND ").append(field).append("=?"); parameters.add(value);
            }
        }
        String keyword = Objects.toString(args.get("keyword"), "").trim();
        if (!keyword.isEmpty()) {
            if (keyword.length() > 100 || !table.equals("ads_content_hot_rank")) throw new BusinessException("关键词筛选只支持内容表，最多一百字。");
            where.append(" AND title LIKE ?"); parameters.add("%" + keyword + "%");
        }
        String dateColumn = SCHEMA.get(table).contains("publish_time") ? "publish_time" : SCHEMA.get(table).contains("time_bucket") ? "time_bucket" : "";
        for (String bound : List.of("startDate", "endDate")) {
            String date = Objects.toString(args.get(bound), "").trim();
            if (!date.isEmpty()) {
                if (dateColumn.isEmpty()) throw new BusinessException("此表不支持日期筛选。");
                try {
                    var parsed = java.time.LocalDate.parse(date);
                    where.append(" AND ").append(dateColumn).append(bound.equals("startDate") ? ">=?" : "<?");
                    parameters.add(bound.equals("startDate") ? parsed.toString() : parsed.plusDays(1).toString());
                } catch (java.time.DateTimeException ex) { throw new BusinessException("日期须使用年-月-日格式。"); }
            }
        }
        String countSql = "SELECT COUNT(*) AS matched_rows FROM " + table + where;
        String dataSql = "SELECT " + String.join(",", new TreeSet<>(SCHEMA.get(table))) + " FROM " + table + where
                + " ORDER BY " + (dateColumn.isEmpty() ? SCHEMA.get(table).contains("word_count") ? "word_count" : "hot_score" : dateColumn) + " DESC LIMIT 50";
        return Map.of("table", table, "matchedRows", executePrepared(countSql, parameters), "rows", executePrepared(dataSql, parameters),
                "scope", "当前已发布分析表；内容排行表不是全部原始新闻", "rowLimit", 50);
    }
    private List<Map<String, Object>> executePrepared(String sql, List<Object> parameters) {
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql); statement.setQueryTimeout(5); statement.setMaxRows(50);
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            return statement;
        }, (rs, index) -> row(rs));
    }
    public Object sql(String input) {
        if (!sqlEnabled()) throw new BusinessException("只读 SQL 尚未配置独立数据库账号，可先使用条件查询工具。");
        String sql = AiSqlPolicy.validate(input, SCHEMA);
        Properties properties = new Properties(); properties.setProperty("user", user); properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "7000");
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5); statement.setMaxRows(100);
                statement.execute("SET SESSION MAX_EXECUTION_TIME=5000");
                try (ResultSet rs = statement.executeQuery(sql)) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() < 100) rows.add(row(rs));
                    return Map.of("sql", sql, "rows", rows, "rowLimit", 100);
                }
            } finally { connection.rollback(); }
        } catch (SQLException ex) { throw new BusinessException("只读查询执行失败，请检查字段、账号权限或查询范围；查询最多运行五秒。"); }
    }
    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor) value = value.toString();
            if (value instanceof String text && text.length() > 1500) value = text.substring(0, 1500);
            row.put(rs.getMetaData().getColumnLabel(i), value);
        }
        return row;
    }
}
