package com.social.hotspot.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.DataSyncCoordinator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/admin/crawler")
public class CrawlerController {
    private static final Set<String> ALLOWED_SOURCES = Set.of(
            "TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS",
            "THE_PAPER", "WEIBO"
    );
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path PROJECT_ROOT = resolveProjectRoot();
    private static final Path RAW_CSV_PATH = PROJECT_ROOT.resolve(Path.of("data", "crawler", "social_event_real.csv"));
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    private static final String CANONICAL_EVENT_NAME = "社交媒体热点事件传播分析";
    private static final List<String> CSV_COLUMNS = Arrays.asList(
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type",
            "title", "content_text", "author_id", "author_name", "publish_time", "crawl_time",
            "like_count", "comment_count", "repost_count", "share_count", "favorite_count", "view_count",
            "hot_rank", "location", "user_age_group", "user_gender", "keywords", "source_url", "image_url", "category"
    );
    private final ObjectMapper objectMapper;
    private final DataSyncCoordinator dataSyncCoordinator;

    public CrawlerController(ObjectMapper objectMapper, DataSyncCoordinator dataSyncCoordinator) {
        this.objectMapper = objectMapper;
        this.dataSyncCoordinator = dataSyncCoordinator;
    }

    @GetMapping("/sources")
    public ApiResponse<List<Map<String, Object>>> sources() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(source("TENCENT_NEWS", "腾讯新闻公开文章", "TENCENT_NEWS", "https://i.news.qq.com/web_feed/get_command_pagination?page=1", "从公开推荐、文章详情和评论总数接口读取真实返回数据"));
        items.add(source("NETEASE_NEWS", "网易新闻公开文章", "NETEASE_NEWS", "https://temp.163.com/special/00804KVA/cm_yaowen20200213.js?callback=data_callback", "从公开要闻列表和文章详情页读取真实返回数据"));
        items.add(source("SOHU_NEWS", "搜狐新闻公开文章", "SOHU_NEWS", "https://news.sohu.com/", "从 news.sohu.com 公开频道页和同域文章详情读取真实返回数据"));
        items.add(source("SINA_NEWS", "新浪新闻公开文章", "SINA_NEWS", "https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2509&num=20&page=1", "从新浪新闻公开滚动接口读取真实返回数据"));
        items.add(source("THE_PAPER", "澎湃新闻公开文章", "THE_PAPER", "https://cache.thepaper.cn/contentapi/wwwIndex/rightSidebar", "从公开热榜接口和文章详情页读取真实返回数据"));
        items.add(source("WEIBO", "微博公开内容", "WEIBO", "https://weibo.com/sitemap/auto.xml", "从 robots.txt 明确允许的 sitemap 和公开详情页读取真实返回数据"));
        return ApiResponse.ok(items);
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody Map<String, Object> request) {
        try {
            return doRun(request);
        } catch (Exception ex) {
            return ApiResponse.fail("采集接口处理失败：" + ex.getClass().getSimpleName() + " - " + ex.getMessage());
        }
    }

    @PostMapping(value = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@RequestBody Map<String, Object> request) {
        SseEmitter emitter = new SseEmitter(150000L);
        CompletableFuture.runAsync(() -> {
            try {
                ApiResponse<Map<String, Object>> response = doRun(request, line -> sendEvent(emitter, "log", line));
                if (response.code() == 0) {
                    sendEvent(emitter, "result", objectMapper.writeValueAsString(response.data()));
                } else {
                    sendEvent(emitter, "error", response.message());
                }
                emitter.complete();
            } catch (Exception ex) {
                try {
                    sendEvent(emitter, "error", "采集接口处理失败：" + ex.getClass().getSimpleName() + " - " + ex.getMessage());
                } catch (RuntimeException ignored) {
                    // The browser may close the stream while the process is finishing.
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ex) {
            throw new IllegalStateException("实时日志通道已断开", ex);
        }
    }

    private ApiResponse<Map<String, Object>> doRun(Map<String, Object> request) throws Exception {
        return doRun(request, null);
    }

    private ApiResponse<Map<String, Object>> doRun(Map<String, Object> request, Consumer<String> progressSink) throws Exception {
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        String source = String.valueOf(safeRequest.getOrDefault("source", safeRequest.getOrDefault("sourceId", "TENCENT_NEWS"))).toUpperCase();
        if (!ALLOWED_SOURCES.contains(source)) {
            return ApiResponse.fail("不支持的采集源：" + source + "，可选源为：" + ALLOWED_SOURCES);
        }
        int requestedLimit = number(safeRequest.getOrDefault("limit", safeRequest.get("count")), defaultLimit(source));
        int limit = Math.max(minLimit(source), Math.min(maxLimit(source), requestedLimit));
        String eventId = CANONICAL_EVENT_ID;

        File script = PROJECT_ROOT.resolve(Path.of("tools", "crawler", "hotspot-crawler.js")).toFile();
        if (!script.exists()) {
            return ApiResponse.fail("未找到 JS 采集脚本：" + script.getAbsolutePath() + "，请确认后端工作目录能定位到项目根目录。");
        }

        ProcessBuilder builder = new ProcessBuilder(
                "node",
                script.getPath(),
                "--source=" + source,
                "--limit=" + limit,
                "--eventId=" + eventId,
                "--output=json",
                "--progress=stderr"
        );
        builder.directory(PROJECT_ROOT.toFile());
        // The CLI mode prints one item at a time for the PowerShell demo. The
        // backend must keep stderr separate so that progress text never mixes
        // into the JSON payload consumed below.
        builder.redirectErrorStream(false);
        Process process = builder.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                return ex.getMessage();
            }
        });
        CompletableFuture<String> errorFuture = CompletableFuture.supplyAsync(() -> {
            StringBuilder progress = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    progress.append(line).append(System.lineSeparator());
                    System.out.println("[爬虫] " + line);
                    System.out.flush();
                    if (progressSink != null) {
                        try {
                            progressSink.accept(line);
                        } catch (RuntimeException ignored) {
                            // Keep the real crawl running even if the browser disconnects.
                        }
                    }
                }
                return progress.toString();
            } catch (Exception ex) {
                return progress.append(ex.getMessage() == null ? "" : ex.getMessage()).toString();
            }
        });
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ApiResponse.fail("采集超时：单次采集限制 90 秒，避免持续抓取");
        }

        String stdout = outputFuture.get(3, TimeUnit.SECONDS);
        String stderr = errorFuture.get(3, TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            String errorMessage = stderr == null || stderr.isBlank() ? stdout : stderr;
            return ApiResponse.fail(errorMessage == null || errorMessage.isBlank() ? "JS采集脚本执行失败" : errorMessage);
        }

        Map<String, Object> data = objectMapper.readValue(stdout, new TypeReference<>() {});
        List<String> progressLines = new ArrayList<>(stderr == null
                ? List.of()
                : stderr.lines().filter(line -> !line.isBlank()).toList());
        String syncStart = "[CSV] 正在把本次采集结果追加到本地 Raw CSV……";
        publishProgress(progressSink, syncStart);
        progressLines.add(syncStart);
        RawCsvResult rawCsv = dataSyncCoordinator.execute(() -> {
            byte[] previousCsv = Files.exists(RAW_CSV_PATH) ? Files.readAllBytes(RAW_CSV_PATH) : null;
            try {
                return mergeRawCsv(data);
            } catch (Exception ex) {
                restoreRawCsv(previousCsv);
                throw ex;
            }
        });
        String syncComplete = "[CSV] 完成：追加 " + rawCsv.appendedCount()
                + " 条，累计 " + rawCsv.totalCount() + " 条。数据库未自动变更，请到 ETL 任务管理手动执行本地 ETL。";
        publishProgress(progressSink, syncComplete);
        progressLines.add(syncComplete);
        data.put("progress_lines", progressLines);
        data.put("progress_log", String.join(System.lineSeparator(), progressLines));
        data.put("run_time", OffsetDateTime.now().toString());
        data.put("csv_path", rawCsv.path().toAbsolutePath().toString());
        data.put("csv_count", rawCsv.totalCount());
        data.put("source_csv_count", rawCsv.sourceCount());
        data.put("append_count", rawCsv.appendedCount());
        data.put("database_sync_status", "NOT_RUN");
        data.put("etl_mode", "LOCAL_MANUAL_PENDING");
        data.put("next_step", "采集结果已追加到本地 CSV；如需写入本地 MySQL，请到 ETL 任务管理上传该 CSV 执行本地 ETL。");
        return ApiResponse.ok(data);
    }

    private void publishProgress(Consumer<String> progressSink, String line) {
        System.out.println(line);
        System.out.flush();
        if (progressSink == null) {
            return;
        }
        try {
            progressSink.accept(line);
        } catch (RuntimeException ignored) {
            // Database synchronization must finish even if the browser disconnects.
        }
    }

    private void restoreRawCsv(byte[] previousCsv) throws Exception {
        if (previousCsv == null) {
            Files.deleteIfExists(RAW_CSV_PATH);
            return;
        }
        Files.write(RAW_CSV_PATH, previousCsv, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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

    @SuppressWarnings("unchecked")
    private RawCsvResult mergeRawCsv(Map<String, Object> data) throws Exception {
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.getOrDefault("items", List.of());
        Files.createDirectories(RAW_CSV_PATH.getParent());
        LinkedHashMap<String, List<String>> mergedRows = readExistingRawRows();
        if (items.isEmpty()) {
            return new RawCsvResult(RAW_CSV_PATH, 0, mergedRows.size(), 0);
        }

        String sourcePlatform = text(data.get("platform")).trim().toUpperCase();
        if (sourcePlatform.isBlank()) {
            sourcePlatform = text(items.get(0).get("platform")).trim().toUpperCase();
        }
        for (Map<String, Object> item : items) {
            Map<String, Object> normalized = new LinkedHashMap<>(item);
            normalized.put("event_id", CANONICAL_EVENT_ID);
            normalized.put("event_name", CANONICAL_EVENT_NAME);
            normalized.put("platform", text(item.get("platform")).trim().toUpperCase());
            normalized.put("publish_time", sqlDateTime(item.get("publish_time"), false));
            normalized.put("crawl_time", sqlDateTime(item.get("crawl_time"), true));
            String sourceKeywords = text(normalized.get("keywords"));
            normalized.put("keywords", sourceKeywords.isBlank()
                    ? keywords(text(item.getOrDefault("content_text", item.get("title"))))
                    : sourceKeywords);
            List<String> cells = CSV_COLUMNS.stream().map(column -> text(normalized.get(column))).toList();
            mergedRows.put(UUID.randomUUID().toString(), cells);
        }

        StringBuilder builder = new StringBuilder("\uFEFF");
        builder.append(String.join(",", CSV_COLUMNS)).append("\n");
        for (List<String> cells : mergedRows.values()) {
            builder.append(toCsvLine(cells)).append("\n");
        }
        Files.writeString(RAW_CSV_PATH, builder.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String mergedPlatform = sourcePlatform;
        int sourceCount = (int) mergedRows.values().stream()
                .filter(cells -> mergedPlatform.equals(platformOf(cells)))
                .count();
        return new RawCsvResult(RAW_CSV_PATH, sourceCount, mergedRows.size(), items.size());
    }

    private LinkedHashMap<String, List<String>> readExistingRawRows() throws Exception {
        LinkedHashMap<String, List<String>> rows = new LinkedHashMap<>();
        if (!Files.exists(RAW_CSV_PATH) || Files.size(RAW_CSV_PATH) == 0) {
            return rows;
        }
        List<String> lines = Files.readAllLines(RAW_CSV_PATH, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return rows;
        }
        List<String> headerCells = parseCsvLine(stripBom(lines.get(0)));
        if (!headerCells.equals(CSV_COLUMNS)) {
            throw new IllegalStateException("活动 Raw 文件表头不是纯真实采集格式，请移除旧文件后重新采集：" + RAW_CSV_PATH.toAbsolutePath());
        }
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(lines.get(index));
            if (cells.size() != CSV_COLUMNS.size()) {
                throw new IllegalStateException("活动 Raw 文件第 " + (index + 1) + " 行字段数不正确：" + RAW_CSV_PATH.toAbsolutePath());
            }
            cells.set(CSV_COLUMNS.indexOf("event_id"), CANONICAL_EVENT_ID);
            cells.set(CSV_COLUMNS.indexOf("event_name"), CANONICAL_EVENT_NAME);
            cells.set(CSV_COLUMNS.indexOf("platform"), cells.get(CSV_COLUMNS.indexOf("platform")).trim().toUpperCase());
            rows.put(index + "::" + UUID.randomUUID(), cells);
        }
        return rows;
    }

    private String platformOf(List<String> cells) {
        return cells.get(CSV_COLUMNS.indexOf("platform")).trim().toUpperCase();
    }

    private int minLimit(String source) {
        return 10;
    }

    private int maxLimit(String source) {
        return 20;
    }

    private int defaultLimit(String source) {
        return 12;
    }

    private Map<String, Object> source(String code, String name, String platform, String url, String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", name);
        item.put("platform", platform);
        item.put("url", url);
        item.put("description", description);
        item.put("min_limit", minLimit(code));
        item.put("max_limit", maxLimit(code));
        item.put("default_limit", defaultLimit(code));
        return item;
    }

    private int number(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String csv(Object value) {
        return "\"" + text(value).replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private String stripBom(String value) {
        return value == null ? "" : value.replaceFirst("^\\uFEFF", "");
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
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
        return cells.stream().map(this::csv).reduce((left, right) -> left + "," + right).orElse("");
    }

    private String keywords(String value) {
        return Arrays.stream(value.replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ").trim().split("\\s+"))
                .filter(item -> item.length() >= 2)
                .limit(8)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private String sqlDateTime(Object value, boolean fallbackToNow) {
        try {
            if (value == null || String.valueOf(value).isBlank()) {
                return fallbackToNow ? OffsetDateTime.now().format(SQL_TIME) : "";
            }
            return OffsetDateTime.parse(String.valueOf(value))
                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                    .format(SQL_TIME);
        } catch (Exception ex) {
            return fallbackToNow ? OffsetDateTime.now().format(SQL_TIME) : "";
        }
    }

    private record RawCsvResult(Path path, int sourceCount, int totalCount, int appendedCount) {
    }

    private record CrawlSyncResult(RawCsvResult rawCsv, Map<String, Object> etlResult) {
    }

}
