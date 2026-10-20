package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.review.DataQualityReviewStore;
import com.social.hotspot.service.review.ReviewApplicationService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/reviews")
public class DataQualityReviewController {
    // 提供审核队列查询和审核决定持久化；提供异步应用审核结果的任务入口。
    private final DataQualityReviewStore store;
    private final ReviewApplicationService application;
    // 注入审核存储和应用任务服务。
    public DataQualityReviewController(DataQualityReviewStore store, ReviewApplicationService application) { this.store = store; this.application = application; }
    // 按审核状态、生效状态和关键词分页查询审核队列。
    @GetMapping public ApiResponse<Map<String, Object>> list(
            @RequestParam(name="status", defaultValue="") String status, @RequestParam(name="applyStatus", defaultValue="") String applyStatus,
            @RequestParam(name="query", defaultValue="") String query, @RequestParam(name="page", defaultValue="1") int page,
            @RequestParam(name="size", defaultValue="20") int size) {
        // 返回带分页信息的审核列表。
        return ApiResponse.ok(store.list(status, applyStatus, query, page, size));
    }
    // 读取指定记录的原始内容、修正内容和审核状态。
    @GetMapping("/{id}") public ApiResponse<Map<String, Object>> detail(@PathVariable("id") long id) { return ApiResponse.ok(store.detail(id)); }
    // 保存修正字段、人工情感和审核决定。
    @PutMapping("/{id}") public ApiResponse<Boolean> decide(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        // 将审核请求交给存储服务校验并持久化。
        store.decide(id, body); return ApiResponse.ok(true);
    }
    // 归档审核记录，不物理删除其审计信息。
    @DeleteMapping("/{id}") public ApiResponse<Boolean> archive(@PathVariable("id") long id) { store.archive(id, "admin"); return ApiResponse.ok(true); }
    // 提交应用审核结果的后台重建任务。
    @PostMapping("/apply") public ApiResponse<Map<String, Object>> apply() { return ApiResponse.ok(application.submit()); }
    // 查询最近一次审核应用任务，供前端轮询进度。
    @GetMapping("/jobs/latest") public ApiResponse<Map<String, Object>> latest() { return ApiResponse.ok(application.latestJob()); }
}
