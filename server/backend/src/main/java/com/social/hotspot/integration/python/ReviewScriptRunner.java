package com.social.hotspot.integration.python;
import com.social.hotspot.config.ProjectPaths;
import com.social.hotspot.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ReviewScriptRunner {
    /** 禁止实例化脚本执行工具。 */
    private ReviewScriptRunner() {}
    /** 调用 Python 质检脚本并返回其日志输出。 */
    public static String execute(String python, List<String> arguments, Path directory) throws Exception {
        // 使用参数列表直接启动解释器，不经 Shell 解析用户参数。
        List<String> command = new ArrayList<>(List.of(python,
                ProjectPaths.root().resolve("server/python/src/hotspot_etl/cli/review_records.py").toString()));
        command.addAll(arguments);
        // 将标准输出和标准错误合并写入当前审核任务的日志文件。
        Path log = directory.resolve("prepare.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) throw new BusinessException("审核输入准备超时");
            // 进程结束后读取完整 UTF-8 日志，供结果返回或失败诊断使用。
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                org.slf4j.LoggerFactory.getLogger(ReviewScriptRunner.class).error("审核输入处理失败：{}", output);
                throw new BusinessException("审核输入校验失败，请检查时间、正文、互动数字或原始文件格式");
            }
            return output;
        } finally {
            // 超时或异常退出时强制回收子进程，并等待其退出。
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
