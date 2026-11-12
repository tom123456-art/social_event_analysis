package com.social.hotspot.etl.cleaning;

import org.jsoup.Jsoup;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.types.DataTypes;
import java.util.regex.Pattern;

import static com.social.hotspot.etl.cleaning.FieldNormalizer.nullIfBlank;
import static com.social.hotspot.etl.cleaning.FieldNormalizer.safeCol;
import static org.apache.spark.sql.functions.*;

/** 文本字段共用的清洗规则；保留原字段供内容键生成和损坏证据核验使用。 */
public final class TextNormalizer {
    // 标题、正文、作者、关键词和类别使用同一套文本清洗规则。
    private static final String[] FIELDS = {"title", "content_text", "author_name", "keywords", "category"};
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern LONG_LETTERS = Pattern.compile("[A-Za-z]{24,}");
    private static final Pattern REPEATED_PUNCTUATION = Pattern.compile(
            "([!\\uFF01?\\uFF1F.\\u3002,\\uFF0C\\u3001;\\uFF1B:\\uFF1A~\\uFF5E_\\-])\\1{2,}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // 禁止实例化仅提供静态方法的文本规范化工具类。
    private TextNormalizer() {}

    // 保留原始字段，生成清洗后的临时列、格式修复标记及供分析使用的正文。
    public static Dataset<Row> normalize(Dataset<Row> raw) {
        Dataset<Row> result = raw;
        Column changed = lit(false);
        // 缺失字段按空串参与处理，修复标记累计记录任一字段是否发生文本变化。
        for (String field : FIELDS) {
            Column original = coalesce(safeCol(raw, field).cast("string"), lit(""));
            Column cleaned = normalizedText(original);
            // 长度截断不计入格式修复，避免再维护一套修复检测正则。
            changed = changed.or(not(original.eqNullSafe(cleaned)));
            result = result.withColumn("_normalized_" + field, cleaned.substr(1, 1000));
        }
        return result.withColumn("text_normalized", changed)
                .withColumn("clean_text", when(nullIfBlank(safeCol(raw, "content_text")).isNotNull(),
                        col("_normalized_content_text")).otherwise(col("_normalized_title")));
    }

    // 为单个 Spark 文本列表达式应用共享清洗规则，并将结果限制为一千字符。
    public static Column cleanText(Column source) {
        return normalizedText(source).substr(1, 1000);
    }

    // 将 Java 字符串清洗方法包装为 Spark 用户自定义函数，供数据集逐行执行。
    private static Column normalizedText(Column source) {
        return udf((UDF1<String, String>) TextNormalizer::normalizeText, DataTypes.StringType).apply(source);
    }

    // 依次提取可见文本、移除控制字符与损坏标记、清除链接和长字母串，再合并重复标点及空白。
    public static String normalizeText(String source) {
        String text = extractVisibleText(source);
        if (text == null || text.isEmpty()) return text;
        text = CONTROLS.matcher(text).replaceAll("");
        text = RecordFilter.explicitDamagePattern().matcher(text).replaceAll("");
        text = URL.matcher(text).replaceAll(" ");
        text = LONG_LETTERS.matcher(text).replaceAll(" ");
        text = REPEATED_PUNCTUATION.matcher(text).replaceAll("$1");
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    // 提取来源片段的可见文本，并保留空值语义。
    private static String extractVisibleText(String source) {
        // 缺失字段与空串直接返回，不进入解析器。
        if (source == null || source.isEmpty()) return source;
        // 以正文片段解析，兼容没有 html/body 外层标签的来源内容；连同子节点删除脚本、样式及模板，防止隐藏代码混入正文。
        var document = Jsoup.parseBodyFragment(source);
        document.select("script, style, template").remove();
        // 提取可见文字，将不换行空格转换为普通空格并去除首尾空白。
        return document.body().text().replace('\u00a0', ' ').strip();
    }
}
