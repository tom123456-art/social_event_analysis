package com.social.hotspot.etl.cleaning;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import static org.apache.spark.sql.functions.*;

/** 字段映射、默认值、类型转换及长度限制；不检测质量或执行文本清洗。 */
public final class FieldNormalizer {
    // 禁止实例化仅提供静态方法的字段规范化工具类。
    private FieldNormalizer() {}

    // 补齐事件与来源关联字段；事件编号优先采用原值，事件名称统一使用作业参数。
    public static Dataset<Row> prepareEvent(Dataset<Row> parsed, String eventId, String eventName) {
        return ensureColumns(parsed, "event_id", "event_name", "url", "source_url", "parent_content_id")
                .withColumn("event_id", coalesce(nullIfBlank(col("event_id")), lit(eventId)))
                .withColumn("event_name", lit(eventName).substr(1, 200));
    }

    /** 输入已通过原始质量检查与文本规范化，原字段仍用于生成内容键。 */
    public static Dataset<Row> normalize(Dataset<Row> raw, String batchId) {
        // 内容编号缺失时，以原始平台、正文和发布时间生成哈希，避免文本清洗影响内容标识。
        // 投影标准字段，采用已清洗的文本临时列，并保留后续过滤和去重需要的质量标记与审核信息。
        return raw.select(
                safeCol(raw, "event_id"), safeCol(raw, "event_name"),
                normalizePlatform(safeCol(raw, "platform")).alias("platform"),
                coalesce(nullIfBlank(safeCol(raw, "content_id")), sha2(concat_ws("||", safeCol(raw, "platform"),
                        safeCol(raw, "content_text"), safeCol(raw, "publish_time")), 256)).substr(1, 128).alias("content_id"),
                coalesce(nullIfBlank(safeCol(raw, "parent_content_id")), lit("")).substr(1, 128).alias("parent_content_id"),
                normalizeContentType(safeCol(raw, "content_type")).alias("content_type"),
                col("_normalized_title").substr(1, 300).alias("title"),
                col("clean_text"), col("_normalized_content_text").alias("content_text"),
                nullIfBlank(safeCol(raw, "author_id")).substr(1, 128).alias("author_id"),
                col("_normalized_author_name").substr(1, 100).alias("author_name"),
                parseTimestamp(safeCol(raw, "publish_time")).alias("publish_time"),
                parseTimestamp(safeCol(raw, "crawl_time")).alias("crawl_time"),
                numberCol(raw, "like_count").alias("like_count"),
                numberCol(raw, "comment_count").alias("comment_count"),
                numberCol(raw, "repost_count").alias("repost_count"),
                numberCol(raw, "share_count").alias("share_count"),
                numberCol(raw, "favorite_count").alias("favorite_count"),
                numberCol(raw, "view_count").alias("view_count"),
                coalesce(nullIfBlank(safeCol(raw, "location")), lit("")).substr(1, 100).alias("location"),
                coalesce(nullIfBlank(safeCol(raw, "user_age_group")), lit("")).substr(1, 50).alias("user_age_group"),
                coalesce(nullIfBlank(safeCol(raw, "user_gender")), lit("")).substr(1, 30).alias("user_gender"),
                col("_normalized_keywords").alias("keywords"),
                normalizeUrl(coalesce(nullIfBlank(safeCol(raw, "source_url")), nullIfBlank(safeCol(raw, "url")))).alias("source_url"),
                coalesce(nullIfBlank(safeCol(raw, "image_url")), lit("")).substr(1, 800).alias("image_url"),
                when(normalizePlatform(safeCol(raw, "platform")).equalTo("WEIBO"), lit(""))
                        .otherwise(normalizeCategory(col("_normalized_category"))).alias("category"),
                col("has_invalid_numeric"), col("is_structurally_invalid"), col("has_invalid_content_id"),
                col("is_potentially_truncated"), col("text_quality_reason"),
                coalesce(safeCol(raw, "_review_id"), lit("")).alias("_review_id"),
                coalesce(safeCol(raw, "_review_hold").equalTo("true"), lit(false)).alias("_review_hold"),
                coalesce(safeCol(raw, "_review_approved").equalTo("true"), lit(false)).alias("_review_approved"),
                coalesce(safeCol(raw, "_review_sentiment"), lit("")).alias("_review_sentiment"),
                col("text_normalized"))
                .withColumn("time_bucket", date_trunc("hour", col("publish_time")))
                .withColumn("sentiment_label", lit("neutral"))
                .withColumn("is_noise", col("clean_text").rlike("(?i)(领取|福利|点击链接|加群|广告|刷屏)"))
                .withColumn("batch_id", lit(batchId));
    }

