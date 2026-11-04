package com.social.hotspot.service.review;
import com.social.hotspot.integration.python.ReviewScriptRunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Service
public class ReviewInputPreparationService {
    private final DataQualityReviewStore store;
    private final ObjectMapper json;
    private final ThreadLocal<List<Map<String, Object>>> application = new ThreadLocal<>();
    @Value("${vm.etl.sentiment-python:python}") private String python;

    /** 构造质检输入准备服务，注入质检存储与 JSON 序列化器。 */
    public ReviewInputPreparationService(DataQualityReviewStore store, ObjectMapper json) { this.store = store; this.json = json; }

    /** 在预留快照作用域内执行动作，动作完成后自动结束应用流程。 */
    public <T> T withSnapshot(List<Map<String, Object>> snapshot, Callable<T> action) throws Exception {
        application.set(snapshot);
        try { return action.call(); } finally { application.remove(); }
    }

    /** 判断是否存在待应用的有效决策。 */
    public boolean hasEffectiveDecisions() { return !store.decisions(List.of()).isEmpty(); }

    /** 准备质检输入：预留决策、导出待处理队列、生成工作目录。 */
    public Prepared prepare(Path source, String batch) throws Exception {
        Path directory = Files.createTempDirectory("social-review-");
        try {
            Path decisions = directory.resolve("decisions.json");
            Path output = directory.resolve("input.csv");
            Path queue = directory.resolve("queue.jsonl");
            List<Map<String, Object>> snapshot = application.get() == null ? List.of() : application.get();
            Files.writeString(decisions, store.encode(store.decisions(snapshot)), StandardCharsets.UTF_8);
            String result = execute(List.of("--input", source.toString(), "--output", output.toString(),
                    "--queue", queue.toString(), "--decisions", decisions.toString()), directory);
            collect(queue, batch);
            Map<String, Object> summary = json.readValue(result.strip(), new TypeReference<>() {});
            Set<Long> matched = new HashSet<>();
            for (Object id : (List<?>) summary.get("matched_ids")) matched.add(((Number) id).longValue());
            for (var item : snapshot) {
                if (!matched.contains(((Number) item.get("id")).longValue()))
                    throw new BusinessException("审核记录的原始版本已不在当前 CSV 中，请重新核对来源；本次未应用");
            }
            return new Prepared(directory, output, snapshot);
        } catch (Exception e) {
            cleanup(directory);
            throw e;
        }
    }

    /** 收集低置信度情感样本，调用质检脚本并写入队列。 */
    public void collectLow(Prepared prepared, Path sentiment, String batch, double threshold) throws Exception {
        Path queue = prepared.directory().resolve("low-confidence.jsonl");
        execute(List.of("--input", prepared.input().toString(), "--sentiment", sentiment.toString(), "--queue", queue.toString(),
                "--threshold", Double.toString(threshold)), prepared.directory());
        collect(queue, batch);
    }

    /** 读取队列目录下的样本文件并组装为记录列表。 */
    private List<Map<String, Object>> collect(Path queue, String batch) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        try (var reader = Files.newBufferedReader(queue, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.isBlank()) entries.add(json.readValue(line, new TypeReference<>() {}));
                if (entries.size() >= 200) { store.collect(entries, batch); entries.clear(); }
            }
        }
        store.collect(entries, batch);
        // 已有内容编号的置信度记录仅来自已生效或正在应用的审核记录。
        List<Map<String, Object>> all = new ArrayList<>();
        try (var reader = Files.newBufferedReader(queue, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) if (!line.isBlank()) all.add(json.readValue(line, new TypeReference<>() {}));
        }
        return all;
    }

    /** 在指定目录执行质检脚本并返回日志输出。 */
    private String execute(List<String> arguments, Path directory) throws Exception { return ReviewScriptRunner.execute(python, arguments, directory); }

    /** 清理工作目录下的临时文件。 */
    private static void cleanup(Path directory) throws Exception {
        try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(directory);
    }

    public record Prepared(Path directory, Path input, List<Map<String, Object>> snapshot) implements AutoCloseable {
        @Override public void close() throws Exception { cleanup(directory); }
    }
}
