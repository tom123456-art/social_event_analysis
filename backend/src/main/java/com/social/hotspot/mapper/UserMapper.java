package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {
    List<Map<String, Object>> users();
    Map<String, Object> userById(@Param("id") Long id);
    int insertUser(@Param("item") Map<String, Object> item);
    int updateUser(@Param("id") Long id, @Param("item") Map<String, Object> item);
    int deleteUser(@Param("id") Long id);
    Map<String, Object> userByUsername(@Param("username") String username);
}