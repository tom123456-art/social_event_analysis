package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.analysis.ContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.config.AnalysisDataset.EVENT_ID;

/** 内容明细的请求入口，只调用本页面的业务服务。 */
@RestController
@RequestMapping("/api")
public class ContentController {
    private final ContentService service;

    public ContentController(ContentService service) {
        this.service = service;
    }

    // 内容明细：筛选查询 → 内容明细列表 → 内容传播样本详情。
    @GetMapping("/analysis/content-page")
    // 按筛选条件分页查询内容。
    public ApiResponse<Map<String, Object>> contentPage(
            // 可选搜索词，用于内容列表的文本检索。
            @RequestParam(name = "query", required = false) String query,
            // 可选平台编码，用于限定内容来源。
            @RequestParam(name = "platform", required = false) String platform,
            // 可选情感标签，用于筛选正面或负面内容。
            @RequestParam(name = "sentiment", required = false) String sentiment,
            // 可选内容类别，与页面类别筛选项对应。
            @RequestParam(name = "category", required = false) String category,
            // 当前页码，未传时从第 1 页开始查询。
            @RequestParam(name = "page", defaultValue = "1") int page,
            // 每页条数，默认 5 条；业务层将其限制在 1 到 100 条之间。
            @RequestParam(name = "pageSize", defaultValue = "5") int pageSize) {
        return ApiResponse.ok(service.contentPage(EVENT_ID, query, platform, sentiment, category, page, pageSize));
    }
}
