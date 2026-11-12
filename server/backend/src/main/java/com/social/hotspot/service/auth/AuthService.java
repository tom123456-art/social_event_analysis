package com.social.hotspot.service.auth;

import com.social.hotspot.exception.AuthException;
import com.social.hotspot.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
    public static final String PRINCIPAL = AuthService.class.getName() + ".principal";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash = passwords.encode("unavailable-account-placeholder");
    private final String bootstrapAdmin;

    public AuthService(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${AUTH_BOOTSTRAP_ADMIN_PASSWORD:123456}") String bootstrapAdmin) {
        this.jdbc = jdbc; this.transactions = transactions;
        this.bootstrapAdmin = bootstrapAdmin;
    }

    @PostConstruct
    /** 仅为预先配置的唯一账号初始化缺失的密码凭据。 */
    public void initialize() {
        seedAccount();
    }

    private void seedAccount() {
        if (bootstrapAdmin.isEmpty()) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM app_user WHERE account_slot=1 AND password_hash IS NULL");
        for (Map<String, Object> row : rows) {
            jdbc.update("UPDATE app_user SET password_hash=?,password_updated_at=CURRENT_TIMESTAMP WHERE id=? AND password_hash IS NULL", passwords.encode(bootstrapAdmin), row.get("id"));
        }
    }

    /** 用户登录：校验用户名与密码，生成会话令牌并返回用户信息。 */
    public Map<String, Object> login(Map<String, Object> item) {
        String username = text(item.get("username")).trim();
        String password = text(item.get("password"));
        if (username.isEmpty() || username.length() > 64 || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AuthException(401, "账号或密码不正确。");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,username,nickname,status,password_hash FROM app_user WHERE username=? AND account_slot=1", username);
        Map<String, Object> user = rows.isEmpty() ? Map.of() : rows.getFirst();
        boolean valid = passwords.matches(password, text(user.getOrDefault("password_hash", dummyHash)));
        if (!valid || user.isEmpty()) throw new AuthException(401, "账号或密码不正确。");
        if (!"ENABLED".equals(user.get("status"))) throw new AuthException(403, "该账号已被禁用，请联系管理员。");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        return transactions.execute(status -> {
            // 会话创建与密码修改串行执行，避免改密后仍创建旧凭据会话。
            Map<String, Object> locked = jdbc.queryForMap("SELECT password_hash,status FROM app_user WHERE id=? AND account_slot=1 FOR UPDATE", user.get("id"));
            if (!hashEquals(text(user.get("password_hash")), text(locked.get("password_hash")))) throw new AuthException(401, "账号密码已更新，请重新登录。");
            if (!"ENABLED".equals(locked.get("status"))) throw new AuthException(403, "该账号已被禁用，请联系管理员。");
            jdbc.update("UPDATE app_user SET token_hash=?,session_created_at=CURRENT_TIMESTAMP,session_expires_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 24 HOUR) WHERE id=? AND account_slot=1", digest(token), user.get("id"));
            Map<String, Object> result = new LinkedHashMap<>(publicUser(user));
            result.put("token", token); result.put("expiresIn", 86400);
            return result;
        });
    }

    /** 校验会话令牌，返回当前用户信息，令牌无效时抛出认证异常。 */
    public Map<String, Object> authenticate(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [0-9a-f]{64}")) throw new AuthException(401, "请先登录系统。");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,username,nickname,status FROM app_user WHERE token_hash=? AND session_expires_at>CURRENT_TIMESTAMP AND account_slot=1", digest(authorization.substring(7)));
        if (rows.isEmpty()) throw new AuthException(401, "登录已失效，请重新登录。");
        Map<String, Object> user = rows.getFirst();
        if (!"ENABLED".equals(user.get("status"))) throw new AuthException(403, "该账号已被禁用，请联系管理员。");
        return publicUser(user);
    }

    /** 仅清除匹配的当前会话，旧令牌退出不能注销后来的登录。 */
    public void logout(String authorization) {
        if (authorization != null && authorization.matches("Bearer [0-9a-f]{64}"))
            jdbc.update("UPDATE app_user SET token_hash=NULL,session_created_at=NULL,session_expires_at=NULL WHERE token_hash=? AND account_slot=1", digest(authorization.substring(7)));
    }

    /** 修改密码：校验旧密码后更新为新密码。 */
    public void changePassword(long userId, Map<String, Object> item) {
        String next = newPassword(item.get("password"));
        if (!next.equals(text(item.get("confirmPassword")))) throw new BusinessException("两次输入的密码不一致。");
        String old = text(item.get("oldPassword"));
        if (old.isEmpty() || old.getBytes(StandardCharsets.UTF_8).length > 72) throw new BusinessException("原密码不正确。");
        String encoded = passwords.encode(next);
        transactions.executeWithoutResult(status -> {
            Map<String, Object> record = jdbc.queryForMap("SELECT password_hash FROM app_user WHERE id=? AND account_slot=1 FOR UPDATE", userId);
            if (!passwords.matches(old, text(record.get("password_hash")))) throw new BusinessException("原密码不正确。");
            jdbc.update("UPDATE app_user SET password_hash=?,password_updated_at=CURRENT_TIMESTAMP,token_hash=NULL,session_created_at=NULL,session_expires_at=NULL WHERE id=? AND account_slot=1", encoded, userId);
        });
    }

    /** 校验新密码强度并返回哈希后的密码，不符合要求时抛出异常。 */
    public static String newPassword(Object value) {
        String password = text(value);
        if (password.length() < 8 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72 || password.isBlank())
            throw new BusinessException("密码须为八至六十四位字符，中文密码总长度不能超过七十二字节。");
        return password;
    }

    /** 返回不含敏感字段的对外用户视图。 */
    public static Map<String, Object> publicUser(Map<String, Object> user) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("id", "username", "nickname", "status")) result.put(key, user.get(key));
        return result;
    }

    @SuppressWarnings("unchecked")
    /** 从请求属性中取出当前登录用户，未登录抛出认证异常。 */
    public static Map<String, Object> current(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL);
        if (!(value instanceof Map)) throw new AuthException(401, "请先登录系统。");
        return (Map<String, Object>) value;
    }

    /** 对令牌做 SHA-256 摘要，返回十六进制字符串。 */
    public static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    /** 常量时间比较两个哈希字符串，避免时序攻击。 */
    private static boolean hashEquals(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    /** 将任意值转为字符串，空值返回空字符串。 */
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
