package com.social.hotspot.common;

import java.util.regex.Pattern;

// 统一过滤用户提示。
public final class UserMessages {
    // 中文提示必须包含中文，而不是只包含英文错误或状态代码。
    private static final Pattern CHINESE = Pattern.compile("[\\p{IsHan}]");
    // 英文句子、异常类名、多行日志和数据库连接信息不向用户展示。
    private static final Pattern TECHNICAL = Pattern.compile("[\\r\\n\\uFFFD]|[A-Za-z]{2,}\\s+[A-Za-z]{2,}|Exception|Traceback|Error:|SQLSTATE|jdbc:|java\\.|org\\.|com\\.");

    // 工具类不需要实例化。
    private UserMessages() {
        // 构造器保持私有。
    }

    // 返回可读中文；无法安全展示时采用调用方指定的中文提示。
    public static String chinese(String message, String fallback) {
        // 空提示统一采用备用说明。
        if (message == null || message.isBlank()) {
            // 返回备用中文提示。
            return fallback;
        }
        // 清理首尾空白，但不尝试翻译不可控的第三方异常。
        String text = message.trim();
        // 保留中文业务说明，技术详情留在日志中。
        return CHINESE.matcher(text).find() && !TECHNICAL.matcher(text).find() ? text : fallback;
    }
}
