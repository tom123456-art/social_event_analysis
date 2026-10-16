// 声明包名 com.social.hotspot.service.user。
package com.social.hotspot.service.user;
// 复用账号及密码校验与凭据更新规则。

// 引入用户资料数据库映射接口。
import com.social.hotspot.mapper.UserMapper;
// 引入 Spring 业务组件注解。
import org.springframework.stereotype.Service;
// 引入列表，保存多条数据。
import java.util.List;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

// 注册为 Spring 业务组件，供构造器注入。
@Service
// 定义用户管理服务类。
public class UserManagementService {
    // 保存数据库映射接口。
    private final UserMapper mapper;
    // 保存认证服务，用于初始化和重置用户密码。
    private final com.social.hotspot.service.auth.AuthService auth;
    // 构造用户管理服务，接收所需组件并保存依赖。
    public UserManagementService(UserMapper mapper, com.social.hotspot.service.auth.AuthService auth) { this.mapper = mapper; this.auth = auth; }
    // 查询用户列表；角色只保留 ADMIN 与 USER；peek 在流遍历时修改每条用户记录的角色。
    public List<Map<String, Object>> users() { return mapper.users().stream().peek(item -> item.put("role", normalizeRole(item.get("role")))).toList(); }
    // 规范角色、补充默认状态后新增用户；角色只保留 ADMIN 与 USER；缺少状态时默认为 ENABLED。
    @org.springframework.transaction.annotation.Transactional
    // 接收用户字段，新增账号并在同一事务内保存初始密码。
    public Map<String, Object> saveUser(Map<String, Object> item) {
        // 校验新增密码并提取可用于初始化凭据的明文值。
        String password = com.social.hotspot.service.auth.AuthService.newPassword(item.get("password"));
        // 校验并规范用户名，拒绝不符合账号规则的输入。
        item.put("username", com.social.hotspot.service.auth.AuthService.username(item.get("username")));
        // 将角色限制为 ADMIN 或 USER，未提供状态时设为 ENABLED。
        item.put("role", normalizeRole(item.get("role"))); item.putIfAbsent("status", "ENABLED");
        // 插入用户资料，并由 Mapper 回填生成的用户 ID。
        try { mapper.insertUser(item); }
        // 将数据库唯一键冲突转换为账号已存在的业务提示。
        catch (org.springframework.dao.DuplicateKeyException ex) { throw new com.social.hotspot.exception.BusinessException("该账号已存在。"); }
        // 使用新增 ID 保存密码凭据，失败时与资料插入一起回滚。
        auth.setInitialPassword(((Number) item.get("id")).longValue(), password);
        // 从响应映射移除密码，避免向调用方回传明文。
        item.remove("password");
        // 返回已回填用户 ID 且移除密码的用户资料。
        return item;
    }
    // 更新用户资料；角色只保留 ADMIN 与 USER；缺少状态时默认为 ENABLED。
    @org.springframework.transaction.annotation.Transactional
    // 按用户 ID 更新资料，并在提供新密码时同步更新凭据。
    public Map<String, Object> updateUser(Long id, Map<String, Object> item) {
        // 先确认账号存在，避免对不存在的 ID 执行修改。
        if (mapper.userById(id) == null) throw new com.social.hotspot.exception.BusinessException("账号不存在。");
        // 读取可选新密码，未提供时使用空串表示保持原密码。
        String password = String.valueOf(item.getOrDefault("password", ""));
        // 仅在提供非空密码时校验密码规则。
        if (!password.isEmpty()) com.social.hotspot.service.auth.AuthService.newPassword(password);
        // 移除资料映射中的密码，防止进入普通资料更新或响应。
        item.remove("password");
        // 规范角色并为缺失的状态设置 ENABLED。
        item.put("role", normalizeRole(item.get("role"))); item.putIfAbsent("status", "ENABLED");
        // 更新该用户的资料字段，密码由认证服务独立处理。
        mapper.updateUser(id, item);
        // 非空新密码通过认证服务重置，失败时回滚本次事务。
        if (!password.isEmpty()) auth.resetPassword(id, password);
        // 重新读取数据库资料，返回实际保存后的字段。
        return mapper.userById(id);
    }
    // 删除用户；数据库受影响行数大于零才视为删除或更新成功。
    public boolean deleteUser(Long id) { return mapper.deleteUser(id) > 0; }
    // 去除角色首尾空格并忽略大小写，只有 ADMIN 为管理员，其余统一为 USER。
    private String normalizeRole(Object role) { return "ADMIN".equalsIgnoreCase(String.valueOf(role == null ? "" : role).trim()) ? "ADMIN" : "USER"; }
// 结束类定义。
}
