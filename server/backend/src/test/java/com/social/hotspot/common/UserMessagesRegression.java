// 声明用户提示回归检查所在的包。
package com.social.hotspot.common;

// 独立验证技术异常不会直接成为用户提示。
public class UserMessagesRegression {
    // 执行中文提示与异常过滤检查。
    public static void main(String[] args) {
        // 遍历英文、混合异常和多行日志样本。
        for (String message : new String[]{"Network Error", "上传失败：IllegalStateException - bad input", "任务失败：中文\njava.lang.Exception: error", "中文提示 Request failed"}) {
            // 非用户可读消息必须使用中文备用提示。
            check("请稍后重试。".equals(UserMessages.chinese(message, "请稍后重试。")));
        // 结束异常样本检查。
        }
        // 正常中文业务说明不应丢失。
        check("原始数据表头已变化，请重新上传。".equals(UserMessages.chinese("原始数据表头已变化，请重新上传。", "失败")));
        // 验证中文提示中保留配置项名称。
        check("请配置目录（VM_ETL_WAREHOUSE_OUTPUT）。".equals(UserMessages.chinese("请配置目录（VM_ETL_WAREHOUSE_OUTPUT）。", "失败")));
        // 统一失败响应也必须过滤第三方英文。
        check("操作失败，请稍后重试。".equals(ApiResponse.fail("Network Error").message()));
        // 所有断言完成后报告结果。
        System.out.println("中文提示与技术异常过滤检查通过。");
    // 结束检查入口。
    }

    // 断言失败时中止回归检查。
    private static void check(boolean condition) {
        // 失败时提供中文说明。
        if (!condition) throw new AssertionError("用户提示中文化检查失败。");
    // 结束断言方法。
    }
// 结束回归检查类。
}
