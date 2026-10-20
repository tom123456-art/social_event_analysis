package com.social.hotspot.service.etl;

import com.social.hotspot.cache.AnalyticsCache;
import com.social.hotspot.config.ProjectPaths;
import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.common.UserMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.social.hotspot.common.sync.DataSyncCoordinator;
import com.social.hotspot.service.analysis.AnalyticsService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@org.springframework.stereotype.Service
// 定义 ETL 业务实现类。
public class EtlTaskServiceImpl implements EtlTaskService {
    // 上传异常完整记录在后端日志中。
    private static final Logger LOG = LoggerFactory.getLogger(EtlTaskServiceImpl.class);
    // 定义并初始化项目根目录。
    private static final Path PROJECT_ROOT = ProjectPaths.root();
    // 定义并初始化正式原始 CSV 路径。
    private static final Path RAW_CSV_PATH = PROJECT_ROOT.resolve(Path.of("data", "crawler", "social_event_real.csv"));
    // 配置统一事件标识、统一事件名称。
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    private static final String CANONICAL_EVENT_NAME = "网络媒体热点事件传播态势与舆情分析";
    // 定义当前标准 CSV 字段顺序，按列名统一口径。
    private static final List<String> RAW_COLUMNS = Arrays.asList(
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type",
            "title", "content_text", "author_id", "author_name", "publish_time", "crawl_time",
            "like_count", "comment_count", "repost_count", "favorite_count", "view_count",
            "keywords", "source_url", "image_url", "category"
    );
    // 定义历史兼容 CSV 字段顺序，按列名统一口径。
    private static final List<String> LEGACY_RAW_COLUMNS = Arrays.asList(
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type",
            "title", "content_text", "author_id", "author_name", "publish_time", "crawl_time",
            "like_count", "comment_count", "repost_count", "share_count", "favorite_count", "view_count",
            "location", "user_age_group", "user_gender", "keywords", "source_url", "image_url", "category"
    );
    // 定义采集输入 CSV 字段顺序，按列名统一口径。
    private static final List<String> CRAWLER_RAW_COLUMNS = Arrays.asList(
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type", "title", "content_text",
            "author_name", "publish_time", "crawl_time", "like_count", "comment_count", "repost_count",
            "favorite_count", "keywords", "source_url", "image_url", "category"
    );
    // 定义历史兼容 CSV 字段顺序，按列名统一口径。
    private static final List<String> LEGACY_CRAWLER_RAW_COLUMNS = Arrays.asList(
            "event_id", "event_name", "platform", "content_id", "content_type", "title", "content_text",
            "author_name", "publish_time", "crawl_time", "like_count", "comment_count", "repost_count",
            "favorite_count", "keywords", "source_url", "image_url", "category"
    );
    // 集中保存业务服务、远程 ETL 服务、同步任务协调器。
    private final AnalyticsService service;
    private final EtlWorkflowService remoteEtlService;
    private final DataSyncCoordinator dataSyncCoordinator;
    // 保存概览缓存组件。
    private final AnalyticsCache analyticsCache;

    // 构造 ETL 业务实现，接收所需组件并保存依赖。
    public EtlTaskServiceImpl(AnalyticsService service, EtlWorkflowService remoteEtlService,
                         DataSyncCoordinator dataSyncCoordinator, AnalyticsCache analyticsCache) {
        this.service = service;
        this.remoteEtlService = remoteEtlService;
        this.dataSyncCoordinator = dataSyncCoordinator;
        this.analyticsCache = analyticsCache;
    }
    @Override
    // 查询批次并包装响应，最多返回 200 条。
    public ApiResponse<List<Map<String, Object>>> batchesResponse(int limit) {
        return ApiResponse.ok(service.batches(Math.min(limit, 200)));
    }

    @Override
    // 查询批次日志并包装响应。
    public ApiResponse<List<Map<String, Object>>> logsResponse(String batchId) {
        return ApiResponse.ok(service.taskLogs(batchId));
    }

