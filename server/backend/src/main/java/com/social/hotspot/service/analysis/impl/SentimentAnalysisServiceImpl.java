package com.social.hotspot.service.analysis.impl;

import com.social.hotspot.service.analysis.SentimentAnalysisService;

import com.social.hotspot.mapper.SentimentAnalysisMapper;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** 情感分析业务实现：读取已有情感结果供页面统计，本接口不重新运行情感模型。 */
@Service
public class SentimentAnalysisServiceImpl implements SentimentAnalysisService {
    private final SentimentAnalysisMapper mapper;

    public SentimentAnalysisServiceImpl(SentimentAnalysisMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Map<String, Object>> sentimentAnalysis(String eventId) {
        // 情感分析 / 微博公众情绪、新闻传播导向：查询正负面内容及其情感概率、互动、时间等字段。
        // 两个标签页共用样本；前端分别计算情感趋势、风险观察、传播关注指数及明细列表。
        return mapper.sentimentAnalysisRows(eventId);
    }
}
