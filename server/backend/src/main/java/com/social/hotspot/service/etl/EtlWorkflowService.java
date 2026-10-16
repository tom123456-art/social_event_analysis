// 声明包名 com.social.hotspot.service.etl。
package com.social.hotspot.service.etl;
import com.social.hotspot.integration.spark.RemoteCommandExecutor;
import com.social.hotspot.integration.spark.RemoteCommandResult;
import com.social.hotspot.integration.python.LocalSentimentRunner;
import com.social.hotspot.integration.storage.SftpFileTransfer;
import com.social.hotspot.integration.hive.HivePartitionRegistrar;
import com.social.hotspot.service.review.ReviewInputPreparationService;

// 引入 SSH 通道。
// 引入远程命令执行通道。
// 引入文件上传通道。
// 引入 SSH 客户端工具。
import com.jcraft.jsch.JSch;
// 引入 SSH 会话。
import com.jcraft.jsch.Session;
// 引入项目根目录定位工具。
import com.social.hotspot.config.ProjectPaths;
// 引入日志接口，技术失败详情只记录到后端日志。
import org.slf4j.Logger;
// 引入日志工厂。
import org.slf4j.LoggerFactory;
// 引入配置注入注解。
import org.springframework.beans.factory.annotation.Value;
// 引入 Spring 业务组件注解。
import org.springframework.stereotype.Service;

// 引入内存字节输出流。
// 引入字节输入流。
import java.io.InputStream;
// 引入字节输出流。
import java.io.OutputStream;
// 引入可定位字节位置的文件读写工具。
import java.io.RandomAccessFile;
// 引入标准字符集常量。
import java.nio.charset.StandardCharsets;
// 引入文件操作工具。
import java.nio.file.Files;
// 引入文件覆盖与原子移动选项。
import java.nio.file.StandardCopyOption;
// 引入文件打开选项。
import java.nio.file.StandardOpenOption;
// 引入文件路径类型。
import java.nio.file.Path;
// 引入不含时区的日期时间类型。
import java.time.LocalDateTime;
// 引入日期时间格式工具。
import java.time.format.DateTimeFormatter;
// 引入保持插入顺序的键值映射。
import java.util.LinkedHashMap;
// 引入键值映射，按字段名存取数据。
import java.util.Map;
// 引入属性文件读写工具。
import java.util.Properties;
// 引入时间单位。

// 注册为 Spring 业务组件，供构造器注入。
@Service
/** 本地情感推理后上传资源，执行远程 Spark 清洗和聚合。 */
// 定义远程 ETL 执行服务类。
public class EtlWorkflowService {
    private final com.social.hotspot.service.review.ReviewInputPreparationService reviewPipeline;

    public EtlWorkflowService(com.social.hotspot.service.review.ReviewInputPreparationService reviewPipeline) {
        this.reviewPipeline = reviewPipeline;
    }
    // 保存远程任务完整错误，不把命令、密码或堆栈返回用户。
    private static final Logger LOG = LoggerFactory.getLogger(EtlWorkflowService.class);
    // 定义并初始化远程事件标识。
    private static final String EVENT_ID = "public_rss_latest";
    // 定义并初始化远程事件名称。
    private static final String EVENT_NAME = "网络媒体热点事件传播态势与舆情分析";
    // 定义并初始化含毫秒的批次时间格式。
    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    // 注入配置 vm.etl.enabled，未配置时使用默认值“true”。
    @Value("${vm.etl.enabled:true}")
    // 保存远程 ETL 开关。
    private boolean enabled;
    // 注入配置 vm.etl.host，未配置时使用默认值“192.168.154.121”。
    @Value("${vm.etl.host:192.168.154.121}")
    // 保存虚拟机地址。
    private String host;
    // 注入配置 vm.etl.port，未配置时使用默认值“22”。
    @Value("${vm.etl.port:22}")
    // 保存 SSH 端口。
    private int port;
    // 注入配置 vm.etl.username，未配置时使用默认值“root”。
    @Value("${vm.etl.username:root}")
    // 保存登录用户名。
    private String username;
    // 注入配置 vm.etl.ssh-password，未配置时使用默认值“空字符串”。
    @Value("${vm.etl.ssh-password:}")
    // 保存 SSH 密码。
    private String sshPassword;
    // 注入配置 vm.etl.database-password，未配置时使用默认值“空字符串”。
    @Value("${vm.etl.database-password:}")
    // 保存数据库密码。
    private String databasePassword;
    // 注入配置 vm.etl.database-host，未配置时使用默认值“192.168.154.121”。
    @Value("${vm.etl.database-host:192.168.154.121}")
    // 保存数据库地址。
    private String databaseHost;
    // 注入配置 vm.etl.app-home，未配置时使用默认值“/opt/apps/social-hotspot-analytics”。
    @Value("${vm.etl.app-home:/opt/apps/social-hotspot-analytics}")
    // 保存虚拟机应用目录。
    private String appHome;
    // 注入配置 vm.etl.spark-submit，未配置时使用默认值“/opt/bigdata/spark/bin/spark-submit”。
    @Value("${vm.etl.spark-submit:/opt/bigdata/spark/bin/spark-submit}")
    // 保存 Spark 提交程序路径。
    private String sparkSubmit;
    // 注入配置 vm.etl.spark-master，未配置时使用默认值“spark://192.168.154.121:7077”。
    @Value("${vm.etl.spark-master:spark://192.168.154.121:7077}")
    // 保存 Spark 主节点地址。
    private String sparkMaster;
    // 注入配置 vm.etl.warehouse-output，未配置时使用默认值“空字符串”。
    @Value("${vm.etl.warehouse-output:}")
    // 保存数仓输出目录。
    private String warehouseOutput;
    // 注入配置 vm.etl.hdfs-root，未配置时使用默认值“hdfs://192.168.154.121:8020/social-hotspot-analytics”。
    @Value("${vm.etl.hdfs-root:hdfs://192.168.154.121:8020/social-hotspot-analytics}")
    // 保存 HDFS 项目根路径。
    private String hdfsRoot;
    // 注入配置 vm.etl.sentiment-model，未配置时使用默认值“空字符串”。
    @Value("${vm.etl.sentiment-model:}")
    // 保存本地模型路径。
    private String sentimentModel;
    // 注入配置 vm.etl.sentiment-python，未配置时使用默认值“python”。
    @Value("${vm.etl.sentiment-python:python}")
    // 保存 Python 程序路径。
    private String sentimentPython;
    // 注入配置 vm.etl.sentiment-script，未配置时使用默认值“server/python/src/hotspot_etl/cli/sentiment_inference.py”。
    @Value("${vm.etl.sentiment-script:server/python/src/hotspot_etl/cli/sentiment_inference.py}")
    // 保存情感推理脚本路径。
    private String sentimentScript;
    // 注入配置 vm.etl.sentiment-batch-size，未配置时使用默认值“32”。
    @Value("${vm.etl.sentiment-batch-size:32}")
    // 保存每批模型推理样本数。
    private int sentimentBatchSize;
    // 注入配置 vm.etl.sentiment-min-confidence，未配置时使用默认值“0.95”。
    @Value("${vm.etl.sentiment-min-confidence:0.95}")
    // 保存最低情感置信度。
    private double sentimentMinConfidence;
    // 注入配置 vm.etl.timeout-seconds，未配置时使用默认值“1800”。
    @Value("${vm.etl.timeout-seconds:1800}")
    // 保存超时秒数。
    private int timeoutSeconds;

