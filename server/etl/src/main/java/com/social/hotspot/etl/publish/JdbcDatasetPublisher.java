package com.social.hotspot.etl.publish;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import static org.apache.spark.sql.functions.*;

public final class JdbcDatasetPublisher {
    // 禁止实例化 JDBC 发布工具类。
    private JdbcDatasetPublisher() {}
    // 将 Spark 数据集追加写入指定 MySQL 表。
    public static void writeJdbc(Dataset<Row> data, String url, String user, String password, String table) {
        // 创建 Spark JDBC 写入连接参数。
        Properties properties = new Properties();
        // 设置数据库用户名。
        properties.put("user", user);
        // 设置数据库密码。
        properties.put("password", password);
        // 指定 MySQL Connector/J 驱动。
        properties.put("driver", "com.mysql.cj.jdbc.Driver");
        // 使用追加模式发布数据，快照清理由上层流程协调。
        data.write().mode(SaveMode.Append).jdbc(url, table, properties);
    }

    // 新增事件信息，已存在时更新名称和实际内容时间范围。
    public static void upsertEvent(String url, String user, String password, String eventId, String eventName, Dataset<Row> detail) throws Exception {
        // 从有效明细计算事件最早及最晚发布时间。
        Row row = detail.agg(min(col("publish_time")).alias("start_time"), max(col("publish_time")).alias("end_time")).first();
        // 使用预编译语句写入事件，并自动关闭连接和语句。
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = connection.prepareStatement("""
                     insert into event_info(event_id, event_name, description, start_time, end_time, status)
                     values(?, ?, ?, ?, ?, 'ACTIVE')
                     on duplicate key update event_name=values(event_name), start_time=values(start_time), end_time=values(end_time), updated_at=current_timestamp
                     """)) {
            // 绑定事件主键。
            ps.setString(1, eventId);
            // 绑定事件展示名称。
            ps.setString(2, eventName);
            // 说明事件分析结果来自真实采集和 Spark 计算。
            ps.setString(3, "由公开数据源真实采集后经 Spark ETL 计算生成");
            // 绑定实际内容时间范围起点。
            ps.setTimestamp(4, row.getAs("start_time"));
            // 绑定实际内容时间范围终点。
            ps.setTimestamp(5, row.getAs("end_time"));
            // 执行插入或主键冲突更新。
            ps.executeUpdate();
        }
    }
}
