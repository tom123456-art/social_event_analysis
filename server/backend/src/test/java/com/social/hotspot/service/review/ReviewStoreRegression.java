package com.social.hotspot.service.review;
import com.social.hotspot.service.review.DataQualityReviewStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Isolated state-contract regression; deliberately never connects to the real database. */
public class ReviewStoreRegression {
    public static void main(String[] args) {
        var jdbc = new MemoryJdbc();
        var tx = new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        var store = new DataQualityReviewStore(jdbc, new ObjectMapper(), tx);
        String raw = store.encode(Map.of("platform", "TENCENT_NEWS", "content_id", "news-001",
                "title", "新闻原文标题", "content_text", "新闻原文正文", "like_count", "", "comment_count", "5"));
        jdbc.row.putAll(Map.of("id", 1L, "revision", 1L, "status", "PENDING", "apply_status", "WAITING",
                "source_key", "key", "source_version", "version", "original_data", raw, "corrected_data", raw));
        jdbc.row.put("manual_sentiment", "");
        jdbc.row.put("reasons", "[\"ID_FORMAT\"]");
        store.decide(1, Map.of("revision", 1, "status", "APPROVED", "manual_sentiment", "neutral",
                "corrected_data", Map.of("title", "新闻修正标题", "like_count", "")));
        if (!"WAITING".equals(jdbc.row.get("apply_status"))) throw new AssertionError("Approval auto-applied");
        if (!"".equals(store.object(jdbc.row.get("corrected_data")).get("like_count"))) throw new AssertionError("Blank replaced");
        expectFailure(() -> store.decide(1, Map.of("revision", 1, "status", "APPROVED", "corrected_data", Map.of())));
        expectFailure(() -> store.decide(1, Map.of("revision", 2, "status", "APPROVED", "corrected_data", Map.of("content_id", "other"))));
        expectFailure(() -> store.archive(1, "admin"));
        store.collect(List.of(Map.of("existing_id", 1, "reasons", List.of("LOW_CONFIDENCE"))), "batch");
        if (!jdbc.row.get("reasons").toString().contains("ID_FORMAT")
                || !jdbc.row.get("reasons").toString().contains("LOW_CONFIDENCE")) throw new AssertionError("Review reasons lost");
        var snapshot = store.reserveForApplication();
        if (!"PROCESSING".equals(jdbc.row.get("apply_status"))) throw new AssertionError("Snapshot not reserved");
        expectFailure(() -> store.reserveForApplication());
        expectFailure(() -> store.decide(1, Map.of("revision", 2, "corrected_data", Map.of())));
        store.finishApplication(snapshot, "batch", null);
        if (!"APPROVED".equals(jdbc.row.get("effective_decision"))) throw new AssertionError("Decision not effective");
        store.finishApplication(snapshot, null, "late failure");
        if (!"APPLIED".equals(jdbc.row.get("apply_status"))) throw new AssertionError("Successful job reversed");
        store.decide(1, Map.of("revision", 2, "status", "REJECTED", "corrected_data", Map.of()));
        // Before a new application, the previous effective decision is still used.
        if (!"APPROVED".equals(store.decisions(List.of()).getFirst().get("decision"))) throw new AssertionError("Unapplied edit leaked");
        var failed = store.reserveForApplication();
        store.finishApplication(failed, null, "failure");
        if (!"APPROVED".equals(store.decisions(List.of()).getFirst().get("decision"))) throw new AssertionError("Failed application replaced decision");
        var retry = store.reserveForApplication();
        store.finishApplication(retry, "retry-batch", null);
        store.archive(1, "admin");
        if (!Integer.valueOf(1).equals(jdbc.row.get("is_deleted"))) throw new AssertionError("Not soft archived");
        if (!"REJECTED".equals(store.decisions(List.of()).getFirst().get("decision"))) throw new AssertionError("Archived effective overlay lost");
        System.out.println("PASS: optimistic revisions, empty values, immutable IDs, snapshot locking, idempotent finish, soft archive and persistent overlays.");
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (BusinessException expected) { }
    }

    private static class MemoryJdbc extends JdbcTemplate {
        final Map<String, Object> row = new LinkedHashMap<>();

        @Override public Map<String, Object> queryForMap(String sql, Object... args) { return new LinkedHashMap<>(row); }
        @Override public <T> T queryForObject(String sql, Class<T> type) { return queryForObject(sql, type, new Object[0]); }
        @Override public List<Map<String, Object>> queryForList(String sql) { return queryForList(sql, new Object[0]); }
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            throw new AssertionError("Unexpected SQL: " + sql);
        }
        @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("effective_decision IS NOT NULL") && row.get("effective_decision") == null) return List.of();
            if (sql.contains("status IN") && !List.of("APPROVED", "REJECTED").contains(row.get("status"))) return List.of();
            if (sql.contains("apply_status IN") && !List.of("WAITING", "FAILED").contains(row.get("apply_status"))) return List.of();
            return List.of(new LinkedHashMap<>(row));
        }
        @Override public int update(String sql, Object... args) {
            if (sql.startsWith("UPDATE data_quality_review SET corrected_data")) {
                row.put("corrected_data", args[0]); row.put("manual_sentiment", args[1]); row.put("status", args[2]);
                row.put("apply_status", "WAITING"); row.put("revision", ((Number) row.get("revision")).longValue() + 1);
            } else if (sql.startsWith("UPDATE data_quality_review SET reasons")) row.put("reasons", args[0]);
            else if (sql.startsWith("UPDATE data_quality_review SET apply_status='PROCESSING'")) row.put("apply_status", "PROCESSING");
            else if (sql.startsWith("UPDATE data_quality_review SET apply_status='APPLIED'")) {
                if (!"PROCESSING".equals(row.get("apply_status")) || !row.get("revision").equals(args[5])) return 0;
                row.put("apply_status", "APPLIED"); row.put("effective_decision", args[0]);
                row.put("effective_data", args[1]); row.put("effective_sentiment", args[2]);
            } else if (sql.startsWith("UPDATE data_quality_review SET apply_status='FAILED'")) {
                if (!"PROCESSING".equals(row.get("apply_status")) || !row.get("revision").equals(args[2])) return 0;
                row.put("apply_status", "FAILED");
            }
            else if (sql.startsWith("UPDATE data_quality_review SET is_deleted=1")) row.put("is_deleted", 1);
            else throw new AssertionError("Unexpected SQL: " + sql);
            return 1;
        }
    }
}
