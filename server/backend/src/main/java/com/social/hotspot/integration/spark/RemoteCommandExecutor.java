package com.social.hotspot.integration.spark;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class RemoteCommandExecutor {
    private RemoteCommandExecutor() {}
    private static final Logger LOG = LoggerFactory.getLogger(RemoteCommandExecutor.class);
    public static RemoteCommandResult exec(Session session, String command, int timeoutSeconds) throws Exception {
        // 循环读取远程命令的标准输出。
        // 在 SSH 会话中创建远程命令执行通道；创建错误输出缓冲；收集远程错误输出，失败时用于诊断。
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        channel.setErrStream(stderr);
        // 获取远程命令的标准输出流；用 bash 执行已转义命令；建立 SSH 连接或通道，最多等待 30 秒。
        InputStream stdout = channel.getInputStream();
        channel.setCommand("bash -c " + sh(command));
        channel.connect(Math.min(timeoutSeconds * 1000, 30000));
        // 创建输出缓冲或输出流；将秒转换为毫秒并加到当前时间，得到命令最晚完成时间。
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        try {
            // 创建字节缓冲。
            byte[] buffer = new byte[8192];
            // 在截止时间内读取输出，直到通道关闭且无待读字节。
            while (System.currentTimeMillis() < deadline && (!channel.isClosed() || stdout.available() > 0)) {
                // 输入流有可读字节就继续读取。
                while (stdout.available() > 0) {
                    // 读取字节到缓冲区，并保存本次读取的字节数。
                    int read = stdout.read(buffer, 0, Math.min(buffer.length, stdout.available()));
                    // 仅处理实际读到的字节。
                    if (read > 0) output.write(buffer, 0, read);
                }
                // 暂停 100 毫秒。
                Thread.sleep(100);
            }
            // 截止时间内通道未关闭则报告超时。
            if (!channel.isClosed()) {
                // 不记录带密码的命令，日志只保存超时秒数。
                LOG.error("虚拟机命令执行超时，等待秒数：{}", timeoutSeconds);
                // 抛出远程数据处理超时的状态异常。
                throw new IllegalStateException("数据处理超时，请稍后查看批次状态，或联系管理员检查虚拟机服务。");
            }
            // 输入流有可读字节就继续读取。
            while (stdout.available() > 0) {
                // 写入指定字节或实际读取区间。
                output.write(bufferRead(stdout));
            }
            // 读取远程命令的退出码。
            int exit = channel.getExitStatus();
            RemoteCommandResult result = new RemoteCommandResult(output.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8), exit);
            // 远程退出码非零时附日志报错。
            if (exit != 0) {
                // 输出详情只记录到日志，不返回远程命令与英文堆栈。
                LOG.error("虚拟机命令执行失败，退出码={}，错误输出={}，标准输出={}", exit, tail(result.stderr(), 3000), tail(result.stdout(), 3000));
                // 抛出远程数据处理执行失败的状态异常。
                throw new IllegalStateException("远程数据处理执行失败，请联系管理员检查任务日志。");
            }
            return result;
        // 无论成功还是异常，都执行以下收尾步骤。
        } finally {
            // 关闭远程会话或通道，释放连接资源。
            channel.disconnect();
        }
    }
    private static byte[] bufferRead(InputStream input) throws Exception {
        // 创建字节缓冲；读取字节到缓冲区，并保存本次读取的字节数。
        byte[] buffer = new byte[Math.max(1, input.available())];
        int read = input.read(buffer);
        // 缓冲读满时直接返回。
        if (read == buffer.length) return buffer;
        byte[] result = new byte[Math.max(0, read)];
        // 仅处理实际读到的字节。
        if (read > 0) System.arraycopy(buffer, 0, result, 0, read);
        return result;
    }
    public static String sh(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    public static String tail(String value, int max) {
        // 空值走默认分支。
        if (value == null) return "";
        // 按问号前的条件，选择问号后或冒号后的返回值。
        return value.length() <= max ? value : value.substring(value.length() - max);
    }
}
