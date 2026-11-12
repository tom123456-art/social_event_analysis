package com.social.hotspot.etl.cleaning;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import static org.apache.spark.sql.functions.*;

/**
 * 将来源字段整理为统一结构，负责别名映射、缺省值、类型转换和长度限制。
 * 文本清洗由 TextNormalizer 完成，质量判定和记录淘汰由 RecordFilter 完成。
 */
public final class FieldNormalizer {
    // 禁止实例化仅提供静态方法的字段规范化工具类。
    private FieldNormalizer() {}

    // 补齐事件与来源关联字段；事件编号优先采用原值，事件名称统一使用作业参数。
    public static Dataset<Row> prepareEvent(Dataset<Row> parsed, String eventId, String eventName) {
        // 先补齐后续直接引用的列，避免来源缺列导致 Spark 分析表达式失败。
        // 原事件编号为空白时使用参数编号；事件名称始终采用参数值并限制为 200 个字符。
        return ensureColumns(parsed, "event_id", "event_name", "url", "source_url", "parent_content_id")
                .withColumn("event_id", coalesce(nullIfBlank(col("event_id")), lit(eventId)))
                .withColumn("event_name", lit(eventName).substr(1, 200));
    }

    /**
     * 输入应已追加原始质量标记与清洗文本列；有质量问题的记录此时尚未必被过滤。
     * 输出统一字段、质量标记和审核信息，供后续有效性过滤及去重继续使用。
     */
    public static Dataset<Row> normalize(Dataset<Row> raw, String batchId) {
        // 内容编号缺失时，以原始平台、正文和发布时间生成哈希，避免文本清洗影响内容标识。
        // 投影标准字段，采用已清洗的文本临时列，并保留后续过滤和去重需要的质量标记与审核信息。
        return raw.select(
                // 事件关联字段由 prepareEvent 提前补齐，此处保留原有列名。
                safeCol(raw, "event_id"), safeCol(raw, "event_name"),
                // 各平台别名统一为标准代码，便于后续白名单过滤和内容分组。
                normalizePlatform(safeCol(raw, "platform")).alias("platform"),
                // 优先使用去空格后的原编号；缺失时用原始平台、正文、发布时间生成 SHA-256。
                // 哈希输入不是清洗后文本，避免格式修复使同一来源记录的标识发生变化。
                coalesce(nullIfBlank(safeCol(raw, "content_id")), sha2(concat_ws("||", safeCol(raw, "platform"),
                        safeCol(raw, "content_text"), safeCol(raw, "publish_time")), 256)).substr(1, 128).alias("content_id"),
                // 无父级内容时使用空串；父级编号同样限制为 128 个字符。
                coalesce(nullIfBlank(safeCol(raw, "parent_content_id")), lit("")).substr(1, 128).alias("parent_content_id"),
                // 将内容类型约束到允许的枚举，无法识别时回退为 post。
                normalizeContentType(safeCol(raw, "content_type")).alias("content_type"),
                // 标题取清洗结果并收紧至 300 字符；分析文本与清洗正文分别保留。
                col("_normalized_title").substr(1, 300).alias("title"),
                col("clean_text"), col("_normalized_content_text").alias("content_text"),
                // 作者编号空白时保留为空值；作者名称使用已清洗的文本，最多保留 100 字符。
                nullIfBlank(safeCol(raw, "author_id")).substr(1, 128).alias("author_id"),
                col("_normalized_author_name").substr(1, 100).alias("author_name"),
                // 发布时间和采集时间采用相同解析规则，后续过滤要求两个时间都非空。
                parseTimestamp(safeCol(raw, "publish_time")).alias("publish_time"),
                parseTimestamp(safeCol(raw, "crawl_time")).alias("crawl_time"),
                // 六种互动计数统一转为长整数；数值异常标记仍单独保留，不在这里淘汰记录。
                numberCol(raw, "like_count").alias("like_count"),
                numberCol(raw, "comment_count").alias("comment_count"),
                numberCol(raw, "repost_count").alias("repost_count"),
                numberCol(raw, "share_count").alias("share_count"),
                numberCol(raw, "favorite_count").alias("favorite_count"),
                numberCol(raw, "view_count").alias("view_count"),
                // 地区、年龄组、性别缺失时使用空串，各自按目标字段长度截断。
                coalesce(nullIfBlank(safeCol(raw, "location")), lit("")).substr(1, 100).alias("location"),
                coalesce(nullIfBlank(safeCol(raw, "user_age_group")), lit("")).substr(1, 50).alias("user_age_group"),
                coalesce(nullIfBlank(safeCol(raw, "user_gender")), lit("")).substr(1, 30).alias("user_gender"),
                // 关键词沿用文本清洗阶段的结果及其 1000 字符限制。
                col("_normalized_keywords").alias("keywords"),
                // 来源地址优先 source_url，缺失时回退 url，再验证协议及空白字符。
                normalizeUrl(coalesce(nullIfBlank(safeCol(raw, "source_url")), nullIfBlank(safeCol(raw, "url")))).alias("source_url"),
                // 图片地址只处理空白和长度，不复用来源地址的协议校验。
                coalesce(nullIfBlank(safeCol(raw, "image_url")), lit("")).substr(1, 800).alias("image_url"),
                // 微博类别固定为空串；新闻平台对清洗后的类别进行归并。
                when(normalizePlatform(safeCol(raw, "platform")).equalTo("WEIBO"), lit(""))
                        .otherwise(normalizeCategory(col("_normalized_category"))).alias("category"),
                // 原样传递前置检查标记，让后续过滤仍可依据原始质量证据作判断。
                col("has_invalid_numeric"), col("is_structurally_invalid"), col("has_invalid_content_id"),
                col("is_potentially_truncated"), col("text_quality_reason"),
                // 审核编号和情绪缺失时补空串；审核状态按字符串 true 判断，缺失时补 false。
                coalesce(safeCol(raw, "_review_id"), lit("")).alias("_review_id"),
                coalesce(safeCol(raw, "_review_hold").equalTo("true"), lit(false)).alias("_review_hold"),
                coalesce(safeCol(raw, "_review_approved").equalTo("true"), lit(false)).alias("_review_approved"),
                coalesce(safeCol(raw, "_review_sentiment"), lit("")).alias("_review_sentiment"),
                // 保留格式修复标记，完成投影后再追加时间桶、初始情绪及批次等派生字段。
                col("text_normalized"))
                // 将发布时间截断到整点，便于按小时聚合传播趋势。
                .withColumn("time_bucket", date_trunc("hour", col("publish_time")))
                // 此处仅设置默认中性标签，具体情绪判定由后续环节负责。
                .withColumn("sentiment_label", lit("neutral"))
                // 命中任一营销类词语即标记噪声，此处只打标而不删除记录。
                .withColumn("is_noise", col("clean_text").rlike("(?i)(领取|福利|点击链接|加群|广告|刷屏)"))
                // 批次编号来自作业参数，用于关联本次处理结果。
                .withColumn("batch_id", lit(batchId));
    }

