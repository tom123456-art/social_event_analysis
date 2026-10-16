package com.social.hotspot.etl.metric;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import static org.apache.spark.sql.functions.*;

public final class InteractionHeatCalculator {
    // 禁止实例化纯静态计算工具类。
    private InteractionHeatCalculator() {}
    // 定义参与平台热度计算的互动字段。
    private static final String[] INTERACTION_FIELDS = {"like_count", "comment_count", "repost_count", "favorite_count"};
    // 至少百分之一记录有正值时，互动字段才被视为有效信号。
    private static final double SIGNAL_COVERAGE_THRESHOLD = 0.01D;
    // 按平台能力保留互动字段，并生成统一互动总量。
    public static Dataset<Row> applyInteractionPolicy(Dataset<Row> data) {
        // 从原始数据开始逐字段应用平台互动能力策略。
        Dataset<Row> result = data;
        // 处理每个候选互动字段。
        for (String field : INTERACTION_FIELDS) {
            // 支持的字段缺失值按零处理，不支持的字段保持空值。
            result = result.withColumn(field, when(supportsInteractionField(field), coalesce(col(field), lit(0L)))
                    .otherwise(lit(null).cast("long")));
        }
        // 存储中不支持的字段保持空值，汇总时按零处理以免整项变空。
        // 初始化互动总量表达式。
        Column total = lit(0L);
        // 累加已标准化的四类互动字段。
        for (String field : INTERACTION_FIELDS) {
            total = total.plus(coalesce(col(field), lit(0L)));
        }
        // 将分享和浏览量按统一口径补入互动总量。
        total = total.plus(coalesce(col("share_count"), lit(0L)))
                .plus(coalesce(col("view_count"), lit(0L)).divide(100));
        // 写入互动总量和转发字段别名。
        return result.withColumn("interaction_count", total)
                .withColumn("forward_count", col("repost_count"));
    }

    // 计算同一事件、同一平台内部的相对热度指数。
    public static Dataset<Row> buildPlatformRelativeHeat(Dataset<Row> valid) {
        // 以事件和平台作为百分位排名窗口。
        WindowSpec platformWindow = Window.partitionBy("event_id", "platform");
        // 删除旧热度排名，避免重复计算产生歧义。
        Dataset<Row> result = valid.drop("hot_rank");
        // 为每个互动字段计算信号覆盖率和百分位。
        for (String field : INTERACTION_FIELDS) {
            // 生成覆盖率字段名。
            String coverageColumn = "_" + field + "_coverage";
            // 生成百分位字段名。
            String percentileColumn = "_" + field + "_percentile";
            // 对互动量取对数后排名，降低极端值影响。
            result = result
                    .withColumn(coverageColumn, avg(when(col(field).gt(0), lit(1D)).otherwise(lit(0D))).over(platformWindow))
                    .withColumn(percentileColumn, round(percent_rank().over(platformWindow.orderBy(log1p(col(field)).asc())).multiply(100), 2));
        }

        // 初始化有效互动信号数量和百分位合计表达式。
        Column interactionSignalCount = lit(0);
        Column interactionPercentileSum = lit(0D);
        // 只聚合平台实际支持且覆盖率达标的互动字段。
        for (String field : INTERACTION_FIELDS) {
            // 判断当前字段是否可作为热度信号。
            Column available = supportsInteractionField(field)
                    .and(col("_" + field + "_coverage").geq(SIGNAL_COVERAGE_THRESHOLD));
            interactionSignalCount = interactionSignalCount.plus(when(available, lit(1)).otherwise(lit(0)));
            interactionPercentileSum = interactionPercentileSum.plus(when(available, col("_" + field + "_percentile")).otherwise(lit(0D)));
        }
        // 至少存在一个有效字段时才认为内容具备热度信号。
        Column interactionAvailable = interactionSignalCount.gt(0);
        // 对可用字段的百分位做等权平均。
        Column relativeIndex = when(interactionAvailable, interactionPercentileSum.divide(interactionSignalCount))
                .otherwise(lit(null).cast("double"));

        // 保存热度计算中间量和最终展示指标。
        return result
                .withColumn("interaction_signal_count", interactionSignalCount)
                .withColumn("has_heat_signal", interactionAvailable)
                .withColumn("signal_mode", heatAlgorithmExpr())
                .withColumn("platform_heat_index", round(relativeIndex, 2))
                .withColumn("hot_score", coalesce(round(relativeIndex, 2), lit(0D)));
    }

    // 返回指定互动字段在当前平台是否有业务意义。
    public static Column supportsInteractionField(String field) {
        // 读取当前记录的平台代码。
        Column platform = col("platform");
        // 根据平台能力映射字段支持关系。
        return switch (field) {
            case "like_count" -> platform.isin("TENCENT_NEWS", "SOHU_NEWS", "THE_PAPER");
            case "comment_count" -> platform.isin("TENCENT_NEWS", "SOHU_NEWS", "THE_PAPER", "SINA_NEWS", "NETEASE_NEWS");
            case "repost_count" -> platform.equalTo("TENCENT_NEWS");
            case "favorite_count" -> platform.isin("TENCENT_NEWS", "SOHU_NEWS");
            default -> lit(false);
        };
    }

    // 返回平台对应的热度算法标识，供审计和展示使用。
    public static Column heatAlgorithmExpr() {
        return when(col("platform").equalTo("TENCENT_NEWS"), "TENCENT_4_FIELDS")
                .when(col("platform").equalTo("SOHU_NEWS"), "LIKE_COMMENT_FAVORITE_3_FIELDS")
                .when(col("platform").equalTo("THE_PAPER"), "LIKE_COMMENT_2_FIELDS")
                .when(col("platform").isin("SINA_NEWS", "NETEASE_NEWS"), "COMMENT_1_FIELD")
                .otherwise("UNSUPPORTED");
    }
}
