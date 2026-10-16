// 声明包名 com.social.hotspot.service.analysis。
package com.social.hotspot.service.analysis;
import com.social.hotspot.service.analysis.impl.AnalyticsServiceImpl;

// 引入反射方法类型。
import java.lang.reflect.Method;
// 引入不含时区的日期时间类型。
import java.time.LocalDateTime;
// 引入列表，保存多条数据。
import java.util.List;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

/** 独立回归检查：通过 main 验证类别首达统计边界。 */
// 定义类别热度先后关系回归测试类。
public class CategoryPropagationRegression {
    // 定义并初始化测试基准时间。
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 24, 0, 0);

    // 构造指定平台、小时和关注指数的测试数据。
    private static Map<String, Object> point(String platform, int hour, double attention) {
        // 返回Map.of("platform", platform, "category", "technology", "time_bucket", BASE.plusHours(hour),。
        return Map.of("platform", platform, "category", "technology", "time_bucket", BASE.plusHours(hour),
                // 将类别内容量和平台内容量设为一。
                "content_count", 1, "platform_content_count", 1,
                // 根据目标关注指数反推测试数据的平均相对热度。
                "average_relative_heat_index", attention * attention / 100);
    // 结束当前方法。
    }

    // 关闭未检查类型转换的编译警告。
    @SuppressWarnings("unchecked")
    // 程序入口，启动应用或运行本类回归检查。
    public static void main(String[] args) throws Exception {
        // 创建业务服务。
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(null, null, null);
        // 通过反射获取类别热度先后关系分析方法。
        Method method = com.social.hotspot.service.analysis.CategoryPropagationAnalyzer.class.getDeclaredMethod("analyzeCategoryPropagation", List.class);
        // 测试中允许反射访问私有成员以验证内部规则。
        method.setAccessible(true);
        // 传入空记录列表并获取分析结果。
        Map<String, Object> empty = (Map<String, Object>) method.invoke(service, List.of());
        // 验证空输入生成空时间轴。
        check(((List<?>) empty.get("timeline")).isEmpty(), "empty timeline");
        // 调用分析方法，测试关注指数低于阈值的记录。
        Map<String, Object> below = (Map<String, Object>) method.invoke(service,
                // 继续提供平台、小时和关注指数的测试样本。
                List.of(point("TENCENT_NEWS", 0, 34)));
        // 验证关注指数低于 35 的记录不生成首达记录。
        check(((List<?>) below.get("activations")).isEmpty(), "below 35 excluded");
        // 调用分析方法，测试按峰值比例确定首达阈值。
        Map<String, Object> threshold = (Map<String, Object>) method.invoke(service,
                // 继续提供平台、小时和关注指数的测试样本。
                List.of(point("TENCENT_NEWS", 0, 41), point("TENCENT_NEWS", 1, 43), point("TENCENT_NEWS", 2, 60)));
        // 读取首达记录。
        Map<String, Object> activation = (Map<String, Object>) ((List<?>) threshold.get("activations")).get(0);
        // 验证首达时间取首次达到峰值 70% 的时刻。
        check(BASE.plusHours(1).equals(activation.get("first_hot_time")), "first point over 70% peak");
        // 验证峰值为 60 时首达阈值为 42。
        check(Double.valueOf(42).equals(activation.get("threshold")), "70% peak threshold");
        // 调用分析方法，测试多个平台同时首达的情况。
        Map<String, Object> tied = (Map<String, Object>) method.invoke(service,
                // 继续提供平台、小时和关注指数的测试样本。
                List.of(point("TENCENT_NEWS", 0, 35), point("NETEASE_NEWS", 0, 35)));
        // 读取汇总指标。
        Map<String, Object> summary = (Map<String, Object>) tied.get("summary");
        // 验证同时最早达标的两个平台均被保留。
        check(((List<?>) summary.get("lead_platforms")).size() == 2, "tied leads preserved");
        // 验证同小时首达的平台不生成有向先后关系。
        check(((List<?>) tied.get("relations")).isEmpty(), "same-hour pair has no direction");
        // 分别测试 72 小时和 73 小时的首达时间差。
        for (int hour : new int[]{72, 73}) {
            // 调用分析方法，测试平台首达时间差的边界。
            Map<String, Object> relation = (Map<String, Object>) method.invoke(service,
                    // 继续提供平台、小时和关注指数的测试样本。
                    List.of(point("TENCENT_NEWS", 0, 50), point("NETEASE_NEWS", hour, 50)));
            // 验证时间差不超过 72 小时时才生成先后关系。
            check(((List<?>) relation.get("relations")).size() == (hour == 72 ? 1 : 0), "72-hour relation boundary");
        // 结束当前遍历循环。
        }
        // 前置断言通过后输出测试结果。
        System.out.println("PASS: empty input, minimum threshold, 70% peak, tied leads, same-hour exclusion, 72-hour boundary.");
    // 结束当前方法。
    }

    // 断言不成立时抛出错误。
    private static void check(boolean condition, String description) {
        // 断言失败时抛出错误并附测试说明。
        if (!condition) throw new AssertionError(description);
    // 结束当前方法。
    }
// 结束类定义。
}
