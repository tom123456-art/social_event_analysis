package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.AnalyticsService;
import com.social.hotspot.common.sync.DataSyncCoordinator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalyticsController {
    private static final Path PROJECT_ROOT = resolveProjectRoot();
    private static final Path RAW_CSV_PATH = PROJECT_ROOT.resolve(Path.of("data", "crawler", "social_event_real.csv"));
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    private static final String CANONICAL_EVENT_NAME = "社交媒体热点事件传播分析";
    private static final List<String> RAW_COLUMNS = Arrays.asList(
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type",
            "title", "content_text", "author_id", "author_name", "publish_time", "crawl_time",
            "like_count", "comment_count", "repost_count", "share_count", "favorite_count", "view_count",
            "hot_rank", "location", "user_age_group", "user_gender", "keywords", "source_url", "image_url", "category"
    );
    private final AnalyticsService service;
    private final DataSyncCoordinator dataSyncCoordinator;

    public AnalyticsController(AnalyticsService service, DataSyncCoordinator dataSyncCoordinator) {
        this.service = service;
        this.dataSyncCoordinator = dataSyncCoordinator;
    }

    private static Path resolveProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve(Path.of("tools", "crawler", "hotspot-crawler.js")))) {
                return cursor;
            }
        }
        return current;
    }

    @GetMapping("/events")
    public ApiResponse<List<Map<String, Object>>> events() {
        return ApiResponse.ok(service.events());
    }

    @GetMapping("/events/{eventId}/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(@PathVariable("eventId") String eventId) {
        return ApiResponse.ok(service.dashboard(eventId));
    }

    @GetMapping("/admin/overview")
    public ApiResponse<Map<String, Object>> adminOverview() {
        return ApiResponse.ok(service.adminOverview());
    }

    @GetMapping("/etl/batches")
    public ApiResponse<List<Map<String, Object>>> batches(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(service.batches(Math.min(limit, 200)));
    }

    @GetMapping("/etl/batches/{batchId}/logs")
    public ApiResponse<List<Map<String, Object>>> logs(@PathVariable("batchId") String batchId) {
        return ApiResponse.ok(service.taskLogs(batchId));
    }

    @GetMapping("/etl/raw/file")
    public ApiResponse<Map<String, Object>> rawFile() throws Exception {
        ensureRawCsvSchema();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", RAW_CSV_PATH.toAbsolutePath().toString());
        data.put("exists", Files.exists(RAW_CSV_PATH));
        data.put("size", Files.exists(RAW_CSV_PATH) ? Files.size(RAW_CSV_PATH) : 0);
        data.put("columns", RAW_COLUMNS);
        data.putAll(rawCsvPreview(100));
        return ApiResponse.ok(data);
    }

    @PostMapping("/etl/run")
    public ApiResponse<Map<String, Object>> runEtl(@RequestBody(required = false) Map<String, Object> body) throws Exception {
        String eventId = body == null ? "" : String.valueOf(body.getOrDefault("eventId", ""));
        return ApiResponse.ok(dataSyncCoordinator.execute(() -> service.runLocalCsvEtl(RAW_CSV_PATH, eventId)));
    }

    @PostMapping("/etl/raw/upload")
    public ApiResponse<Map<String, Object>> uploadRawCsv(@RequestParam("file") MultipartFile file) {
        try {
            return doUploadRawCsv(file);
        } catch (Exception ex) {
            return ApiResponse.fail("CSV 上传处理失败：" + ex.getClass().getSimpleName() + " - " + ex.getMessage());
        }
    }

    private ApiResponse<Map<String, Object>> doUploadRawCsv(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail("请选择要上传的 CSV 文件");
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!fileName.toLowerCase().endsWith(".csv")) {
            return ApiResponse.fail("只允许上传 CSV 文件");
        }

        String content = new String(file.getBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        content = stripBom(content).trim();
        if (content.isBlank()) {
            return ApiResponse.fail("CSV 文件为空");
        }

        String[] lines = content.split("\n");
        String header = stripBom(lines[0]).trim();
        List<String> uploadColumns = parseCsvLine(header);
        if (!RAW_COLUMNS.equals(uploadColumns)) {
            return ApiResponse.fail("CSV 表头与 ETL Raw Schema 不一致，应为：" + String.join(",", RAW_COLUMNS));
        }

        StringBuilder rows = new StringBuilder();
        int rowCount = 0;
        for (int i = 1; i < lines.length; i++) {
            String row = lines[i].trim();
            if (row.isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(row);
            if (cells.size() != RAW_COLUMNS.size()) {
                return ApiResponse.fail("CSV 第 " + (i + 1) + " 行字段数量不正确，应为 " + RAW_COLUMNS.size() + " 列，实际为 " + cells.size() + " 列");
            }
            rows.append(normalizeRawRow(row, uploadColumns.size())).append("\n");
            rowCount++;
        }
        if (rowCount == 0) {
            return ApiResponse.fail("CSV 中没有可追加的数据行");
        }

        Map<String, Object> etlResult = dataSyncCoordinator.execute(() -> {
            byte[] previousCsv = Files.exists(RAW_CSV_PATH) ? Files.readAllBytes(RAW_CSV_PATH) : null;
            try {
                Files.createDirectories(RAW_CSV_PATH.getParent());
                ensureRawCsvSchema();
                boolean writeHeader = !Files.exists(RAW_CSV_PATH) || Files.size(RAW_CSV_PATH) == 0;
                StringBuilder output = new StringBuilder();
                if (writeHeader) {
                    output.append('\uFEFF').append(String.join(",", RAW_COLUMNS)).append("\n");
                }
                output.append(rows);
                Files.writeString(RAW_CSV_PATH, output.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return service.runLocalCsvEtl(RAW_CSV_PATH, CANONICAL_EVENT_ID);
            } catch (Exception ex) {
                restoreRawCsv(previousCsv);
                throw ex;
            }
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file_name", fileName);
        data.put("append_count", rowCount);
        data.put("raw_path", RAW_CSV_PATH.toAbsolutePath().toString());
        data.put("upload_time", OffsetDateTime.now().toString());
        data.put("database_sync_status", etlResult.get("status"));
        data.put("etl_mode", "LOCAL_CSV_ETL");
        data.put("etl_batch_id", etlResult.get("batch_id"));
        data.put("etl_source_count", etlResult.get("source_count"));
        data.put("etl_valid_count", etlResult.get("valid_count"));
        data.put("etl_dirty_count", etlResult.get("dirty_count"));
        data.put("etl_duplicate_count", etlResult.get("duplicate_count"));
        data.put("next_step", "CSV 已追加并自动完成本地 ADS 分析表与 MySQL 同步，无需经过虚拟机。");
        return ApiResponse.ok(data);
    }

    private void restoreRawCsv(byte[] previousCsv) throws Exception {
        if (previousCsv == null) {
            Files.deleteIfExists(RAW_CSV_PATH);
            return;
        }
        Files.write(RAW_CSV_PATH, previousCsv, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private String stripBom(String value) {
        return value == null ? "" : value.replaceFirst("^\\uFEFF", "");
    }

    private void ensureRawCsvSchema() throws Exception {
        if (!Files.exists(RAW_CSV_PATH) || Files.size(RAW_CSV_PATH) == 0) {
            return;
        }
        List<String> lines = Files.readAllLines(RAW_CSV_PATH, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return;
        }
        List<String> headerCells = parseCsvLine(stripBom(lines.get(0)).trim());
        if (headerCells.equals(RAW_COLUMNS)) {
            return;
        }
        throw new IllegalStateException("活动 Raw 文件表头不是纯真实采集格式，请移除旧文件后重新采集：" + RAW_CSV_PATH.toAbsolutePath());
    }

    private Map<String, Object> rawCsvPreview(int limit) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("row_count", 0);
        data.put("preview_rows", List.of());
        if (!Files.exists(RAW_CSV_PATH) || Files.size(RAW_CSV_PATH) == 0) {
            return data;
        }
        List<String> lines = Files.readAllLines(RAW_CSV_PATH, StandardCharsets.UTF_8);
        if (lines.size() <= 1) {
            return data;
        }
        List<List<String>> rows = new java.util.ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String row = lines.get(i).trim();
            if (row.isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(row);
            while (cells.size() < RAW_COLUMNS.size()) {
                cells.add("");
            }
            rows.add(cells);
        }
        int from = Math.max(0, rows.size() - Math.max(1, limit));
        List<Map<String, Object>> preview = new java.util.ArrayList<>();
        List<List<String>> latestRows = new java.util.ArrayList<>(rows.subList(from, rows.size()));
        int crawlTimeIndex = RAW_COLUMNS.indexOf("crawl_time");
        int rankIndex = RAW_COLUMNS.indexOf("hot_rank");
        int urlIndex = RAW_COLUMNS.indexOf("source_url");
        latestRows.sort((left, right) -> {
            int timeCompare = right.get(crawlTimeIndex).compareTo(left.get(crawlTimeIndex));
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Long.compare(parseLong(left.get(rankIndex)), parseLong(right.get(rankIndex)));
        });
        for (List<String> cells : latestRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (int i = 0; i < RAW_COLUMNS.size(); i++) {
                item.put(RAW_COLUMNS.get(i), cells.get(i));
            }
            item.put("url", cells.get(urlIndex));
            item.put("source_url", cells.get(urlIndex));
            preview.add(item);
        }
        data.put("row_count", rows.size());
        data.put("preview_rows", preview);
        data.put("preview_limit", limit);
        return data;
    }

    private String normalizeRawRow(String row, int sourceColumnSize) {
        List<String> cells = parseCsvLine(row);
        if (cells.size() < sourceColumnSize) {
            return row;
        }
        while (cells.size() < RAW_COLUMNS.size()) {
            cells.add("");
        }
        cells.set(0, CANONICAL_EVENT_ID);
        cells.set(1, CANONICAL_EVENT_NAME);
        return toCsvLine(cells);
    }

    private List<String> parseCsvLine(String line) {
        java.util.ArrayList<String> cells = new java.util.ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private String toCsvLine(List<String> cells) {
        return cells.stream().map(this::csv).collect(java.util.stream.Collectors.joining(","));
    }

    private String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value == null || value.isBlank() ? "0" : value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
