package com.social.hotspot.integration.hive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 以可重复执行的增量方式升级数仓表元数据，不改写已有分区的数据结构。 */
public final class HiveWarehouseSchema {
    /** 禁止实例化纯静态的数仓结构工具类。 */
    private HiveWarehouseSchema() {}

    /** 根据表名和已有字段生成缺失字段的 ALTER TABLE 语句。 */
    public static String missingColumns(String table, Set<String> existing) {
        // 按字段名到 Hive 类型建立待补充字段的有序映射，保证生成 SQL 稳定可审计。
        Map<String, String> additions = new LinkedHashMap<>();
        if (table.equals("social_ods.ods_social_content_raw")) {
            additions.put("source_url", "STRING");
            additions.put("image_url", "STRING");
            additions.put("category", "STRING");
        } else if (table.equals("social_dwd.dwd_social_content_detail")) {
            additions.put("share_count", "BIGINT");
            additions.put("forward_count", "BIGINT");
            for (String field : new String[]{"location", "user_age_group", "user_gender", "source_url", "image_url", "category", "sentiment_source", "content_text", "signal_mode"}) {
                additions.put(field, "STRING");
            }
            for (String field : new String[]{"sentiment_positive_score", "sentiment_neutral_score", "sentiment_negative_score", "platform_heat_index"}) {
                additions.put(field, "DOUBLE");
            }
            additions.put("has_heat_signal", "BOOLEAN");
            additions.put("interaction_signal_count", "INT");
        } else throw new IllegalArgumentException("Unsupported warehouse table");
        // 只保留当前表尚未存在的字段，避免重复执行时重复添加列。
        String columns = additions.entrySet().stream().filter(e -> !existing.contains(e.getKey()))
                .map(e -> "`" + e.getKey() + "` " + e.getValue()).collect(Collectors.joining(", "));
        // 没有缺失字段时返回空串，有缺失字段时返回受限的增量变更语句。
        return columns.isEmpty() ? "" : "ALTER TABLE " + table + " ADD COLUMNS (" + columns + ") RESTRICT;";
    }
}
