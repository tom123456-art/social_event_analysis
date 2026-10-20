package com.social.hotspot.service.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AnalysisValues {
    private AnalysisValues() {}
    public static final Set<String> GENERIC_SOURCE_CATEGORIES = Set.of("未分类", "新闻", "微博", "微博文章");
    private static final Set<String> CATEGORY_LABELS = Set.of("财经", "政治", "科技", "体育", "文娱", "社会", "综合");
    public static String normalizeFilter(String value) {
        // 空值走默认分支。
        if (value == null || value.isBlank() || "全部".equals(value) || "all".equalsIgnoreCase(value)) {
            // 返回空值。
            return null;
        }
        return value.trim();
    }

    public static String normalizePlatform(Object value) {
        // 将平台值转换为去除首尾空白的文本，空值转为空字符串；将平台代码转换为大写。
        String platform = value == null ? "" : String.valueOf(value).trim();
        String upper = platform.toUpperCase(Locale.ROOT);
        if ("SINA_WEIBO".equals(upper)) return "WEIBO";
        // 平台代码为空时进入默认值分支。
        if (upper.isBlank()) {
            return "UNKNOWN";
        }
        // 检查平台代码是否属于允许的平台集合。
        if (Set.of("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "WEIBO", "UNKNOWN").contains(upper)) {
            // 返回大写平台代码。
            return upper;
        }
        // 检查平台文本是否包含 RSS 或新闻平台名称。
        // 返回upper.contains("RSS") || platform.contains("新闻") || platform.contains("中新网") || platform.contains("百度")。
        return upper.contains("RSS") || platform.contains("新闻") || platform.contains("中新网") || platform.contains("百度")
                // 匹配新闻平台特征时返回 NEWS，否则返回 UNKNOWN。
                ? "NEWS" : "UNKNOWN";
    }

    public static String normalizeCategoryLabel(Object value) {
        // 将值转换为去除首尾空白的文本，空值转为空字符串。
        String text = value == null ? "" : String.valueOf(value).trim();
        // 排除泛化类别和无效值。
        if (text.isBlank() || GENERIC_SOURCE_CATEGORIES.contains(text) || text.matches("\\d+")) {
            // 返回空值。
            return null;
        }
        // 返回switch (text.toLowerCase(Locale.ROOT)) {。
        return switch (text.toLowerCase(Locale.ROOT)) {
            // 将标签转为标准中文类别；未知标签只接受预设合法类别；将标签转为标准中文类别；未知标签只接受预设合法类别；将标签转为标准中文类别；未知标签只接受预设合法类别。
            case "finance" -> "财经";
            case "politics" -> "政治";
            case "technology" -> "科技";
            // 将标签转为标准中文类别；未知标签只接受预设合法类别；将标签转为标准中文类别；未知标签只接受预设合法类别；将标签转为标准中文类别；未知标签只接受预设合法类别。
            case "sports" -> "体育";
            case "culture", "娱乐", "影视", "音乐" -> "文娱";
            case "society", "社会新闻", "民生" -> "社会";
            // 将标签转为标准中文类别；未知标签只接受预设合法类别；将标签转为标准中文类别；未知标签只接受预设合法类别。
            case "general", "综合类", "新闻" -> "综合";
            default -> CATEGORY_LABELS.contains(text) ? text : null;
        };
    }

    public static String categoryLabel(Map<String, Object> row) {
        // 读取文本，缺失时使用默认值。
        String text = String.join(" ", String.valueOf(row.getOrDefault("title", "")),
                // 把清洗后的正文加入分类文本，字段缺少时使用空字符串；将作者名拼接到分类文本中。
                String.valueOf(row.getOrDefault("clean_text", "")),
                String.valueOf(row.getOrDefault("author_name", "")));
        // 判断是否命中类别关键词，命中后返回粗分类类别。
        if (containsAny(text, "股票", "基金", "证券", "银行", "保险", "融资", "资本", "央行", "经济", "金融", "财经", "股市", "投资", "贸易", "营收", "利润")) {
            return "财经";
        }
        // 判断是否命中类别关键词，命中后返回粗分类类别。
        if (containsAny(text, "人工智能", "AI", "芯片", "科技", "技术", "机器人", "算法", "数字化", "互联网", "量子", "航天", "新能源")) {
            return "科技";
        }
        // 判断是否命中类别关键词，命中后返回粗分类类别。
        if (containsAny(text, "政府", "国务院", "政策", "政治", "外交", "总统", "议会", "选举", "法治", "依法", "国际")) {
            return "政治";
        }
        // 判断是否命中类别关键词，命中后返回粗分类类别。
        if (containsAny(text, "足球", "篮球", "体育", "赛事", "比赛", "夺冠", "奥运", "球员", "联赛")) {
            return "体育";
        }
        // 判断是否命中类别关键词，命中后返回粗分类类别。
        if (containsAny(text, "电影", "电视剧", "演唱会", "明星", "文娱", "文化", "音乐", "展览", "非遗")) {
            return "文娱";
        }
        // 判断是否命中类别关键词，命中后返回粗分类类别。
        if (containsAny(text, "教育", "医疗", "交通", "民生", "旅游", "消费", "就业", "暴雨", "安全", "社区", "养老")) {
            return "社会";
        }
        return "综合";
    }

    public static String categoryCode(String label) {
        // 返回Map.of("财经", "finance", "政治", "politics", "科技", "technology", "体育", "sports",。
        return Map.of("财经", "finance", "政治", "politics", "科技", "technology", "体育", "sports",
                // 补充文娱、社会和综合类别代码映射，未匹配时使用综合代码。
                "文娱", "culture", "社会", "society", "综合", "general").getOrDefault(label, "general");
    }

    public static boolean containsAny(String text, String... values) {
        // 遍历待匹配的关键词。
        for (String value : values) {
            // 检查文本是否包含当前关键词。
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    public static void addLong(Map<String, Object> target, String key, Object value) {
        // 将旧字段值和新增值都转为长整数后相加，写回汇总字段。
        target.put(key, longValue(target.get(key)) + longValue(value));
    }

    public static void addDouble(Map<String, Object> target, String key, Object value) {
        // 将旧字段值和新增值都转为小数后相加，写回汇总字段。
        target.put(key, doubleValue(target.get(key)) + doubleValue(value));
    }

    public static long longValue(Object value) {
        // 空值走默认分支。
        if (value == null) {
            // 返回0L。
            return 0L;
        }
        try {
            // 创建BigDecimal对象并返回。
            return new BigDecimal(String.valueOf(value)).longValue();
        // 捕获 NumberFormatException，通过 ex 读取失败原因。
        } catch (NumberFormatException ex) {
            // 返回0L。
            return 0L;
        }
    }

    public static double doubleValue(Object value) {
        // 空值走默认分支。
        if (value == null) {
            // 返回0D。
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        // 捕获 NumberFormatException，通过 ex 读取失败原因。
        } catch (NumberFormatException ex) {
            // 返回0D。
            return 0D;
        }
    }

    public static LocalDateTime timestampValue(Object value) {
        // 空值走默认分支。
        if (value == null) {
            // 返回最大时间标记无效日期，调用方需排除，不作为真实发布时间。
            return LocalDateTime.MAX;
        }
        // 将时间值转换为文本，并把日期时间分隔符替换为空格。
        String text = String.valueOf(value).replace('T', ' ');
        // 时间文本超过十九个字符时进入截取分支。
        if (text.length() > 19) {
            // 更新文本，新值为text.substring(0, 19)。
            text = text.substring(0, 19);
        }
        try {
            // 返回LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()。
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        // 捕获 日期解析异常，采用兜底或继续尝试。
        } catch (DateTimeParseException ignored) {
        }
        // 依次尝试四种日期时间格式。
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/M/d H:mm:ss", "yyyy/M/d H:mm")) {
            try {
                // 返回LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))。
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern));
            // 捕获 日期解析异常，采用兜底或继续尝试。
            } catch (DateTimeParseException ignored) {
            }
        }
        // 返回最大时间标记无效日期，调用方需排除，不作为真实发布时间。
        return LocalDateTime.MAX;
    }
}
