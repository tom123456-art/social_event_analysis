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

    public ReviewInputPreparationService(DataQualityReviewStore store, ObjectMapper json) { this.store = store; this.json = json; }

    public <T> T withSnapshot(List<Map<String, Object>> snapshot, Callable<T> action) throws Exception {
        application.set(snapshot);
        try { return action.call(); } finally { application.remove(); }
    }

    public boolean hasEffectiveDecisions() { return !store.decisions(List.of()).isEmpty(); }

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

    public void collectLow(Prepared prepared, Path sentiment, String batch, double threshold) throws Exception {
        Path queue = prepared.directory().resolve("low-confidence.jsonl");
        execute(List.of("--input", prepared.input().toString(), "--sentiment", sentiment.toString(), "--queue", queue.toString(),
                "--threshold", Double.toString(threshold)), prepared.directory());
        collect(queue, batch);
    }

    private List<Map<String, Object>> collect(Path queue, String batch) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        try (var reader = Files.newBufferedReader(queue, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.isBlank()) entries.add(json.readValue(line, new TypeReference<>() {}));
                if (entries.size() >= 200) { store.collect(entries, batch); entries.clear(); }
            }
        }
        store.collect(entries, batch);
        // Existing-id confidence entries only occur for applied or application records.
        List<Map<String, Object>> all = new ArrayList<>();
        try (var reader = Files.newBufferedReader(queue, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) if (!line.isBlank()) all.add(json.readValue(line, new TypeReference<>() {}));
        }
        return all;
    }

    private String execute(List<String> arguments, Path directory) throws Exception { return ReviewScriptRunner.execute(python, arguments, directory); }

    private static void cleanup(Path directory) throws Exception {
        try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(directory);
    }

    public record Prepared(Path directory, Path input, List<Map<String, Object>> snapshot) implements AutoCloseable {
        @Override public void close() throws Exception { cleanup(directory); }
    }
}
