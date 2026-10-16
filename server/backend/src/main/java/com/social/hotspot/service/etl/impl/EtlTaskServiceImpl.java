// 声明包名 com.social.hotspot.service.etl.impl。
package com.social.hotspot.service.etl.impl;

// 引入热点概览缓存组件。
import com.social.hotspot.cache.AnalyticsCache;
// 引入项目根目录定位工具。
import com.social.hotspot.config.ProjectPaths;
// 引入统一接口响应对象。
import com.social.hotspot.common.ApiResponse;
// 引入提示过滤器，上传失败不直接返回英文异常或堆栈。
import com.social.hotspot.common.UserMessages;
// 引入日志记录接口。
import org.slf4j.Logger;
// 引入日志工厂。
import org.slf4j.LoggerFactory;
// 引入数据同步任务协调器。
import com.social.hotspot.common.sync.DataSyncCoordinator;
// 引入分析业务接口。
import com.social.hotspot.service.analysis.AnalyticsService;
// 引入 ETL 业务接口。
import com.social.hotspot.service.etl.EtlTaskService;
// 引入远程 ETL 执行服务。
import com.social.hotspot.service.etl.EtlWorkflowService;
// 引入上传文件类型。
import org.springframework.web.multipart.MultipartFile;

// 引入文件或网络读写异常。
import java.io.IOException;
// 引入标准字符集常量。
import java.nio.charset.StandardCharsets;
// 引入文件系统不支持原子移动的异常。
import java.nio.file.AtomicMoveNotSupportedException;
// 引入文件操作工具。
import java.nio.file.Files;
// 引入文件路径类型。
import java.nio.file.Path;
// 引入文件覆盖与原子移动选项。
import java.nio.file.StandardCopyOption;
// 引入文件打开选项。
import java.nio.file.StandardOpenOption;
// 引入带时区偏移量的日期时间类型。
import java.time.OffsetDateTime;
// 引入数组工具。
import java.util.Arrays;
// 引入保持插入顺序的键值映射。
import java.util.LinkedHashMap;
// 引入列表，保存多条数据。
import java.util.List;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

