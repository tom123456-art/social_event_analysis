package com.social.hotspot.integration.python;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static com.social.hotspot.integration.spark.RemoteCommandExecutor.tail;

public final class LocalSentimentRunner {
    private LocalSentimentRunner() {}
    private static final Logger LOG = LoggerFactory.getLogger(LocalSentimentRunner.class);
    public static String run(Path input, Path output, Path model, Path script, String sentimentPython, int sentimentBatchSize, int timeoutSeconds) throws Exception {
        // 创建进程启动器。
        ProcessBuilder builder = new ProcessBuilder(
                // 指定配置的 Python 程序。
                sentimentPython,
                // 指定 Python 推理脚本路径。
                script.toString(),
                // 设置推理脚本的输入文件绝对路径参数。
                "--input", input.toAbsolutePath().toString(),
                // 设置推理脚本的输出文件绝对路径参数。
                "--output", output.toAbsolutePath().toString(),
                // 设置情感模型的绝对路径参数。
                "--model", model.toAbsolutePath().toString(),
                // 设置推理批次大小，最小取一。
                "--batch-size", Integer.toString(Math.max(1, sentimentBatchSize))
        // 结束前面跨行的方法调用或对象构造。
        );
        // 合并进程错误输出和标准输出，统一收集日志。
        builder.redirectErrorStream(true);
        // 明确 Python 输出使用 UTF-8。
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        // 禁止联网下载模型，仅使用本地资源。
        builder.environment().put("HF_HUB_OFFLINE", "1");
        // 让 Transformers 按离线模式加载模型。
        builder.environment().put("TRANSFORMERS_OFFLINE", "1");
        // 启动配置好的情感推理进程。
        Process process = builder.start();
        // 创建推理日志缓冲。
        ByteArrayOutputStream outputLog = new ByteArrayOutputStream();
        // 启动虚拟线程读取推理进程输出。
        Thread reader = Thread.ofVirtual().start(() -> {
            // 打开自动关闭资源，离开 try 时关闭文件或流。
            try (InputStream stream = process.getInputStream()) {
                // 持续读取进程日志。
                stream.transferTo(outputLog);
            // 捕获 Exception，采用兜底或继续尝试。
            } catch (Exception ignored) {
                // 日志读取失败在此忽略，后面根据退出码判断推理结果。
            // 结束异常处理分支。
            }
        // 结束回调处理，回到外层调用流程。
        });
        // 等待进程结束或超时，记录是否正常结束。
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        // 超时后终止推理并报错。
        if (!completed) {
            // 强制终止超时进程。
            process.destroyForcibly();
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("本地情感分析执行超时，请联系管理员检查模型运行环境。");
        // 结束当前条件分支。
        }
        // 等待日志线程完成。
        reader.join();
        // 将推理输出字节按 UTF-8 转换为日志文本。
        String log = outputLog.toString(StandardCharsets.UTF_8);
        // 进程正常退出且生成结果文件才视为成功。
        if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            // 推理日志仅供开发者诊断，不把英文进程输出混入用户提示。
            LOG.error("本地情感模型推理失败：{}", tail(log, 3000));
            // 抛出情感模型推理失败的状态异常。
            throw new IllegalStateException("情感分析执行失败，请联系管理员检查模型和运行环境。");
        // 结束当前条件分支。
        }
        // 返回日志对象。
        return log;
    // 结束当前方法。
    }
}
