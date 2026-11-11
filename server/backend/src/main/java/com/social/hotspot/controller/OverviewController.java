package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.analysis.OverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.config.AnalysisDataset.EVENT_ID;

/** 热点门户的请求入口，只调用本页面的业务服务。 */
@RestController
@RequestMapping("/api")
public class OverviewController {
    private final OverviewService service;

    public OverviewController(OverviewService service) {
        this.service = service;
    }

    // 热点门户：顶部指标 → 正在被关注的内容 → 数据来源 → 来源贡献排行 → 热点分类 → 关键词热度。
    @GetMapping("/analysis/overview")
    public ApiResponse<Map<String, Object>> overview() {
        // 使用固定分析集合调用门户业务，将各板块数据放入统一响应的 data 字段。
        return ApiResponse.ok(service.overview(EVENT_ID));
    }
}
