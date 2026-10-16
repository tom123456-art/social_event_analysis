package com.social.hotspot.etl.publish;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 先暂存 Spark 输出，再在一个事务中替换指定事件的全部 ADS 聚合。 */
public final class AdsSnapshotPublisher implements AutoCloseable {
    private final String url;
    private final String user;
    private final String password;
    private final Map<String, String> stages = new LinkedHashMap<>();
    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    public AdsSnapshotPublisher(String url, String user, String password) throws Exception {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** 创建与目标表结构相同的暂存表，返回供 Spark 写入的表名。 */
    public String stage(String table) throws Exception {
        // 约束可拼接进 SQL 的表名，拒绝非 ADS 表和非法标识符。
        if (!table.matches("ads_[a-z_]+")) throw new IllegalArgumentException("Invalid ADS table");
        String stage = table + "_" + suffix;
        try (var connection = openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE `" + stage + "` LIKE `" + table + "`");
        }
        // 保存正式表与暂存表的对应关系，供发布和清理阶段使用。
        stages.put(table, stage);
        return stage;
    }

    /** 原子替换同一事件在所有已暂存 ADS 表中的数据。 */
    public void publish(String eventId) throws Exception {
        // Spark 写暂存表可能超过连接空闲时限，发布前重新建立连接。
        try (var connection = openConnection()) {
        connection.setAutoCommit(false);
        try {
            // 各张 ADS 表共用同一事务，避免事件快照只更新一部分。
            for (var entry : stages.entrySet()) {
                String table = entry.getKey();
                // 收集非自增字段，避免复制暂存表主键引发冲突。
                var columns = new ArrayList<String>();
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SHOW COLUMNS FROM `" + table + "`")) {
                    while (rows.next()) {
                        if (!rows.getString("Extra").contains("auto_increment"))
                            columns.add("`" + rows.getString("Field") + "`");
                    }
                }
                try (var delete = connection.prepareStatement("DELETE FROM `" + table + "` WHERE event_id=?")) {
                    delete.setString(1, eventId); delete.executeUpdate();
                }
                // INSERT 和 SELECT 使用相同字段顺序，按事件筛选暂存数据。
                String names = String.join(",", columns);
                try (var insert = connection.prepareStatement("INSERT INTO `" + table + "` (" + names + ") SELECT "
                        + names + " FROM `" + entry.getValue() + "` WHERE event_id=?")) {
                    insert.setString(1, eventId); insert.executeUpdate();
                }
            }
            // 所有表替换成功后一次性提交。
            connection.commit();
        } catch (Exception e) {
            // 发布失败时回滚；若回滚也失败，则把该异常附加到原始异常。
            try { connection.rollback(); } catch (Exception rollback) { e.addSuppressed(rollback); }
            throw e;
        } finally { connection.setAutoCommit(true); }
        }
    }

    /** 使用保存的连接参数建立 JDBC 连接。 */
    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(url, user, password);
    }

    @Override public void close() {
        // 清理失败仅记录错误，不能把已提交的快照发布标记为失败。
        for (String stage : stages.values()) {
            try (var connection = openConnection(); var statement = connection.createStatement()) { statement.executeUpdate("DROP TABLE IF EXISTS `" + stage + "`"); }
            catch (Exception e) { System.err.println("ADS staging cleanup failed: " + stage + ": " + e.getMessage()); }
        }
    }
}
