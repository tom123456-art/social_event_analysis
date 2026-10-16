package com.social.hotspot.etl.tracking;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import static org.apache.spark.sql.functions.*;

public final class EtlProgressRecorder {
    // 禁止实例化进度记录工具类。
    private EtlProgressRecorder() {}
    // 按批次主键新增或更新 ETL 数量、阶段和执行状态。
    public static void upsertBatch(String url, String user, String password, String batchId, String eventId, String sourcePath,
                                    String status, String stage, LocalDateTime startedAt, LocalDateTime finishedAt, String error,
                                    long sourceCount, long validCount, long dirtyCount, long duplicateCount) throws Exception {
        // 使用短连接执行批次状态写入，自动释放数据库资源。
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = connection.prepareStatement("""
                     insert into etl_batch(batch_id, event_id, source_path, source_count, valid_count, dirty_count, duplicate_count, status, current_stage, started_at, finished_at, error_message)
                     values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     on duplicate key update source_count=values(source_count), valid_count=values(valid_count), dirty_count=values(dirty_count),
                     duplicate_count=values(duplicate_count), status=values(status), current_stage=values(current_stage), finished_at=values(finished_at), error_message=values(error_message), updated_at=current_timestamp
                     """)) {
            // 绑定批次唯一标识。
            ps.setString(1, batchId);
            // 绑定批次对应的事件标识。
            ps.setString(2, eventId);
            // 记录本次读取的数据源路径。
            ps.setString(3, sourcePath);
            // 记录读取的原始记录数。
            ps.setLong(4, sourceCount);
            // 记录通过清洗的有效记录数。
            ps.setLong(5, validCount);
            // 记录质量筛选淘汰数量。
            ps.setLong(6, dirtyCount);
            // 记录重复版本淘汰数量。
            ps.setLong(7, duplicateCount);
            // 写入批次执行状态。
            ps.setString(8, status);
            // 写入当前处理阶段。
            ps.setString(9, stage);
            // 将可空的 Java 开始时间转换为 JDBC 时间戳。
            ps.setTimestamp(10, startedAt == null ? null : Timestamp.valueOf(startedAt));
            // 尚未完成的批次保留空结束时间。
            ps.setTimestamp(11, finishedAt == null ? null : Timestamp.valueOf(finishedAt));
            // 截断错误说明以符合数据库字段长度。
            ps.setString(12, truncate(error, 1000));
            // 提交批次状态更新。
            ps.executeUpdate();
        }
    }

    // 追加单个 ETL 阶段的输入输出数量及执行结果。
    public static void logTask(String url, String user, String password, String batchId, String taskName, String stage,
                                long input, long output, String status, String error) throws Exception {
        // 使用日志写入时刻作为阶段日志时间。
        LocalDateTime now = LocalDateTime.now();
        // 创建阶段日志插入语句并自动关闭 JDBC 资源。
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = connection.prepareStatement("""
                     insert into etl_task_log(batch_id, task_name, task_stage, input_count, output_count, status, started_at, finished_at, error_message)
                     values(?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            // 将阶段日志关联至所属批次。
            ps.setString(1, batchId);
            // 绑定任务名称。
            ps.setString(2, taskName);
            // 绑定阶段标识。
            ps.setString(3, stage);
            // 绑定阶段输入记录数。
            ps.setLong(4, input);
            // 绑定阶段输出记录数。
            ps.setLong(5, output);
            // 绑定阶段执行状态。
            ps.setString(6, status);
            // 当前日志未单独采集阶段开始时间，使用写入时刻。
            ps.setTimestamp(7, Timestamp.valueOf(now));
            // 记录日志写入时刻作为阶段结束时间。
            ps.setTimestamp(8, Timestamp.valueOf(now));
            // 将错误信息限制在数据库允许的长度内。
            ps.setString(9, truncate(error, 1000));
            // 执行阶段日志插入。
            ps.executeUpdate();
        }
    }

    // 将可空文本限制在指定最大字符长度内。
    public static String truncate(String value, int maxLength) {
        // 空值和未超长文本保持原值。
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        // 仅保留允许长度的前缀。
        return value.substring(0, maxLength);
    }
}