    @Override
    // 检查表头并返回 CSV 信息与预览。
    public ApiResponse<Map<String, Object>> rawFileResponse() throws Exception {
        // 预览前检查正式 CSV 表头。
        ensureRawCsvSchema();
        Map<String, Object> data = new LinkedHashMap<>();
        // 组装响应数据容器，包含：本地文件路径、文件是否存在、文件字节数。
        data.put("path", RAW_CSV_PATH.toAbsolutePath().toString());
        data.put("exists", Files.exists(RAW_CSV_PATH));
        data.put("size", Files.exists(RAW_CSV_PATH) ? Files.size(RAW_CSV_PATH) : 0);
        // 向响应数据容器写入标准字段列表；把最近最多 100 条预览和行数信息合并到文件响应。
        data.put("columns", RAW_COLUMNS);
        data.putAll(rawCsvPreview(100));
        return ApiResponse.ok(data);
    }

    @Override
    // 串行触发增量 ETL，成功后删除旧概览缓存。
    public ApiResponse<Map<String, Object>> runIncrementalResponse(Map<String, Object> body) throws Exception {
        return ApiResponse.ok(dataSyncCoordinator.execute(() -> {
            // 依据检查点处理新增完整行，无基线时全量处理。
            Map<String, Object> result = remoteEtlService.runIncremental(RAW_CSV_PATH);
            // ETL 成功后删除旧缓存，下次请求读取新结果。
            if ("SUCCESS".equals(result.get("status"))) analyticsCache.evictEvent(CANONICAL_EVENT_ID);
            return result;
        }));
    }

    @Override
    // 处理 CSV 上传并包装失败响应。
    public ApiResponse<Map<String, Object>> replaceRawCsvResponse(MultipartFile file) {
        try {
            return doReplaceRawCsv(file);
        } catch (Exception ex) {
            // 保存完整异常，供开发者排查文件或远程任务问题。
            LOG.error("原始数据上传处理失败", ex);
            // 将异常消息转换为中文，并返回上传失败响应。
            return ApiResponse.fail(UserMessages.chinese(ex.getMessage(), "数据文件上传处理失败，请检查文件格式或联系管理员。"));
        }
    }

    // 先用临时文件执行全量 ETL，成功后才替换正式 CSV，避免失败上传破坏现有输入。
    private ApiResponse<Map<String, Object>> doReplaceRawCsv(MultipartFile file) throws Exception {
        // 检查上传文件是否缺失或为空。
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail("请选择要上传的 CSV 文件");
        }
        // 读取上传文件名，缺失时使用空字符串。
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        // 检查上传文件是否不是 CSV 文件。
        if (!fileName.toLowerCase().endsWith(".csv")) {
            return ApiResponse.fail("只允许上传 CSV 文件");
        }

