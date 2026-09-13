package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface BusinessMapper {
    List<Map<String, Object>> users();

    Map<String, Object> userById(@Param("id") Long id);

    int insertUser(@Param("item") Map<String, Object> item);

    int updateUser(@Param("id") Long id, @Param("item") Map<String, Object> item);

    int deleteUser(@Param("id") Long id);

    Map<String, Object> userByUsername(@Param("username") String username);

    List<Map<String, Object>> interactions(@Param("limit") int limit);

    List<Map<String, Object>> userInteractions(@Param("username") String username, @Param("limit") int limit);

    int insertInteraction(@Param("item") Map<String, Object> item);

    int deleteInteraction(@Param("id") Long id);

    List<Map<String, Object>> submissions(@Param("limit") int limit);

    List<Map<String, Object>> userSubmissions(@Param("username") String username, @Param("limit") int limit);

    int insertSubmission(@Param("item") Map<String, Object> item);

    int updateSubmissionStatus(@Param("id") Long id, @Param("status") String status);

    int deleteSubmission(@Param("id") Long id);

    List<Map<String, Object>> contents(@Param("eventId") String eventId, @Param("limit") int limit);

}
