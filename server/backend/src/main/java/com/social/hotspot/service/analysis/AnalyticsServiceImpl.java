package com.social.hotspot.service.analysis;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.aggregateHeatTrend;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.aggregateNoise;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.aggregatePlatformTimeline;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.aggregateRecentPlatformTimeline;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.aggregateSentimentTrend;
import static com.social.hotspot.service.analysis.CategoryPropagationAnalyzer.analyzeCategoryPropagation;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.categoryRank;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.enrichContentRows;
import static com.social.hotspot.service.analysis.AnalysisValues.normalizeCategoryLabel;
import static com.social.hotspot.service.analysis.AnalysisValues.normalizeFilter;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.normalizeOverview;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.recentRows;
import static com.social.hotspot.service.analysis.CategoryPropagationAnalyzer.SPREAD_PLATFORMS;
import static com.social.hotspot.service.analysis.AnalysisValues.timestampValue;

import com.social.hotspot.cache.AnalyticsCache;

import com.social.hotspot.mapper.AnalyticsMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分析服务实现：整理数据库原始结果并计算页面需要的派生指标。 */
@Service
public class AnalyticsServiceImpl implements AnalyticsService {
    // 定义并初始化统一事件标识。
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    // 集中保存数据库访问接口、概览缓存组件、事务模板。
    private final AnalyticsMapper mapper;
    private final AnalyticsCache analyticsCache;
    private final TransactionTemplate transactionTemplate;

