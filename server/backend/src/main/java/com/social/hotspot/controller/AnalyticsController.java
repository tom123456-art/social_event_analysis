// 声明包名 com.social.hotspot.controller.analysis。
package com.social.hotspot.controller;

// 引入统一接口响应对象。
import com.social.hotspot.common.ApiResponse;
// 引入分析业务接口。
import com.social.hotspot.service.analysis.AnalyticsService;
// 引入 GET 路由注解。
import org.springframework.web.bind.annotation.GetMapping;
// 引入路径参数绑定注解。
import org.springframework.web.bind.annotation.PathVariable;
// 引入路径映射注解。
import org.springframework.web.bind.annotation.RequestMapping;
// 引入查询参数绑定注解。
import org.springframework.web.bind.annotation.RequestParam;
// 引入 JSON 控制器注解。
import org.springframework.web.bind.annotation.RestController;

// 引入列表，保存多条数据。
import java.util.List;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

// 注册控制器，返回对象由 Spring 转为 JSON。
@RestController
// 设置控制器统一接口路径前缀。
@RequestMapping("/api")
// 定义分析结果控制器类。
public class AnalyticsController {
    // 保存业务服务。
    private final AnalyticsService service;

    // 构造分析结果控制器，接收所需组件并保存依赖。
    public AnalyticsController(AnalyticsService service) {
        // 把传入的业务服务保存到成员字段。
        this.service = service;
    // 结束当前方法。
    }
    // 将 GET 请求 /events 交给下面的方法。
    @GetMapping("/events")
    // 查询可展示的事件列表。
    public ApiResponse<List<Map<String, Object>>> events() {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.events());
    // 结束当前方法。
    }

    // 将 GET 请求 /events/{eventId}/dashboard 交给下面的方法。
    @GetMapping("/events/{eventId}/dashboard")
    // 组装热点概览并利用 Redis 缓存。
    public ApiResponse<Map<String, Object>> dashboard(@PathVariable("eventId") String eventId) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.dashboard(eventId));
    // 结束当前方法。
    }

    // 将 GET 请求 /events/{eventId}/keyword-analysis 交给下面的方法。
    @GetMapping("/events/{eventId}/keyword-analysis")
    // 查询关键词分析内容样本。
    public ApiResponse<List<Map<String, Object>>> keywordAnalysis(@PathVariable("eventId") String eventId) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.keywordAnalysis(eventId));
    // 结束当前方法。
    }

    // 将 GET 请求 /events/{eventId}/propagation-evidence 交给下面的方法。
    @GetMapping("/events/{eventId}/propagation-evidence")
    // 按平台、类别与时间分页查询原始内容，核查统计依据。
    public ApiResponse<Map<String, Object>> propagationEvidence(
            // 绑定参数 eventId，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @PathVariable("eventId") String eventId,
            // 绑定参数 "platform"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam("platform") String platform,
            // 绑定参数 name = "category", required = false，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "category", required = false) String category,
            // 绑定参数 name = "hour", required = false，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "hour", required = false) String hour,
            // 绑定参数 name = "page", defaultValue = "1"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "page", defaultValue = "1") int page) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.propagationEvidence(eventId, platform, category, hour, page));
    // 结束当前方法或多行参数定义。
    }

    // 将 GET 请求 /events/{eventId}/trend-content-rank 交给下面的方法。
    @GetMapping("/events/{eventId}/trend-content-rank")
    // 查询日期范围内高热度内容。
    public ApiResponse<List<Map<String, Object>>> trendContentRankByRange(
            // 绑定参数 eventId，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @PathVariable("eventId") String eventId,
            // 绑定参数 "startDate"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam("startDate") String startDate,
            // 绑定参数 "endDate"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam("endDate") String endDate) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.trendContentRankByRange(eventId, startDate, endDate));
    // 结束当前方法或多行参数定义。
    }

    // 将 GET 请求 /events/{eventId}/sentiment-analysis 交给下面的方法。
    @GetMapping("/events/{eventId}/sentiment-analysis")
    // 查询情感样本。
    public ApiResponse<List<Map<String, Object>>> sentimentAnalysis(@PathVariable("eventId") String eventId) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.sentimentAnalysis(eventId));
    // 结束当前方法。
    }

    // 将 GET 请求 /events/{eventId}/content-page 交给下面的方法。
    @GetMapping("/events/{eventId}/content-page")
    // 按筛选条件分页查询内容。
    public ApiResponse<Map<String, Object>> contentPage(
            // 绑定参数 eventId，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @PathVariable("eventId") String eventId,
            // 绑定参数 name = "query", required = false，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "query", required = false) String query,
            // 绑定参数 name = "platform", required = false，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "platform", required = false) String platform,
            // 绑定参数 name = "sentiment", required = false，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "sentiment", required = false) String sentiment,
            // 绑定参数 name = "category", required = false，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "category", required = false) String category,
            // 绑定参数 name = "page", defaultValue = "1"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "page", defaultValue = "1") int page,
            // 绑定参数 name = "pageSize", defaultValue = "5"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "pageSize", defaultValue = "5") int pageSize) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.contentPage(eventId, query, platform, sentiment, category, page, pageSize));
    // 结束当前方法或多行参数定义。
    }

    // 将 GET 请求 /admin/overview 交给下面的方法。
    @GetMapping("/admin/overview")
    // 读取后台事件、主题与批次概览。
    public ApiResponse<Map<String, Object>> adminOverview() {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(service.adminOverview());
    // 结束当前方法。
    }

// 结束类定义。
}
