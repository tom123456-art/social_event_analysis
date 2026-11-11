package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

/** 情感分析业务接口，实现位于 SentimentAnalysisServiceImpl。 */
public interface SentimentAnalysisService {
    /** 情感分析：返回已计算情感标签和概率的样本，供前端分别统计微博情绪与新闻传播导向。 */
    List<Map<String, Object>> sentimentAnalysis(String eventId);
}
