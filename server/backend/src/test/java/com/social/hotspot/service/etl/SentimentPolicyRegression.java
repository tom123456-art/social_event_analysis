// 声明包名 com.social.hotspot.service.etl。
package com.social.hotspot.service.etl;
import com.social.hotspot.service.etl.EtlWorkflowService;

// 引入文件操作工具。
import java.nio.file.Files;
// 引入文件路径类型。
import java.nio.file.Path;
// 引入属性文件读写工具。
import java.util.Properties;

/** 验证规则或阈值变化后旧增量检查点失效。 */
// 定义增量情感策略回归测试类。
public class SentimentPolicyRegression {
    // 程序入口，启动应用或运行本类回归检查。
    public static void main(String[] args) throws Exception {
        // 创建业务服务。
        EtlWorkflowService service = new EtlWorkflowService(null);
        // 通过反射获取最低情感置信度字段。
        var threshold = EtlWorkflowService.class.getDeclaredField("sentimentMinConfidence");
        // 测试中允许反射访问私有成员以验证内部规则。
        threshold.setAccessible(true);
        // 通过反射设置阈值，测试配置变化。
        threshold.setDouble(service, 0.90);
        // 通过反射获取检查点读取方法。
        var read = EtlWorkflowService.class.getDeclaredMethod("readCheckpoint", Path.class);
        // 测试中允许反射访问私有成员以验证内部规则。
        read.setAccessible(true);
        // 创建起始或测试目录。
        Path directory = Files.createTempDirectory("sentiment-policy-test-");
        // 根据父目录拼接csv路径。
        Path csv = directory.resolve("raw.csv");
        // 根据父目录拼接增量检查点路径。
        Path checkpoint = directory.resolve(".raw.csv.etl-checkpoint.properties");
        // 进入异常捕获或资源清理代码块。
        try {
            // 写入文本，显式字符集决定编码。
            Files.writeString(csv, "platform,content_id\nWEIBO,1\n");
            // 检查点不存在时，验证读取结果为空。
            check(read.invoke(service, checkpoint) == null, "missing checkpoint requests full rebuild");
            // 为测试 CSV 登记固定批次基线，验证规则变更前后的复用行为。
            service.markFullBaseline(csv, "20261010000000000");
            // 策略版本一致时，验证检查点读取成功。
            check(read.invoke(service, checkpoint) != null, "matching binary policy accepts baseline");
            Properties eligibilityProperties = new Properties();
            try (var input = Files.newInputStream(checkpoint)) { eligibilityProperties.load(input); }
            eligibilityProperties.remove("sentimentEligibilityPolicy");
            try (var output = Files.newOutputStream(checkpoint)) { eligibilityProperties.store(output, null); }
            check(read.invoke(service, checkpoint) == null, "old content-dropping policy requests full rebuild");
            service.markFullBaseline(csv, "20261010000000000");
            // 创建文本规则测试属性。
            Properties qualityProperties = new Properties();
            // 打开自动关闭资源，离开 try 时关闭文件或流。
            try (var input = Files.newInputStream(checkpoint)) { qualityProperties.load(input); }
            // 从文本规则测试属性移除文本质量规则版本，不再保留此字段。
            qualityProperties.remove("textQualityPolicy");
            // 打开自动关闭资源，离开 try 时关闭文件或流。
            try (var output = Files.newOutputStream(checkpoint)) { qualityProperties.store(output, null); }
            // 文本质量规则变更时，验证旧检查点被拒绝。
            check(read.invoke(service, checkpoint) == null, "changed text quality rules request global rebuild");
            // 为测试 CSV 登记固定批次基线，验证规则变更前后的复用行为。
            service.markFullBaseline(csv, "20261010000000000");
            // 通过反射设置阈值，测试配置变化。
            threshold.setDouble(service, 0.95);
            // 置信度阈值变更时，验证旧检查点被拒绝。
            check(read.invoke(service, checkpoint) == null, "changed threshold requests full rebuild");
            // 通过反射设置阈值，测试配置变化。
            threshold.setDouble(service, 0.90);
            // 创建检查点属性。
            Properties properties = new Properties();
            // 打开自动关闭资源，离开 try 时关闭文件或流。
            try (var input = Files.newInputStream(checkpoint)) { properties.load(input); }
            // 从检查点属性移除情感策略版本，不再保留此字段。
            properties.remove("sentimentPolicy");
            // 打开自动关闭资源，离开 try 时关闭文件或流。
            try (var output = Files.newOutputStream(checkpoint)) { properties.store(output, null); }
            // 缺少当前策略字段时，验证旧检查点被拒绝。
            check(read.invoke(service, checkpoint) == null, "legacy checkpoint requests full rebuild");
            // 向检查点属性写入情感策略版本。
            properties.setProperty("sentimentPolicy", "MODEL_BINARY_V1");
            // 打开自动关闭资源，离开 try 时关闭文件或流。
            try (var output = Files.newOutputStream(checkpoint)) { properties.store(output, null); }
            // 词表策略版本不一致时，验证旧检查点被拒绝。
            check(read.invoke(service, checkpoint) == null, "unvalidated vocabulary policy requests full rebuild");
            // 前置断言通过后输出测试结果。
            System.out.println("PASS: matching baseline, threshold changes, missing/legacy/unvalidated policies.");
        // 无论成功还是异常，都执行以下收尾步骤。
        } finally {
            // 临时文件存在时删除，不存在则继续。
            Files.deleteIfExists(checkpoint);
            // 临时文件存在时删除，不存在则继续。
            Files.deleteIfExists(csv);
            // 临时文件存在时删除，不存在则继续。
            Files.deleteIfExists(directory);
        // 结束资源清理步骤。
        }
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
