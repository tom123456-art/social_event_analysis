package com.social.hotspot.etl.source;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import static org.apache.spark.sql.functions.*;

public final class InputDatasetReader {
    // 禁止实例化数据读取工具类。
    private InputDatasetReader() {}
    // 按输入文件扩展名选择 JSON 或 CSV 读取方式。
    public static Dataset<Row> readInput(SparkSession spark, String input) {
        // JSON 和 JSONL 文件按逐行 JSON 读取。
        if (input.toLowerCase().endsWith(".json") || input.toLowerCase().endsWith(".jsonl")) {
            // 每行是一条记录，不使用整文件多行 JSON 解析。
            return spark.read().option("multiLine", "false").json(input);
        }
        // CSV 使用表头列名，所有字段先按字符串读取，允许带换行的引号字段。
        return spark.read().option("header", "true").option("inferSchema", "false").option("multiLine", "true")
                // 双引号作为转义字符，损坏记录不立即中断读取；将结构解析失败内容保存到质量检测专用字段。
                .option("escape", Character.toString((char) 34)).option("mode", "PERMISSIVE")
                .option("columnNameOfCorruptRecord", "_corrupt_record").csv(input);
    }
}
