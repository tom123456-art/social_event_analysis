// 声明包名 com.social.hotspot.config。
package com.social.hotspot.config;

// 引入文件路径类型。
import java.nio.file.Path;

// 定义项目目录定位回归测试类。
public class ProjectPathsRegression {
    // 程序入口，启动应用或运行本类回归检查。
    public static void main(String[] args) {
        // 定位项目根目录。
        Path root = ProjectPaths.root();
        // 依次测试项目根目录、server、backend 和 etl 起始目录。
        for (String location : new String[]{"", "server", "server/backend", "server/etl"}) {
            // 检查不同起始目录定位出的项目根目录是否一致。
            if (!root.equals(ProjectPaths.rootFrom(root.resolve(location)))) {
                // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
                throw new AssertionError("Unexpected project root from " + location);
            // 结束当前条件分支。
            }
        // 结束当前遍历循环。
        }
        // 保存测试前的项目目录系统属性值。
        String previous = System.getProperty("social.hotspot.home");
        // 进入异常捕获或资源清理代码块。
        try {
            // 向System写入social.hotspot.home。
            System.setProperty("social.hotspot.home", root.toString());
            // 配置的项目目录未生效时抛出断言错误。
            if (!root.equals(ProjectPaths.root())) throw new AssertionError("Explicit home override failed");
        // 无论成功还是异常，都执行以下收尾步骤。
        } finally {
            // 原先未设置项目目录属性时，清除测试写入的属性。
            if (previous == null) System.clearProperty("social.hotspot.home");
            // 恢复测试前已有的属性。
            else System.setProperty("social.hotspot.home", previous);
        // 结束资源清理步骤。
        }
        // 前置断言通过后输出测试结果。
        System.out.println("PASS: root, server, backend and ETL working directories; explicit home override.");
    // 结束当前方法。
    }
// 结束类定义。
}