    // 构造分析业务实现，接收所需组件并保存依赖。
    public AnalyticsServiceImpl(AnalyticsMapper mapper, AnalyticsCache analyticsCache,
                                // 接收事务管理器参数。
                                PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.analyticsCache = analyticsCache;
        // 使用传入的事务管理器创建事务模板。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    // 查询可展示的事件列表。
    public List<Map<String, Object>> events() {
        // 查询事件列表并转换为数据流。
        return mapper.events().stream()
                // 只保留统一事件标识对应的记录。
                .filter(row -> CANONICAL_EVENT_ID.equals(row.get("event_id"))
                        // 同时要求事件状态为启用。
                        && "ACTIVE".equals(row.get("status")))
                // 逐条处理事件记录并设置事件名称。
                .map(row -> {
                    // 创建事件记录，复制原行；向事件记录写入事件名称。
                    Map<String, Object> event = new LinkedHashMap<>(row);
                    event.put("event_name", "网络媒体热点事件传播态势与舆情分析");
                    // 返回事件记录。
                    return event;
                }).toList();
    }

    @Override
    // 组装热点概览并利用 Redis 缓存。
    public Map<String, Object> dashboard(String eventId) {
        // 读取事件的概览缓存。
        Map<String, Object> cached = analyticsCache.getDashboard(eventId);
        // 缓存命中即返回。
        if (cached != null) return cached;
        // 按时间与平台合并内容量和热度；按平台合并最早时间并计算分钟延迟；复制内容记录并补充页面需要的字段。
        List<Map<String, Object>> heatTrend = aggregateHeatTrend(mapper.heatTrend(eventId));
        List<Map<String, Object>> platformTimeline = aggregatePlatformTimeline(mapper.platformTimeline(eventId));
        List<Map<String, Object>> trendContentRank = enrichContentRows(mapper.trendContentRank(eventId, 600));
        // 查询平台日度类别热度；查询平台日度热度；统计最近七个自然日，以日均热度乘内容量累计热度。
        List<Map<String, Object>> platformCategoryHeat = mapper.platformCategoryHeat(eventId);
        List<Map<String, Object>> platformDailyHeat = mapper.platformDailyHeat(eventId);
        List<Map<String, Object>> recentPlatformTimeline = aggregateRecentPlatformTimeline(platformDailyHeat);
        // 识别类别热度达标先后。
        Map<String, Object> categoryPropagation = analyzeCategoryPropagation(
                // 读取小时类别数据，再以样本最新日期筛选最近七个自然日。
                recentRows(mapper.platformCategoryHourlyHeat(eventId), "time_bucket", 7));
        // 按时间、平台和正负面标签汇总样本数；复制内容记录并补充页面需要的字段；复制内容记录并补充页面需要的字段。
        List<Map<String, Object>> sentimentTrend = aggregateSentimentTrend(mapper.sentimentTrend(eventId));
        List<Map<String, Object>> contentRank = enrichContentRows(mapper.contentRank(eventId, 20));
        List<Map<String, Object>> realPublicContents = enrichContentRows(mapper.realPublicContents(eventId, 300));

        Map<String, Object> data = new LinkedHashMap<>();
        // 组装响应数据容器，包含：事件概览、热度时序、平台最早发布时间汇总。
        data.put("overview", normalizeOverview(mapper.overview(eventId), platformTimeline));
        data.put("heatTrend", heatTrend);
        data.put("platformTimeline", platformTimeline);
        // 组装响应数据容器，包含：近期平台汇总、趋势页面内容样本、平台类别热度。
        data.put("recentPlatformTimeline", recentPlatformTimeline);
        data.put("trendContentRank", trendContentRank);
        data.put("platformCategoryHeat", platformCategoryHeat);
        // 组装响应数据容器，包含：类别热度先后连线、类别热度首达时间轴、类别首达概览。
        data.put("categoryPropagationLinks", categoryPropagation.get("links"));
        data.put("categoryPropagationTimeline", categoryPropagation.get("timeline"));
        data.put("categoryPropagationSummary", categoryPropagation.get("summary"));
        // 组装响应数据容器，包含：平台日度热度、平台互动汇总、关键词排名。
        data.put("platformDailyHeat", platformDailyHeat);
        data.put("interaction", mapper.interaction(eventId));
        data.put("keywordRank", mapper.keywordRank(eventId, 30));
        // 组装响应数据容器，包含：情感时序、高热度内容、公开内容样本。
        data.put("sentimentTrend", sentimentTrend);
        data.put("contentRank", contentRank);
        data.put("realPublicContents", realPublicContents);
        // 组装响应数据容器，包含：类别热度排名、主题数量、主题排名。
        data.put("categoryRank", categoryRank(realPublicContents.isEmpty() ? contentRank : realPublicContents));
        data.put("topicCount", mapper.topicCount(eventId));
        data.put("topicRank", mapper.topicRank(eventId, 30));
        // 向响应数据容器写入噪声汇总；向响应数据容器写入最近批次；概览组装完成后写入 Redis，下次请求可直接读取。
        data.put("noiseSummary", aggregateNoise(mapper.noiseSummary(eventId)));
        data.put("latestBatch", mapper.latestBatch(eventId));
        analyticsCache.putDashboard(eventId, data);
        // 返回响应数据容器。
        return data;
    }

    @Override
    // 查询日期范围内高热度内容。
    public List<Map<String, Object>> trendContentRankByRange(String eventId, String startDate, String endDate) {
        // 检查开始日期或结束日期是否缺失。
        if (startDate == null || endDate == null || startDate.isBlank() || endDate.isBlank()
                // 同时检查开始日期是否晚于结束日期。
                || startDate.compareTo(endDate) > 0) {
            // 返回空列表。
            return List.of();
        }
        // 返回enrichContentRows(mapper.trendContentRankByRange(eventId, startDate, endDate, 3000))。
        return enrichContentRows(mapper.trendContentRankByRange(eventId, startDate, endDate, 3000));
    }

    @Override
    // 查询关键词分析内容样本。
    public List<Map<String, Object>> keywordAnalysis(String eventId) {
        // 返回enrichContentRows(mapper.keywordAnalysisRows(eventId, 10_000))。
        return enrichContentRows(mapper.keywordAnalysisRows(eventId, 10_000));
    }

    @Override
    // 按平台、类别与时间分页查询原始内容，核查统计依据。
    public Map<String, Object> propagationEvidence(String eventId, String platform, String category, String hour, int page) {
        // 只接受参与分析的固定新闻平台代码。
        if (!SPREAD_PLATFORMS.contains(platform)) throw new IllegalArgumentException("无效的平台");
        // 未填写类别时使用空值，否则转换为标准类别标签。
        String label = category == null || category.isBlank() ? null : normalizeCategoryLabel(category);
        // 已填写类别但无法转换为允许的标签时，抛出参数异常。
        if (category != null && !category.isBlank() && label == null) throw new IllegalArgumentException("无效的类别");
        // 以数据最新日期为基准筛选最近若干自然日；识别类别热度达标先后；读取汇总指标。
        List<Map<String, Object>> rows = recentRows(mapper.platformCategoryHourlyHeat(eventId), "time_bucket", 7);
        Map<String, Object> analysis = analyzeCategoryPropagation(rows);
        Map<String, Object> summary = (Map<String, Object>) analysis.get("summary");
        // 读取窗口起点或字节起点；读取窗口终点或字节终点。
        LocalDateTime start = (LocalDateTime) summary.get("window_start");
        LocalDateTime end = (LocalDateTime) summary.get("window_end");
        // 开始时间或结束时间无效时，返回空列表和零条记录。
        if (start == null || end == null) return Map.of("items", List.of(), "total", 0);
        // 日度汇总和小时类别信号采用同一七个自然日窗口。
        // 更新窗口起点或字节起点，新值为start.toLocalDate().atStartOfDay()。
        start = start.toLocalDate().atStartOfDay();
        // 更新窗口终点或字节终点，新值为end.toLocalDate().plusDays(1).atStartOfDay()。
        end = end.toLocalDate().plusDays(1).atStartOfDay();
        // 已指定小时参数时，进入小时范围处理分支。
        if (hour != null && !hour.isBlank()) {
            // 兼容日期格式，无法解析时用最大时间标记无效值。
            LocalDateTime selected = timestampValue(hour);
            // 检查所选小时是否解析失败或超出日期范围。
            if (selected.equals(LocalDateTime.MAX) || selected.isBefore(start) || !selected.isBefore(end)
                    // 同时检查所选时间是否不是整点。
                    || selected.getMinute() != 0 || selected.getSecond() != 0) {
                throw new IllegalArgumentException("无效的统计小时");
            }
            // 更新窗口起点或字节起点，新值为选择的小时；更新窗口终点或字节终点，新值为selected.plusHours(1)。
            start = selected;
            end = selected.plusHours(1);
        }
        // 将页码限制在 1 到 100000 之间；向处理结果写入当前页记录。
        int safePage = Math.min(100000, Math.max(1, page));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", enrichContentRows(mapper.propagationContents(eventId, platform, label, start, end, (safePage - 1) * 20, 20)));
        // 组装处理结果，包含：记录总数、页码、每页数量。
        result.put("total", mapper.propagationContentCount(eventId, platform, label, start, end));
        result.put("page", safePage);
        result.put("page_size", 20);
        // 组装处理结果，包含：窗口开始时间、窗口结束时间、analysis。
        result.put("window_start", start);
        result.put("window_end", end);
        result.put("analysis", analysis);
        return result;
    }

    @Override
    // 查询情感样本。
    public List<Map<String, Object>> sentimentAnalysis(String eventId) {
        // 返回查询情感分析样本。
        return mapper.sentimentAnalysisRows(eventId);
    }

    @Override
    // 按筛选条件分页查询内容。
    public Map<String, Object> contentPage(String eventId, String query, String platform,
                                            String sentiment, String category, int page, int pageSize) {
        // 计算限制后的页码；每页至少一条、最多一百条，限制单次查询规模；空值和全部选项转为空筛选条件，其余文本去首尾空格。
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        String normalizedQuery = normalizeFilter(query);
        // 空值和全部选项转为空筛选条件，其余文本去首尾空格；空值和全部选项转为空筛选条件，其余文本去首尾空格。
        String normalizedPlatform = normalizeFilter(platform);
        String normalizedSentiment = normalizeFilter(sentiment);
        // 空值和全部选项转为空筛选条件，其余文本去首尾空格；将从一开始的页码换算成数据库跳过记录数；按筛选条件分页查询内容。
        String normalizedCategory = normalizeFilter(category);
        int offset = (safePage - 1) * safePageSize;
        List<Map<String, Object>> rows = mapper.contentPage(eventId, normalizedQuery, normalizedPlatform,
                // 继续传入情感、类别、分页偏移量与页大小，只查询当前页。
                normalizedSentiment, normalizedCategory, offset, safePageSize);
        Map<String, Object> result = new LinkedHashMap<>();
        // 组装处理结果，包含：数据行、记录总数。
        result.put("rows", enrichContentRows(rows));
        result.put("total", mapper.contentCount(eventId, normalizedQuery, normalizedPlatform,
                // 统计总数时采用同样的情感与类别条件，保持分页口径一致。
                normalizedSentiment, normalizedCategory));
        // 组装处理结果，包含：页码、每页数量。
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        return result;
    }

    // 空值和全部选项转为空筛选条件，其余文本去首尾空格。

    /**
     * 将平台名称和旧版代码转换为统一平台代码。
     */
    // 将平台名称和旧版代码转换为统一平台代码。

    // 复制概览并按标准平台代码重新统计平台数。

    // 按时间与平台合并内容量和热度。

    // 按平台合并最早时间并计算分钟延迟。

    // 按时间、平台和正负面标签汇总样本数。

    // 按平台汇总内容、重复和噪声数。

    // 以数据最新日期为基准筛选最近若干自然日。

    // 统计最近七个自然日，以日均热度乘内容量累计热度。

    /**
     * 根据小时类别关注指数计算首达时间、平台先后关系和时间差。
     */
    // 识别类别热度达标先后。

    // 规范类别，排除泛化类别与无效数字。

    // 复制内容记录并补充页面需要的字段。

    // 按类别汇总内容量与热度，并选最高热度代表标题。

    // 根据关键词确定内容类别。

    // 中文类别转为英文代码。

    // 检查文本是否包含任一候选词。

    // 将字段值按长整数累加。

    // 将字段值按小数累加。

    // 安全转为长整数，空值和格式错误按零处理。

    // 安全转为小数，空值和格式错误按零处理。

    // 兼容日期格式，无法解析时用最大时间标记无效值。

    @Override
    // 读取后台事件、主题与批次概览。
    public Map<String, Object> adminOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        // 组装响应数据容器，包含：事件数量、主题数量、batches。
        data.put("eventCount", mapper.eventCount());
        data.put("topicCount", mapper.topicCount(CANONICAL_EVENT_ID));
        data.put("batches", mapper.batches(20));
        // 返回响应数据容器。
        return data;
    }

    @Override
    // 查询 ETL 批次列表，请求注解中的默认值和可选性决定输入规则。
    public List<Map<String, Object>> batches(int limit) {
        // 返回查询 ETL 批次列表。
        return mapper.batches(limit);
    }

    @Override
    // 按批次查询任务日志。
    public List<Map<String, Object>> taskLogs(String batchId) {
        // 返回按批次查询任务日志。
        return mapper.taskLogs(batchId);
    }

}