    /** 校验本地构建产物后上传至虚拟机，执行 Spark 任务并核验数据库结果。 */
    // 按默认全量模式执行远程 ETL。
    public Map<String, Object> run(Path localCsv) throws Exception {
        // 调用全量 ETL 方法并返回执行结果。
        return runFull(localCsv);
    // 结束当前方法。
    }

    // 执行全量 ETL 并标记模式。
    public Map<String, Object> runFull(Path localCsv) throws Exception {
        // 本地情感推理后上传资源，执行远程 Spark 并核验结果，保存为处理结果。
        Map<String, Object> result = runRemote(localCsv, "full", null);
        // 向处理结果写入ETL 模式。
        result.put("etl_mode", "FULL");
        // 返回处理结果。
        return result;
    // 结束当前方法。
    }

    // 依据检查点处理新增完整行，无基线时全量处理。
    public Map<String, Object> runIncremental(Path localCsv) throws Exception {
        // Effective review overrides may replace or remove an old DWD key.
        // Rebuild instead of append-merging so a rejected old row cannot survive.
        if (reviewPipeline.hasEffectiveDecisions()) {
            Map<String, Object> result = runFull(localCsv);
            markFullBaseline(localCsv, result.get("batch_id").toString());
            result.put("etl_mode", "FULL_REVIEW_BASELINE");
            return result;
        }
        // 检查数仓输出目录是否缺失。
        if (warehouseOutput == null || warehouseOutput.isBlank()) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("尚未配置数仓输出目录（VM_ETL_WAREHOUSE_OUTPUT），无法运行增量任务，请联系管理员。");
        // 结束当前条件分支。
        }
        // 生成 CSV 旁边的隐藏检查点路径，保存为检查点路径。
        Path checkpointPath = checkpointPath(localCsv);
        // 读取检查点，规则变更后拒绝复用旧基线，保存为增量检查点。
        IncrementalCheckpoint checkpoint = readCheckpoint(checkpointPath);
        // 向前查找文件尾部换行，确定已完整写入的行边界，保存为完整行末尾字节位置。
        long completeSize = lastCompleteRecordBoundary(localCsv);
        // 按 UTF-8 还原 CSV 表头，保存为CSV 表头。
        String header = readHeader(localCsv);

