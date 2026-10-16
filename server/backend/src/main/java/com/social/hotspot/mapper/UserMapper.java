// 声明包名 com.social.hotspot.mapper.user。
package com.social.hotspot.mapper;

// 引入 MyBatis 接口注解。
import org.apache.ibatis.annotations.Mapper;
// 引入 SQL 参数命名注解。
import org.apache.ibatis.annotations.Param;

// 引入列表，保存多条数据。
import java.util.List;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

// 让 MyBatis 创建访问代理，SQL 位于对应映射文件。
@Mapper
// 定义用户数据访问接口，约定调用方式。
public interface UserMapper {
    // 查询用户列表。
    List<Map<String, Object>> users();
    // 按编号查询用户。
    Map<String, Object> userById(@Param("id") Long id);
    // 新增用户并返回受影响行数。
    int insertUser(@Param("item") Map<String, Object> item);
    // 更新用户资料。
    int updateUser(@Param("id") Long id, @Param("item") Map<String, Object> item);
    // 删除用户。
    int deleteUser(@Param("id") Long id);
    // 按用户名查询资料。
    Map<String, Object> userByUsername(@Param("username") String username);
// 结束接口定义。
}
