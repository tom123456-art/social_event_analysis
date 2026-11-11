package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 情感分析数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface SentimentAnalysisMapper {
    // 情感分析 / 微博情绪与新闻导向：查询通过过滤的正负面样本。
    List<Map<String, Object>> sentimentAnalysisRows(@Param("eventId") String eventId);
}
