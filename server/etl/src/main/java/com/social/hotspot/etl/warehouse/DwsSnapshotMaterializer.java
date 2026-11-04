package com.social.hotspot.etl.warehouse;

import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructField;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 应用层读取数据前，先持久化当前批次的汇总层快照。 */
public final class DwsSnapshotMaterializer {
    private DwsSnapshotMaterializer() {}

    public static Map<String, Dataset<Row>> materialize(SparkSession spark, String root, String batch,
                                                       Map<String, Dataset<Row>> summaries) throws Exception {
        if (root == null || root.isBlank()) throw new IllegalArgumentException("Warehouse output is required for four-layer ETL");
        if (!batch.matches("[0-9]+")) throw new IllegalArgumentException("Invalid warehouse batch ID");
        Map<String, Dataset<Row>> stored = new LinkedHashMap<>();
        StringBuilder ddl = new StringBuilder("CREATE DATABASE IF NOT EXISTS social_dws;\n");
        for (var entry : summaries.entrySet()) {
            String name = entry.getKey();
            if (!name.matches("dws_[a-z_]+")) throw new IllegalArgumentException("Invalid DWS table name");
            String tableRoot = root + "/dws/" + name;
            String partition = tableRoot + "/batch_id=" + batch;
            Dataset<Row> data = entry.getValue().drop("batch_id");
            data.write().mode(SaveMode.ErrorIfExists).parquet(partition);
            // 重新读取已提交批次，避免应用层继续使用内存中的明细汇总结果。
            stored.put(name, spark.read().parquet(partition).drop("batch_id"));
            ddl.append("CREATE EXTERNAL TABLE IF NOT EXISTS social_dws.").append(name).append(" (\n");
            StructField[] fields = data.schema().fields();
            for (int i = 0; i < fields.length; i++) {
                ddl.append('`').append(fields[i].name()).append("` ")
                        .append(fields[i].dataType().catalogString());
                ddl.append(i == fields.length - 1 ? "\n" : ",\n");
            }
            ddl.append(") PARTITIONED BY (batch_id STRING) STORED AS PARQUET LOCATION '")
                    .append(tableRoot.replace("'", "''")).append("';\n")
                    .append("ALTER TABLE social_dws.").append(name)
                    .append(" ADD IF NOT EXISTS PARTITION (batch_id='").append(batch)
                    .append("') LOCATION '").append(partition.replace("'", "''")).append("';\n");
        }
        Path manifest = new Path(root + "/manifests/batch_id=" + batch + "/dws.sql");
        var fs = manifest.getFileSystem(spark.sparkContext().hadoopConfiguration());
        fs.mkdirs(manifest.getParent());
        try (var output = fs.create(manifest, false)) {
            output.write(ddl.toString().getBytes(StandardCharsets.UTF_8));
        }
        return stored;
    }
}
