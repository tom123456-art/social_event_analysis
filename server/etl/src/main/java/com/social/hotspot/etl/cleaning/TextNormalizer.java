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

/**
 * 对标题、正文等字段执行统一文本清洗，原始字段保持不变。
 * 清洗结果写入临时列供字段规范化使用，原文仍用于内容编号生成和质量核验。
 */
public final class TextNormalizer {
    // 标题、正文、作者、关键词和类别使用同一套文本清洗规则。
    private static final String[] FIELDS = {"title", "content_text", "author_name", "keywords", "category"};
    // Cc 表示控制字符，Cf 表示格式字符（如零宽字符），两类字符均不保留。
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    // 忽略协议大小写，匹配从 HTTP/HTTPS 协议开始、直到空白前的链接文本。
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    // 连续至少 24 个英文字母按长字母串清除，普通短单词不会命中此规则。
    private static final Pattern LONG_LETTERS = Pattern.compile("[A-Za-z]{24,}");
    // 捕获一个标点，反向引用要求后续至少再重复两次，即相同标点连续出现至少三次。
    private static final Pattern REPEATED_PUNCTUATION = Pattern.compile(
            "([!\\uFF01?\\uFF1F.\\u3002,\\uFF0C\\u3001;\\uFF1B:\\uFF1A~\\uFF5E_\\-])\\1{2,}");
    // 合并正则可识别的连续空白，清除链接后留下的多余间隔也在此处理。
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // 禁止实例化仅提供静态方法的文本规范化工具类。
    private TextNormalizer() {}

    // 保留原始字段，生成清洗后的临时列、格式修复标记及供分析使用的正文。
    public static Dataset<Row> normalize(Dataset<Row> raw) {
        // Dataset 转换返回新数据集，通过 result 逐步追加临时列，不覆盖原始文本列。
        Dataset<Row> result = raw;
        // 初始表达式为假，再对每个字段的变化条件取逻辑或。
        Column changed = lit(false);
        // 缺失字段按空串参与处理，修复标记累计记录任一字段是否发生文本变化。
        for (String field : FIELDS) {
            // 缺列、空值统一转为空串；显式转字符串，保证文本 UDF 的输入类型一致。
            Column original = coalesce(safeCol(raw, field).cast("string"), lit(""));
            // 此处构建 Spark 列表达式，实际清洗在后续执行数据集计算时完成。
            Column cleaned = normalizedText(original);
            // 使用空值安全比较累计变化标记；比较发生在截断前，单纯超长不算格式修复。
            changed = changed.or(not(original.eqNullSafe(cleaned)));
            // Spark 的 substr 起始位置为 1，每个清洗字段最多保留 1000 个字符。
            result = result.withColumn("_normalized_" + field, cleaned.substr(1, 1000));
        }
        // text_normalized 仅表示格式变化，不表示记录是否通过质量检查。
        // 是否回退标题按原始正文是否非空白判断，而不是按清洗后正文是否为空判断。
        // 因此原正文若只包含链接，清洗后为空，也不会在此处改用标题。
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
        // UDF1 声明一个字符串入参，StringType 声明返回列类型，apply 将函数绑定到输入列。
        return udf((UDF1<String, String>) TextNormalizer::normalizeText, DataTypes.StringType).apply(source);
    }

    // 依次提取可见文本、移除控制字符与损坏标记、清除链接和长字母串，再合并重复标点及空白。
    public static String normalizeText(String source) {
        // 先解析 HTML 并解码实体，后续正则只处理提取后的文本。
        String text = extractVisibleText(source);
        // 保留 null 与空串的语义，不再创建正则匹配器。
        if (text == null || text.isEmpty()) return text;
        // 删除控制字符和格式字符，不额外插入空格。
        text = CONTROLS.matcher(text).replaceAll("");
        // 复用质量检查的明确损坏规则；原始损坏证据应已由前置检查保留。
        text = RecordFilter.explicitDamagePattern().matcher(text).replaceAll("");
        // 链接替换成空格，避免链接两侧的词直接粘连。
        text = URL.matcher(text).replaceAll(" ");
        // 长字母串同样替换为空格，保留相邻文本的边界。
        text = LONG_LETTERS.matcher(text).replaceAll(" ");
        // $1 引用捕获到的首个标点，将三个及以上相同标点压缩为一个。
        text = REPEATED_PUNCTUATION.matcher(text).replaceAll("$1");
        // 最后合并空白并去除首尾空格；本方法本身不限制文本长度。
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    // 提取来源片段的可见文本，并保留空值语义。
    private static String extractVisibleText(String source) {
        // 缺失字段与空串直接返回，不进入解析器。
        if (source == null || source.isEmpty()) return source;
        // 以正文片段解析，兼容没有 html/body 外层标签的来源内容；连同子节点删除脚本、样式及模板，防止隐藏代码混入正文。
        var document = Jsoup.parseBodyFragment(source);
        // 删除整个节点及子节点，而不只是去掉标签，避免脚本或样式代码残留。
        document.select("script, style, template").remove();
        // 提取可见文字，将不换行空格转换为普通空格并去除首尾空白。
        return document.body().text().replace('\u00a0', ' ').strip();
    }
}
