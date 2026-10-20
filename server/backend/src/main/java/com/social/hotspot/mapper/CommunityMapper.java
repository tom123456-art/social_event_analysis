package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

// 让 MyBatis 创建访问代理，SQL 位于对应映射文件。
@Mapper
// 定义互动与投稿数据访问接口，约定调用方式。
public interface CommunityMapper {
    // 查询近期互动。
    List<Map<String, Object>> interactions(@Param("limit") int limit);
    // 查询指定用户互动。
    List<Map<String, Object>> userInteractions(@Param("username") String username, @Param("limit") int limit);
    // 写入互动记录。
    int insertInteraction(@Param("item") Map<String, Object> item);
    // 删除指定互动。
    int deleteInteraction(@Param("id") Long id);
    // 查询近期投稿。
    List<Map<String, Object>> submissions(@Param("limit") int limit);
    // 查询指定用户投稿。
    List<Map<String, Object>> userSubmissions(@Param("username") String username, @Param("limit") int limit);
    // 写入投稿。
    int insertSubmission(@Param("item") Map<String, Object> item);
    // 更新投稿审核状态。
    int updateSubmissionStatus(@Param("id") Long id, @Param("status") String status);
    // 删除投稿。
    int deleteSubmission(@Param("id") Long id);
    // 查询指定事件内容。
    List<Map<String, Object>> contents(@Param("eventId") String eventId, @Param("limit") int limit);
}
