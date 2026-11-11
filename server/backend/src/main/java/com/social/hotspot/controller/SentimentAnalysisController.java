package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.analysis.SentimentAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.config.AnalysisDataset.EVENT_ID;

/** 情感分析的请求入口，只调用本页面的业务服务。 */
@RestController
@RequestMapping("/api")
public class SentimentAnalysisController {
    private final SentimentAnalysisService service;

    public SentimentAnalysisController(SentimentAnalysisService service) {
        this.service = service;
    }

    // 情感分析 / 微博公众情绪：顶部指标 → 微博情感趋势 → 微博风险观察 → 微博评论明细。
    // 情感分析 / 新闻传播导向：顶部指标 → 新闻情感导向与热度趋势 → 传播关注研判 → 负面报道占比与传播热度 → 新闻关注明细。
    @GetMapping("/analysis/sentiment-analysis")
    // 调用情感业务并统一返回带标签、概率的样本，前端分别生成微博与新闻分析板块。
    public ApiResponse<List<Map<String, Object>>> sentimentAnalysis() {
        return ApiResponse.ok(service.sentimentAnalysis(EVENT_ID));
    }
}
