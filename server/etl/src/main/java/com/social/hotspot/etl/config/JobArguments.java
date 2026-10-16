package com.social.hotspot.etl.config;

import java.util.HashMap;
import java.util.Map;
import static org.apache.spark.sql.functions.*;

public final class JobArguments {
    // 禁止实例化命令行参数工具类。
    private JobArguments() {}
    // 将双短横线参数转换为键值映射。
    public static Map<String, String> parseArgs(String[] args) {
        // 保存解析结果，同名参数以后出现的值覆盖前值。
        Map<String, String> params = new HashMap<>();
        // 顺序扫描全部命令行参数。
        for (int i = 0; i < args.length; i++) {
            // 只处理以双短横线开头的选项。
            if (args[i].startsWith("--")) {
                // 去掉选项前缀，得到配置键。
                String key = args[i].substring(2);
                // 下一个元素不是选项时作为值，否则将当前选项当作布尔开关。
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                // 保存当前参数键值。
                params.put(key, value);
            }
        }
        // 返回完整参数映射。
        return params;
    }

    // 读取必填参数，缺失或空白时中止任务启动。
    public static String required(Map<String, String> params, String key) {
        // 按配置键读取原始参数。
        String value = params.get(key);
        // 必填参数不允许缺失或只包含空白。
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("\u7f3a\u5c11\u5fc5\u8981\u53c2\u6570\uff1a--" + key);
        }
        // 返回已通过非空校验的参数值。
        return value;
    }
}
