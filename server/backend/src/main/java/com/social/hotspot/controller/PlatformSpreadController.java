package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.analysis.PlatformSpreadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.config.AnalysisDataset.EVENT_ID;

/** 平台扩散的请求入口，只调用本页面的业务服务。 */
@RestController
@RequestMapping("/api")
public class PlatformSpreadController {
    private final PlatformSpreadService service;

    public PlatformSpreadController(PlatformSpreadService service) {
        this.service = service;
    }

    // 平台扩散：跨平台扩散网络 → 平台热度漏斗 → 类别热度响应时间线 → 类别扩散结论摘要。
    @GetMapping("/analysis/platform-spread")
    public ApiResponse<Map<String, Object>> platformSpread() {
        // 调用扩散页业务，统一返回网络、漏斗、时间线和结论摘要的数据。
        return ApiResponse.ok(service.platformSpread(EVENT_ID));
    }

    // 平台扩散：类别扩散结论摘要中的“查看依据”弹框。
    @GetMapping("/analysis/propagation-evidence")
    // 按平台、类别与时间分页查询原始内容，核查统计依据。
    public ApiResponse<Map<String, Object>> propagationEvidence(
            // 接收要核查的新闻平台编码，业务层校验是否属于五个分析平台。
            @RequestParam("platform") String platform,
            // 可选类别；不传时查询该平台在统计窗口内的全部类别。
            @RequestParam(name = "category", required = false) String category,
            // hour 为兼容参数名；传入实际分钟时只查询该分钟。
            @RequestParam(name = "hour", required = false) String hour,
            // 证据列表页码，默认第 1 页；业务层固定每页 20 条。
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "date", required = false) String date) {
        return ApiResponse.ok(service.propagationEvidence(EVENT_ID, platform, category, hour, page, date));
    }

    public ApiResponse<Map<String, Object>> propagationEvidence(String platform, String category, String hour, int page) {
        return propagationEvidence(platform, category, hour, page, null);
    }
}
