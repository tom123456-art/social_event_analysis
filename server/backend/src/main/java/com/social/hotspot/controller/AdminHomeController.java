package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.analysis.AdminHomeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.config.AnalysisDataset.EVENT_ID;

/** 后台首页的请求入口，只调用本页面的业务服务。 */
@RestController
@RequestMapping("/api")
public class AdminHomeController {
    private final AdminHomeService service;

    public AdminHomeController(AdminHomeService service) {
        this.service = service;
    }

    // 后台首页：顶部指标 → 数据来源、ETL成功率、平台热度排行 → 最近处理批次 → 高热内容审核、数据质量巡检。
    @GetMapping("/admin/overview")
    public ApiResponse<Map<String, Object>> adminOverview() {
        // 调用后台首页业务，统一返回即时查询的批次指标和允许缓存的图表数据。
        return ApiResponse.ok(service.adminOverview(EVENT_ID));
    }
}