// 注册为 Spring 业务组件，供构造器注入。
@org.springframework.stereotype.Service
// 定义 ETL 业务实现类。
public class EtlTaskServiceImpl implements EtlTaskService {
    // 上传异常完整记录在后端日志中。
    private static final Logger LOG = LoggerFactory.getLogger(EtlTaskServiceImpl.class);
    // 定义并初始化项目根目录。
    private static final Path PROJECT_ROOT = ProjectPaths.root();
    // 定义并初始化正式原始 CSV 路径。
    private static final Path RAW_CSV_PATH = PROJECT_ROOT.resolve(Path.of("data", "crawler", "social_event_real.csv"));
    // 定义并初始化统一事件标识。
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    // 定义并初始化统一事件名称。
    private static final String CANONICAL_EVENT_NAME = "网络媒体热点事件传播态势与舆情分析";
    // 定义当前标准 CSV 字段顺序，按列名统一口径。
    private static final List<String> RAW_COLUMNS = Arrays.asList(
            // 按顺序列出字段：事件标识、事件名称、平台代码、内容标识、父内容标识、内容类型。
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type",
            // 按顺序列出字段：标题、正文、作者标识、作者名称、发布时间、采集时间。
            "title", "content_text", "author_id", "author_name", "publish_time", "crawl_time",
            // 按顺序列出字段：点赞数、评论数、转发数、收藏数、浏览数。
            "like_count", "comment_count", "repost_count", "favorite_count", "view_count",
            // 按顺序列出字段：关键词、原始来源链接、图片链接、内容类别。
            "keywords", "source_url", "image_url", "category"
    // 结束前面跨行的方法调用或对象构造。
    );
    // 定义历史兼容 CSV 字段顺序，按列名统一口径。
    private static final List<String> LEGACY_RAW_COLUMNS = Arrays.asList(
            // 按顺序列出字段：事件标识、事件名称、平台代码、内容标识、父内容标识、内容类型。
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type",
            // 按顺序列出字段：标题、正文、作者标识、作者名称、发布时间、采集时间。
            "title", "content_text", "author_id", "author_name", "publish_time", "crawl_time",
            // 按顺序列出字段：点赞数、评论数、转发数、share_count、收藏数、浏览数。
            "like_count", "comment_count", "repost_count", "share_count", "favorite_count", "view_count",
            // 按顺序列出字段：location、user_age_group、user_gender、关键词、原始来源链接、图片链接、内容类别。
            "location", "user_age_group", "user_gender", "keywords", "source_url", "image_url", "category"
    // 结束前面跨行的方法调用或对象构造。
    );
    // 定义采集输入 CSV 字段顺序，按列名统一口径。
    private static final List<String> CRAWLER_RAW_COLUMNS = Arrays.asList(
            // 按顺序列出字段：事件标识、事件名称、平台代码、内容标识、父内容标识、内容类型、标题、正文。
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type", "title", "content_text",
            // 按顺序列出字段：作者名称、发布时间、采集时间、点赞数、评论数、转发数。
            "author_name", "publish_time", "crawl_time", "like_count", "comment_count", "repost_count",
            // 按顺序列出字段：收藏数、关键词、原始来源链接、图片链接、内容类别。
            "favorite_count", "keywords", "source_url", "image_url", "category"
    // 结束前面跨行的方法调用或对象构造。
    );
    // 定义历史兼容 CSV 字段顺序，按列名统一口径。
    private static final List<String> LEGACY_CRAWLER_RAW_COLUMNS = Arrays.asList(
            // 按顺序列出字段：事件标识、事件名称、平台代码、内容标识、内容类型、标题、正文。
            "event_id", "event_name", "platform", "content_id", "content_type", "title", "content_text",
            // 按顺序列出字段：作者名称、发布时间、采集时间、点赞数、评论数、转发数。
            "author_name", "publish_time", "crawl_time", "like_count", "comment_count", "repost_count",
            // 按顺序列出字段：收藏数、关键词、原始来源链接、图片链接、内容类别。
            "favorite_count", "keywords", "source_url", "image_url", "category"
    // 结束前面跨行的方法调用或对象构造。
    );
    // 保存业务服务。
    private final AnalyticsService service;
    // 保存远程 ETL 服务。
    private final EtlWorkflowService remoteEtlService;
    // 保存同步任务协调器。
    private final DataSyncCoordinator dataSyncCoordinator;
    // 保存概览缓存组件。
    private final AnalyticsCache analyticsCache;

