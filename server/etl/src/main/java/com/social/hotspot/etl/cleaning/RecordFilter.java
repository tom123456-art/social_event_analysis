package com.social.hotspot.etl.cleaning;
import static com.social.hotspot.etl.cleaning.FieldNormalizer.*;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.types.DataTypes;
import static org.apache.spark.sql.functions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.regex.Pattern;

/** 原始质量检查和有效性过滤；可修复异常只标记，不作为淘汰条件。 */
public final class RecordFilter {
    // 从共享资源加载质量规则，避免与其他处理环节维护不同的乱码判定口径。
    private static final JsonNode RULES = loadRules();
    // 分别检测明确损坏标记、混合编码损坏和疑似缺字片段。
    private static final Pattern EXPLICIT = Pattern.compile(RULES.get("explicitDamagePattern").asText());
    private static final Pattern MIXED = Pattern.compile(RULES.get("mixedDamagePattern").asText());
    private static final Pattern MISSING = Pattern.compile(RULES.get("missingCharactersPattern").asText());
    private static final int MINIMUM_FRAGMENTS = RULES.get("minimumMissingFragments").asInt();

    // 禁止实例化仅提供静态方法的记录质量过滤工具类。
    private RecordFilter() {}

    // 返回共享规则版本，供作业记录本次质量检查使用的规则口径。
    public static String version() { return RULES.get("version").asText(); }

    // 向同包文本清洗器提供同一份明确损坏标记规则。
    static Pattern explicitDamagePattern() { return EXPLICIT; }

    // 在文本被清洗前检查原始证据，仅追加质量标记，不修改原字段或删除记录。
    public static Dataset<Row> inspectRaw(Dataset<Row> raw) {
        return raw.withColumn("text_quality_reason", textQualityReason(raw))
                .withColumn("has_invalid_numeric", hasInvalidNumeric(raw))
                .withColumn("is_structurally_invalid", safeCol(raw, "_corrupt_record").isNotNull())
                .withColumn("has_invalid_content_id", not(coalesce(nullIfBlank(safeCol(raw, "content_id")), lit(""))
                        .rlike("^[A-Za-z0-9_-]{3,128}$")))
                .withColumn("is_potentially_truncated", hasPotentialTruncation(raw));
    }

    // 过滤乱码、人工暂缓、必要字段缺失及正文无效记录；数值、编号和截断异常不单独作为淘汰条件。
    public static Dataset<Row> retainUsable(Dataset<Row> normalized) {
        // 有效字符仅计汉字、英文字母和数字；正文至少四个有效字符，且占去空白字符的比例不低于五分之一。
        Column meaningfulChars = regexp_replace(col("clean_text"), "[^\\p{IsHan}A-Za-z0-9]", "");
        Column compactChars = regexp_replace(col("clean_text"), "\\s+", "");
        return normalized.filter(col("text_quality_reason").isNull())
                .filter(not(col("_review_hold")))
                .filter(col("event_id").isNotNull())
                .filter(col("platform").isin("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "WEIBO"))
                // 微博必须有原正文有效字符，不能只靠标题补足。
                .filter(not(col("platform").equalTo("WEIBO")).or(length(regexp_replace(
                        col("content_text"), "[^\\p{IsHan}A-Za-z0-9]", "")).geq(1)))
                .filter(not(col("is_structurally_invalid")))
                .filter(col("publish_time").isNotNull())
                .filter(col("crawl_time").isNotNull())
                .filter(length(meaningfulChars).geq(4))
                .filter(length(meaningfulChars).multiply(5).geq(length(compactChars)))
                .drop("has_invalid_numeric", "is_structurally_invalid", "has_invalid_content_id",
                        "is_potentially_truncated", "text_normalized", "text_quality_reason");
    }
    // 通过标题省略号和关键词末尾分隔符识别疑似截断。
    private static Column hasPotentialTruncation(Dataset<Row> data) {
        // 将标题空值转为空串后去掉首尾空格；将关键词空值转为空串后去掉首尾空格。
        Column title = trim(coalesce(safeCol(data, "title"), lit("")));
        Column keywords = trim(coalesce(safeCol(data, "keywords"), lit("")));
        // 任一字段存在截断特征即标记当前记录。
        return title.rlike(".*(\\.\\.\\.|\\u2026)$").or(keywords.rlike(".*[,\\uFF0C\\u3001;\\uFF1B|/]$"));
    }

    // 检查互动字段是否包含非空但不符合允许格式的数值。
    private static Column hasInvalidNumeric(Dataset<Row> data) {
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
        var detect = udf((UDF1<String, String>) RecordFilter::rejectionReason, DataTypes.StringType);
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

    // 返回首个命中的文本损坏原因码；空文本或未命中规则时返回空值，由后续有效性过滤判断可用性。
    public static String rejectionReason(String text) {
        if (text == null || text.isEmpty()) return null;
        if (EXPLICIT.matcher(text).find()) return "ENCODING_DAMAGE_MARKER";
        if (MIXED.matcher(text).find()) return "MIXED_ENCODING_DAMAGE";
        var matcher = MISSING.matcher(text);
        // 疑似缺字按片段数量判断，未达到共享规则阈值时不作拒绝处理。
        int fragments = 0;
        while (matcher.find()) {
            if (++fragments >= MINIMUM_FRAGMENTS) return "MULTIPLE_MISSING_CHARACTER_FRAGMENTS";
        }
        return null;
    }

    // 从类路径读取共享规则；资源缺失或解析失败时终止类初始化，避免静默跳过质量检查。
    private static JsonNode loadRules() {
        try (InputStream input = RecordFilter.class.getResourceAsStream("/text-quality-rules.json")) {
            if (input == null) throw new IllegalStateException("缺少文本质量规则文件，请检查部署资源。");
            return new ObjectMapper().readTree(input);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