    // 统一平台代码的大小写与分隔符，并将各来源别名映射为标准平台代码。
    public static Column normalizePlatform(Column value) {
        Column normalized = upper(regexp_replace(trim(coalesce(value, lit("UNKNOWN"))), "[\\s-]+", "_"));
        return when(normalized.isin("TENCENTNEWS", "TENCENT_NEWS", "TENCENT"), "TENCENT_NEWS")
                .when(normalized.isin("NETEASENEWS", "NETEASE_NEWS", "NETEASE"), "NETEASE_NEWS")
                .when(normalized.isin("SOHUNEWS", "SOHU_NEWS", "SOHU"), "SOHU_NEWS")
                .when(normalized.isin("SINANEWS", "SINA_NEWS", "SINA"), "SINA_NEWS")
                .when(normalized.isin("THEPAPER", "THE_PAPER", "PAPER"), "THE_PAPER")
                .when(normalized.isin("WEIBO", "SINA_WEIBO"), "WEIBO").otherwise(normalized);
    }

    // 内容类型仅保留允许的枚举值，缺失或未知类型统一按普通帖子处理。
    private static Column normalizeContentType(Column value) {
        Column normalized = lower(trim(coalesce(value, lit("post"))));
        return when(normalized.isin("news", "article", "post", "video", "image", "comment"), normalized).otherwise("post");
    }

    // 将无意义类别归为未分类，合并常见类别别名，并限制输出长度。
    private static Column normalizeCategory(Column original) {
        return when(lower(original).isin("", "null", "undefined", "none", "n/a", "nan", "unknown", "未知", "其他"), "未分类")
                .when(original.rlike("^\\d+$"), "未分类")
                .when(original.isin("娱乐", "影视", "音乐"), "文娱")
                .when(original.isin("社会新闻", "民生"), "社会")
                .when(original.isin("综合类", "新闻"), "综合").otherwise(original.substr(1, 100));
    }

    // 仅保留不含空白字符的 HTTP 或 HTTPS 链接，其他来源地址统一置为空串。
    private static Column normalizeUrl(Column value) {
        return when(value.rlike("(?i)^https?://\\S+$"), trim(value)).otherwise(lit(""));
    }

    // 将数据集投影为目标数据集的完整字段结构。
    public static Dataset<Row> alignToSchema(Dataset<Row> data, Dataset<Row> schemaSource) {
        org.apache.spark.sql.types.StructField[] fields = schemaSource.schema().fields();
        Column[] projection = new Column[fields.length];
        for (int index = 0; index < fields.length; index++) {
            org.apache.spark.sql.types.StructField field = fields[index];
            projection[index] = safeCol(data, field.name()).cast(field.dataType()).alias(field.name());
        }
        return data.select(projection);
    }

    // 确保数据集包含指定字段，不存在字段统一补空值。
    public static Dataset<Row> ensureColumns(Dataset<Row> data, String... names) {
        Dataset<Row> result = data;
        for (String name : names) {
            if (!hasColumn(result, name)) result = result.withColumn(name, lit(null));
        }
        return result;
    }

    // 安全读取字段，缺失字段时返回空列。
    public static Column safeCol(Dataset<Row> data, String name) {
        return hasColumn(data, name) ? col(name) : lit(null);
    }

    // 判断数据集是否包含指定名称的字段，并忽略大小写差异。
    public static boolean hasColumn(Dataset<Row> data, String name) {
        for (String column : data.columns()) {
            if (column.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // 将空白文本转换为空值并保留非空文本的去空格结果。
    public static Column nullIfBlank(Column column) {
        return when(trim(column).equalTo(""), null).otherwise(trim(column));
    }

    // 将互动数字字段中的逗号、空值和小数统一为长整数。
    private static Column numberCol(Dataset<Row> data, String name) {
        Column normalized = regexp_replace(trim(coalesce(safeCol(data, name), lit(""))), ",", "");
        // 无法解析的数值按零处理。
        return coalesce(round(normalized.cast("double"), 0).cast("long"), lit(0L));
    }

    // 将常见日期时间文本转换为 Spark 时间戳。
    private static Column parseTimestamp(Column value) {
        Column normalized = trim(regexp_replace(coalesce(value, lit("")), "\\s+", " "));
        String pattern = "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?$";
        Column year = regexp_extract(normalized, pattern, 1);
        Column month = regexp_extract(normalized, pattern, 2);
        Column day = regexp_extract(normalized, pattern, 3);
        Column hour = regexp_extract(normalized, pattern, 4);
        Column minute = regexp_extract(normalized, pattern, 5);
        Column second = regexp_extract(normalized, pattern, 6);
        Column canonical = concat(year, lit("-"), lpad(month, 2, "0"), lit("-"), lpad(day, 2, "0"),
                lit(" "), lpad(hour, 2, "0"), lit(":"), minute, lit(":"),
                when(length(second).equalTo(0), lit("00")).otherwise(second));
        // 按标准格式解析时间戳，无法匹配的文本返回空值。
        return to_timestamp(canonical, "yyyy-MM-dd HH:mm:ss");
    }
}
