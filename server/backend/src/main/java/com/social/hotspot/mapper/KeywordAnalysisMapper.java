package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 关键词分析数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface KeywordAnalysisMapper {
    // 关键词分析 / 关键词分析各板块：查询带关键词的新闻样本。
    List<Map<String, Object>> keywordAnalysisRows(@Param("eventId") String eventId, @Param("limit") int limit);
}