        // 尚无检查点时先全量处理，建立完整 DWD 基线。
        // 检查点不可复用时先建立全量基线。
        if (checkpoint == null) {
            // 本地情感推理后上传资源，执行远程 Spark 并核验结果，保存为处理结果。
            Map<String, Object> result = runRemote(localCsv, "full", null);
            // 成功后登记字节位置、批次和表头，供后续增量使用。
            writeCheckpoint(checkpointPath, new IncrementalCheckpoint(
                    // 登记最新完整行末尾的字节位置、本次成功批次号与表头，作为下次增量起点。
                    completeSize, String.valueOf(result.get("batch_id")), header));
            // 向处理结果写入ETL 模式。
            result.put("etl_mode", "FULL_BASELINE");
            // 向处理结果写入结果说明。
            result.put("message", "已完成原始数据全量处理，并建立增量处理基线。");
            // 返回处理结果。
            return result;
        // 结束当前条件分支。
        }
        // 表头变化会影响字段口径，拒绝旧增量基线。
        if (!checkpoint.header().equals(header)) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("原始数据表头已变化，请通过“替换原始数据”重新上传并执行全量处理。");
        // 结束当前条件分支。
        }
        // 文件变小表示字节位置失效，要求全量重建。
        if (completeSize < checkpoint.committedBytes()) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("原始数据文件变小，旧增量位置已失效，请通过“替换原始数据”执行全量处理。");
        // 结束当前条件分支。
        }
        // 没有新增完整行时不启动 Spark。
        if (completeSize == checkpoint.committedBytes()) {
            // 创建处理结果。
            Map<String, Object> result = new LinkedHashMap<>();
            // 向处理结果写入处理状态。
            result.put("status", "SUCCESS");
            // 向处理结果写入执行方式。
            result.put("mode", "VM_SPARK_ETL");
            // 向处理结果写入ETL 模式。
            result.put("etl_mode", "INCREMENTAL_NO_CHANGES");
            // 向处理结果写入是否无新增数据。
            result.put("no_changes", true);
            // 向处理结果写入批次号。
            result.put("batch_id", checkpoint.batchId());
            // 向处理结果写入输入数。
            result.put("source_count", 0L);
            // 向处理结果写入有效数。
            result.put("valid_count", 0L);
            // 向处理结果写入结果说明。
            result.put("message", "原始数据没有新增完整记录，无需启动数据处理任务。");
            // 返回处理结果。
            return result;
        // 结束当前条件分支。
        }

        // 创建增量临时文件。
        Path delta = Files.createTempFile(localCsv.getParent(), "social-event-incremental-", ".csv");
        // 进入异常捕获或资源清理代码块。
        try {
            // 复制检查点后的新增完整数据，并补上表头。
            writeDeltaCsv(localCsv, delta, header, checkpoint.committedBytes(), completeSize);
            // 拼接上一批 DWD 目录路径。
            String baseDetail = warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + checkpoint.batchId();
            // 本地情感推理后上传资源，执行远程 Spark 并核验结果，保存为处理结果。
            Map<String, Object> result = runRemote(delta, "incremental", baseDetail);
            // 成功后登记字节位置、批次和表头，供后续增量使用。
            writeCheckpoint(checkpointPath, new IncrementalCheckpoint(
                    // 登记最新完整行末尾的字节位置、本次成功批次号与表头，作为下次增量起点。
                    completeSize, String.valueOf(result.get("batch_id")), header));
            // 向处理结果写入ETL 模式。
            result.put("etl_mode", "INCREMENTAL");
            // 向处理结果写入增量字节数。
            result.put("incremental_bytes", completeSize - checkpoint.committedBytes());
            // 返回处理结果。
            return result;
        // 无论成功还是异常，都执行以下收尾步骤。
        } finally {
            // 临时文件存在时删除，不存在则继续。
            Files.deleteIfExists(delta);
        // 结束资源清理步骤。
        }
    // 结束当前方法。
    }

    // 登记全量批次作为增量基线。
    public void markFullBaseline(Path localCsv, String batchId) throws Exception {
        // 成功后登记字节位置、批次和表头，供后续增量使用。
        writeCheckpoint(checkpointPath(localCsv), new IncrementalCheckpoint(
                // 用全量 CSV 的完整行边界、成功批次号和表头构造新基线。
                lastCompleteRecordBoundary(localCsv), batchId, readHeader(localCsv)));
    // 结束当前方法。
    }

    // 本地情感推理后上传资源，执行远程 Spark 并核验结果。
    private Map<String, Object> runRemote(Path localCsv, String etlMode, String baseDetail) throws Exception {
        try (var prepared = reviewPipeline.prepare(localCsv, "prepare-" + LocalDateTime.now().format(BATCH_FORMAT))) {
            return runPrepared(prepared, etlMode, baseDetail);
        }
    }

    private Map<String, Object> runPrepared(com.social.hotspot.service.review.ReviewInputPreparationService.Prepared prepared,
                                          String etlMode, String baseDetail) throws Exception {
        Path localCsv = prepared.input();
        // 执行任务前先检查置信度合法性。
        validateSentimentThreshold();
        // 远程 ETL 未启用时进入报错分支。
        if (!enabled) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("远程数据处理未启用，请检查任务开关（VM_ETL_ENABLED）。");
        // 结束当前条件分支。
        }
        // 检查 SSH 密码是否缺失。
        if (sshPassword == null || sshPassword.isBlank()) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("未配置虚拟机登录密码，请检查配置项（VM_ETL_SSH_PASSWORD）。");
        // 结束当前条件分支。
        }
        // 检查数据库密码是否缺失。
        if (databasePassword == null || databasePassword.isBlank()) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("未配置虚拟机数据库密码，请检查 VM_ETL_DATABASE_PASSWORD 配置。");
        // 结束当前条件分支。
        }
        // 检查情感模型路径是否缺失。
        if (sentimentModel == null || sentimentModel.isBlank()) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("未配置情感模型，请检查模型路径（VM_ETL_SENTIMENT_MODEL）。");
        // 结束当前条件分支。
        }
        // 定位项目根目录。
        Path projectRoot = ProjectPaths.root();
        // 将配置文本转换为配置的模型路径。
        Path configuredModel = Path.of(sentimentModel);
        // 根据父目录拼接模型绝对路径路径。
        Path localSentimentModel = (configuredModel.isAbsolute() ? configuredModel : projectRoot.resolve(configuredModel)).normalize();
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.isDirectory(localSentimentModel)
                // 同时检查模型配置文件是否缺失。
                || !Files.isRegularFile(localSentimentModel.resolve("config.json"))
                // 同时检查模型词表文件是否缺失。
                || !Files.isRegularFile(localSentimentModel.resolve("vocab.txt"))) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("情感模型目录无效，请检查模型文件是否完整。");
        // 结束当前方法或多行参数定义。
        }
        // 将配置文本转换为配置的脚本路径。
        Path configuredScript = Path.of(sentimentScript);
        // 根据父目录拼接脚本绝对路径路径。
        Path localSentimentScript = (configuredScript.isAbsolute() ? configuredScript : projectRoot.resolve(configuredScript)).normalize();
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.isRegularFile(localSentimentScript)) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("未找到本机情感推理脚本：" + localSentimentScript);
        // 结束当前条件分支。
        }
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.exists(localCsv)) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalArgumentException("未找到待处理的 CSV 文件：" + localCsv.toAbsolutePath());
        // 结束当前条件分支。
        }

        // 将当前时间按批次格式转换为批次号。
        String batchId = LocalDateTime.now().format(BATCH_FORMAT);
        // 拼接远程数据目录路径。
        String remoteDataDir = appHome + "/data/crawler";
        // 拼接远程构建包目录路径。
        String remoteEtlDir = appHome + "/server/etl/target";
        // 拼接远程 SQL 目录路径。
        String remoteDeployDir = appHome + "/deploy/sql";
        // 拼接远程 CSV路径。
        String remoteCsv = remoteDataDir + "/etl_input_" + batchId + ".csv";
        // 拼接远程情感结果路径。
        String remoteSentiment = remoteDataDir + "/sentiment_result_" + batchId + ".csv";
        // 拼接远程 ETL 构建包路径。
        String remoteJar = remoteEtlDir + "/etl-1.0.0-SNAPSHOT.jar";
        // 拼接远程 SQL 脚本路径。
        String remoteSchema = remoteDeployDir + "/schema.sql";
        // 拼接CSV 上传临时路径路径。
        String csvTemp = remoteCsv + ".uploading-" + batchId;
        // 拼接情感上传临时路径路径。
        String sentimentTemp = remoteSentiment + ".uploading";
        // 拼接构建包临时路径路径。
        String jarTemp = remoteJar + ".uploading-" + batchId;
        // 拼接脚本临时路径路径。
        String schemaTemp = remoteSchema + ".uploading-" + batchId;
        // 根据父目录拼接本地构建包路径。
        Path localJar = projectRoot.resolve("server/etl/target/etl-1.0.0-SNAPSHOT.jar");
        // 根据父目录拼接本地 SQL路径。
        Path localSchema = projectRoot.resolve("deploy/sql/schema.sql");
        Path localHiveSchema = projectRoot.resolve("deploy/sql/hive_tables.sql");
        if (!Files.isRegularFile(localHiveSchema)) {
            throw new IllegalStateException("Missing Hive warehouse schema: " + localHiveSchema);
        }
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.exists(localJar)) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("未找到 ETL 构建包：" + localJar.toAbsolutePath());
        // 结束当前条件分支。
        }
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.exists(localSchema)) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalStateException("未找到数据库初始化脚本：" + localSchema.toAbsolutePath());
        // 结束当前条件分支。
        }

        // 创建本地推理结果。
        Path localSentimentResult = Files.createTempFile("social-hotspot-sentiment-" + batchId + "-", ".csv");
        // 声明保存情感推理日志的变量。
        String sentimentLog;
        // 进入异常捕获或资源清理代码块。
        try {
            // 更新推理日志，新值为启动 Python 推理，收集日志并检查超时与退出码。
            sentimentLog = runLocalSentiment(localCsv, localSentimentResult, localSentimentModel, localSentimentScript);
            reviewPipeline.collectLow(prepared, localSentimentResult, batchId, sentimentMinConfidence);
        // 捕获 Exception，通过 exception 读取失败原因。
        } catch (Exception exception) {
            // 临时文件存在时删除，不存在则继续。
            Files.deleteIfExists(localSentimentResult);
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw exception;
        // 结束异常处理分支。
        }

        // 创建SSH 客户端。
        JSch jsch = new JSch();
        // 使用用户名、主机和端口创建 SSH 会话。
        Session session = jsch.getSession(username, host, port);
        // 设置 SSH 密码，用于连接虚拟机，不将其写入响应。
        session.setPassword(sshPassword);
        // 关闭 SSH 主机密钥严格校验，连接方便但不验证服务器身份。
        session.setConfig("StrictHostKeyChecking", "no");
        // 建立 SSH 连接或通道，最多等待 30 秒。
        session.connect(Math.min(timeoutSeconds * 1000, 30000));
        // 进入异常捕获或资源清理代码块。
        try {
            // 通过 SSH 执行远程命令，检查超时及退出码。
            exec(session, "mkdir -p " + sh(appHome) + " " + sh(remoteDataDir) + " " + sh(remoteEtlDir) + " " + sh(remoteDeployDir), timeoutSeconds);
            // 上传至远程临时路径，完整后再正式替换。
            upload(session, localCsv, csvTemp);
            // 上传至远程临时路径，完整后再正式替换。
            upload(session, localSentimentResult, sentimentTemp);
            // 上传至远程临时路径，完整后再正式替换。
            upload(session, localJar, jarTemp);
            // 上传至远程临时路径，完整后再正式替换。
            upload(session, localSchema, schemaTemp);
            upload(session, localHiveSchema, remoteDeployDir + "/hive_tables.sql");
            // 通过 SSH 执行远程命令，检查超时及退出码。
            exec(session, "mv " + sh(csvTemp) + " " + sh(remoteCsv) + " && mv " + sh(jarTemp) + " " + sh(remoteJar)
                    // 继续拼接命令、路径或文本：+ " && mv " + sh(schemaTemp) + " " + sh(remoteSchema)；sh 负责参数引号转义。
                    + " && mv " + sh(schemaTemp) + " " + sh(remoteSchema)
                    // 继续拼接命令、路径或文本：+ " && mv " + sh(sentimentTemp) + " " + sh(remoteSentiment), timeoutSeconds);；sh 负责参数引号转义。
                    + " && mv " + sh(sentimentTemp) + " " + sh(remoteSentiment), timeoutSeconds);
            // 拼接本批次 HDFS 原始目录路径。
            String hdfsRawDir = hdfsRoot + "/raw/social_event/batch_id=" + batchId;
            // 拼接HDFS 原始 CSV路径。
            String hdfsRaw = hdfsRawDir + "/social_event_real.csv";
            // 拼接HDFS 情感结果路径。
            String hdfsSentiment = hdfsRawDir + "/_sentiment/sentiment_result.csv";
            // 通过 SSH 执行远程命令，检查超时及退出码。
            exec(session, "hdfs dfs -mkdir -p " + sh(hdfsRawDir + "/_sentiment")
                    // 继续拼接命令、路径或文本：+ " && hdfs dfs -put -f " + sh(remoteCsv) + " " + sh(hdfsRaw)；sh 负责参数引号转义。
                    + " && hdfs dfs -put -f " + sh(remoteCsv) + " " + sh(hdfsRaw)
                    // 继续拼接命令、路径或文本：+ " && hdfs dfs -put -f " + sh(remoteSentiment) + " " + sh(hdfsSentiment), timeoutSeconds);；sh 负责参数引号转义。
                    + " && hdfs dfs -put -f " + sh(remoteSentiment) + " " + sh(hdfsSentiment), timeoutSeconds);

            // 构造连接 MySQL 并执行初始化脚本的命令。
            String mysqlSchema = "mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=root --password="
                    // 继续拼接命令、路径或文本：+ sh(databasePassword) + " --default-character-set=utf8mb4 < " + sh(remoteSchema);；sh 负责参数引号转义。
                    + sh(databasePassword) + " --default-character-set=utf8mb4 < " + sh(remoteSchema);
            // 通过 SSH 执行远程命令，检查超时及退出码。
            exec(session, mysqlSchema, timeoutSeconds);

            // 构造设置数据库密码并提交 Spark 作业的命令。
            String sparkCommand = "export DB_PASSWORD=" + sh(databasePassword) + " && "
                    // 继续拼接命令、路径或文本：+ sh(sparkSubmit) + " --class com.social.hotspot.etl.job.HotspotAnalysisJob"；sh 负责参数引号转义。
                    + sh(sparkSubmit) + " --class com.social.hotspot.etl.job.HotspotAnalysisJob"
                    // 继续拼接命令、路径或文本：+ " --master " + sh(sparkMaster)；sh 负责参数引号转义。
                    + " --master " + sh(sparkMaster)
                    // 继续拼接命令、路径或文本：+ " --deploy-mode client --driver-memory 768m"；sh 负责参数引号转义。
                    + " --deploy-mode client --driver-memory 768m"
                    // 继续拼接命令、路径或文本：+ " " + sh(remoteJar)；sh 负责参数引号转义。
                    + " " + sh(remoteJar)
                    // 继续拼接命令、路径或文本：+ " --input " + sh(hdfsRaw)；sh 负责参数引号转义。
                    + " --input " + sh(hdfsRaw)
                    // 继续拼接命令、路径或文本：+ " --sentiment-input " + sh(hdfsSentiment)；sh 负责参数引号转义。
                    + " --sentiment-input " + sh(hdfsSentiment)
                    // 继续拼接命令、路径或文本：+ " --event-id " + sh(EVENT_ID)；sh 负责参数引号转义。
                    + " --event-id " + sh(EVENT_ID)
                    // 继续拼接命令、路径或文本：+ " --event-name " + sh(EVENT_NAME)；sh 负责参数引号转义。
                    + " --event-name " + sh(EVENT_NAME)
                    // 继续拼接命令、路径或文本：+ " --batch-id " + sh(batchId)；sh 负责参数引号转义。
                    + " --batch-id " + sh(batchId)
                    // 继续拼接命令、路径或文本：+ " --etl-mode " + sh(etlMode)；sh 负责参数引号转义。
                    + " --etl-mode " + sh(etlMode)
                    // 继续拼接命令、路径或文本：+ " --sentiment-min-confidence " + sh(Double.toString(sentimentMinConfidence))；sh 负责参数引号转义。
                    + " --sentiment-min-confidence " + sh(Double.toString(sentimentMinConfidence))
                    // 继续拼接命令、路径或文本：+ (baseDetail == null ? "" : " --base-detail " + sh(baseDetail))；sh 负责参数引号转义。
                    + (baseDetail == null ? "" : " --base-detail " + sh(baseDetail))
                    // 继续拼接命令、路径或文本：+ " --jdbc-url " + sh("jdbc:mysql://" + databaseHost + ":3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false")；sh 负责参数引号转义。
                    + " --jdbc-url " + sh("jdbc:mysql://" + databaseHost + ":3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false")
                    // 继续拼接命令、路径或文本：+ " --jdbc-user root --jdbc-password \"$DB_PASSWORD\""；sh 负责参数引号转义。
                    + " --jdbc-user root --jdbc-password \"$DB_PASSWORD\""
                    // 继续拼接命令、路径或文本：+ (warehouseOutput == null || warehouseOutput.isBlank() ? "" : " --warehouse-output " + sh(warehouseOutput));；sh 负责参数引号转义。
                    + (warehouseOutput == null || warehouseOutput.isBlank() ? "" : " --warehouse-output " + sh(warehouseOutput));
            // 持续读取远程命令输出，检查超时和退出码，保存为Spark 执行结果。
            RemoteCommandResult sparkResult = exec(session, sparkCommand, timeoutSeconds);
            // 将 Hive 分区状态初始化为未配置。
            String hivePartitionStatus = "NOT_CONFIGURED";
            // 空值，保存为Hive 错误说明。
            String hivePartitionError = null;
            // 已配置数仓输出目录时执行以下操作。
            if (warehouseOutput != null && !warehouseOutput.isBlank()) {
                // 进入异常捕获或资源清理代码块。
                try {
                    // 注册本批次 ODS 与 DWD 分区位置。
                    registerHivePartitions(session, batchId, hdfsRawDir,
                            // 指定本批次 DWD 分区目录，供 Hive 注册外部分区。
                            warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + batchId);
                    // 更新Hive 注册状态，新值为"REGISTERED"。
                    hivePartitionStatus = "REGISTERED";
                // 捕获 Exception，通过 hiveError 读取失败原因。
                } catch (Exception hiveError) {
                    // Hive 注册为辅助步骤，其失败不掩盖 Spark 与 MySQL 的处理结果。
                    // 更新Hive 注册状态，新值为"REGISTRATION_FAILED"。
                    hivePartitionStatus = "REGISTRATION_FAILED";
                    // 更新Hive 错误说明，新值为保留日志末尾，限制返回文本长度。
                    hivePartitionError = tail(hiveError.getMessage(), 1000);
                // 结束异常处理分支。
                }
            // 结束当前条件分支。
            }

            // 构造查询批次执行状态、数据计数和事件概览的 SQL。
            String verifySql = "select concat(batch_id, '|', status, '|', source_count, '|', valid_count, '|', dirty_count, '|', duplicate_count) from social_hotspot_analytics.etl_batch where batch_id=" + sh(batchId)
                    // 继续拼接命令、路径或文本：+ "; select concat(content_count, '|', platform_count) from social_hotspot_analytics.ads_event_overview where event_id=" + sh(EVENT_ID);；sh 负责参数引号转义。
                    + "; select concat(content_count, '|', platform_count) from social_hotspot_analytics.ads_event_overview where event_id=" + sh(EVENT_ID);
            // 持续读取远程命令输出，检查超时和退出码，保存为核验命令结果。
            RemoteCommandResult verify = exec(session, "mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=root --password=" + sh(databasePassword) + " --batch --skip-column-names --execute=" + sh(verifySql), timeoutSeconds);
            // 将核验命令输出拆为非空文本行数组。
            String[] rows = verify.stdout().lines().filter(line -> !line.isBlank()).toArray(String[]::new);
            // 检查数据库核验结果是否少于两行。
            if (rows.length < 2) {
                // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
                // 核验原文留在日志，不把数据库输出直接显示在弹窗。
                LOG.error("远程任务数据库核验结果不完整：{}", verify.stdout());
                // 抛出数据库核验结果不完整的状态异常。
                throw new IllegalStateException("数据处理已结束，但数据库核验结果不完整，请联系管理员检查任务结果。");
            // 结束当前条件分支。
            }
            // 按竖线拆分批次核验字段，并保留空字段。
            String[] batch = rows[0].trim().split("\\|", -1);
            // 按竖线拆分事件概览，并保留空字段。
            String[] overview = rows[1].trim().split("\\|", -1);
            // 批次字段不足六列或执行状态不是成功时进入报错分支。
            if (batch.length < 6 || !"SUCCESS".equals(batch[1])) {
                // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
                // 记录失败批次的完整核验输出。
                LOG.error("远程任务批次核验失败：{}", rows[0]);
                // 抛出数据处理批次执行失败的状态异常。
                throw new IllegalStateException("数据处理批次执行失败，请联系管理员检查任务日志。");
            // 结束当前条件分支。
            }

            // 将 DWD 核验状态初始化为未配置。
            String dwdStatus = "NOT_CONFIGURED";
            // 已配置数仓输出目录时执行以下操作。
            if (warehouseOutput != null && !warehouseOutput.isBlank()) {
                // 拼接DWD 目录路径。
                String dwdPath = warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + batchId;
                // 按数仓路径协议选择 DWD 目录核验方式。
                RemoteCommandResult warehouse = warehouseOutput.startsWith("hdfs://")
                        // 执行 HDFS 目录检查命令，返回 DWD 目录是否存在。
                        ? exec(session, "hdfs dfs -test -d " + sh(dwdPath) + " && echo DWD_PRESENT || echo DWD_MISSING", timeoutSeconds)
                        // 执行虚拟机本地目录检查命令，返回 DWD 目录是否存在。
                        : exec(session, "test -d " + sh(stripFileUri(dwdPath)) + " && echo DWD_PRESENT || echo DWD_MISSING", timeoutSeconds);
                // 更新DWD 检查状态，新值为warehouse.stdout().contains("DWD_PRESENT") ? "PRESENT" : "NOT_VERIFIED"。
                dwdStatus = warehouse.stdout().contains("DWD_PRESENT") ? "PRESENT" : "NOT_VERIFIED";
            // 结束当前条件分支。
            }
            // 创建处理结果。
            Map<String, Object> result = new LinkedHashMap<>();
            // 向处理结果写入处理状态。
            result.put("status", "SUCCESS");
            // 向处理结果写入执行方式。
            result.put("mode", "VM_SPARK_ETL");
            // 向处理结果写入虚拟机地址。
            result.put("vm_host", host);
            // 向处理结果写入虚拟机应用目录。
            result.put("vm_app_home", appHome);
            // 向处理结果写入批次号。
            result.put("batch_id", batch[0]);
            // 向处理结果写入输入数。
            result.put("source_count", parseLong(batch[2]));
            // 向处理结果写入有效数。
            result.put("valid_count", parseLong(batch[3]));
            // 向处理结果写入脏数据数。
            result.put("dirty_count", parseLong(batch[4]));
            // 向处理结果写入重复记录数。
            result.put("duplicate_count", parseLong(batch[5]));
            // 向处理结果写入数据库内容数。
            result.put("database_content_count", parseLong(overview[0]));
            // 向处理结果写入数据库平台数。
            result.put("database_platform_count", parseLong(overview.length > 1 ? overview[1] : "0"));
            // 向处理结果写入DWD 检查状态。
            result.put("hdfs_dwd_status", dwdStatus);
            // 向处理结果写入Hive 分区注册状态。
            result.put("hive_partition_status", hivePartitionStatus);
            // 存在 Hive 分区注册错误时，将错误写入处理结果。
            if (hivePartitionError != null) result.put("hive_partition_error", hivePartitionError);
            // 向处理结果写入Spark 日志末尾。
            result.put("spark_log_tail", tail(sparkResult.stdout() + "\n" + sparkResult.stderr(), 4000));
            // 向处理结果写入推理日志。
            result.put("sentiment_log", tail(sentimentLog, 1000));
            // 向处理结果写入结果说明。
            result.put("message", "CSV 已上传至虚拟机，并已由 Spark ETL 清洗后写入虚拟机 MySQL 数据库。");
            // 返回处理结果。
            return result;
        // 无论成功还是异常，都执行以下收尾步骤。
        } finally {
            // 关闭远程会话或通道，释放连接资源。
            session.disconnect();
            // 临时文件存在时删除，不存在则继续。
            Files.deleteIfExists(localSentimentResult);
        // 结束资源清理步骤。
        }
    // 结束当前方法。
    }

    // 通过 SFTP 上传文件并关闭通道。
    private void upload(Session session, Path localFile, String remotePath) throws Exception { SftpFileTransfer.upload(session, localFile, remotePath, timeoutSeconds); }

    // 启动 Python 推理，收集日志并检查超时与退出码。
    private String runLocalSentiment(Path input, Path output, Path model, Path script) throws Exception { return LocalSentimentRunner.run(input, output, model, script, sentimentPython, sentimentBatchSize, timeoutSeconds); }

    // 生成 CSV 旁边的隐藏检查点路径。
    private Path checkpointPath(Path localCsv) {
        // 返回localCsv.resolveSibling("." + localCsv.getFileName() + ".etl-checkpoint.properties")。
        return localCsv.resolveSibling("." + localCsv.getFileName() + ".etl-checkpoint.properties");
    // 结束当前方法。
    }

    // 读取检查点，规则变更后拒绝复用旧基线。
    private IncrementalCheckpoint readCheckpoint(Path path) throws Exception {
        // 执行任务前先检查置信度合法性。
        validateSentimentThreshold();
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.isRegularFile(path)) return null;
        // 创建检查点属性。
        Properties properties = new Properties();
        // 打开自动关闭资源，离开 try 时关闭文件或流。
        try (InputStream input = Files.newInputStream(path)) {
            // 读取检查点属性，恢复字节位置和策略版本。
            properties.load(input);
        // 结束受保护的操作步骤。
        }
        // 检查检查点的情感策略版本是否与当前版本不一致。
        if (!"MODEL_BINARY_V2_VALIDATED_VOCAB".equals(properties.getProperty("sentimentPolicy"))
                // 同时检查文本质量规则版本是否不一致。
                || !"TEXT_QUALITY_V2".equals(properties.getProperty("textQualityPolicy"))
                || !"REVIEW_OVERLAY_V1".equals(properties.getProperty("reviewPolicy"))
                || !"SOURCE_RANK_REMOVED_V2".equals(properties.getProperty("sourceRankPolicy"))
                || !"SENTIMENT_ONLY_V1".equals(properties.getProperty("sentimentEligibilityPolicy"))
                || !"POLARITY_GATE_V2".equals(properties.getProperty("sentimentPolarityGatePolicy"))
                // 同时检查最低情感置信度是否发生变化。
                || !Double.toString(sentimentMinConfidence).equals(properties.getProperty("sentimentMinConfidence"))) {
            // 返回空值。
            return null;
        // 结束当前方法或多行参数定义。
        }
        // 从检查点属性读取批次号。
        String batchId = properties.getProperty("batchId", "").trim();
        // 从检查点属性读取CSV 表头。
        String header = properties.getProperty("header", "");
        // 从检查点属性读取已处理字节位置。
        long committedBytes = Long.parseLong(properties.getProperty("committedBytes", "-1"));
        // 返回committedBytes >= 0 && !batchId.isBlank() && !header.isBlank()。
        return committedBytes >= 0 && !batchId.isBlank() && !header.isBlank()
                // 检查点字段有效时创建检查点对象，否则返回空值。
                ? new IncrementalCheckpoint(committedBytes, batchId, header) : null;
    // 结束当前方法。
    }

    // 先写临时文件，再替换检查点。
    private void writeCheckpoint(Path path, IncrementalCheckpoint checkpoint) throws Exception {
        // 创建所需父目录，已存在时直接继续。
        Files.createDirectories(path.getParent());
        // 创建检查点临时文件。
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        // 创建检查点属性。
        Properties properties = new Properties();
        // 向检查点属性写入已处理字节位置。
        properties.setProperty("committedBytes", Long.toString(checkpoint.committedBytes()));
        // 向检查点属性写入批次号。
        properties.setProperty("batchId", checkpoint.batchId());
        // 向检查点属性写入CSV 表头。
        properties.setProperty("header", checkpoint.header());
        // 向检查点属性写入情感策略版本。
        properties.setProperty("sentimentPolicy", "MODEL_BINARY_V2_VALIDATED_VOCAB");
        // 向检查点属性写入文本质量规则版本。
        properties.setProperty("textQualityPolicy", "TEXT_QUALITY_V2");
        properties.setProperty("reviewPolicy", "REVIEW_OVERLAY_V1");
        properties.setProperty("sourceRankPolicy", "SOURCE_RANK_REMOVED_V2");
        properties.setProperty("sentimentEligibilityPolicy", "SENTIMENT_ONLY_V1");
        properties.setProperty("sentimentPolarityGatePolicy", "POLARITY_GATE_V2");
        // 向检查点属性写入最低情感置信度。
        properties.setProperty("sentimentMinConfidence", Double.toString(sentimentMinConfidence));
        // 打开自动关闭资源，离开 try 时关闭文件或流。
        try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
            // 保存属性到临时文件，随后替换检查点。
            properties.store(output, "Raw CSV incremental ETL checkpoint");
        // 结束受保护的操作步骤。
        }
        // 进入异常捕获或资源清理代码块。
        try {
            // 移动临时文件，按选项覆盖旧文件或进行原子移动。
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // 捕获 Exception，采用兜底或继续尝试。
        } catch (Exception ignored) {
            // 移动临时文件，按选项覆盖旧文件或进行原子移动。
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        // 结束异常处理分支。
        }
    // 结束当前方法。
    }

    // 按 UTF-8 还原 CSV 表头。
    private String readHeader(Path csv) throws Exception {
        // 打开自动关闭资源，离开 try 时关闭文件或流。
        try (RandomAccessFile file = new RandomAccessFile(csv.toFile(), "r")) {
            // 读取文件中的一行文本。
            String line = file.readLine();
            // 没有表头说明原始文件为空，返回可读的中文说明。
            if (line == null) throw new IllegalArgumentException("原始数据文件为空，请先上传包含表头和有效记录的数据文件。");
            // 按指定字符集将文本转换为字节数组。
            byte[] bytes = line.getBytes(StandardCharsets.ISO_8859_1);
            // 创建String对象并返回。
            return new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        // 结束受保护的操作步骤。
        }
    // 结束当前方法。
    }

    // 校验置信度为有限数且在 (0.5, 1] 内。
    private void validateSentimentThreshold() {
        // 校验置信度有限且位于 (0.5, 1]。
        if (!Double.isFinite(sentimentMinConfidence) || sentimentMinConfidence <= 0.5D || sentimentMinConfidence > 1D) {
            // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
            throw new IllegalArgumentException("情感最低置信度必须大于 0.5 且不超过 1，请检查配置。");
        // 结束当前条件分支。
        }
    // 结束当前方法。
    }

    // 向前查找文件尾部换行，确定已完整写入的行边界。
    private long lastCompleteRecordBoundary(Path csv) throws Exception {
        // 打开自动关闭资源，离开 try 时关闭文件或流。
        try (RandomAccessFile file = new RandomAccessFile(csv.toFile(), "r")) {
            // 读取文件字节长度。
            long length = file.length();
            // 文件为空时返回零字节。
            if (length == 0) return 0;
            // 移动读取位置，查找文件边界。
            file.seek(length - 1);
            // 遇到换行时确定完整行边界。
            if (file.readByte() == '\n') return length;
            // 从文件尾部向前寻找换行，确定完整记录末尾。
            for (long position = length - 2; position >= 0; position--) {
                // 移动读取位置，查找文件边界。
                file.seek(position);
                // 遇到换行时确定完整行边界。
                if (file.readByte() == '\n') return position + 1;
            // 结束当前遍历循环。
            }
            // 返回0。
            return 0;
        // 结束受保护的操作步骤。
        }
    // 结束当前方法。
    }

    // 复制新增字节区间并补上表头。
    private void writeDeltaCsv(Path source, Path target, String header, long start, long end) throws Exception {
        // 打开自动关闭资源，离开 try 时关闭文件或流。
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING);
             // 创建输入流或输入文件。
             RandomAccessFile input = new RandomAccessFile(source.toFile(), "r")) {
            // 写入指定字节或实际读取区间。
            output.write((header + "\n").getBytes(StandardCharsets.UTF_8));
            // 跳过已处理字节，定位新增区间。
            input.seek(start);
            // 分配 64 KB 复制缓冲，分批读取增量文件。
            byte[] buffer = new byte[64 * 1024];
            // 计算起止位置之间还需复制的字节数。
            long remaining = end - start;
            // 继续复制字节，直到剩余字节数为零。
            while (remaining > 0) {
                // 读取字节到缓冲区，并保存本次读取的字节数。
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                // 读到文件末尾后退出复制。
                if (read < 0) break;
                // 写入指定字节或实际读取区间。
                output.write(buffer, 0, read);
                // 扣除已复制字节，下一轮只读剩余区间。
                remaining -= read;
            // 结束当前条件循环。
            }
        // 结束当前方法或多行参数定义。
        }
    // 结束当前方法。
    }

    // 注册本批次 ODS 与 DWD 外部分区。
    private void registerHivePartitions(Session session, String batchId, String rawPath, String dwdPath) throws Exception { HivePartitionRegistrar.register(session, batchId, rawPath, dwdPath, appHome, warehouseOutput, timeoutSeconds); }

    // 持续读取远程命令输出，检查超时和退出码。

    private RemoteCommandResult exec(Session session, String command, int timeoutSeconds) throws Exception { return RemoteCommandExecutor.exec(session, command, timeoutSeconds); }

    // 返回实际读到的字节。

    // 给 shell 参数加单引号并转义内部引号。
    private String sh(String value) { return RemoteCommandExecutor.sh(value); }

    // 去除本地路径的 file:// 前缀。
    private String stripFileUri(String path) {
        // 按问号前的条件，选择问号后或冒号后的返回值。
        return path.startsWith("file://") ? path.substring("file://".length()) : path;
    // 结束当前方法。
    }

    // 转为长整数，格式错误时返回零。
    private long parseLong(String value) {
        // 尝试转为长整数，空值按零，下一行捕获数字格式异常。
        try { return Long.parseLong(value == null ? "0" : value.trim()); }
        // 捕获 NumberFormatException，采用兜底或继续尝试。
        catch (NumberFormatException ignored) { return 0; }
    // 结束当前方法。
    }

    // 保留日志末尾，限制返回文本长度。
    private String tail(String value, int max) { return RemoteCommandExecutor.tail(value, max); }

    // 定义远程命令输出、错误输出和退出码记录类型，自动生成构造与只读访问方法。
    // 定义增量字节位置、批次号与表头记录类型，自动生成构造与只读访问方法。
    private record IncrementalCheckpoint(long committedBytes, String batchId, String header) {}
// 结束类定义。
}
