// 将本类归入清洗规则包，统一原始字段处理与质量规则的代码位置。
package com.social.hotspot.etl.cleaning;

// 以 JSON 树读取共享文本损坏规则。
import com.fasterxml.jackson.databind.JsonNode;
// 将规则资源解析为 JSON 树。
import com.fasterxml.jackson.databind.ObjectMapper;

// 通过输入流读取打包后的规则资源。
import java.io.InputStream;
// 预编译文本损坏规则的正则表达式。
import java.util.regex.Pattern;

/** 在规范化清洗移除损坏标记之前，检测原始文本的编码损坏。 */
public final class TextDamageDetector {
    // 从类路径加载与 Python 共用的文本质量规则。
    private static final JsonNode RULES = loadRules();
    // 编译明确编码损坏标记的匹配模式。
    private static final Pattern EXPLICIT = Pattern.compile(RULES.get("explicitDamagePattern").asText());
    // 编译混合编码损坏模式。
    private static final Pattern MIXED = Pattern.compile(RULES.get("mixedDamagePattern").asText());
    // 编译疑似字符缺失片段模式。
    private static final Pattern MISSING = Pattern.compile(RULES.get("missingCharactersPattern").asText());
    // 达到此片段数量才判定存在多处缺字。
    private static final int MINIMUM_FRAGMENTS = RULES.get("minimumMissingFragments").asInt();

    // 禁止实例化静态规则检测器。
    private TextDamageDetector() {}

    // 返回规则版本，供推理结果审计。
    public static String version() { return RULES.get("version").asText(); }

    // 按明确损坏、混合损坏、缺字片段的优先级返回拒绝原因。
    public static String rejectionReason(String text) {
        // 空文本没有可判定的编码损坏证据。
        if (text == null || text.isEmpty()) return null;
        // 明确编码损坏标记直接构成拒绝原因。
        if (EXPLICIT.matcher(text).find()) return "ENCODING_DAMAGE_MARKER";
        // 混合编码损坏也直接构成拒绝原因。
        if (MIXED.matcher(text).find()) return "MIXED_ENCODING_DAMAGE";
        // 创建缺字片段匹配器。
        var matcher = MISSING.matcher(text);
        // 统计当前文本中的缺字片段数量。
        int fragments = 0;
        // 逐个检查缺字片段，达到阈值后停止扫描。
        while (matcher.find()) {
            // 每命中一个片段递增计数并检查阈值。
            if (++fragments >= MINIMUM_FRAGMENTS) return "MULTIPLE_MISSING_CHARACTER_FRAGMENTS";
        }
        // 未命中任何拒绝规则时返回空原因。
        return null;
    }

    // 从打包资源读取规则，规则不可用时阻止检测器初始化。
    private static JsonNode loadRules() {
        // 自动关闭类路径资源输入流。
        try (InputStream input = TextDamageDetector.class.getResourceAsStream("/text-quality-rules.json")) {
            // 缺少规则时明确报错，不静默跳过质量检测。
            if (input == null) throw new IllegalStateException("缺少文本质量规则文件，请检查部署资源。");
            // 将 JSON 资源解析成规则树。
            return new ObjectMapper().readTree(input);
        // 捕获资源缺失或 JSON 解析失败，阻止以不完整规则继续清洗。
        } catch (Exception exception) {
            // 将读取失败转换为类初始化错误，保留原始异常。
            throw new ExceptionInInitializerError(exception);
        }
    }
}
