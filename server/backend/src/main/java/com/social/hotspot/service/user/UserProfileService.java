// 声明包名 com.social.hotspot.service.user。
package com.social.hotspot.service.user;

// 引入用户资料数据库映射接口。
import com.social.hotspot.mapper.UserMapper;
// 引入 Spring 业务组件注解。
import org.springframework.stereotype.Service;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

// 注册为 Spring 业务组件，供构造器注入。
@Service
// 定义个人资料服务类。
public class UserProfileService {
    // 保存数据库映射接口。
    private final UserMapper mapper;
    // 构造个人资料服务，接收所需组件并保存依赖。
    public UserProfileService(UserMapper mapper) { this.mapper = mapper; }
    // 查询已注册账号的个人资料。
    public Map<String, Object> profile(String username) {
        // 按用户名查询资料，保存为用户资料。
        Map<String, Object> user = mapper.userByUsername(username);
        // 返回资料时规范角色。
        if (user != null) { user.put("role", normalizeRole(user.get("role"))); return user; }
        // 未找到已注册账号时拒绝资料请求，不自动创建用户。
        throw new com.social.hotspot.exception.BusinessException("账号不存在，请先注册。");
    // 结束当前方法。
    }
    // 更新个人资料并保留原角色与状态。
    public Map<String, Object> updateProfile(String username, Map<String, Object> item) {
        // 查询当前账号资料。
        Map<String, Object> user = profile(username);
        // 保留原用户角色和状态，个人资料更新不能在此直接更改这两个字段。
        item.put("role", user.getOrDefault("role", "USER")); item.put("status", user.getOrDefault("status", "ENABLED"));
        // 按用户编号更新资料，再按用户名重新读取数据库结果。
        mapper.updateUser(number(user.get("id")).longValue(), item); return mapper.userByUsername(username);
    // 结束当前方法。
    }
    // 去除角色首尾空格并忽略大小写，只有 ADMIN 为管理员，其余统一为 USER。
    private String normalizeRole(Object role) { return "ADMIN".equalsIgnoreCase(String.valueOf(role == null ? "" : role).trim()) ? "ADMIN" : "USER"; }
    // 编号已是数值时直接返回，否则解析为长整数；非法文本会抛出异常。
    private Number number(Object value) { if (value instanceof Number number) return number; return Long.parseLong(String.valueOf(value)); }
// 结束类定义。
}
