// 将本类归入清洗规则包，统一原始字段处理与质量规则的代码位置。
package com.social.hotspot.etl.cleaning;
// 复用平台互动字段适配规则，计算规范化后的互动总量。
import static com.social.hotspot.etl.metric.InteractionHeatCalculator.applyInteractionPolicy;
// 构建原始互动数值非法标记。
import static com.social.hotspot.etl.cleaning.RecordQualityExpressions.hasInvalidNumeric;
// 构建标题和关键词疑似截断标记。
import static com.social.hotspot.etl.cleaning.RecordQualityExpressions.hasPotentialTruncation;
// 将空白字段转换为 NULL，供缺失值回退使用。
import static com.social.hotspot.etl.cleaning.DataFrameColumns.nullIfBlank;
// 将来源互动字段解析为长整数列。
import static com.social.hotspot.etl.cleaning.DataFrameColumns.numberCol;
// 将来源日期文本解析为标准时间戳列。
import static com.social.hotspot.etl.cleaning.DataFrameColumns.parseTimestamp;
// 标记原始文本是否命中规范化规则。
import static com.social.hotspot.etl.cleaning.RecordQualityExpressions.requiresTextNormalization;
// 安全引用可选字段，缺列时生成空值。
import static com.social.hotspot.etl.cleaning.DataFrameColumns.safeCol;

// 使用 Spark 列表达式描述字段转换和质量条件。
import org.apache.spark.sql.Column;
// 使用分布式数据集承载来源或规范化记录。
import org.apache.spark.sql.Dataset;
// 以通用行结构读取不同来源的数据。
import org.apache.spark.sql.Row;
// 将单参数 Java 函数包装为 Spark 用户定义函数。
import org.apache.spark.sql.api.java.UDF1;
// 指定字符串 UDF 的 Spark 返回类型。
import org.apache.spark.sql.types.DataTypes;
// 使用 Spark 内置函数构造列转换、条件判断及字符串处理表达式。
import static org.apache.spark.sql.functions.*;
// 将 HTML 片段解析为可见文本。
import com.social.hotspot.etl.cleaning.HtmlTextCleaner;

