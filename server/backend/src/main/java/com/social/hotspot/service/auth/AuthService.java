package com.social.hotspot.service.auth;

import com.social.hotspot.exception.AuthException;
import com.social.hotspot.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
    private final String bootstrapStudent;

    public AuthService(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${AUTH_BOOTSTRAP_ADMIN_PASSWORD:123456}") String bootstrapAdmin,
            @Value("${AUTH_BOOTSTRAP_STUDENT_PASSWORD:123456}") String bootstrapStudent) {
        this.jdbc = jdbc; this.transactions = transactions;
        this.bootstrapAdmin = bootstrapAdmin; this.bootstrapStudent = bootstrapStudent;
    }

    @PostConstruct
    public void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("db/auth-schema.sql")).execute(jdbc.getDataSource());
        seedLegacy("admin", "ADMIN", bootstrapAdmin);
        seedLegacy("student", "USER", bootstrapStudent);
    }

    private void seedLegacy(String username, String role, String password) {
        if (password.isEmpty()) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT u.id FROM app_user u LEFT JOIN app_user_credential c ON c.user_id=u.id WHERE u.username=? AND u.role=? AND c.user_id IS NULL", username, role);
        for (Map<String, Object> row : rows) {
            jdbc.update("INSERT IGNORE INTO app_user_credential(user_id,password_hash) VALUES (?,?)", row.get("id"), passwords.encode(password));
        }
    }

    public Map<String, Object> register(Map<String, Object> item) {
        String username = username(item.get("username"));
        String password = newPassword(item.get("password"));
        if (!password.equals(text(item.get("confirmPassword")))) throw new BusinessException("两次输入的密码不一致。");
        String nickname = profileText(item.get("nickname"), "昵称", 100);
        if (nickname == null) throw new BusinessException("请填写昵称。");
        String phone = profileText(item.get("phone"), "手机号", 32);
        String email = profileText(item.get("email"), "邮箱", 120);
        String bio = profileText(item.get("bio"), "个人简介", 300);
        if (phone != null && !phone.matches("\\+?[0-9][0-9 ()-]{5,31}")) throw new BusinessException("手机号格式不正确。");
        if (email != null && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new BusinessException("邮箱格式不正确。");
        String hash = passwords.encode(password);
        try {
            return transactions.execute(status -> {
                jdbc.update("INSERT INTO app_user(username,nickname,phone,email,bio,role,status) VALUES (?,?,?,?,?,'USER','ENABLED')", username, nickname, phone, email, bio);
                Map<String, Object> user = jdbc.queryForMap("SELECT id,username,nickname,role,status FROM app_user WHERE username=?", username);
                jdbc.update("INSERT INTO app_user_credential(user_id,password_hash) VALUES (?,?)", user.get("id"), hash);
                return publicUser(user);
            });
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("该账号已存在，请更换账号或直接登录。");
        }
    }

    public Map<String, Object> login(Map<String, Object> item) {
        String username = text(item.get("username")).trim();
        String password = text(item.get("password"));
        if (username.isEmpty() || username.length() > 64 || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AuthException(401, "账号或密码不正确。");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT u.id,u.username,u.nickname,u.role,u.status,c.password_hash FROM app_user u JOIN app_user_credential c ON c.user_id=u.id WHERE u.username=?", username);
        Map<String, Object> user = rows.isEmpty() ? Map.of() : rows.getFirst();
        boolean valid = passwords.matches(password, text(user.getOrDefault("password_hash", dummyHash)));
        if (!valid || user.isEmpty()) throw new AuthException(401, "账号或密码不正确。");
        if (!"ENABLED".equals(user.get("status"))) throw new AuthException(403, "该账号已被禁用，请联系管理员。");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        return transactions.execute(status -> {
            // Serialize session creation with password changes for this user.
            Map<String, Object> locked = jdbc.queryForMap("SELECT password_hash FROM app_user_credential WHERE user_id=? FOR UPDATE", user.get("id"));
            if (!hashEquals(text(user.get("password_hash")), text(locked.get("password_hash")))) throw new AuthException(401, "账号密码已更新，请重新登录。");
            jdbc.update("DELETE FROM app_user_session WHERE expires_at <= CURRENT_TIMESTAMP");
            jdbc.update("INSERT INTO app_user_session(token_hash,user_id,expires_at) VALUES (?,?,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 24 HOUR))", digest(token), user.get("id"));
            Map<String, Object> result = new LinkedHashMap<>(publicUser(user));
            result.put("token", token); result.put("expiresIn", 86400);
            return result;
        });
    }

    public Map<String, Object> authenticate(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [0-9a-f]{64}")) throw new AuthException(401, "请先登录系统。");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT u.id,u.username,u.nickname,u.role,u.status FROM app_user_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP", digest(authorization.substring(7)));
        if (rows.isEmpty()) throw new AuthException(401, "登录已失效，请重新登录。");
        Map<String, Object> user = rows.getFirst();
        if (!"ENABLED".equals(user.get("status"))) throw new AuthException(403, "该账号已被禁用，请联系管理员。");
        return publicUser(user);
    }

    public void logout(String authorization) {
        if (authorization != null && authorization.matches("Bearer [0-9a-f]{64}"))
            jdbc.update("DELETE FROM app_user_session WHERE token_hash=?", digest(authorization.substring(7)));
    }

    public void changePassword(long userId, Map<String, Object> item) {
        String next = newPassword(item.get("password"));
        if (!next.equals(text(item.get("confirmPassword")))) throw new BusinessException("两次输入的密码不一致。");
        String old = text(item.get("oldPassword"));
        if (old.isEmpty() || old.getBytes(StandardCharsets.UTF_8).length > 72) throw new BusinessException("原密码不正确。");
        String encoded = passwords.encode(next);
        transactions.executeWithoutResult(status -> {
            Map<String, Object> record = jdbc.queryForMap("SELECT password_hash FROM app_user_credential WHERE user_id=? FOR UPDATE", userId);
            if (!passwords.matches(old, text(record.get("password_hash")))) throw new BusinessException("原密码不正确。");
            jdbc.update("UPDATE app_user_credential SET password_hash=? WHERE user_id=?", encoded, userId);
            jdbc.update("DELETE FROM app_user_session WHERE user_id=?", userId);
        });
    }

    public void setInitialPassword(long userId, String password) {
        jdbc.update("INSERT INTO app_user_credential(user_id,password_hash) VALUES (?,?)", userId, passwords.encode(newPassword(password)));
    }

    public void resetPassword(long userId, String password) {
        String encoded = passwords.encode(newPassword(password));
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO app_user_credential(user_id,password_hash) VALUES (?,?) ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash)", userId, encoded);
            jdbc.update("DELETE FROM app_user_session WHERE user_id=?", userId);
        });
    }

    public static String username(Object value) {
        String username = text(value).trim();
        if (!username.matches("[A-Za-z0-9_]{3,32}")) throw new BusinessException("账号须为三至三十二位字母、数字或下划线。");
        return username;
    }

    public static String newPassword(Object value) {
        String password = text(value);
        if (password.length() < 8 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72 || password.isBlank())
            throw new BusinessException("密码须为八至六十四位字符，中文密码总长度不能超过七十二字节。");
        return password;
    }

    public static Map<String, Object> publicUser(Map<String, Object> user) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("id", "username", "nickname", "status")) result.put(key, user.get(key));
        result.put("role", "ADMIN".equalsIgnoreCase(text(user.get("role"))) ? "admin" : "user");
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> current(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL);
        if (!(value instanceof Map)) throw new AuthException(401, "请先登录系统。");
        return (Map<String, Object>) value;
    }

    public static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static boolean hashEquals(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String profileText(Object value, String label, int limit) {
        String content = text(value).trim();
        if (content.codePointCount(0, content.length()) > limit) throw new BusinessException(label + "最多允许" + limit + "个字符。");
        return content.isEmpty() ? null : content;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
