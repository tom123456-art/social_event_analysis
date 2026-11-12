package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.config.AnalysisDataset;
import com.social.hotspot.mapper.ContentMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 后台内容只读查询，不提供站内投稿或互动功能。 */
@RestController
@RequestMapping("/api/admin")
public class AdminContentController {
    private final ContentMapper mapper;

    public AdminContentController(ContentMapper mapper) { this.mapper = mapper; }

    @GetMapping("/contents")
    public ApiResponse<List<Map<String, Object>>> contents(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ApiResponse.ok(mapper.rankedContents(AnalysisDataset.EVENT_ID, Math.max(1, Math.min(limit, 300))));
    }
}
