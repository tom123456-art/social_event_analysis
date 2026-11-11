package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 平台扩散数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface PlatformSpreadMapper {
    // 平台扩散 / 平台汇总：查询各平台最早发布时间、内容量和热度。
    List<Map<String, Object>> platformTimeline(@Param("eventId") String eventId);

    // 平台扩散 / 日度热度：查询平台每日内容量与平均热度。
    List<Map<String, Object>> platformDailyHeat(@Param("eventId") String eventId);

    // 平台扩散 / 类别扩散：按平台、分钟、类别统计内容量与平均相对热度。
    List<Map<String, Object>> platformCategoryHourlyHeat(@Param("eventId") String eventId);

    // 平台扩散 / 查看依据：按平台、类别和时间窗口分页查询新闻。
    List<Map<String, Object>> propagationContents(@Param("eventId") String eventId, @Param("platform") String platform, @Param("category") String category, @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end, @Param("offset") int offset, @Param("limit") int limit);

    // 平台扩散 / 查看依据：按相同筛选条件统计新闻总数。
    long propagationContentCount(@Param("eventId") String eventId, @Param("platform") String platform, @Param("category") String category, @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);
}
