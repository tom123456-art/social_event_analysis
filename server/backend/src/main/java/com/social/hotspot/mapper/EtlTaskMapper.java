package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** ETL 批次和日志数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface EtlTaskMapper {
    // ETL 批次和日志 / 批次列表：按开始时间倒序查询批次。
    List<Map<String, Object>> batches(@Param("limit") int limit);

    // ETL 批次和日志 / 批次日志：按开始时间查询任务日志。
    List<Map<String, Object>> taskLogs(@Param("batchId") String batchId);
}
