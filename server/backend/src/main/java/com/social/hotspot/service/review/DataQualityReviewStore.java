package com.social.hotspot.service.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DataQualityReviewStore {
    private static final Set<String> EDITABLE = Set.of("platform", "title", "content_text", "author_name",
            "publish_time", "crawl_time", "keywords", "category", "source_url", "image_url", "parent_content_id",
            "like_count", "comment_count", "repost_count", "favorite_count");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public DataQualityReviewStore(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
    }

    @PostConstruct
    public void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("db/review-schema.sql")).execute(jdbc.getDataSource());
        Integer provenance = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ads_content_hot_rank' AND COLUMN_NAME='sentiment_source'", Integer.class);
        if (provenance != null && provenance == 0) jdbc.execute("ALTER TABLE ads_content_hot_rank ADD COLUMN sentiment_source VARCHAR(20) DEFAULT 'MODEL'");
        // A process restart must not leave a permanently locked application job.
        // Deployment is single-backend; do not run multiple instances against this store.
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE data_quality_review SET apply_status='FAILED', apply_error=? WHERE apply_status='PROCESSING'",
                    "服务重启，任务未确认完成，请重试");
        });
    }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException("审核数据序列化失败", e); }
    }

    public Map<String, Object> object(Object value) {
        if (value == null) return new LinkedHashMap<>();
        try { return json.readValue(value.toString(), new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new BusinessException("审核数据格式不正确", e); }
    }

    public void collect(List<Map<String, Object>> entries, String batch) {
        tx.executeWithoutResult(s -> {
            for (Map<String, Object> item : entries) {
                if (item.containsKey("existing_id")) {
                    var existing = jdbc.queryForList("SELECT id,reasons FROM data_quality_review WHERE id=?", item.get("existing_id"));
                    if (!existing.isEmpty()) jdbc.update("UPDATE data_quality_review SET reasons=?,last_seen_batch=? WHERE id=?",
                            mergeReasons(existing.getFirst().get("reasons"), item.get("reasons")), batch, item.get("existing_id"));
                    continue;
                }
                Map<String, Object> raw = (Map<String, Object>) item.get("original_data");
                var previous = jdbc.queryForList("SELECT reasons FROM data_quality_review WHERE source_key=? AND source_version=?",
                        item.get("source_key"), item.get("source_version"));
                String reasons = mergeReasons(previous.isEmpty() ? null : previous.getFirst().get("reasons"), item.get("reasons"));
                jdbc.update("INSERT INTO data_quality_review (source_key,source_version,source_row,event_id,platform,content_id,title,original_data,corrected_data,reasons,last_seen_batch) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE reasons=VALUES(reasons),last_seen_batch=VALUES(last_seen_batch)",
                        item.get("source_key"), item.get("source_version"), item.get("source_row"), "public_rss_latest",
                        clip(raw.get("platform"), 50), clip(raw.get("content_id"), 128), clip(raw.get("title"), 300),
                        encode(raw), encode(raw), reasons, batch);
            }
        });
    }

    private String mergeReasons(Object previous, Object current) {
        var result = new java.util.LinkedHashSet<String>();
        try {
            if (previous != null) result.addAll(json.readValue(previous.toString(), new TypeReference<List<String>>() {}));
            if (current instanceof List<?> list) for (Object reason : list) result.add(reason.toString());
            return encode(result);
        } catch (Exception e) { throw new BusinessException("审核原因格式不正确", e); }
    }

    public Map<String, Object> list(String status, String apply, String query, int page, int size) {
        page = Math.max(1, page); size = Math.max(1, Math.min(100, size));
        String where = " WHERE is_deleted=0";
        List<Object> args = new ArrayList<>();
        if (!status.isBlank()) { where += " AND status=?"; args.add(status); }
        if (!apply.isBlank()) { where += " AND apply_status=?"; args.add(apply); }
        if (!query.isBlank()) {
            where += " AND (title LIKE ? OR content_id LIKE ? OR platform LIKE ?)";
            for (int i = 0; i < 3; i++) args.add("%" + query + "%");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM data_quality_review" + where, Long.class, args.toArray());
        List<Object> paging = new ArrayList<>(args); paging.add(size); paging.add((page - 1) * size);
        var items = jdbc.queryForList("SELECT id,platform,content_id,title,reasons,status,apply_status,reviewer,review_note,apply_error,updated_at FROM data_quality_review"
                + where + " ORDER BY id DESC LIMIT ? OFFSET ?", paging.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size,
                "waiting_count", jdbc.queryForObject("SELECT COUNT(*) FROM data_quality_review WHERE is_deleted=0 AND status IN ('APPROVED','REJECTED') AND apply_status IN ('WAITING','FAILED')", Long.class));
    }

    public Map<String, Object> detail(long id) {
        var row = jdbc.queryForMap("SELECT * FROM data_quality_review WHERE id=? AND is_deleted=0", id);
        row.put("original_data", object(row.get("original_data")));
        row.put("corrected_data", object(row.get("corrected_data")));
        return row;
    }

    public void decide(long id, Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", "PENDING"));
        if (!Set.of("PENDING", "APPROVED", "REJECTED").contains(status)) throw new BusinessException("审核状态不正确");
        String sentiment = String.valueOf(body.getOrDefault("manual_sentiment", ""));
        if (!Set.of("", "positive", "negative", "neutral").contains(sentiment)) throw new BusinessException("人工情感标签不正确");
        Object payload = body.get("corrected_data");
        if (!(payload instanceof Map<?, ?>)) throw new BusinessException("请提交修正后的字段");
        Map<String, Object> correction = (Map<String, Object>) payload;
        String actor = clip(body.getOrDefault("reviewer", "admin"), 64);
        tx.executeWithoutResult(s -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM data_quality_review WHERE id=? AND is_deleted=0 FOR UPDATE", id);
            if ("PROCESSING".equals(row.get("apply_status"))) throw new BusinessException("本记录正在应用，暂不能修改");
            if (!String.valueOf(row.get("revision")).equals(String.valueOf(body.get("revision"))))
                throw new BusinessException("记录已被其他操作修改，请刷新后重试");
            Map<String, Object> raw = object(row.get("original_data"));
            for (var entry : correction.entrySet()) {
                if (!EDITABLE.contains(entry.getKey()) && !String.valueOf(entry.getValue()).equals(String.valueOf(raw.get(entry.getKey()))))
                    throw new BusinessException("不能修改来源标识或原始内容 ID");
            }
            Map<String, Object> full = new LinkedHashMap<>(raw);
            correction.forEach((k, v) -> { if (EDITABLE.contains(k)) full.put(k, v == null ? "" : v.toString()); });
            if ("APPROVED".equals(status)) validate(full);
            String note = clip(body.get("review_note"), 1000);
            jdbc.update("UPDATE data_quality_review SET corrected_data=?,manual_sentiment=?,status=?,apply_status='WAITING',revision=revision+1,reviewer=?,review_note=?,reviewed_at=NOW(),apply_error=NULL WHERE id=?",
                    encode(full), sentiment, status, actor, note, id);
        });
    }

    private void validate(Map<String, Object> data) {
        if (!Set.of("TENCENT_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "NETEASE_NEWS", "WEIBO").contains(data.get("platform")))
            throw new BusinessException("请选择标准平台");
        for (String field : List.of("like_count", "comment_count", "repost_count", "favorite_count")) {
            String value = String.valueOf(data.getOrDefault(field, "")).trim();
            if (!value.isEmpty() && (!value.matches("[0-9]+") || value.length() > 18)) throw new BusinessException("互动量必须为空或非负整数");
        }
        if ((String.valueOf(data.getOrDefault("title", "")) + String.valueOf(data.getOrDefault("content_text", ""))).isBlank())
            throw new BusinessException("标题和正文不能同时为空");
    }

    public void archive(long id, String actor) {
        tx.executeWithoutResult(s -> {
            var row = jdbc.queryForMap("SELECT status,apply_status FROM data_quality_review WHERE id=? AND is_deleted=0 FOR UPDATE", id);
            if ("PROCESSING".equals(row.get("apply_status"))) throw new BusinessException("正在应用，不能归档");
            if (!"APPLIED".equals(row.get("apply_status"))) throw new BusinessException("请先审核并应用决定，再归档，避免丢失未生效的操作");
            jdbc.update("UPDATE data_quality_review SET is_deleted=1 WHERE id=?", id);
        });
    }

    public List<Map<String, Object>> reserveForApplication() {
        return tx.execute(s -> {
            var snapshot = jdbc.queryForList("SELECT id,revision,status,corrected_data,manual_sentiment FROM data_quality_review WHERE is_deleted=0 AND status IN ('APPROVED','REJECTED') AND apply_status IN ('WAITING','FAILED') ORDER BY id FOR UPDATE");
            if (snapshot.isEmpty()) throw new BusinessException("没有待应用的审核结果");
            for (var row : snapshot) {
                int updated = jdbc.update("UPDATE data_quality_review SET apply_status='PROCESSING',apply_error=NULL WHERE id=? AND revision=? AND apply_status IN ('WAITING','FAILED')", row.get("id"), row.get("revision"));
                if (updated != 1) throw new BusinessException("审核记录状态已变化，请刷新后重试");
            }
            return snapshot;
        });
    }

    public List<Map<String, Object>> decisions(List<Map<String, Object>> snapshot) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT * FROM data_quality_review WHERE effective_decision IS NOT NULL")) {
            result.put(((Number) row.get("id")).longValue(), decision(row, row.get("effective_decision"), row.get("effective_data"), row.get("effective_sentiment")));
        }
        for (var entry : snapshot) {
            long id = ((Number) entry.get("id")).longValue();
            var row = jdbc.queryForMap("SELECT * FROM data_quality_review WHERE id=?", id);
            result.put(id, decision(row, entry.get("status"), entry.get("corrected_data"), entry.get("manual_sentiment")));
        }
        return new ArrayList<>(result.values());
    }

    private Map<String, Object> decision(Map<String, Object> row, Object status, Object data, Object sentiment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id")); result.put("source_key", row.get("source_key")); result.put("source_version", row.get("source_version"));
        result.put("decision", status);
        Map<String, Object> full = object(data); full.keySet().retainAll(EDITABLE);
        result.put("corrected_data", full); result.put("manual_sentiment", sentiment == null ? "" : sentiment);
        return result;
    }

    public void finishApplication(List<Map<String, Object>> snapshot, String batch, String error) {
        tx.executeWithoutResult(s -> {
            for (var row : snapshot) {
                long id = ((Number) row.get("id")).longValue();
                if (error == null) {
                    jdbc.update("UPDATE data_quality_review SET apply_status='APPLIED',effective_decision=?,effective_data=?,effective_sentiment=?,applied_batch=?,apply_error=NULL WHERE id=? AND revision=? AND apply_status='PROCESSING'",
                            row.get("status"), row.get("corrected_data"), row.get("manual_sentiment"), batch, id, row.get("revision"));
                } else jdbc.update("UPDATE data_quality_review SET apply_status='FAILED',apply_error=? WHERE id=? AND revision=? AND apply_status='PROCESSING'", clip(error, 1000), id, row.get("revision"));
            }
        });
    }

    private static String clip(Object value, int length) {
        String text = value == null ? "" : value.toString();
        return text.substring(0, Math.min(length, text.length()));
    }
}
