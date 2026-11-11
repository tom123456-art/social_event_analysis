package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.analysis.KeywordAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.config.AnalysisDataset.EVENT_ID;

/** 关键词分析的请求入口，只调用本页面的业务服务。 */
@RestController
@RequestMapping("/api")
public class KeywordAnalysisController {
    private final KeywordAnalysisService service;

    public KeywordAnalysisController(KeywordAnalysisService service) {
        this.service = service;
    }

    // 关键词分析：主题关键词云 → 主题热度趋势 → 主题-平台分布 → 话题动量排行（新兴话题、衰退话题）→ 话题报道量与互动关注分布 → 主题关键词观察清单。
    @GetMapping("/analysis/keyword-analysis")
    // 调用关键词业务并统一返回新闻样本，词频、主题归并和图表统计由前端完成。
    public ApiResponse<List<Map<String, Object>>> keywordAnalysis() {
        return ApiResponse.ok(service.keywordAnalysis(EVENT_ID));
    }
}
