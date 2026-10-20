package com.social.hotspot.etl.cleaning;
import static com.social.hotspot.etl.metric.InteractionHeatCalculator.applyInteractionPolicy;
import static com.social.hotspot.etl.cleaning.RecordQualityExpressions.hasInvalidNumeric;
import static com.social.hotspot.etl.cleaning.RecordQualityExpressions.hasPotentialTruncation;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.nullIfBlank;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.numberCol;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.parseTimestamp;
import static com.social.hotspot.etl.cleaning.RecordQualityExpressions.requiresTextNormalization;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.safeCol;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.types.DataTypes;
import static org.apache.spark.sql.functions.*;
import com.social.hotspot.etl.cleaning.HtmlTextCleaner;

// 将不同来源的原始字段转换为统一明细结构。
public final class ContentNormalizer {
    // 禁止实例化规范化工具类。
    private ContentNormalizer() {}
    // 构建标准字段并附加质量标记、小时桶和批次信息。
    public static Dataset<Row> normalize(Dataset<Row> raw, String batchId) {
        // 按统一仓库字段顺序投影原始数据。
        Dataset<Row> normalized = raw.select(
                        // 保留事件归属信息，并统一来源平台编码。
                        safeCol(raw, "event_id"),
                        safeCol(raw, "event_name"),
                        normalizePlatform(safeCol(raw, "platform")).alias("platform"),
                        // 缺失内容 ID 时使用平台、正文和时间生成哈希，长度限制为 128；父内容 ID 缺失时用空文本占位，并限制长度；规范内容类型，无法识别时归为帖子。
                        coalesce(nullIfBlank(safeCol(raw, "content_id")), sha2(concat_ws("||", safeCol(raw, "platform"), safeCol(raw, "content_text"), safeCol(raw, "publish_time")), 256)).substr(1, 128).alias("content_id"),
                        coalesce(nullIfBlank(safeCol(raw, "parent_content_id")), lit("")).substr(1, 128).alias("parent_content_id"),
                        normalizeContentType(safeCol(raw, "content_type")).alias("content_type"),
                        // 清洗标题和正文；分析正文可回退到标题，另保留原正文用于微博有效性校验。
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "title")), lit(""))).substr(1, 300).alias("title"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "content_text")), nullIfBlank(safeCol(raw, "title")), lit(""))).alias("clean_text"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "content_text")), lit(""))).alias("content_text"),
                        // 空白作者 ID 转空值并限制长度；清洗作者名并限制展示长度；分别解析两个时间字段，缺失或解析失败时保留空值。
                        nullIfBlank(safeCol(raw, "author_id")).substr(1, 128).alias("author_id"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "author_name")), lit(""))).substr(1, 100).alias("author_name"),
                        parseTimestamp(safeCol(raw, "publish_time")).alias("publish_time"),
                        // 独立解析采集时间，不以发布时间补缺；解析点赞量，异常文本按数值规范化规则处理；解析评论量。
                        parseTimestamp(safeCol(raw, "crawl_time")).alias("crawl_time"),
                        numberCol(raw, "like_count").alias("like_count"),
                        numberCol(raw, "comment_count").alias("comment_count"),
                        // 将转发、分享和收藏计数统一解析为数值。
                        numberCol(raw, "repost_count").alias("repost_count"),
                        numberCol(raw, "share_count").alias("share_count"),
                        numberCol(raw, "favorite_count").alias("favorite_count"),
                        // 解析浏览量；规范地区文本，缺失时保留空串；保留来源提供的年龄分组并限制长度。
                        numberCol(raw, "view_count").alias("view_count"),
                        coalesce(nullIfBlank(safeCol(raw, "location")), lit("")).substr(1, 100).alias("location"),
                        coalesce(nullIfBlank(safeCol(raw, "user_age_group")), lit("")).substr(1, 50).alias("user_age_group"),
                        // 保留来源提供的性别标签并限制长度；清洗关键词文本，保持后续聚合所需的原始词串；优先采用 source_url，缺失时读取旧版 url 字段。
                        coalesce(nullIfBlank(safeCol(raw, "user_gender")), lit("")).substr(1, 30).alias("user_gender"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "keywords")), lit(""))).substr(1, 1000).alias("keywords"),
                        normalizeUrl(coalesce(nullIfBlank(safeCol(raw, "source_url")), nullIfBlank(safeCol(raw, "url")))).alias("source_url"),
                        // 保留图片链接，缺失时用空文本占位；统一类别别名和无效类别标签；记录原始互动字段是否包含非法数值。
                        coalesce(nullIfBlank(safeCol(raw, "image_url")), lit("")).substr(1, 800).alias("image_url"),
                        when(normalizePlatform(safeCol(raw, "platform")).equalTo("WEIBO"), lit(""))
                                .otherwise(normalizeCategory(safeCol(raw, "category"))).alias("category"),
                        hasInvalidNumeric(raw).alias("has_invalid_numeric"),
                        // 标记结构损坏、非法内容标识和疑似截断，供后续质量过滤及审核使用。
                        safeCol(raw, "_corrupt_record").isNotNull().alias("is_structurally_invalid"),
                        not(coalesce(nullIfBlank(safeCol(raw, "content_id")), lit("")).rlike("^[A-Za-z0-9_-]{3,128}$")).alias("has_invalid_content_id"),
                        hasPotentialTruncation(raw).alias("is_potentially_truncated"),
                        // 保留文本损坏检测返回的原因；保留关联审核记录 ID；将审核暂缓标记转换为布尔列。
                        safeCol(raw, "text_quality_reason").alias("text_quality_reason"),
                        coalesce(safeCol(raw, "_review_id"), lit("")).alias("_review_id"),
                        coalesce(safeCol(raw, "_review_hold").equalTo("true"), lit(false)).alias("_review_hold"),
                        // 将人工审核通过标记转换为布尔列；保留人工情感标签，缺失时不覆盖模型判断；记录文本是否需要清洗修复。
                        coalesce(safeCol(raw, "_review_approved").equalTo("true"), lit(false)).alias("_review_approved"),
                        coalesce(safeCol(raw, "_review_sentiment"), lit("")).alias("_review_sentiment"),
                        requiresTextNormalization(raw).alias("text_normalized")
                )
                // 生成小时聚合键和默认情感占位，标记推广噪声候选。
                .withColumn("time_bucket", date_trunc("hour", col("publish_time")))
                .withColumn("sentiment_label", lit("neutral"))
                .withColumn("is_noise", col("clean_text").rlike("(?i)(领取|福利|点击链接|加群|广告|刷屏)"))
                // 写入本次 ETL 批次号。
                .withColumn("batch_id", lit(batchId));
        // 按平台支持关系修正互动字段并计算互动总量。
        return applyInteractionPolicy(normalized);
    }

    // 构造正文清洗表达式，并限制分析文本长度。
    public static Column cleanText(Column source) {
        // 使用 HTML 解析器提取文本，再清除控制字符和常见乱码片段。
        var htmlCleaner = udf((UDF1<String, String>) HtmlTextCleaner::clean, DataTypes.StringType);
        Column cleaned = regexp_replace(htmlCleaner.apply(source), "[\\p{Cc}\\p{Cf}]", "");
        cleaned = regexp_replace(cleaned, "\\uFFFD|\\u951f\\u65a4\\u62f7|\\u00C3\\u00A9", "");
        // 移除链接和异常长字母串，并压缩重复标点。
        cleaned = regexp_replace(cleaned, "(?i)https?://\\S+", " ");
        cleaned = regexp_replace(cleaned, "[A-Za-z]{24,}", " ");
        cleaned = regexp_replace(cleaned, "([!\\uFF01?\\uFF1F.\\u3002,\\uFF0C\\u3001;\\uFF1B:\\uFF1A~\\uFF5E_\\-])\\1{2,}", "$1");
        // 合并空白并截取前 1000 个字符。
        return trim(regexp_replace(cleaned, "\\s+", " ")).substr(1, 1000);
    }

    // 将平台名称中的空格和短横线统一，并映射已知别名。
    public static Column normalizePlatform(Column value) {
        // 生成大写且使用下划线分隔的平台编码。
        Column normalized = upper(regexp_replace(trim(coalesce(value, lit("UNKNOWN"))), "[\\s-]+", "_"));
        // 依次识别六个标准平台，未知编码保持规范化结果。
        return when(normalized.isin("TENCENTNEWS", "TENCENT_NEWS", "TENCENT"), "TENCENT_NEWS")
                // 将网易来源的三种别名统一为 NETEASE_NEWS；将搜狐来源的三种别名统一为 SOHU_NEWS；将新浪新闻别名统一为 SINA_NEWS，不与微博编码混用。
                .when(normalized.isin("NETEASENEWS", "NETEASE_NEWS", "NETEASE"), "NETEASE_NEWS")
                .when(normalized.isin("SOHUNEWS", "SOHU_NEWS", "SOHU"), "SOHU_NEWS")
                .when(normalized.isin("SINANEWS", "SINA_NEWS", "SINA"), "SINA_NEWS")
                // 将澎湃来源别名统一为 THE_PAPER；将新浪微博别名统一为 WEIBO；未知来源保留规范化后的编码，交由质量过滤决定是否接受。
                .when(normalized.isin("THEPAPER", "THE_PAPER", "PAPER"), "THE_PAPER")
                .when(normalized.isin("WEIBO", "SINA_WEIBO"), "WEIBO")
                .otherwise(normalized);
    }

    // 仅接受约定内容类型，其余类型统一为帖子。
    public static Column normalizeContentType(Column value) {
        // 将类型文本去空格并转小写，缺失时使用帖子类型。
        Column normalized = lower(trim(coalesce(value, lit("post"))));
        // 只保留约定的六种内容类型。
        return when(normalized.isin("news", "article", "post", "video", "image", "comment"), normalized)
                // 未识别的类型回退为 post，不因此删除正文。
                .otherwise("post");
    }

    // 清洗类别标签并合并中文类别别名。
    public static Column normalizeCategory(Column value) {
        // 获取纯文本类别原值；使用小写副本判断无效占位词。
        Column original = trim(cleanText(coalesce(value, lit(""))));
        Column lowerCase = lower(original);
        // 空值、占位词和纯数字归为未分类，已知别名映射为标准类别。
        return when(lowerCase.isin("", "null", "undefined", "none", "n/a", "nan", "unknown", "未知", "其他"), "未分类")
                // 纯数字无法表示业务类别，统一归为未分类；娱乐、影视及音乐来源类别归并为文娱；社会新闻和民生来源类别归并为社会。
                .when(original.rlike("^\\d+$"), "未分类")
                .when(original.isin("娱乐", "影视", "音乐"), "文娱")
                .when(original.isin("社会新闻", "民生"), "社会")
                // 综合类和泛新闻标签归并为综合；其它类别保留原文本，并截取到数据库允许的长度。
                .when(original.isin("综合类", "新闻"), "综合")
                .otherwise(original.substr(1, 100));
    }

    // 只接受没有空白字符的 HTTP 或 HTTPS 地址。
    public static Column normalizeUrl(Column value) {
        // 只保留 HTTP 或 HTTPS 且不含空白的地址，否则使用空串。
        return when(value.rlike("(?i)^https?://\\S+$"), trim(value)).otherwise(lit(""));
    }
}