        // 创建CSV 文本；更新CSV 文本，新值为移除文本开头的 BOM 标记。
        String content = new String(file.getBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        content = stripBom(content).trim();
        // 检查上传文件内容是否为空白。
        if (content.isBlank()) {
            return ApiResponse.fail("CSV 文件为空");
        }

        // 按引号状态拆分逻辑记录，保留引用字段内部换行。
        List<String> records = parseCsvRecords(content);
        // 检查 CSV 解析结果是否为空。
        if (records.isEmpty()) {
            return ApiResponse.fail("数据文件为空，请选择包含有效记录的文件。");
        }
        // 移除文本开头的 BOM 标记；处理逗号分隔与引号转义，解析 CSV 字段。
        String header = stripBom(records.get(0)).trim();
        List<String> uploadColumns = parseCsvLine(header);
        // 检查上传表头是否不符合支持的字段结构。
        if (!isSupportedRawSchema(uploadColumns)) {
            return ApiResponse.fail("数据文件表头不受支持，请使用当前采集格式或系统标准字段结构。");
        }

        // 创建记录列表；将有效数据行计数初始化为零。
        StringBuilder rows = new StringBuilder();
        int rowCount = 0;
        // 跳过 CSV 表头，依次处理上传的数据行。
        for (int i = 1; i < records.size(); i++) {
            // 读取当前行。
            String row = records.get(i).trim();
            // 检查空白记录。
            if (row.isBlank()) {
                // 跳过当前元素，继续下一轮。
                continue;
            }
            // 处理逗号分隔与引号转义，解析 CSV 字段。
            List<String> cells = parseCsvLine(row);
            // 检查数据行字段数是否与表头一致。
            if (cells.size() != uploadColumns.size()) {
                return ApiResponse.fail("CSV 第 " + (i + 1) + " 行字段数量不正确，应为 " + uploadColumns.size() + " 列，实际为 " + cells.size() + " 列");
            }
            // 按顺序追加字符、字段或记录到文本缓冲；将有效行计数增加一，推进下标或计数。
            rows.append(normalizeRawRow(cells, uploadColumns)).append("\n");
            rowCount++;
        }
        // 有效数据行数为零时进入报错分支。
        if (rowCount == 0) {
            return ApiResponse.fail("CSV 中没有可追加的数据行");
        }

        // 通过同步任务协调器执行上传处理并获取 ETL 结果。
        Map<String, Object> etlResult = dataSyncCoordinator.execute(() -> {
            // 在原始 CSV 所在目录构造临时文件路径。
            Path tempCsv = RAW_CSV_PATH.resolveSibling(
                    // 使用纳秒计数拼接临时文件名，减少并发上传重名。
                    RAW_CSV_PATH.getFileName() + ".uploading-" + System.nanoTime());
            try {
                // 创建所需父目录，已存在时直接继续；创建输出缓冲或输出流；按顺序追加字符、字段或记录到文本缓冲。
                Files.createDirectories(RAW_CSV_PATH.getParent());
                StringBuilder output = new StringBuilder();
                output.append('\uFEFF').append(String.join(",", RAW_COLUMNS)).append("\n");
                // 按顺序追加字符、字段或记录到文本缓冲；写入文本，显式字符集决定编码。
                output.append(rows);
                Files.writeString(tempCsv, output.toString(), StandardCharsets.UTF_8,
                        // 指定创建新文件与写入方式。
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

                // 先对临时 CSV 上传并执行 ETL，不提前覆盖正式文件。
                // 全量成功前正式文件保持原状，仍可读取。
                // 执行全量 ETL 并标记模式。
                Map<String, Object> result = remoteEtlService.runFull(tempCsv);
                // ETL 成功后删除旧缓存，下次请求读取新结果。
                if ("SUCCESS".equals(result.get("status"))) analyticsCache.evictEvent(CANONICAL_EVENT_ID);
                // 全量成功后替换正式 CSV。
                replaceRawCsv(tempCsv);
                // 替换正式文件后登记新的全量基线。
                remoteEtlService.markFullBaseline(RAW_CSV_PATH, String.valueOf(result.get("batch_id")));
                return result;
            } catch (Exception ex) {
                // 清理临时文件，不掩盖原业务异常。
                deleteQuietly(tempCsv);
                throw ex;
            // 无论成功还是异常，都执行以下收尾步骤。
            } finally {
                // 清理临时文件，不掩盖原业务异常。
                deleteQuietly(tempCsv);
            }
        });

        Map<String, Object> data = new LinkedHashMap<>();
        // 组装响应数据容器，包含：上传文件名、本次上传有效行数、正式原始文件路径。
        data.put("file_name", fileName);
        data.put("append_count", rowCount);
        data.put("raw_path", RAW_CSV_PATH.toAbsolutePath().toString());
        // 组装响应数据容器，包含：上传时间、数据库同步状态、ETL 模式。
        data.put("upload_time", OffsetDateTime.now().toString());
        data.put("database_sync_status", etlResult.get("status"));
        data.put("etl_mode", "VM_SPARK_ETL");
        // 组装响应数据容器，包含：ETL 批次号、ETL 输入数、ETL 有效数。
        data.put("etl_batch_id", etlResult.get("batch_id"));
        data.put("etl_source_count", etlResult.get("source_count"));
        data.put("etl_valid_count", etlResult.get("valid_count"));
        // 组装响应数据容器，包含：ETL 脏数据数、ETL 重复数、处理完成说明。
        data.put("etl_dirty_count", etlResult.get("dirty_count"));
        data.put("etl_duplicate_count", etlResult.get("duplicate_count"));
        data.put("next_step", "CSV 已同步到虚拟机节点本地路径，并由 Spark 集群完成清洗、聚合和 MySQL 同步。");
        return ApiResponse.ok(data);
    }

    // 重试移动 CSV，优先原子移动。
    private void replaceRawCsv(Path tempCsv) throws Exception {
        // 空值。
        IOException lastFailure = null;
        // 文件移动最多重试十次，下标从零开始。
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                try {
                    // 移动临时文件，按选项覆盖旧文件或进行原子移动。
                    Files.move(tempCsv, RAW_CSV_PATH, StandardCopyOption.REPLACE_EXISTING,
                            // 指定以原子移动方式替换文件。
                            StandardCopyOption.ATOMIC_MOVE);
                // 捕获 文件系统不支持原子移动的异常，通过 ex 读取失败原因。
                } catch (AtomicMoveNotSupportedException ex) {
                    // 移动临时文件，按选项覆盖旧文件或进行原子移动。
                    Files.move(tempCsv, RAW_CSV_PATH, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            // 捕获 文件或网络读写异常，通过 ex 读取失败原因。
            } catch (IOException ex) {
                // 更新最后一次文件移动异常，新值为ex。
                lastFailure = ex;
                try {
                    // 等待 250 毫秒再重试移动，缓解文件暂时被占用的情况。
                    Thread.sleep(250L);
                // 捕获 InterruptedException，通过 interrupted 读取失败原因。
                } catch (InterruptedException interrupted) {
                    // 恢复线程中断标记，保留调用方的取消信号。
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw lastFailure == null ? new IOException("无法替换 Raw CSV") : lastFailure;
    }

    // 尝试清理临时文件，失败不掩盖原错误。
    private void deleteQuietly(Path path) {
        try {
            // 临时文件存在时删除，不存在则继续。
            Files.deleteIfExists(path);
        // 捕获 Exception，采用兜底或继续尝试。
        } catch (Exception ignored) {
            // 清理失败不能覆盖原来的上传或 ETL 错误。
        }
    }

    // 移除文本开头的 BOM 标记。
    private String stripBom(String value) {
        // 按问号前的条件，选择问号后或冒号后的返回值。
        return value == null ? "" : value.replaceFirst("^\\uFEFF", "");
    }

    // 检查正式 CSV 表头。
    private void ensureRawCsvSchema() throws Exception {
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.exists(RAW_CSV_PATH) || Files.size(RAW_CSV_PATH) == 0) {
            return;
        }
        // 按 UTF-8 读取原始 CSV 文件的全部文本行。
        List<String> lines = parseCsvRecords(stripBom(Files.readString(RAW_CSV_PATH, StandardCharsets.UTF_8)));
        // 文件没有文本行时进入空结果分支。
        if (lines.isEmpty()) {
            return;
        }
        // 处理逗号分隔与引号转义，解析 CSV 字段。
        List<String> headerCells = parseCsvLine(stripBom(lines.get(0)).trim());
        // 表头符合支持的字段结构时进入读取分支。
        if (isSupportedRawSchema(headerCells)) {
            return;
        }
        throw new IllegalStateException("当前原始数据文件表头不受支持，请检查字段名称和顺序后重新上传。");
    }

    // 判断表头是否属于支持的字段结构。
    private boolean isSupportedRawSchema(List<String> columns) {
        return columns.equals(RAW_COLUMNS)
                // 也接受旧版原始 CSV 字段结构；也接受当前采集 CSV 字段结构；也接受旧版采集 CSV 字段结构。
                || columns.equals(LEGACY_RAW_COLUMNS)
                || columns.equals(CRAWLER_RAW_COLUMNS)
                || columns.equals(LEGACY_CRAWLER_RAW_COLUMNS);
    }

    // 读取 CSV 并返回最近记录预览。
    private Map<String, Object> rawCsvPreview(int limit) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        // 组装响应数据容器，包含：文件数据行数、预览记录。
        data.put("row_count", 0);
        data.put("preview_rows", List.of());
        // 检查文件是否存在、长度或项目标志，不满足时停止或返回默认值。
        if (!Files.exists(RAW_CSV_PATH) || Files.size(RAW_CSV_PATH) == 0) {
            // 返回响应数据容器。
            return data;
        }
        // 按 UTF-8 读取原始 CSV 文件的全部文本行。
        List<String> lines = parseCsvRecords(stripBom(Files.readString(RAW_CSV_PATH, StandardCharsets.UTF_8)));
        // 文件只有表头或没有内容时进入空结果分支。
        if (lines.size() <= 1) {
            // 返回响应数据容器。
            return data;
        }
        // 处理逗号分隔与引号转义，解析 CSV 字段；创建记录列表。
        List<String> headerCells = parseCsvLine(stripBom(lines.get(0)).trim());
        List<List<String>> rows = new java.util.ArrayList<>();
        // 跳过表头，依次读取文件数据行。
        for (int i = 1; i < lines.size(); i++) {
            // 读取当前行。
            String row = lines.get(i).trim();
            // 检查空白记录。
            if (row.isBlank()) {
                // 跳过当前元素，继续下一轮。
                continue;
            }
            // 处理逗号分隔与引号转义，解析 CSV 字段；把按列名投影到标准字段，输入缺列时留空的结果加入记录列表。
            List<String> cells = parseCsvLine(row);
            rows.add(projectRawCells(cells, headerCells));
        }
        // 读取预览起始下标；创建预览列表；创建最近记录列表。
        int from = Math.max(0, rows.size() - Math.max(1, limit));
        List<Map<String, Object>> preview = new java.util.ArrayList<>();
        List<List<String>> latestRows = new java.util.ArrayList<>(rows.subList(from, rows.size()));
        // 查找采集时间字段在原始列中的下标；查找来源链接字段在原始列中的下标。
        int crawlTimeIndex = RAW_COLUMNS.indexOf("crawl_time");
        int urlIndex = RAW_COLUMNS.indexOf("source_url");
        // 预览按采集时间和来源链接排序，不使用源平台排名。
        latestRows.sort((left, right) -> {
            // 读取采集时间比较结果。
            int timeCompare = right.get(crawlTimeIndex).compareTo(left.get(crawlTimeIndex));
            // 采集时间不相同时，按时间比较结果排序。
            if (timeCompare != 0) {
                // 返回采集时间比较结果。
                return timeCompare;
            }
            return left.get(urlIndex).compareTo(right.get(urlIndex));
        });
        // 遍历已排序的预览记录。
        for (List<String> cells : latestRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            // 按原始字段顺序逐列构造预览记录。
            for (int i = 0; i < RAW_COLUMNS.size(); i++) {
                // 以标准列名为键、对应字段值为值，构造前端预览记录。
                item.put(RAW_COLUMNS.get(i), cells.get(i));
            }
            // 向当前记录写入页面来源链接；向当前记录写入原始来源链接；把当前记录加入预览列表。
            item.put("url", cells.get(urlIndex));
            item.put("source_url", cells.get(urlIndex));
            preview.add(item);
        }
        // 组装响应数据容器，包含：文件数据行数、预览记录、预览上限。
        data.put("row_count", rows.size());
        data.put("preview_rows", preview);
        data.put("preview_limit", limit);
        // 返回响应数据容器。
        return data;
    }

    // 统一字段顺序和事件标识。
    private String normalizeRawRow(List<String> cells, List<String> sourceColumns) {
        // 按列名投影到标准字段，输入缺列时留空；统一记录的事件标识或事件名称；统一记录的事件标识或事件名称。
        List<String> normalized = projectRawCells(cells, sourceColumns);
        normalized.set(RAW_COLUMNS.indexOf("event_id"), CANONICAL_EVENT_ID);
        normalized.set(RAW_COLUMNS.indexOf("event_name"), CANONICAL_EVENT_NAME);
        return toCsvLine(normalized);
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
            }
        }
        // 返回标准列值列表。
        return projected;
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
    }

    // 加双引号并转义内部引号，字段换行改为空格。
    private String csv(String value) {
        // 空值转为空字符串，非空文本保持原值。
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

}
