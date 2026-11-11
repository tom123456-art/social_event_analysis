package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.analysis.HotspotTrendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.config.AnalysisDataset.EVENT_ID;

/** 热点趋势的请求入口，只调用本页面的业务服务。 */
@RestController
@RequestMapping("/api")
public class HotspotTrendController {
    private final HotspotTrendService service;

    public HotspotTrendController(HotspotTrendService service) {
        this.service = service;
    }

    // 热点趋势：顶部指标、互动热度趋势 → 类别热度趋势 → 平台热度峰值。
    @GetMapping("/analysis/hotspot-trend")
    public ApiResponse<Map<String, Object>> hotspotTrend() {
        // 调用趋势页业务，统一返回平台日度热度和类别热度两组明细。
        return ApiResponse.ok(service.hotspotTrend(EVENT_ID));
    }

    // 热点趋势：选定时段热门事件前三名。
    @GetMapping("/analysis/trend-content-rank")
    // 查询日期范围内高热度内容。
    public ApiResponse<List<Map<String, Object>>> trendContentRankByRange(
            // 接收日期选择器的起始日期，格式为 yyyy-MM-dd。
            @RequestParam("startDate") String startDate,
            // 接收日期选择器的结束日期，格式为 yyyy-MM-dd。
            @RequestParam("endDate") String endDate) {
        return ApiResponse.ok(service.trendContentRankByRange(EVENT_ID, startDate, endDate));
    }
}