    // 统一平台代码的大小写与分隔符，并将各来源别名映射为标准平台代码。
    public static Column normalizePlatform(Column value) {
        // 空值先替换为 UNKNOWN，再去首尾空格，将空白及连字符替换为下划线并转大写。
        Column normalized = upper(regexp_replace(trim(coalesce(value, lit("UNKNOWN"))), "[\\s-]+", "_"));
        // 逐个平台归并紧连写法、下划线写法和简称；未识别代码保留，交由白名单过滤。
        return when(normalized.isin("TENCENTNEWS", "TENCENT_NEWS", "TENCENT"), "TENCENT_NEWS")
                .when(normalized.isin("NETEASENEWS", "NETEASE_NEWS", "NETEASE"), "NETEASE_NEWS")
                .when(normalized.isin("SOHUNEWS", "SOHU_NEWS", "SOHU"), "SOHU_NEWS")
                .when(normalized.isin("SINANEWS", "SINA_NEWS", "SINA"), "SINA_NEWS")
                .when(normalized.isin("THEPAPER", "THE_PAPER", "PAPER"), "THE_PAPER")
                .when(normalized.isin("WEIBO", "SINA_WEIBO"), "WEIBO").otherwise(normalized);
    }

    // 内容类型仅保留允许的枚举值，缺失或未知类型统一按普通帖子处理。
    private static Column normalizeContentType(Column value) {
        // 先转小写并去首尾空格，使枚举匹配不受来源大小写影响。
        Column normalized = lower(trim(coalesce(value, lit("post"))));
        return when(normalized.isin("news", "article", "post", "video", "image", "comment"), normalized).otherwise("post");
    }

    // 将无意义类别归为未分类，合并常见类别别名，并限制输出长度。
    private static Column normalizeCategory(Column original) {
        // 占位词、未知类别及纯数字类别统一为“未分类”；其余按文娱、社会、综合归并别名。
        // 未命中归并规则的类别保留原文本，最多输出 100 个字符。
        return when(lower(original).isin("", "null", "undefined", "none", "n/a", "nan", "unknown", "未知", "其他"), "未分类")
                .when(original.rlike("^\\d+$"), "未分类")
                .when(original.isin("娱乐", "影视", "音乐"), "文娱")
                .when(original.isin("社会新闻", "民生"), "社会")
                .when(original.isin("综合类", "新闻"), "综合").otherwise(original.substr(1, 100));
    }

