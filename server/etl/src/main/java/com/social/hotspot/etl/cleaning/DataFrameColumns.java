package com.social.hotspot.etl.cleaning;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import static org.apache.spark.sql.functions.*;

// 提供缺列补齐、模式对齐及字符串到数值和时间的列级转换。
public final class DataFrameColumns {
    // 禁止实例化字段处理工具类。
    private DataFrameColumns() {}
    // 将数据集投影为目标数据集的完整字段结构。
    public static Dataset<Row> alignToSchema(Dataset<Row> data, Dataset<Row> schemaSource) {
        // 读取目标字段定义；按目标字段数量创建投影表达式数组。
        org.apache.spark.sql.types.StructField[] fields = schemaSource.schema().fields();
        Column[] projection = new Column[fields.length];
        // 逐个生成字段转换或空值占位表达式。
        for (int index = 0; index < fields.length; index++) {
            // 读取当前目标字段定义；已存在字段执行类型转换，不存在字段生成同类型空列。
            org.apache.spark.sql.types.StructField field = fields[index];
            projection[index] = hasColumn(data, field.name())
                    // 已有字段转换为目标类型，并保持目标字段名；缺失字段生成目标类型的空值，保证后续合并的模式一致。
                    ? col(field.name()).cast(field.dataType()).alias(field.name())
                    : lit(null).cast(field.dataType()).alias(field.name());
        }
        // 按目标字段顺序返回对齐后的数据集。
        return data.select(projection);
    }

    // 确保数据集包含指定字段，不存在字段统一补空值。
    public static Dataset<Row> ensureColumns(Dataset<Row> data, String... names) {
        // 从输入数据集开始逐步追加缺失字段。
        Dataset<Row> result = data;
        // 检查每个要求字段。
        for (String name : names) {
            // 仅为不存在的字段追加空列。
            if (!hasColumn(result, name)) {
                // 为当前缺失字段追加空值列，保留其它列不变。
                result = result.withColumn(name, lit(null));
            }
        }
        // 返回包含全部要求字段的数据集。
        return result;
    }

    // 安全读取字段，缺失字段时返回空列。
    public static Column safeCol(Dataset<Row> data, String name) {
        // 存在字段时返回列引用，否则返回空值表达式。
        return hasColumn(data, name) ? col(name) : lit(null);
    }

    // 判断数据集是否包含指定名称的字段，并忽略大小写差异。
    public static boolean hasColumn(Dataset<Row> data, String name) {
        // 遍历 Spark 返回的实际字段名称。
        for (String column : data.columns()) {
            // 找到不区分大小写的同名字段后立即返回。
            if (column.equalsIgnoreCase(name)) {
                // 找到同名字段后立即结束搜索并报告存在。
                return true;
            }
        }
        // 遍历结束仍未找到目标字段。
        return false;
    }

    // 将空白文本转换为空值并保留非空文本的去空格结果。
    public static Column nullIfBlank(Column column) {
        // 首尾空白被裁掉后若为空则返回 NULL，其余返回裁剪结果。
        return when(trim(column).equalTo(""), null).otherwise(trim(column));
    }

    // 将互动数字字段中的逗号、空值和小数统一为长整数。
    public static Column numberCol(Dataset<Row> data, String name) {
        // 获取字段并清理千分位分隔符。
        Column normalized = regexp_replace(trim(coalesce(safeCol(data, name), lit(""))), ",", "");
        // 无法解析的数值按零处理。
        return coalesce(round(normalized.cast("double"), 0).cast("long"), lit(0L));
    }

    // 将常见日期时间文本转换为 Spark 时间戳。
    public static Column parseTimestamp(Column value) {
        // 合并多余空白并规范化输入文本；定义支持短秒字段的日期时间格式；提取日期时间的各组成部分。
        Column normalized = trim(regexp_replace(coalesce(value, lit("")), "\\s+", " "));
        String pattern = "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?$";
        Column year = regexp_extract(normalized, pattern, 1);
        // 从正则第二个分组提取月份；从正则第三个分组提取日期；从正则第四个分组提取小时。
        Column month = regexp_extract(normalized, pattern, 2);
        Column day = regexp_extract(normalized, pattern, 3);
        Column hour = regexp_extract(normalized, pattern, 4);
        // 从正则第五个分组提取分钟；从可选的第六个分组提取秒，省略秒时得到空串；拼接为固定宽度的标准时间文本。
        Column minute = regexp_extract(normalized, pattern, 5);
        Column second = regexp_extract(normalized, pattern, 6);
        Column canonical = concat(year, lit("-"), lpad(month, 2, "0"), lit("-"), lpad(day, 2, "0"),
                // 在日期后拼接空格、补齐为两位的小时以及分钟。
                lit(" "), lpad(hour, 2, "0"), lit(":"), minute, lit(":"),
                // 来源未提供秒时补 00，否则保留原秒值。
                when(length(second).equalTo(0), lit("00")).otherwise(second));
        // 按标准格式解析时间戳，无法匹配的文本返回空值。
        return to_timestamp(canonical, "yyyy-MM-dd HH:mm:ss");
    }
}
