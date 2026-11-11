package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 内容明细数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface ContentMapper {
    // 内容明细 / 内容明细列表：按搜索条件查询当前页。
    List<Map<String, Object>> contentPage(@Param("eventId") String eventId, @Param("query") String query, @Param("platform") String platform, @Param("sentiment") String sentiment, @Param("category") String category, @Param("offset") int offset, @Param("pageSize") int pageSize);

    // 内容明细 / 内容明细分页：按相同搜索条件统计总条数。
    long contentCount(@Param("eventId") String eventId, @Param("query") String query, @Param("platform") String platform, @Param("sentiment") String sentiment, @Param("category") String category);
}