    // 仅保留不含空白字符的 HTTP 或 HTTPS 链接，其他来源地址统一置为空串。
    private static Column normalizeUrl(Column value) {
        // 先验证整串再 trim；含首尾空白的传入值也不符合此正则，空值同样落入空串分支。
        return when(value.rlike("(?i)^https?://\\S+$"), trim(value)).otherwise(lit(""));
    }

    // 将数据集投影为目标数据集的完整字段结构。
    public static Dataset<Row> alignToSchema(Dataset<Row> data, Dataset<Row> schemaSource) {
        // 以目标数据集的字段顺序和类型为准，不保留输入中额外的字段。
        org.apache.spark.sql.types.StructField[] fields = schemaSource.schema().fields();
        Column[] projection = new Column[fields.length];
        for (int index = 0; index < fields.length; index++) {
            org.apache.spark.sql.types.StructField field = fields[index];
            // 存在的列转换为目标类型，缺失的列补同类型空值，并统一输出列名。
            projection[index] = safeCol(data, field.name()).cast(field.dataType()).alias(field.name());
        }
        // 一次性投影，得到可用于按列名合并的字段顺序、名称及类型。
        return data.select(projection);
    }

    // 确保数据集包含指定字段，不存在字段统一补空值。
    public static Dataset<Row> ensureColumns(Dataset<Row> data, String... names) {
        Dataset<Row> result = data;
        for (String name : names) {
            // 已存在的列保持原值及类型，仅为缺列追加空值列。
            if (!hasColumn(result, name)) result = result.withColumn(name, lit(null));
        }
        return result;
    }

    // 安全读取字段，缺失字段时返回空列。
    public static Column safeCol(Dataset<Row> data, String name) {
        // 返回的是列表达式；lit(null) 可继续参与 cast、coalesce 等转换。
        return hasColumn(data, name) ? col(name) : lit(null);
    }

    // 判断数据集是否包含指定名称的字段，并忽略大小写差异。
    public static boolean hasColumn(Dataset<Row> data, String name) {
        for (String column : data.columns()) {
            // 仅比较列名，不读取数据行；找到匹配列后立即结束扫描。
            if (column.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // 将空白文本转换为空值并保留非空文本的去空格结果。
    public static Column nullIfBlank(Column column) {
        // 空串或纯空格转为 null；原值为 null 时 trim 结果仍为 null，不会转成字符串占位词。
        return when(trim(column).equalTo(""), null).otherwise(trim(column));
    }

    // 将互动数字字段中的逗号、空值和小数统一为长整数。
    private static Column numberCol(Dataset<Row> data, String name) {
        // 缺列或空值先补空串，去首尾空格后移除千分位逗号。
        Column normalized = regexp_replace(trim(coalesce(safeCol(data, name), lit(""))), ",", "");
        // 先转 double 并四舍五入，再转 long；转换结果为空时补 0，而不是直接截掉小数。
        // 非法数值转空值依赖 Spark 非 ANSI 模式；开启 ANSI 模式时可能抛出转换异常。
        return coalesce(round(normalized.cast("double"), 0).cast("long"), lit(0L));
    }

    // 将常见日期时间文本转换为 Spark 时间戳。
    private static Column parseTimestamp(Column value) {
        // 缺失值补空串，连续空白压成一个空格，便于统一匹配日期与时间之间的分隔符。
        Column normalized = trim(regexp_replace(coalesce(value, lit("")), "\\s+", " "));
        // 捕获组依次为年、月、日、时、分、秒；日期支持横线或斜线，秒可省略。
        String pattern = "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?$";
        // 按捕获组提取各时间分量；未匹配时 regexp_extract 返回空串。
        Column year = regexp_extract(normalized, pattern, 1);
        Column month = regexp_extract(normalized, pattern, 2);
        Column day = regexp_extract(normalized, pattern, 3);
        Column hour = regexp_extract(normalized, pattern, 4);
        Column minute = regexp_extract(normalized, pattern, 5);
        Column second = regexp_extract(normalized, pattern, 6);
        // 月、日、小时左侧补零，缺省秒补 00，拼成统一的 yyyy-MM-dd HH:mm:ss 文本。
        Column canonical = concat(year, lit("-"), lpad(month, 2, "0"), lit("-"), lpad(day, 2, "0"),
                lit(" "), lpad(hour, 2, "0"), lit(":"), minute, lit(":"),
                when(length(second).equalTo(0), lit("00")).otherwise(second));
        // 按标准格式解析，并由 Spark 会话时区解释时间；非 ANSI 模式下解析失败返回空值。
        return to_timestamp(canonical, "yyyy-MM-dd HH:mm:ss");
    }
}
