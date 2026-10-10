package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalyticsController {
    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }
    @GetMapping("/events")
    public ApiResponse<List<Map<String, Object>>> events() {
        return ApiResponse.ok(service.events());
    }

    @GetMapping("/events/{eventId}/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(@PathVariable("eventId") String eventId) {
        return ApiResponse.ok(service.dashboard(eventId));
    }

    @GetMapping("/events/{eventId}/keyword-analysis")
    public ApiResponse<List<Map<String, Object>>> keywordAnalysis(@PathVariable("eventId") String eventId) {
        return ApiResponse.ok(service.keywordAnalysis(eventId));
    }

    @GetMapping("/events/{eventId}/propagation-evidence")
    public ApiResponse<Map<String, Object>> propagationEvidence(
            @PathVariable("eventId") String eventId,
            @RequestParam("platform") String platform,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "hour", required = false) String hour,
            @RequestParam(name = "page", defaultValue = "1") int page) {
        return ApiResponse.ok(service.propagationEvidence(eventId, platform, category, hour, page));
    }

    @GetMapping("/events/{eventId}/trend-content-rank")
    public ApiResponse<List<Map<String, Object>>> trendContentRankByRange(
            @PathVariable("eventId") String eventId,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate) {
        return ApiResponse.ok(service.trendContentRankByRange(eventId, startDate, endDate));
    }

    @GetMapping("/events/{eventId}/sentiment-analysis")
    public ApiResponse<List<Map<String, Object>>> sentimentAnalysis(@PathVariable("eventId") String eventId) {
        return ApiResponse.ok(service.sentimentAnalysis(eventId));
    }

    @GetMapping("/events/{eventId}/content-page")
    public ApiResponse<Map<String, Object>> contentPage(
            @PathVariable("eventId") String eventId,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "sentiment", required = false) String sentiment,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "5") int pageSize) {
        return ApiResponse.ok(service.contentPage(eventId, query, platform, sentiment, category, page, pageSize));
    }

    @GetMapping("/admin/overview")
    public ApiResponse<Map<String, Object>> adminOverview() {
        return ApiResponse.ok(service.adminOverview());
    }


}
