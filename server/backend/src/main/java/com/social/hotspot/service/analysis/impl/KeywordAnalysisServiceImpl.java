package com.social.hotspot.service.analysis.impl;

import com.social.hotspot.service.analysis.KeywordAnalysisService;

import com.social.hotspot.mapper.KeywordAnalysisMapper;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.enrichContentRows;

/** 关键词分析业务实现：查询新闻样本并规范展示字段，主题归并和图表统计由前端完成。 */
@Service
public class KeywordAnalysisServiceImpl implements KeywordAnalysisService {
    private final KeywordAnalysisMapper mapper;

    public KeywordAnalysisServiceImpl(KeywordAnalysisMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Map<String, Object>> keywordAnalysis(String eventId) {
        // 关键词分析 / 词云、主题趋势、平台分布、话题动量、互动分布、观察清单：共用最近最多 10000 条带关键词的新闻。
        // 后端查询并规范内容字段；主题归并、词频和各图表的筛选计算仍由当前前端页面完成。
        return enrichContentRows(mapper.keywordAnalysisRows(eventId, 10_000));
    }
}
