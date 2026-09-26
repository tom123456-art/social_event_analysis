package com.social.hotspot.service;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RemoteEtlService {
    private static final String EVENT_ID = "public_rss_latest";
    private static final String EVENT_NAME = "Social Media Hotspot Event Propagation Analysis";
    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Value("${vm.etl.enabled:true}")
    private boolean enabled;
    @Value("${vm.etl.host:192.168.154.121}")
    private String host;
    @Value("${vm.etl.port:22}")
    private int port;
    @Value("${vm.etl.username:root}")
    private String username;
    @Value("${vm.etl.ssh-password:}")
    private String sshPassword;
    @Value("${vm.etl.database-password:}")
    private String databasePassword;
    @Value("${vm.etl.database-host:192.168.154.121}")
    private String databaseHost;
    @Value("${vm.etl.worker-hosts:192.168.154.121,192.168.154.122,192.168.154.123}")
    private String workerHosts;
    @Value("${vm.etl.app-home:/opt/apps/social-hotspot-analytics}")
    private String appHome;
    @Value("${vm.etl.spark-submit:/opt/bigdata/spark/bin/spark-submit}")
    private String sparkSubmit;
    @Value("${vm.etl.spark-master:spark://192.168.154.121:7077}")
    private String sparkMaster;
    @Value("${vm.etl.warehouse-output:}")
    private String warehouseOutput;
    @Value("${vm.etl.sentiment-model:}")
    private String sentimentModel;
    @Value("${vm.etl.timeout-seconds:1800}")
    private int timeoutSeconds;

    public Map<String, Object> run(Path localCsv) throws Exception {
        if (!enabled) {
            throw new IllegalStateException("远程 Spark ETL 未启用，请检查 VM_ETL_ENABLED 配置。");
        }
        if (sshPassword == null || sshPassword.isBlank()) {
            throw new IllegalStateException("未配置虚拟机 SSH 密码，请检查 VM_ETL_SSH_PASSWORD 配置。");
        }
        if (databasePassword == null || databasePassword.isBlank()) {
            throw new IllegalStateException("未配置虚拟机数据库密码，请检查 VM_ETL_DATABASE_PASSWORD 配置。");
        }
        if (sentimentModel == null || sentimentModel.isBlank()) {
            throw new IllegalStateException("未配置 HanLP 情感模型，请检查 VM_ETL_SENTIMENT_MODEL 配置。");
        }
        Path projectRoot = resolveProjectRoot();
        Path configuredModel = Path.of(sentimentModel);
        Path localSentimentModel = (configuredModel.isAbsolute() ? configuredModel : projectRoot.resolve(configuredModel)).normalize();
        if (!Files.isRegularFile(localSentimentModel)) {
            throw new IllegalStateException("未找到 HanLP 情感模型文件：" + localSentimentModel);
        }
        if (!Files.exists(localCsv)) {
            throw new IllegalArgumentException("未找到待处理的 CSV 文件：" + localCsv.toAbsolutePath());
        }

        String batchId = LocalDateTime.now().format(BATCH_FORMAT);
        String remoteDataDir = appHome + "/data/crawler";
        String remoteEtlDir = appHome + "/etl/target";
        String remoteDeployDir = appHome + "/deploy/sql";
        String remoteModelDir = appHome + "/etl/models";
        String remoteCsv = remoteDataDir + "/social_event_real.csv";
        String remoteJar = remoteEtlDir + "/social-hotspot-etl-1.0.0-SNAPSHOT.jar";
        String remoteSchema = remoteDeployDir + "/schema.sql";
        String remoteModelName = localSentimentModel.getFileName().toString();
        String remoteModel = remoteModelDir + "/" + remoteModelName;
        String csvTemp = remoteCsv + ".uploading-" + batchId;
        String jarTemp = remoteJar + ".uploading-" + batchId;
        String schemaTemp = remoteSchema + ".uploading-" + batchId;
        String modelTemp = remoteModel + ".uploading-" + batchId;
        Path localJar = projectRoot.resolve("etl/target/social-hotspot-etl-1.0.0-SNAPSHOT.jar");
        Path localSchema = projectRoot.resolve("deploy/sql/schema.sql");
        if (!Files.exists(localJar)) {
            throw new IllegalStateException("未找到 ETL 构建包：" + localJar.toAbsolutePath());
        }
        if (!Files.exists(localSchema)) {
            throw new IllegalStateException("未找到数据库初始化脚本：" + localSchema.toAbsolutePath());
        }

        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port);
        session.setPassword(sshPassword);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(Math.min(timeoutSeconds * 1000, 30000));
        try {
            exec(session, "mkdir -p " + sh(appHome) + " " + sh(remoteDataDir) + " " + sh(remoteEtlDir) + " " + sh(remoteDeployDir) + " " + sh(remoteModelDir), timeoutSeconds);
            upload(session, localCsv, csvTemp);
            upload(session, localJar, jarTemp);
            upload(session, localSchema, schemaTemp);
            upload(session, localSentimentModel, modelTemp);
            exec(session, "mv " + sh(csvTemp) + " " + sh(remoteCsv) + " && mv " + sh(jarTemp) + " " + sh(remoteJar)
                    + " && mv " + sh(schemaTemp) + " " + sh(remoteSchema) + " && mv " + sh(modelTemp) + " " + sh(remoteModel), timeoutSeconds);
            syncFileToWorkers(localCsv, remoteDataDir, remoteCsv);
            syncFileToWorkers(localSentimentModel, remoteModelDir, remoteModel);

            String mysqlSchema = "mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=root --password="
                    + sh(databasePassword) + " --default-character-set=utf8mb4 < " + sh(remoteSchema);
            exec(session, mysqlSchema, timeoutSeconds);

            String sparkCommand = "export DB_PASSWORD=" + sh(databasePassword) + " && "
                    + sh(sparkSubmit) + " --class com.social.hotspot.etl.SocialHotspotEtlJob"
                    + " --master " + sh(sparkMaster)
                    + " --deploy-mode client --driver-memory 768m"
                    + " --conf " + sh("spark.executorEnv.HANLP_SENTIMENT_MODEL=" + remoteModel)
                    + " --conf " + sh("spark.driverEnv.HANLP_SENTIMENT_MODEL=" + remoteModel)
                    + " " + sh(remoteJar)
                    + " --input " + sh(fileUri(remoteCsv))
                    + " --event-id " + sh(EVENT_ID)
                    + " --event-name " + sh(EVENT_NAME)
                    + " --batch-id " + sh(batchId)
                    + " --jdbc-url " + sh("jdbc:mysql://" + databaseHost + ":3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false")
                    + " --jdbc-user root --jdbc-password \"$DB_PASSWORD\""
                    + (warehouseOutput == null || warehouseOutput.isBlank() ? "" : " --warehouse-output " + sh(warehouseOutput))
                    + " --sentiment-model " + sh(remoteModel);
            ExecResult sparkResult = exec(session, sparkCommand, timeoutSeconds);

            String verifySql = "select concat(batch_id, '|', status, '|', source_count, '|', valid_count, '|', dirty_count, '|', duplicate_count) from social_hotspot_analytics.etl_batch where batch_id=" + sh(batchId)
                    + "; select concat(content_count, '|', platform_count) from social_hotspot_analytics.ads_event_overview where event_id=" + sh(EVENT_ID);
            ExecResult verify = exec(session, "mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=root --password=" + sh(databasePassword) + " --batch --skip-column-names --execute=" + sh(verifySql), timeoutSeconds);
            String[] rows = verify.stdout().lines().filter(line -> !line.isBlank()).toArray(String[]::new);
            if (rows.length < 2) {
                throw new IllegalStateException("远程 ETL 已结束，但数据库校验结果不完整：" + verify.stdout());
            }
            String[] batch = rows[0].trim().split("\\|", -1);
            String[] overview = rows[1].trim().split("\\|", -1);
            if (batch.length < 6 || !"SUCCESS".equals(batch[1])) {
                throw new IllegalStateException("远程 ETL 批次执行失败：" + rows[0]);
            }

            String dwdStatus = "NOT_CONFIGURED";
            if (warehouseOutput != null && !warehouseOutput.isBlank()) {
                String dwdPath = warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + batchId;
                ExecResult warehouse = warehouseOutput.startsWith("hdfs://")
                        ? exec(session, "hdfs dfs -test -d " + sh(dwdPath) + " && echo DWD_PRESENT || echo DWD_MISSING", timeoutSeconds)
                        : exec(session, "test -d " + sh(stripFileUri(dwdPath)) + " && echo DWD_PRESENT || echo DWD_MISSING", timeoutSeconds);
                dwdStatus = warehouse.stdout().contains("DWD_PRESENT") ? "PRESENT" : "NOT_VERIFIED";
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("mode", "VM_SPARK_ETL");
            result.put("vm_host", host);
            result.put("vm_app_home", appHome);
            result.put("batch_id", batch[0]);
            result.put("source_count", parseLong(batch[2]));
            result.put("valid_count", parseLong(batch[3]));
            result.put("dirty_count", parseLong(batch[4]));
            result.put("duplicate_count", parseLong(batch[5]));
            result.put("database_content_count", parseLong(overview[0]));
            result.put("database_platform_count", parseLong(overview.length > 1 ? overview[1] : "0"));
            result.put("hdfs_dwd_status", dwdStatus);
            result.put("spark_log_tail", tail(sparkResult.stdout() + "\n" + sparkResult.stderr(), 4000));
            result.put("message", "CSV 已上传至虚拟机，并已由 Spark ETL 清洗后写入虚拟机 MySQL 数据库。");
            return result;
        } finally {
            session.disconnect();
        }
    }

    private void upload(Session session, Path localFile, String remotePath) throws Exception {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(Math.min(timeoutSeconds * 1000, 30000));
        try (InputStream input = Files.newInputStream(localFile)) {
            sftp.put(input, remotePath, ChannelSftp.OVERWRITE);
        } finally {
            sftp.disconnect();
        }
    }

    private void syncFileToWorkers(Path localFile, String remoteDir, String remoteFile) throws Exception {
        for (String configuredHost : workerHosts.split(",")) {
            String workerHost = configuredHost.trim();
            if (workerHost.isBlank() || workerHost.equals(host)) {
                continue;
            }
            Session workerSession = null;
            try {
                workerSession = connect(workerHost);
                exec(workerSession, "mkdir -p " + sh(remoteDir), timeoutSeconds);
                upload(workerSession, localFile, remoteFile);
            } finally {
                if (workerSession != null) {
                    workerSession.disconnect();
                }
            }
        }
    }

    private Session connect(String targetHost) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, targetHost, port);
        session.setPassword(sshPassword);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(Math.min(timeoutSeconds * 1000, 30000));
        return session;
    }

    private ExecResult exec(Session session, String command, int timeoutSeconds) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        channel.setErrStream(stderr);
        InputStream stdout = channel.getInputStream();
        channel.setCommand("bash -c " + sh(command));
        channel.connect(Math.min(timeoutSeconds * 1000, 30000));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        try {
            byte[] buffer = new byte[8192];
            while (System.currentTimeMillis() < deadline && (!channel.isClosed() || stdout.available() > 0)) {
                while (stdout.available() > 0) {
                    int read = stdout.read(buffer, 0, Math.min(buffer.length, stdout.available()));
                    if (read > 0) output.write(buffer, 0, read);
                }
                Thread.sleep(100);
            }
            if (!channel.isClosed()) {
                throw new IllegalStateException("虚拟机命令执行超时：" + tail(command, 500));
            }
            while (stdout.available() > 0) {
                output.write(bufferRead(stdout));
            }
            int exit = channel.getExitStatus();
            ExecResult result = new ExecResult(output.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8), exit);
            if (exit != 0) {
                throw new IllegalStateException("虚拟机命令执行失败（退出码=" + exit + "）：" + tail(result.stderr() + "\n" + result.stdout(), 3000));
            }
            return result;
        } finally {
            channel.disconnect();
        }
    }

    private byte[] bufferRead(InputStream input) throws Exception {
        byte[] buffer = new byte[Math.max(1, input.available())];
        int read = input.read(buffer);
        if (read == buffer.length) return buffer;
        byte[] result = new byte[Math.max(0, read)];
        if (read > 0) System.arraycopy(buffer, 0, result, 0, read);
        return result;
    }

    private Path resolveProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve("etl/target/social-hotspot-etl-1.0.0-SNAPSHOT.jar"))) return cursor;
        }
        return current;
    }

    private String sh(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String fileUri(String path) {
        return path.startsWith("file:") ? path : "file://" + path;
    }

    private String stripFileUri(String path) {
        return path.startsWith("file://") ? path.substring("file://".length()) : path;
    }

    private long parseLong(String value) {
        try { return Long.parseLong(value == null ? "0" : value.trim()); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String tail(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(value.length() - max);
    }

    private record ExecResult(String stdout, String stderr, int exitCode) {}
}
