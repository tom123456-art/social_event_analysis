// 声明包名 com.social.hotspot.config。
package com.social.hotspot.config;

// 引入文件操作工具。
import java.nio.file.Files;
// 引入文件路径类型。
import java.nio.file.Path;

/** 定位项目根目录与共享资源路径。 */
// 定义项目根目录定位工具类。
public final class ProjectPaths {
    // 私有构造器禁止创建工具类实例，调用方直接使用静态方法。
    private ProjectPaths() {
    // 结束当前方法。
    }

    // 优先读取手动配置，否则向上定位项目根。
    public static Path root() {
        // 读取系统属性中的项目目录配置。
        String configured = System.getProperty("social.hotspot.home");
        // 系统属性未指定项目目录时，读取环境变量中的目录。
        if (configured == null || configured.isBlank()) configured = System.getenv("SOCIAL_HOTSPOT_HOME");
        // 存在项目目录配置时，将其转换为规范化绝对路径并返回。
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        // 返回rootFrom(Path.of(""))。
        return rootFrom(Path.of(""));
    // 结束当前方法。
    }

    // 从起始目录向上查找项目标志文件。
    static Path rootFrom(Path directory) {
        // 逐级向父目录查找项目标志文件，找到即返回。
        for (Path cursor = directory.toAbsolutePath().normalize(); cursor != null; cursor = cursor.getParent()) {
            // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
            if (Files.isRegularFile(cursor.resolve("server/pom.xml"))
                    // 同时要求Files.isRegularFile(cursor.resolve("deploy/sql/schema.sql"))) {。
                    && Files.isRegularFile(cursor.resolve("deploy/sql/schema.sql"))) {
                // 返回向上查找的当前目录。
                return cursor;
            // 结束当前方法或多行参数定义。
            }
        // 结束当前遍历循环。
        }
        // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
        throw new IllegalStateException("未找到项目根目录，请配置项目路径（SOCIAL_HOTSPOT_HOME）后重试。");
    // 结束当前方法。
    }
// 结束类定义。
}
