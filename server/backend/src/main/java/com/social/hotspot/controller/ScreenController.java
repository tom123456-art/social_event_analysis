package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.analysis.ScreenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.config.AnalysisDataset.EVENT_ID;

/** 数据大屏的请求入口，只调用本页面的业务服务。 */
@RestController
@RequestMapping("/api")
public class ScreenController {
    private final ScreenService service;

    public ScreenController(ScreenService service) {
        this.service = service;
    }

    // 数据大屏：五源热度矩阵、传播热度脉冲、高热内容排行 → 数据质量概览、跨平台时序、关键词情报矩阵、内容流 → 热点类型构成、平台情绪结构、峰值传播强度。
    @GetMapping("/analysis/screen")
    public ApiResponse<Map<String, Object>> screen() {
        // 调用大屏业务，统一返回各板块共用的统计结果和内容样本。
        return ApiResponse.ok(service.screen(EVENT_ID));
    }
}
