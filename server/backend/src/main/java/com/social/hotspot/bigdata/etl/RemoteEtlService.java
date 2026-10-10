package com.social.hotspot.bigdata.etl;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.social.hotspot.config.ProjectPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Service
/** 远程 ETL 服务：上传资源到虚拟机并触发 Spark 情感分析任务。 */
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
    @Value("${vm.etl.app-home:/opt/apps/social-hotspot-analytics}")
    private String appHome;
    @Value("${vm.etl.spark-submit:/opt/bigdata/spark/bin/spark-submit}")
    private String sparkSubmit;
    @Value("${vm.etl.spark-master:spark://192.168.154.121:7077}")
    private String sparkMaster;
    @Value("${vm.etl.warehouse-output:}")
    private String warehouseOutput;
    @Value("${vm.etl.hdfs-root:hdfs://192.168.154.121:8020/social-hotspot-analytics}")
    private String hdfsRoot;
    @Value("${vm.etl.sentiment-model:}")
    private String sentimentModel;
    @Value("${vm.etl.sentiment-python:python}")
    private String sentimentPython;
    @Value("${vm.etl.sentiment-script:server/etl/scripts/infer_sentiment.py}")
    private String sentimentScript;
    @Value("${vm.etl.sentiment-batch-size:32}")
    private int sentimentBatchSize;
    @Value("${vm.etl.timeout-seconds:1800}")
    private int timeoutSeconds;

    /** 校验本地构建产物后上传至虚拟机，执行 Spark 任务并核验数据库结果。 */
    public Map<String, Object> run(Path localCsv) throws Exception {
        return runFull(localCsv);
    }

    public Map<String, Object> runFull(Path localCsv) throws Exception {
        Map<String, Object> result = runRemote(localCsv, "full", null);
        result.put("etl_mode", "FULL");
        return result;
    }

    public Map<String, Object> runIncremental(Path localCsv) throws Exception {
        if (warehouseOutput == null || warehouseOutput.isBlank()) {
            throw new IllegalStateException("Incremental ETL requires VM_ETL_WAREHOUSE_OUTPUT.");
        }
        Path checkpointPath = checkpointPath(localCsv);
        IncrementalCheckpoint checkpoint = readCheckpoint(checkpointPath);
        long completeSize = lastCompleteRecordBoundary(localCsv);
        String header = readHeader(localCsv);

        // The first run after enabling this feature establishes a complete DWD snapshot.
        if (checkpoint == null) {
            Map<String, Object> result = runRemote(localCsv, "full", null);
            writeCheckpoint(checkpointPath, new IncrementalCheckpoint(
                    completeSize, String.valueOf(result.get("batch_id")), header));
            result.put("etl_mode", "FULL_BASELINE");
            result.put("message", "Incremental baseline created from the complete Raw CSV.");
            return result;
        }
        if (!checkpoint.header().equals(header)) {
            throw new IllegalStateException("Raw CSV header changed. Use Replace Base CSV to run a full rebuild.");
        }
        if (completeSize < checkpoint.committedBytes()) {
            throw new IllegalStateException("Raw CSV became smaller. Use Replace Base CSV to run a full rebuild.");
        }
        if (completeSize == checkpoint.committedBytes()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("mode", "VM_SPARK_ETL");
            result.put("etl_mode", "INCREMENTAL_NO_CHANGES");
            result.put("no_changes", true);
            result.put("batch_id", checkpoint.batchId());
            result.put("source_count", 0L);
            result.put("valid_count", 0L);
            result.put("message", "Raw CSV has no appended complete records; ETL was not started.");
            return result;
        }

        Path delta = Files.createTempFile(localCsv.getParent(), "social-event-incremental-", ".csv");
        try {
            writeDeltaCsv(localCsv, delta, header, checkpoint.committedBytes(), completeSize);
            String baseDetail = warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + checkpoint.batchId();
            Map<String, Object> result = runRemote(delta, "incremental", baseDetail);
            writeCheckpoint(checkpointPath, new IncrementalCheckpoint(
                    completeSize, String.valueOf(result.get("batch_id")), header));
            result.put("etl_mode", "INCREMENTAL");
            result.put("incremental_bytes", completeSize - checkpoint.committedBytes());
            return result;
        } finally {
            Files.deleteIfExists(delta);
        }
    }

    public void markFullBaseline(Path localCsv, String batchId) throws Exception {
        writeCheckpoint(checkpointPath(localCsv), new IncrementalCheckpoint(
                lastCompleteRecordBoundary(localCsv), batchId, readHeader(localCsv)));
    }

    private Map<String, Object> runRemote(Path localCsv, String etlMode, String baseDetail) throws Exception {
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
            throw new IllegalStateException("未配置 Erlangshen 情感模型，请检查 VM_ETL_SENTIMENT_MODEL 配置。");
        }
        Path projectRoot = ProjectPaths.root();
        Path configuredModel = Path.of(sentimentModel);
        Path localSentimentModel = (configuredModel.isAbsolute() ? configuredModel : projectRoot.resolve(configuredModel)).normalize();
        if (!Files.isDirectory(localSentimentModel)
                || !Files.isRegularFile(localSentimentModel.resolve("config.json"))
                || !Files.isRegularFile(localSentimentModel.resolve("vocab.txt"))) {
            throw new IllegalStateException("Erlangshen 模型目录无效：" + localSentimentModel);
        }
        Path configuredScript = Path.of(sentimentScript);
        Path localSentimentScript = (configuredScript.isAbsolute() ? configuredScript : projectRoot.resolve(configuredScript)).normalize();
        if (!Files.isRegularFile(localSentimentScript)) {
            throw new IllegalStateException("未找到本机情感推理脚本：" + localSentimentScript);
        }
        if (!Files.exists(localCsv)) {
            throw new IllegalArgumentException("未找到待处理的 CSV 文件：" + localCsv.toAbsolutePath());
        }

        String batchId = LocalDateTime.now().format(BATCH_FORMAT);
        String remoteDataDir = appHome + "/data/crawler";
        String remoteEtlDir = appHome + "/server/etl/target";
        String remoteDeployDir = appHome + "/deploy/sql";
        String remoteCsv = remoteDataDir + "/social_event_real.csv";
        String remoteSentiment = remoteDataDir + "/sentiment_result_" + batchId + ".csv";
        String remoteJar = remoteEtlDir + "/social-hotspot-etl-1.0.0-SNAPSHOT.jar";
        String remoteSchema = remoteDeployDir + "/schema.sql";
        String csvTemp = remoteCsv + ".uploading-" + batchId;
        String sentimentTemp = remoteSentiment + ".uploading";
        String jarTemp = remoteJar + ".uploading-" + batchId;
        String schemaTemp = remoteSchema + ".uploading-" + batchId;
        Path localJar = projectRoot.resolve("server/etl/target/social-hotspot-etl-1.0.0-SNAPSHOT.jar");
        Path localSchema = projectRoot.resolve("deploy/sql/schema.sql");
        if (!Files.exists(localJar)) {
            throw new IllegalStateException("未找到 ETL 构建包：" + localJar.toAbsolutePath());
        }
        if (!Files.exists(localSchema)) {
            throw new IllegalStateException("未找到数据库初始化脚本：" + localSchema.toAbsolutePath());
        }

        Path localSentimentResult = Files.createTempFile("social-hotspot-sentiment-" + batchId + "-", ".csv");
        String sentimentLog;
        try {
            sentimentLog = runLocalSentiment(localCsv, localSentimentResult, localSentimentModel, localSentimentScript);
        } catch (Exception exception) {
            Files.deleteIfExists(localSentimentResult);
            throw exception;
        }

        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port);
        session.setPassword(sshPassword);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(Math.min(timeoutSeconds * 1000, 30000));
        try {
            exec(session, "mkdir -p " + sh(appHome) + " " + sh(remoteDataDir) + " " + sh(remoteEtlDir) + " " + sh(remoteDeployDir), timeoutSeconds);
            upload(session, localCsv, csvTemp);
            upload(session, localSentimentResult, sentimentTemp);
            upload(session, localJar, jarTemp);
            upload(session, localSchema, schemaTemp);
            exec(session, "mv " + sh(csvTemp) + " " + sh(remoteCsv) + " && mv " + sh(jarTemp) + " " + sh(remoteJar)
                    + " && mv " + sh(schemaTemp) + " " + sh(remoteSchema)
                    + " && mv " + sh(sentimentTemp) + " " + sh(remoteSentiment), timeoutSeconds);
            String hdfsRawDir = hdfsRoot + "/raw/social_event/batch_id=" + batchId;
            String hdfsRaw = hdfsRawDir + "/social_event_real.csv";
            String hdfsSentiment = hdfsRawDir + "/sentiment_result.csv";
            exec(session, "hdfs dfs -mkdir -p " + sh(hdfsRawDir)
                    + " && hdfs dfs -put -f " + sh(remoteCsv) + " " + sh(hdfsRaw)
                    + " && hdfs dfs -put -f " + sh(remoteSentiment) + " " + sh(hdfsSentiment), timeoutSeconds);

            String mysqlSchema = "mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=root --password="
                    + sh(databasePassword) + " --default-character-set=utf8mb4 < " + sh(remoteSchema);
            exec(session, mysqlSchema, timeoutSeconds);

            String sparkCommand = "export DB_PASSWORD=" + sh(databasePassword) + " && "
                    + sh(sparkSubmit) + " --class com.social.hotspot.etl.SocialHotspotEtlJob"
                    + " --master " + sh(sparkMaster)
                    + " --deploy-mode client --driver-memory 768m"
                    + " " + sh(remoteJar)
                    + " --input " + sh(hdfsRaw)
                    + " --sentiment-input " + sh(hdfsSentiment)
                    + " --event-id " + sh(EVENT_ID)
                    + " --event-name " + sh(EVENT_NAME)
                    + " --batch-id " + sh(batchId)
                    + " --etl-mode " + sh(etlMode)
                    + (baseDetail == null ? "" : " --base-detail " + sh(baseDetail))
                    + " --jdbc-url " + sh("jdbc:mysql://" + databaseHost + ":3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false")
                    + " --jdbc-user root --jdbc-password \"$DB_PASSWORD\""
                    + (warehouseOutput == null || warehouseOutput.isBlank() ? "" : " --warehouse-output " + sh(warehouseOutput));
            ExecResult sparkResult = exec(session, sparkCommand, timeoutSeconds);
            String hivePartitionStatus = "NOT_CONFIGURED";
            String hivePartitionError = null;
            if (warehouseOutput != null && !warehouseOutput.isBlank()) {
                try {
                    registerHivePartitions(session, batchId, hdfsRawDir,
                            warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + batchId);
                    hivePartitionStatus = "REGISTERED";
                } catch (Exception hiveError) {
                    // Hive metadata registration is auxiliary; Spark/MySQL success must remain visible.
                    hivePartitionStatus = "REGISTRATION_FAILED";
                    hivePartitionError = tail(hiveError.getMessage(), 1000);
                }
            }

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
            result.put("hive_partition_status", hivePartitionStatus);
            if (hivePartitionError != null) result.put("hive_partition_error", hivePartitionError);
            result.put("spark_log_tail", tail(sparkResult.stdout() + "\n" + sparkResult.stderr(), 4000));
            result.put("sentiment_log", tail(sentimentLog, 1000));
            result.put("message", "CSV 已上传至虚拟机，并已由 Spark ETL 清洗后写入虚拟机 MySQL 数据库。");
            return result;
        } finally {
            session.disconnect();
            Files.deleteIfExists(localSentimentResult);
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

    private String runLocalSentiment(Path input, Path output, Path model, Path script) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                sentimentPython,
                script.toString(),
                "--input", input.toAbsolutePath().toString(),
                "--output", output.toAbsolutePath().toString(),
                "--model", model.toAbsolutePath().toString(),
                "--batch-size", Integer.toString(Math.max(1, sentimentBatchSize))
        );
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("HF_HUB_OFFLINE", "1");
        builder.environment().put("TRANSFORMERS_OFFLINE", "1");
        Process process = builder.start();
        ByteArrayOutputStream outputLog = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream stream = process.getInputStream()) {
                stream.transferTo(outputLog);
            } catch (Exception ignored) {
                // The process exit code below remains the authoritative failure signal.
            }
        });
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("本机 Erlangshen 情感分析执行超时");
        }
        reader.join();
        String log = outputLog.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
            throw new IllegalStateException("本机 Erlangshen 情感分析失败：" + tail(log, 3000));
        }
        return log;
    }

    private Path checkpointPath(Path localCsv) {
        return localCsv.resolveSibling("." + localCsv.getFileName() + ".etl-checkpoint.properties");
    }

    private IncrementalCheckpoint readCheckpoint(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return null;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        String batchId = properties.getProperty("batchId", "").trim();
        String header = properties.getProperty("header", "");
        long committedBytes = Long.parseLong(properties.getProperty("committedBytes", "-1"));
        return committedBytes >= 0 && !batchId.isBlank() && !header.isBlank()
                ? new IncrementalCheckpoint(committedBytes, batchId, header) : null;
    }

    private void writeCheckpoint(Path path, IncrementalCheckpoint checkpoint) throws Exception {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        Properties properties = new Properties();
        properties.setProperty("committedBytes", Long.toString(checkpoint.committedBytes()));
        properties.setProperty("batchId", checkpoint.batchId());
        properties.setProperty("header", checkpoint.header());
        try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "Raw CSV incremental ETL checkpoint");
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readHeader(Path csv) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(csv.toFile(), "r")) {
            String line = file.readLine();
            if (line == null) throw new IllegalArgumentException("Raw CSV is empty: " + csv);
            byte[] bytes = line.getBytes(StandardCharsets.ISO_8859_1);
            return new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        }
    }

    private long lastCompleteRecordBoundary(Path csv) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(csv.toFile(), "r")) {
            long length = file.length();
            if (length == 0) return 0;
            file.seek(length - 1);
            if (file.readByte() == '\n') return length;
            for (long position = length - 2; position >= 0; position--) {
                file.seek(position);
                if (file.readByte() == '\n') return position + 1;
            }
            return 0;
        }
    }

    private void writeDeltaCsv(Path source, Path target, String header, long start, long end) throws Exception {
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING);
             RandomAccessFile input = new RandomAccessFile(source.toFile(), "r")) {
            output.write((header + "\n").getBytes(StandardCharsets.UTF_8));
            input.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = end - start;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private void registerHivePartitions(Session session, String batchId, String rawPath, String dwdPath) throws Exception {
        String partition = batchId.replace("'", "''");
        String sql = "ALTER TABLE social_ods.ods_social_content_raw ADD IF NOT EXISTS PARTITION (batch_id='" + partition
                + "') LOCATION '" + rawPath.replace("'", "''") + "'; "
                + "ALTER TABLE social_dwd.dwd_social_content_detail ADD IF NOT EXISTS PARTITION (batch_id='" + partition
                + "') LOCATION '" + dwdPath.replace("'", "''") + "'";
        String command = "env HADOOP_HOME=/opt/bigdata/hadoop-client HADOOP_CONF_DIR=/opt/bigdata/hadoop-client/etc/hadoop "
                + "HADOOP_OPTS='-Djline.terminal=jline.UnsupportedTerminal -Djansi.passthrough=true -Djansi.force=false' "
                + "TERM=dumb /opt/bigdata/hive/bin/beeline --color=false -u 'jdbc:hive2://127.0.0.1:10000/default' -n root "
                + "--silent=true --showHeader=false -e " + sh(sql);
        exec(session, command, timeoutSeconds);
    }

    private ExecResult exec(Session session, String command, int timeoutSeconds) throws Exception {
        // 循环读取标准输出，既避免缓冲区阻塞，也能在失败时保留诊断信息。
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

    private String sh(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
    private record IncrementalCheckpoint(long committedBytes, String batchId, String header) {}
}
