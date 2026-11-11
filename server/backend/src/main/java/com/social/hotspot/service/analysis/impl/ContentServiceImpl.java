package com.social.hotspot.service.analysis.impl;

import com.social.hotspot.service.analysis.ContentService;

import com.social.hotspot.mapper.ContentMapper;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.enrichContentRows;
import static com.social.hotspot.service.analysis.AnalysisValues.normalizeFilter;

/** 内容明细业务实现：规范筛选与分页参数，再查询内容列表和符合相同条件的总条数。 */
@Service
public class ContentServiceImpl implements ContentService {
    private final ContentMapper mapper;

    public ContentServiceImpl(ContentMapper mapper) {
        this.mapper = mapper;
    }

    /** 内容明细 / 列表与详情：每次按当前筛选直接查库，不使用页面汇总缓存。 */
    @Override
    public Map<String, Object> contentPage(String eventId, String query, String platform,
                                         String sentiment, String category, int page, int pageSize) {
        // 内容明细 / 顶部筛选：将“全部”和空白转为空筛选，限制每页 1 到 100 条并计算分页偏移。
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        String normalizedQuery = normalizeFilter(query);
        String normalizedPlatform = normalizeFilter(platform);
        String normalizedSentiment = normalizeFilter(sentiment);
        String normalizedCategory = normalizeFilter(category);
        int offset = (safePage - 1) * safePageSize;
        // 内容明细 / 列表和详情：按相同筛选查询当前页，并补齐平台、类别、正文和链接字段。
        List<Map<String, Object>> rows = mapper.contentPage(eventId, normalizedQuery, normalizedPlatform,
                normalizedSentiment, normalizedCategory, offset, safePageSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", enrichContentRows(rows));
        // 内容明细 / 分页：用完全相同的筛选统计总条数，返回当前页和每页数量。
        result.put("total", mapper.contentCount(eventId, normalizedQuery, normalizedPlatform,
                normalizedSentiment, normalizedCategory));
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        return result;
    }
}
