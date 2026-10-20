package com.social.hotspot.etl.cleaning;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.hasColumn;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.safeCol;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.types.DataTypes;
import static org.apache.spark.sql.functions.*;
import com.social.hotspot.etl.cleaning.TextDamageDetector;

// 集中构建质量标记表达式，不在此处执行过滤或输出文件。
public final class RecordQualityExpressions {
    // 禁止实例化质量表达式工具类。
    private RecordQualityExpressions() {}
    // 通过标题省略号和关键词末尾分隔符识别疑似截断。
    public static Column hasPotentialTruncation(Dataset<Row> data) {
        // 将标题空值转为空串后去掉首尾空格；将关键词空值转为空串后去掉首尾空格。
        Column title = trim(coalesce(safeCol(data, "title"), lit("")));
        Column keywords = trim(coalesce(safeCol(data, "keywords"), lit("")));
        // 任一字段存在截断特征即标记当前记录。
        return title.rlike(".*(\\.\\.\\.|\\u2026)$").or(keywords.rlike(".*[,\\uFF0C\\u3001;\\uFF1B|/]$"));
    }

    // 判断五个文本字段中是否存在可清洗的格式或编码残留。
    public static Column requiresTextNormalization(Dataset<Row> data) {
        // 定义参与规范化检测的文本字段。
        String[] names = {"title", "content_text", "author_name", "keywords", "category"};
        // 从未命中状态开始构建各字段条件的或表达式。
        Column requiresNormalization = lit(false);
        // 检查每个文本字段。
        for (String name : names) {
            // 缺失字段不产生格式异常；合并控制字符、乱码片段和重复空白命中条件。
            Column value = coalesce(safeCol(data, name), lit(""));
            requiresNormalization = requiresNormalization.or(value.rlike("[\\p{Cc}\\p{Cf}]|\\uFFFD|\\u951f\\u65a4\\u62f7|\\u00C3\\u00A9|\\s{2,}"));
        }
        // 返回整条记录是否需要规范化的 Spark 表达式。
        return requiresNormalization;
    }

    // 检查互动字段是否包含非空但不符合允许格式的数值。
    public static Column hasInvalidNumeric(Dataset<Row> data) {
        // 包含热度字段以及额外的分享和浏览字段。
        String[] names = {"like_count", "comment_count", "repost_count", "share_count", "favorite_count", "view_count"};
        // 初始状态假定数值字段全部有效。
        Column invalid = lit(false);
        // 为各互动字段构建异常判定条件。
        for (String name : names) {
            // 去除数值首尾空白，缺失列仍保持空值；接受 CSV 中形如 2014.0 的非负数值文本；允许前导加号、千分位逗号和小数，数字总长度最多八位。
            Column value = trim(safeCol(data, name));
            Column digits = regexp_replace(value, "[+,.]", "");
            Column validNumber = value.rlike("^\\+?\\d[\\d,]*(?:\\.\\d+)?$").and(length(digits).leq(8));
            // 仅将非空且格式非法的字段计为异常。
            invalid = invalid.or(value.isNotNull().and(value.notEqual("")).and(not(validNumber)));
        }
        // 返回是否存在任一非法互动字段。
        return invalid;
    }

    // 在清洗前检测原始文本，返回首个编码损坏原因。
    public static Column textQualityReason(Dataset<Row> data) {
        // 规定损坏原因的字段检查优先顺序。
        String[] fields = {"title", "content_text", "author_name", "keywords", "category"};
        // 未发现损坏时保持字符串类型空值；注册共享规则的 Java 文本损坏检测函数。
        Column reason = lit(null).cast("string");
        var detect = udf((UDF1<String, String>) TextDamageDetector::rejectionReason, DataTypes.StringType);
        // 遍历各原始字段并优先保留第一条损坏原因。
        for (String field : fields) {
            // 安全读取原始字段，缺列返回空值。
            Column original = safeCol(data, field);
            // 历史明细没有原正文列时使用清洗正文检测。
            if ("content_text".equals(field) && !hasColumn(data, field)) original = safeCol(data, "clean_text");
            // 拼接字段名与损坏原因，已命中的前序原因不被覆盖。
            reason = coalesce(reason, concat(lit(field + ":"), detect.apply(original.cast("string"))));
        }
        // 返回携带字段名的质量原因表达式。
        return reason;
    }
}
