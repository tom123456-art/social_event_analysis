package com.social.hotspot.integration.storage;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SftpFileTransfer {
    private SftpFileTransfer() {}
    public static void upload(Session session, Path localFile, String remotePath, int timeoutSeconds) throws Exception {
        // 在 SSH 会话中创建 SFTP 文件传输通道；建立 SSH 连接或通道，最多等待 30 秒。
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(Math.min(timeoutSeconds * 1000, 30000));
        // 打开自动关闭资源，离开 try 时关闭文件或流。
        try (InputStream input = Files.newInputStream(localFile)) {
            // 上传字节流，OVERWRITE 允许覆盖同名远程文件。
            sftp.put(input, remotePath, ChannelSftp.OVERWRITE);
        // 无论成功还是异常，都执行以下收尾步骤。
        } finally {
            // 关闭远程会话或通道，释放连接资源。
            sftp.disconnect();
        }
    }
}
