package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

// 让 MyBatis 创建访问代理，SQL 位于对应映射文件。
@Mapper
// 定义用户数据访问接口，约定调用方式。
public interface UserMapper {
    // 更新用户资料。
    int updateUser(@Param("id") Long id, @Param("item") Map<String, Object> item);
    // 按用户名查询资料。
    Map<String, Object> userByUsername(@Param("username") String username);
}
