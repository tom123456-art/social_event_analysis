package com.social.hotspot.service.user;

import com.social.hotspot.mapper.UserMapper;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
// 定义个人资料服务类。
public class UserProfileService {
    // 保存数据库映射接口。
    private final UserMapper mapper;
    // 构造个人资料服务，接收所需组件并保存依赖。
    public UserProfileService(UserMapper mapper) { this.mapper = mapper; }
    // 查询已注册账号的个人资料。
    public Map<String, Object> profile(String username) {
        // 按用户名查询资料。
        Map<String, Object> user = mapper.userByUsername(username);
        // 返回资料白名单，不包含密码凭据和内部账号约束字段。
        if (user != null) return user;
        // 查询失败时不自动创建新账号。
        throw new com.social.hotspot.exception.BusinessException("账号不存在，请检查系统账号配置。");
    }
    // 账号身份和启用状态不属于可编辑资料。
    public Map<String, Object> updateProfile(String username, Map<String, Object> item) {
        // 使用认证账号定位资料，保存后重新读取实际值。
        Map<String, Object> user = profile(username);
        mapper.updateUser(number(user.get("id")).longValue(), item); return mapper.userByUsername(username);
    }
    // 编号已是数值时直接返回，否则解析为长整数；非法文本会抛出异常。
    private Number number(Object value) { if (value instanceof Number number) return number; return Long.parseLong(String.valueOf(value)); }
}