// 将不同来源的原始字段转换为统一明细结构。
public final class ContentNormalizer {
    // 禁止实例化规范化工具类。
    private ContentNormalizer() {}
    // 构建标准字段并附加质量标记、小时桶和批次信息。
    public static Dataset<Row> normalize(Dataset<Row> raw, String batchId) {
        // 按统一仓库字段顺序投影原始数据。
        Dataset<Row> normalized = raw.select(
                        // 保留记录归属的事件标识。
                        safeCol(raw, "event_id"),
                        // 保留事件展示名称。
                        safeCol(raw, "event_name"),
                        // 将来源平台别名映射为标准平台代码。
                        normalizePlatform(safeCol(raw, "platform")).alias("platform"),
                        // 缺失内容 ID 时使用平台、正文和时间生成哈希，长度限制为 128。
                        coalesce(nullIfBlank(safeCol(raw, "content_id")), sha2(concat_ws("||", safeCol(raw, "platform"), safeCol(raw, "content_text"), safeCol(raw, "publish_time")), 256)).substr(1, 128).alias("content_id"),
                        // 父内容 ID 缺失时用空文本占位，并限制长度。
                        coalesce(nullIfBlank(safeCol(raw, "parent_content_id")), lit("")).substr(1, 128).alias("parent_content_id"),
                        // 规范内容类型，无法识别时归为帖子。
                        normalizeContentType(safeCol(raw, "content_type")).alias("content_type"),
                        // 清洗标题并限制数据库允许的标题长度。
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "title")), lit(""))).substr(1, 300).alias("title"),
                        // 清洗分析正文，原正文缺失时退回标题。
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "content_text")), nullIfBlank(safeCol(raw, "title")), lit(""))).alias("clean_text"),
                        // 单独保留清洗后的原正文，供微博正文有效性校验。
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "content_text")), lit(""))).alias("content_text"),
                        // 空白作者 ID 转空值并限制长度。
                        nullIfBlank(safeCol(raw, "author_id")).substr(1, 128).alias("author_id"),
                        // 清洗作者名并限制展示长度。
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "author_name")), lit(""))).substr(1, 100).alias("author_name"),
                        // 分别解析两个时间字段，缺失或解析失败时保留空值。
                        parseTimestamp(safeCol(raw, "publish_time")).alias("publish_time"),
                        // 独立解析采集时间，不以发布时间补缺。
                        parseTimestamp(safeCol(raw, "crawl_time")).alias("crawl_time"),
                        // 解析点赞量，异常文本按数值规范化规则处理。
                        numberCol(raw, "like_count").alias("like_count"),
                        // 解析评论量。
                        numberCol(raw, "comment_count").alias("comment_count"),
                        // 解析转发量。
                        numberCol(raw, "repost_count").alias("repost_count"),
                        // 解析分享量。
                        numberCol(raw, "share_count").alias("share_count"),
                        // 解析收藏量。
                        numberCol(raw, "favorite_count").alias("favorite_count"),
                        // 解析浏览量。
                        numberCol(raw, "view_count").alias("view_count"),
                        // 规范地区文本，缺失时保留空串。
                        coalesce(nullIfBlank(safeCol(raw, "location")), lit("")).substr(1, 100).alias("location"),
                        // 保留来源提供的年龄分组并限制长度。
                        coalesce(nullIfBlank(safeCol(raw, "user_age_group")), lit("")).substr(1, 50).alias("user_age_group"),
                        // 保留来源提供的性别标签并限制长度。
                        coalesce(nullIfBlank(safeCol(raw, "user_gender")), lit("")).substr(1, 30).alias("user_gender"),
                        // 清洗关键词文本，保持后续聚合所需的原始词串。
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "keywords")), lit(""))).substr(1, 1000).alias("keywords"),
                        // 优先采用 source_url，缺失时读取旧版 url 字段。
                        normalizeUrl(coalesce(nullIfBlank(safeCol(raw, "source_url")), nullIfBlank(safeCol(raw, "url")))).alias("source_url"),
                        // 保留图片链接，缺失时用空文本占位。
                        coalesce(nullIfBlank(safeCol(raw, "image_url")), lit("")).substr(1, 800).alias("image_url"),
                        // 统一类别别名和无效类别标签。
                        normalizeCategory(safeCol(raw, "category")).alias("category"),
                        // 记录原始互动字段是否包含非法数值。
                        hasInvalidNumeric(raw).alias("has_invalid_numeric"),
                        // 标记 CSV 解析器发现的结构损坏记录。
                        safeCol(raw, "_corrupt_record").isNotNull().alias("is_structurally_invalid"),
                        // 检查原始内容 ID 的字符集和长度。
                        not(coalesce(nullIfBlank(safeCol(raw, "content_id")), lit("")).rlike("^[A-Za-z0-9_-]{3,128}$")).alias("has_invalid_content_id"),
                        // 标记疑似截断的原始文本。
                        hasPotentialTruncation(raw).alias("is_potentially_truncated"),
                        // 保留文本损坏检测返回的原因。
                        safeCol(raw, "text_quality_reason").alias("text_quality_reason"),
                        // 保留关联审核记录 ID。
                        coalesce(safeCol(raw, "_review_id"), lit("")).alias("_review_id"),
                        // 将审核暂缓标记转换为布尔列。
                        coalesce(safeCol(raw, "_review_hold").equalTo("true"), lit(false)).alias("_review_hold"),
                        // 将人工审核通过标记转换为布尔列。
                        coalesce(safeCol(raw, "_review_approved").equalTo("true"), lit(false)).alias("_review_approved"),
                        // 保留人工情感标签，缺失时不覆盖模型判断。
                        coalesce(safeCol(raw, "_review_sentiment"), lit("")).alias("_review_sentiment"),
                        // 记录文本是否需要清洗修复。
                        requiresTextNormalization(raw).alias("text_normalized")
                )
                // 将发布时间截断到小时，用作时间聚合键。
                .withColumn("time_bucket", date_trunc("hour", col("publish_time")))
                // 在模型结果合并前设置默认中性标签。
                .withColumn("sentiment_label", lit("neutral"))
                // 根据推广关键词标记噪声候选文本。
                .withColumn("is_noise", col("clean_text").rlike("(?i)(领取|福利|点击链接|加群|广告|刷屏)"))
                // 写入本次 ETL 批次号。
                .withColumn("batch_id", lit(batchId));
        // 按平台支持关系修正互动字段并计算互动总量。
        return applyInteractionPolicy(normalized);
    }

    // 构造正文清洗表达式，并限制分析文本长度。
    public static Column cleanText(Column source) {
        // 注册基于 HTML 解析器的字符串清洗函数。
        var htmlCleaner = udf((UDF1<String, String>) HtmlTextCleaner::clean, DataTypes.StringType);
        // 清除控制字符和格式控制字符。
        Column cleaned = regexp_replace(htmlCleaner.apply(source), "[\\p{Cc}\\p{Cf}]", "");
        // 移除常见编码替换字符和乱码片段。
        cleaned = regexp_replace(cleaned, "\\uFFFD|\\u951f\\u65a4\\u62f7|\\u00C3\\u00A9", "");
        // 从分析文本移除 HTTP 链接。
        cleaned = regexp_replace(cleaned, "(?i)https?://\\S+", " ");
        // 移除连续 24 个以上字母构成的异常长串。
        cleaned = regexp_replace(cleaned, "[A-Za-z]{24,}", " ");
        // 将连续三次以上的同一标点压缩为一次。
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
                // 将网易来源的三种别名统一为 NETEASE_NEWS。
                .when(normalized.isin("NETEASENEWS", "NETEASE_NEWS", "NETEASE"), "NETEASE_NEWS")
                // 将搜狐来源的三种别名统一为 SOHU_NEWS。
                .when(normalized.isin("SOHUNEWS", "SOHU_NEWS", "SOHU"), "SOHU_NEWS")
                // 将新浪新闻别名统一为 SINA_NEWS，不与微博编码混用。
                .when(normalized.isin("SINANEWS", "SINA_NEWS", "SINA"), "SINA_NEWS")
                // 将澎湃来源别名统一为 THE_PAPER。
                .when(normalized.isin("THEPAPER", "THE_PAPER", "PAPER"), "THE_PAPER")
                // 将新浪微博别名统一为 WEIBO。
                .when(normalized.isin("WEIBO", "SINA_WEIBO"), "WEIBO")
                // 未知来源保留规范化后的编码，交由质量过滤决定是否接受。
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
        // 获取纯文本类别原值。
        Column original = trim(cleanText(coalesce(value, lit(""))));
        // 使用小写副本判断无效占位词。
        Column lowerCase = lower(original);
        // 空值、占位词和纯数字归为未分类，已知别名映射为标准类别。
        return when(lowerCase.isin("", "null", "undefined", "none", "n/a", "nan", "unknown", "未知", "其他"), "未分类")
                // 纯数字无法表示业务类别，统一归为未分类。
                .when(original.rlike("^\\d+$"), "未分类")
                // 娱乐、影视及音乐来源类别归并为文娱。
                .when(original.isin("娱乐", "影视", "音乐"), "文娱")
                // 社会新闻和民生来源类别归并为社会。
                .when(original.isin("社会新闻", "民生"), "社会")
                // 综合类和泛新闻标签归并为综合。
                .when(original.isin("综合类", "新闻"), "综合")
                // 其它类别保留原文本，并截取到数据库允许的长度。
                .otherwise(original.substr(1, 100));
    }

    // 只接受没有空白字符的 HTTP 或 HTTPS 地址。
    public static Column normalizeUrl(Column value) {
        // 只保留 HTTP 或 HTTPS 且不含空白的地址，否则使用空串。
        return when(value.rlike("(?i)^https?://\\S+$"), trim(value)).otherwise(lit(""));
    }
}