    // 构造 ETL 业务实现，接收所需组件并保存依赖。
    public EtlTaskServiceImpl(AnalyticsService service, EtlWorkflowService remoteEtlService,
                         // 声明概览缓存参数。
                         DataSyncCoordinator dataSyncCoordinator, AnalyticsCache analyticsCache) {
        // 把传入的业务服务保存到成员字段。
        this.service = service;
        // 把传入的远程 ETL 服务保存到成员字段。
        this.remoteEtlService = remoteEtlService;
        // 把传入的同步任务协调器保存到成员字段。
        this.dataSyncCoordinator = dataSyncCoordinator;
        // 把传入的概览缓存组件保存到成员字段。
        this.analyticsCache = analyticsCache;
    // 结束当前方法或多行参数定义。
    }
    // 标记此方法重写接口或父类方法。
    @Override
    // 查询批次并包装响应，最多返回 200 条。
    public ApiResponse<List<Map<String, Object>>> batchesResponse(int limit) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.batches(Math.min(limit, 200)));
    // 结束当前方法。
    }

    // 标记此方法重写接口或父类方法。
    @Override
    // 查询批次日志并包装响应。
    public ApiResponse<List<Map<String, Object>>> logsResponse(String batchId) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.taskLogs(batchId));
    // 结束当前方法。
    }

    // 标记此方法重写接口或父类方法。
    @Override
    // 检查表头并返回 CSV 信息与预览。
    public ApiResponse<Map<String, Object>> rawFileResponse() throws Exception {
        // 预览前检查正式 CSV 表头。
        ensureRawCsvSchema();
        // 创建响应数据容器。
        Map<String, Object> data = new LinkedHashMap<>();
        // 向响应数据容器写入本地文件路径。
        data.put("path", RAW_CSV_PATH.toAbsolutePath().toString());
        // 向响应数据容器写入文件是否存在。
        data.put("exists", Files.exists(RAW_CSV_PATH));
        // 向响应数据容器写入文件字节数。
        data.put("size", Files.exists(RAW_CSV_PATH) ? Files.size(RAW_CSV_PATH) : 0);
        // 向响应数据容器写入标准字段列表。
        data.put("columns", RAW_COLUMNS);
        // 把最近最多 100 条预览和行数信息合并到文件响应。
        data.putAll(rawCsvPreview(100));
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(data);
    // 结束当前方法。
    }

    // 标记此方法重写接口或父类方法。
    @Override
    // 串行触发增量 ETL，成功后删除旧概览缓存。
    public ApiResponse<Map<String, Object>> runIncrementalResponse(Map<String, Object> body) throws Exception {
        // 返回ApiResponse.ok(dataSyncCoordinator.execute(() -> {。
        return ApiResponse.ok(dataSyncCoordinator.execute(() -> {
            // 依据检查点处理新增完整行，无基线时全量处理，保存为处理结果。
            Map<String, Object> result = remoteEtlService.runIncremental(RAW_CSV_PATH);
            // ETL 成功后删除旧缓存，下次请求读取新结果。
            if ("SUCCESS".equals(result.get("status"))) analyticsCache.evictEvent(CANONICAL_EVENT_ID);
            // 返回处理结果。
            return result;
        // 结束回调处理，回到外层调用流程。
        }));
    // 结束当前方法。
    }

    // 标记此方法重写接口或父类方法。
    @Override
    // 处理 CSV 上传并包装失败响应。
    public ApiResponse<Map<String, Object>> replaceRawCsvResponse(MultipartFile file) {
        // 进入异常捕获或资源清理代码块。
        try {
            // 返回doReplaceRawCsv(file)。
            return doReplaceRawCsv(file);
        // 捕获 Exception，通过 ex 读取失败原因。
        } catch (Exception ex) {
            // 返回统一失败响应，说明本次校验或处理错误。
            // 保存完整异常，供开发者排查文件或远程任务问题。
            LOG.error("原始数据上传处理失败", ex);
            // 将异常消息转换为中文，并返回上传失败响应。
            return ApiResponse.fail(UserMessages.chinese(ex.getMessage(), "数据文件上传处理失败，请检查文件格式或联系管理员。"));
        // 结束异常处理分支。
        }
    // 结束当前方法。
    }

    // 校验上传，临时文件全量 ETL 成功后替换正式 CSV。
    private ApiResponse<Map<String, Object>> doReplaceRawCsv(MultipartFile file) throws Exception {
        // 检查上传文件是否缺失或为空。
        if (file == null || file.isEmpty()) {
            // 返回统一失败响应，说明本次校验或处理错误。
            return ApiResponse.fail("请选择要上传的 CSV 文件");
        // 结束当前条件分支。
        }
        // 读取上传文件名，缺失时使用空字符串。
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        // 检查上传文件是否不是 CSV 文件。
        if (!fileName.toLowerCase().endsWith(".csv")) {
            // 返回统一失败响应，说明本次校验或处理错误。
            return ApiResponse.fail("只允许上传 CSV 文件");
        // 结束当前条件分支。
        }

        // 创建CSV 文本。
        String content = new String(file.getBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        // 更新CSV 文本，新值为移除文本开头的 BOM 标记。
        content = stripBom(content).trim();
        // 检查上传文件内容是否为空白。
        if (content.isBlank()) {
            // 返回统一失败响应，说明本次校验或处理错误。
            return ApiResponse.fail("CSV 文件为空");
        // 结束当前条件分支。
        }

        // 按引号状态拆分逻辑记录，保留引用字段内部换行，保存为CSV 逻辑记录。
        List<String> records = parseCsvRecords(content);
        // 检查 CSV 解析结果是否为空。
        if (records.isEmpty()) {
            // 返回统一失败响应，说明本次校验或处理错误。
            return ApiResponse.fail("数据文件为空，请选择包含有效记录的文件。");
        // 结束当前条件分支。
        }
        // 移除文本开头的 BOM 标记，保存为CSV 表头。
        String header = stripBom(records.get(0)).trim();
        // 处理逗号分隔与引号转义，解析 CSV 字段，保存为上传字段列表。
        List<String> uploadColumns = parseCsvLine(header);
        // 检查上传表头是否不符合支持的字段结构。
        if (!isSupportedRawSchema(uploadColumns)) {
            // 返回统一失败响应，说明本次校验或处理错误。
            return ApiResponse.fail("数据文件表头不受支持，请使用当前采集格式或系统标准字段结构。");
        // 结束当前条件分支。
        }

        // 创建记录列表。
        StringBuilder rows = new StringBuilder();
        // 将有效数据行计数初始化为零。
        int rowCount = 0;
        // 跳过 CSV 表头，依次处理上传的数据行。
        for (int i = 1; i < records.size(); i++) {
            // 读取当前行。
            String row = records.get(i).trim();
            // 检查空白记录。
            if (row.isBlank()) {
                // 跳过当前元素，继续下一轮。
                continue;
            // 结束当前条件分支。
            }
            // 处理逗号分隔与引号转义，解析 CSV 字段，保存为字段值列表。
            List<String> cells = parseCsvLine(row);
            // 检查数据行字段数是否与表头一致。
            if (cells.size() != uploadColumns.size()) {
                // 返回统一失败响应，说明本次校验或处理错误。
                return ApiResponse.fail("CSV 第 " + (i + 1) + " 行字段数量不正确，应为 " + uploadColumns.size() + " 列，实际为 " + cells.size() + " 列");
            // 结束当前条件分支。
            }
            // 按顺序追加字符、字段或记录到文本缓冲。
            rows.append(normalizeRawRow(cells, uploadColumns)).append("\n");
            // 将有效行计数增加一，推进下标或计数。
            rowCount++;
        // 结束当前遍历循环。
        }
        // 有效数据行数为零时进入报错分支。
        if (rowCount == 0) {
            // 返回统一失败响应，说明本次校验或处理错误。
            return ApiResponse.fail("CSV 中没有可追加的数据行");
        // 结束当前条件分支。
        }

        // 通过同步任务协调器执行上传处理并获取 ETL 结果。
        Map<String, Object> etlResult = dataSyncCoordinator.execute(() -> {
            // 在原始 CSV 所在目录构造临时文件路径。
            Path tempCsv = RAW_CSV_PATH.resolveSibling(
                    // 使用纳秒计数拼接临时文件名，减少并发上传重名。
                    RAW_CSV_PATH.getFileName() + ".uploading-" + System.nanoTime());
            // 进入异常捕获或资源清理代码块。
            try {
                // 创建所需父目录，已存在时直接继续。
                Files.createDirectories(RAW_CSV_PATH.getParent());
                // 创建输出缓冲或输出流。
                StringBuilder output = new StringBuilder();
                // 按顺序追加字符、字段或记录到文本缓冲。
                output.append('\uFEFF').append(String.join(",", RAW_COLUMNS)).append("\n");
                // 按顺序追加字符、字段或记录到文本缓冲。
                output.append(rows);
                // 写入文本，显式字符集决定编码。
                Files.writeString(tempCsv, output.toString(), StandardCharsets.UTF_8,
                        // 指定创建新文件与写入方式。
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

                // 先对临时 CSV 上传并执行 ETL，不提前覆盖正式文件。
                // 全量成功前正式文件保持原状，仍可读取。
                // 执行全量 ETL 并标记模式，保存为处理结果。
                Map<String, Object> result = remoteEtlService.runFull(tempCsv);
                // ETL 成功后删除旧缓存，下次请求读取新结果。
                if ("SUCCESS".equals(result.get("status"))) analyticsCache.evictEvent(CANONICAL_EVENT_ID);
                // 全量成功后替换正式 CSV。
                replaceRawCsv(tempCsv);
                // 替换正式文件后登记新的全量基线。
                remoteEtlService.markFullBaseline(RAW_CSV_PATH, String.valueOf(result.get("batch_id")));
                // 返回处理结果。
                return result;
            // 捕获 Exception，通过 ex 读取失败原因。
            } catch (Exception ex) {
                // 清理临时文件，不掩盖原业务异常。
                deleteQuietly(tempCsv);
                // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
                throw ex;
            // 无论成功还是异常，都执行以下收尾步骤。
            } finally {
                // 清理临时文件，不掩盖原业务异常。
                deleteQuietly(tempCsv);
            // 结束资源清理步骤。
            }
        // 结束回调处理，回到外层调用流程。
        });

        // 创建响应数据容器。
        Map<String, Object> data = new LinkedHashMap<>();
        // 向响应数据容器写入上传文件名。
        data.put("file_name", fileName);
        // 向响应数据容器写入本次上传有效行数。
        data.put("append_count", rowCount);
        // 向响应数据容器写入正式原始文件路径。
        data.put("raw_path", RAW_CSV_PATH.toAbsolutePath().toString());
        // 向响应数据容器写入上传时间。
        data.put("upload_time", OffsetDateTime.now().toString());
        // 向响应数据容器写入数据库同步状态。
        data.put("database_sync_status", etlResult.get("status"));
        // 向响应数据容器写入ETL 模式。
        data.put("etl_mode", "VM_SPARK_ETL");
        // 向响应数据容器写入ETL 批次号。
        data.put("etl_batch_id", etlResult.get("batch_id"));
        // 向响应数据容器写入ETL 输入数。
        data.put("etl_source_count", etlResult.get("source_count"));
        // 向响应数据容器写入ETL 有效数。
        data.put("etl_valid_count", etlResult.get("valid_count"));
        // 向响应数据容器写入ETL 脏数据数。
        data.put("etl_dirty_count", etlResult.get("dirty_count"));
        // 向响应数据容器写入ETL 重复数。
        data.put("etl_duplicate_count", etlResult.get("duplicate_count"));
        // 向响应数据容器写入处理完成说明。
        data.put("next_step", "CSV 已同步到虚拟机节点本地路径，并由 Spark 集群完成清洗、聚合和 MySQL 同步。");
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(data);
    // 结束当前方法。
    }

    // 重试移动 CSV，优先原子移动。
    private void replaceRawCsv(Path tempCsv) throws Exception {
        // 空值，保存为最后一次文件移动异常。
        IOException lastFailure = null;
        // 文件移动最多重试十次，下标从零开始。
        for (int attempt = 0; attempt < 10; attempt++) {
            // 进入异常捕获或资源清理代码块。
            try {
                // 进入异常捕获或资源清理代码块。
                try {
                    // 移动临时文件，按选项覆盖旧文件或进行原子移动。
                    Files.move(tempCsv, RAW_CSV_PATH, StandardCopyOption.REPLACE_EXISTING,
                            // 指定以原子移动方式替换文件。
                            StandardCopyOption.ATOMIC_MOVE);
                // 捕获 文件系统不支持原子移动的异常，通过 ex 读取失败原因。
                } catch (AtomicMoveNotSupportedException ex) {
                    // 移动临时文件，按选项覆盖旧文件或进行原子移动。
                    Files.move(tempCsv, RAW_CSV_PATH, StandardCopyOption.REPLACE_EXISTING);
                // 结束异常处理分支。
                }
                // 结束方法，不再执行后续步骤。
                return;
            // 捕获 文件或网络读写异常，通过 ex 读取失败原因。
            } catch (IOException ex) {
                // 更新最后一次文件移动异常，新值为ex。
                lastFailure = ex;
                // 进入异常捕获或资源清理代码块。
                try {
                    // 等待 250 毫秒再重试移动，缓解文件暂时被占用的情况。
                    Thread.sleep(250L);
                // 捕获 InterruptedException，通过 interrupted 读取失败原因。
                } catch (InterruptedException interrupted) {
                    // 恢复线程中断标记，保留调用方的取消信号。
                    Thread.currentThread().interrupt();
                    // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
                    throw interrupted;
                // 结束异常处理分支。
                }
            // 结束异常处理分支。
            }
        // 结束当前遍历循环。
        }
        // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
        throw lastFailure == null ? new IOException("无法替换 Raw CSV") : lastFailure;
    // 结束当前方法。
    }

    // 尝试清理临时文件，失败不掩盖原错误。
    private void deleteQuietly(Path path) {
        // 进入异常捕获或资源清理代码块。
        try {
            // 临时文件存在时删除，不存在则继续。
            Files.deleteIfExists(path);
        // 捕获 Exception，采用兜底或继续尝试。
        } catch (Exception ignored) {
            // 清理失败不能覆盖原来的上传或 ETL 错误。
        // 结束异常处理分支。
        }
    // 结束当前方法。
    }

    // 移除文本开头的 BOM 标记。
    private String stripBom(String value) {
        // 按问号前的条件，选择问号后或冒号后的返回值。
        return value == null ? "" : value.replaceFirst("^\\uFEFF", "");
    // 结束当前方法。
    }

    // 检查正式 CSV 表头。
    private void ensureRawCsvSchema() throws Exception {
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.exists(RAW_CSV_PATH) || Files.size(RAW_CSV_PATH) == 0) {
            // 结束方法，不再执行后续步骤。
            return;
        // 结束当前条件分支。
        }
        // 按 UTF-8 读取原始 CSV 文件的全部文本行。
        List<String> lines = parseCsvRecords(stripBom(Files.readString(RAW_CSV_PATH, StandardCharsets.UTF_8)));
        // 文件没有文本行时进入空结果分支。
        if (lines.isEmpty()) {
            // 结束方法，不再执行后续步骤。
            return;
        // 结束当前条件分支。
        }
        // 处理逗号分隔与引号转义，解析 CSV 字段，保存为表头字段。
        List<String> headerCells = parseCsvLine(stripBom(lines.get(0)).trim());
        // 表头符合支持的字段结构时进入读取分支。
        if (isSupportedRawSchema(headerCells)) {
            // 结束方法，不再执行后续步骤。
            return;
        // 结束当前条件分支。
        }
        // 抛出具体异常，停止此步骤并由调用方处理，不把失败误报为成功。
        throw new IllegalStateException("当前原始数据文件表头不受支持，请检查字段名称和顺序后重新上传。");
    // 结束当前方法。
    }

    // 判断表头是否属于支持的字段结构。
    private boolean isSupportedRawSchema(List<String> columns) {
        // 返回columns.equals(RAW_COLUMNS)。
        return columns.equals(RAW_COLUMNS)
                // 也接受旧版原始 CSV 字段结构。
                || columns.equals(LEGACY_RAW_COLUMNS)
                // 也接受当前采集 CSV 字段结构。
                || columns.equals(CRAWLER_RAW_COLUMNS)
                // 也接受旧版采集 CSV 字段结构。
                || columns.equals(LEGACY_CRAWLER_RAW_COLUMNS);
    // 结束当前方法。
    }

    // 读取 CSV 并返回最近记录预览。
    private Map<String, Object> rawCsvPreview(int limit) throws Exception {
        // 创建响应数据容器。
        Map<String, Object> data = new LinkedHashMap<>();
        // 向响应数据容器写入文件数据行数。
        data.put("row_count", 0);
        // 向响应数据容器写入预览记录。
        data.put("preview_rows", List.of());
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.exists(RAW_CSV_PATH) || Files.size(RAW_CSV_PATH) == 0) {
            // 返回响应数据容器。
            return data;
        // 结束当前条件分支。
        }
        // 按 UTF-8 读取原始 CSV 文件的全部文本行。
        List<String> lines = parseCsvRecords(stripBom(Files.readString(RAW_CSV_PATH, StandardCharsets.UTF_8)));
        // 文件只有表头或没有内容时进入空结果分支。
        if (lines.size() <= 1) {
            // 返回响应数据容器。
            return data;
        // 结束当前条件分支。
        }
        // 处理逗号分隔与引号转义，解析 CSV 字段，保存为表头字段。
        List<String> headerCells = parseCsvLine(stripBom(lines.get(0)).trim());
        // 创建记录列表。
        List<List<String>> rows = new java.util.ArrayList<>();
        // 跳过表头，依次读取文件数据行。
        for (int i = 1; i < lines.size(); i++) {
            // 读取当前行。
            String row = lines.get(i).trim();
            // 检查空白记录。
            if (row.isBlank()) {
                // 跳过当前元素，继续下一轮。
                continue;
            // 结束当前条件分支。
            }
            // 处理逗号分隔与引号转义，解析 CSV 字段，保存为字段值列表。
            List<String> cells = parseCsvLine(row);
            // 把按列名投影到标准字段，输入缺列时留空的结果加入记录列表。
            rows.add(projectRawCells(cells, headerCells));
        // 结束当前遍历循环。
        }
        // 读取预览起始下标。
        int from = Math.max(0, rows.size() - Math.max(1, limit));
        // 创建预览列表。
        List<Map<String, Object>> preview = new java.util.ArrayList<>();
        // 创建最近记录列表。
        List<List<String>> latestRows = new java.util.ArrayList<>(rows.subList(from, rows.size()));
        // 查找采集时间字段在原始列中的下标。
        int crawlTimeIndex = RAW_COLUMNS.indexOf("crawl_time");
        // 查找来源链接字段在原始列中的下标。
        int urlIndex = RAW_COLUMNS.indexOf("source_url");
        // 预览按采集时间和来源链接排序，不使用源平台排名。
        latestRows.sort((left, right) -> {
            // 读取采集时间比较结果。
            int timeCompare = right.get(crawlTimeIndex).compareTo(left.get(crawlTimeIndex));
            // 采集时间不相同时，按时间比较结果排序。
            if (timeCompare != 0) {
                // 返回采集时间比较结果。
                return timeCompare;
            // 结束当前条件分支。
            }
            return left.get(urlIndex).compareTo(right.get(urlIndex));
        // 结束回调处理，回到外层调用流程。
        });
        // 遍历已排序的预览记录。
        for (List<String> cells : latestRows) {
            // 创建当前记录。
            Map<String, Object> item = new LinkedHashMap<>();
            // 按原始字段顺序逐列构造预览记录。
            for (int i = 0; i < RAW_COLUMNS.size(); i++) {
                // 以标准列名为键、对应字段值为值，构造前端预览记录。
                item.put(RAW_COLUMNS.get(i), cells.get(i));
            // 结束当前遍历循环。
            }
            // 向当前记录写入页面来源链接。
            item.put("url", cells.get(urlIndex));
            // 向当前记录写入原始来源链接。
            item.put("source_url", cells.get(urlIndex));
            // 把当前记录加入预览列表。
            preview.add(item);
        // 结束当前遍历循环。
        }
        // 向响应数据容器写入文件数据行数。
        data.put("row_count", rows.size());
        // 向响应数据容器写入预览记录。
        data.put("preview_rows", preview);
        // 向响应数据容器写入预览上限。
        data.put("preview_limit", limit);
        // 返回响应数据容器。
        return data;
    // 结束当前方法。
    }

    // 统一字段顺序和事件标识。
    private String normalizeRawRow(List<String> cells, List<String> sourceColumns) {
        // 按列名投影到标准字段，输入缺列时留空，保存为标准字段记录。
        List<String> normalized = projectRawCells(cells, sourceColumns);
        // 统一记录的事件标识或事件名称。
        normalized.set(RAW_COLUMNS.indexOf("event_id"), CANONICAL_EVENT_ID);
        // 统一记录的事件标识或事件名称。
        normalized.set(RAW_COLUMNS.indexOf("event_name"), CANONICAL_EVENT_NAME);
        // 返回toCsvLine(normalized)。
        return toCsvLine(normalized);
    // 结束当前方法。
    }

    // 按列名投影到标准字段，输入缺列时留空。
    private List<String> projectRawCells(List<String> cells, List<String> sourceColumns) {
        // 创建标准列值列表。
        List<String> projected = new java.util.ArrayList<>(java.util.Collections.nCopies(RAW_COLUMNS.size(), ""));
        // 按目标字段顺序逐列提取原始 CSV 字段。
        for (int targetIndex = 0; targetIndex < RAW_COLUMNS.size(); targetIndex++) {
            // 读取输入列下标。
            int sourceIndex = sourceColumns.indexOf(RAW_COLUMNS.get(targetIndex));
            // 检查数据行字段数是否与表头一致。
            if (sourceIndex >= 0 && sourceIndex < cells.size()) {
                // 按标准列下标复制字段，不依赖输入顺序。
                projected.set(targetIndex, cells.get(sourceIndex));
            // 结束当前条件分支。
            }
        // 结束当前遍历循环。
        }
        // 返回标准列值列表。
        return projected;
    // 结束当前方法。
    }

    private List<String> parseCsvRecords(String content) {
        return com.social.hotspot.common.CsvRecords.records(content);
    }

    private List<String> parseCsvLine(String line) {
        return com.social.hotspot.common.CsvRecords.cells(line);
    }

    // 转义各字段并用逗号连接成 CSV。
    private String toCsvLine(List<String> cells) {
        // 对各字段进行 CSV 转义后，用逗号连接成一行。
        return cells.stream().map(this::csv).collect(java.util.stream.Collectors.joining(","));
    // 结束当前方法。
    }

    // 加双引号并转义内部引号，字段换行改为空格。
    private String csv(String value) {
        // 空值转为空字符串，非空文本保持原值。
        String text = value == null ? "" : value;
        // 返回"\"" + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""。
        return "\"" + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    // 结束当前方法。
    }

// 结束类定义。
}
