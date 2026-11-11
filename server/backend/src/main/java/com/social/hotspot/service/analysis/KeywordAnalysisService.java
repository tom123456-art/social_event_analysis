package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

/** 关键词分析业务接口，实现位于 KeywordAnalysisServiceImpl。 */
public interface KeywordAnalysisService {
    /** 关键词分析：提供带关键词的新闻样本，前端据此生成词云、主题趋势及其他分析板块。 */
    List<Map<String, Object>> keywordAnalysis(String eventId);
}
