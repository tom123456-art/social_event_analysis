package com.social.hotspot.integration.hive;
import com.social.hotspot.integration.hive.HiveWarehouseSchema;

import java.util.Set;

public class HiveWarehouseSchemaRegression {
    public static void main(String[] args) {
        String table = "social_ods.ods_social_content_raw";
        String initial = HiveWarehouseSchema.missingColumns(table, Set.of("event_id"));
        if (!initial.contains("`source_url` STRING") || !initial.endsWith("RESTRICT;")) {
            throw new AssertionError("ODS upgrade must preserve historical partition schemas");
        }
        if (!HiveWarehouseSchema.missingColumns(table, Set.of("source_url", "image_url", "category")).isEmpty()) {
            throw new AssertionError("Metadata upgrade must be repeatable");
        }
        String partial = HiveWarehouseSchema.missingColumns(table, Set.of("source_url"));
        if (partial.contains("`source_url`")) throw new AssertionError("Existing column added twice");
        String dwd = HiveWarehouseSchema.missingColumns("social_dwd.dwd_social_content_detail", Set.of("event_id"));
        if (!dwd.contains("`platform_heat_index` DOUBLE") || !dwd.contains("`category` STRING")) {
            throw new AssertionError("DWD metadata must expose downstream analysis fields");
        }
        try {
            HiveWarehouseSchema.missingColumns("unrelated.table", Set.of());
            throw new AssertionError("Unrelated table accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: additive Hive schema upgrades, repeatability and table restrictions");
    }
}
