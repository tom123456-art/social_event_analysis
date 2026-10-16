package com.social.hotspot.service.auth;
import com.social.hotspot.service.user.UserProfileService;

import com.social.hotspot.security.AuthInterceptor;
import com.social.hotspot.controller.AuthController;
import com.social.hotspot.controller.UserProfileCommunityController;
import com.social.hotspot.exception.AuthException;
import com.social.hotspot.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.util.*;

public class AuthRegression {
    public static void main(String[] args) {
        Store store = new Store();
        AuthService auth = new AuthService(store, new Transactions(store), "", "");
        Map<String, Object> registration = new LinkedHashMap<>(Map.of("username", "reader", "nickname", "读者", "phone", "13800138000", "email", "reader@example.com", "bio", "新闻分析", "password", "example-pass-123", "confirmPassword", "example-pass-123", "role", "ADMIN", "status", "DISABLED"));
        Map<String, Object> registered = auth.register(registration);
        check("user".equals(registered.get("role")), "public registration cannot create admin");
        check(!registered.containsKey("password_hash") && !registered.containsKey("password"), "response must not expose password");
        long id = ((Number) registered.get("id")).longValue();
        check("读者".equals(registered.get("nickname")) && "ENABLED".equals(registered.get("status")), "registration keeps nickname and forces enabled status");
        check("13800138000".equals(store.users.get(id).get("phone")) && "reader@example.com".equals(store.users.get(id).get("email")) && "新闻分析".equals(store.users.get(id).get("bio")), "profile fields must be stored");
        String stored = store.passwords.get(id);
        check(stored.startsWith("$2a$") && new BCryptPasswordEncoder().matches("example-pass-123", stored), "bcrypt hash must verify");
        reject(BusinessException.class, () -> auth.register(registration));
        auth.register(Map.of("username", "reader2", "nickname", "第二位读者", "password", "example-pass-123", "confirmPassword", "example-pass-123"));
        check(store.users.get(2L).get("phone") == null && store.users.get(2L).get("email") == null && store.users.get(2L).get("bio") == null, "optional empty profile fields remain null");
        Map<String, Object> invalid = new LinkedHashMap<>(registration); invalid.put("username", "invalid");
        for (String field : List.of("nickname", "phone", "email", "bio")) {
            Object original = invalid.get(field);
            invalid.put(field, switch (field) { case "nickname" -> " "; case "phone" -> "abc"; case "email" -> "bad-email"; default -> "x".repeat(301); });
            reject(BusinessException.class, () -> auth.register(invalid)); invalid.put(field, original);
        }
        check(!store.passwords.get(2L).equals(stored), "same passwords need independent salts");
        reject(BusinessException.class, () -> auth.register(Map.of("username", "bad name", "password", "example-pass-123", "confirmPassword", "example-pass-123")));
        reject(BusinessException.class, () -> auth.register(Map.of("username", "other", "password", "short", "confirmPassword", "short")));
        reject(BusinessException.class, () -> AuthService.newPassword("中".repeat(30)));
        reject(BusinessException.class, () -> auth.register(Map.of("username", "other", "password", "example-pass-123", "confirmPassword", "mismatch")));
        store.failCredential = true;
        reject(DataAccessResourceFailureException.class, () -> auth.register(Map.of("username", "rollback", "nickname", "回滚", "password", "example-pass-123", "confirmPassword", "example-pass-123")));
        check(store.users.values().stream().noneMatch(row -> "rollback".equals(row.get("username"))), "failed credential creation must roll back user insertion");
        store.failCredential = false;
        reject(AuthException.class, () -> auth.login(Map.of("username", "reader", "password", "incorrect")));
        reject(AuthException.class, () -> auth.login(Map.of("username", "missing", "password", "example-pass-123")));
        Map<String, Object> logged = auth.login(Map.of("username", "reader", "password", "example-pass-123"));
        String token = (String) logged.get("token"), header = "Bearer " + token;
        check(token.matches("[0-9a-f]{64}") && !store.sessions.containsKey(token), "session must store only token digest");
        check("reader".equals(auth.authenticate(header).get("username")), "session authenticates registered account");
        reject(AuthException.class, () -> auth.authenticate("Bearer local-demo-token"));
        AuthInterceptor interceptor = new AuthInterceptor(auth);
        reject(AuthException.class, () -> interceptor.preHandle(request("/api/events", null), null, null));
        reject(AuthException.class, () -> interceptor.preHandle(request("/api/events/example/dashboard", null), null, null));
        check(interceptor.preHandle(request("/api/events/example/dashboard", header), null, null), "ordinary authenticated users can view event analysis");
        HttpServletRequest front = request("/api/front/profile", header);
        check(interceptor.preHandle(front, null, null), "authenticated user can read own profile");
        reject(AuthException.class, () -> interceptor.preHandle(request("/api/admin/reviews", header), null, null));
        reject(AuthException.class, () -> interceptor.preHandle(request("/api/etl/run", header), null, null));
        UserProfileService profile = (UserProfileService) Proxy.newProxyInstance(AuthRegression.class.getClassLoader(), new Class[]{UserProfileService.class}, (proxy, method, values) -> Map.of("username", values[0]));
        UserProfileCommunityController controller = new UserProfileCommunityController(profile, null);
        check("reader".equals(controller.profile(front).data().get("username")), "profile must use authenticated principal");
        reject(AuthException.class, () -> controller.updateProfile("admin", Map.of(), front));
        store.users.get(id).put("status", "DISABLED");
        reject(AuthException.class, () -> auth.authenticate(header));
        reject(AuthException.class, () -> auth.login(Map.of("username", "reader", "password", "example-pass-123")));
        store.users.get(id).put("status", "ENABLED");
        store.users.get(id).put("role", "ADMIN");
        check(interceptor.preHandle(request("/api/admin/reviews", header), null, null), "admin role is resolved from database, not username");
        store.users.get(id).put("role", "USER");
        reject(BusinessException.class, () -> auth.changePassword(id, Map.of("oldPassword", "wrong", "password", "new-password-123", "confirmPassword", "new-password-123")));
        check(store.sessions.containsKey(AuthService.digest(token)), "failed password change must retain sessions");
        auth.changePassword(id, Map.of("oldPassword", "example-pass-123", "password", "new-password-123", "confirmPassword", "new-password-123"));
        reject(AuthException.class, () -> auth.authenticate(header));
        reject(AuthException.class, () -> auth.login(Map.of("username", "reader", "password", "example-pass-123")));
        String next = (String) auth.login(Map.of("username", "reader", "password", "new-password-123")).get("token");
        store.expired.add(AuthService.digest(next));
        reject(AuthException.class, () -> auth.authenticate("Bearer " + next));
        String finalToken = (String) auth.login(Map.of("username", "reader", "password", "new-password-123")).get("token");
        auth.logout("Bearer " + finalToken);
        reject(AuthException.class, () -> auth.authenticate("Bearer " + finalToken));
        String resetToken = (String) auth.login(Map.of("username", "reader", "password", "new-password-123")).get("token");
        auth.resetPassword(id, "admin-reset-123");
        reject(AuthException.class, () -> auth.authenticate("Bearer " + resetToken));
        reject(AuthException.class, () -> auth.login(Map.of("username", "reader", "password", "new-password-123")));
        check(auth.login(Map.of("username", "reader", "password", "admin-reset-123")).containsKey("token"), "admin reset enables new password");
        store.passwords.remove(2L);
        auth.resetPassword(2L, "legacy-reset-123");
        check(auth.login(Map.of("username", "reader2", "password", "legacy-reset-123")).containsKey("token"), "admin reset enables legacy user without credentials");
        AuthController routes = new AuthController(auth);
        for (int i = 0; i < 20; i++) reject(BusinessException.class, () -> routes.register(Map.of(), front));
        reject(AuthException.class, () -> routes.register(Map.of(), front));
        System.out.println("PASS: registration, BCrypt salts, transactional rollback, login/session expiry/logout/password change, DB roles, disabled users, ownership and request throttling (isolated JDBC double).");
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void reject(Class<? extends Throwable> expected, Runnable action) {
        try { action.run(); } catch (Throwable ex) { if (expected.isInstance(ex)) return; throw new AssertionError("Unexpected failure", ex); }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    static HttpServletRequest request(String uri, String header) {
        Map<String, Object> attributes = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(AuthRegression.class.getClassLoader(), new Class[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getHeader" -> header;
            case "getMethod" -> "GET";
            case "getRequestURI" -> uri;
            case "getContextPath" -> "";
            case "getRemoteAddr" -> "127.0.0.1";
            case "setAttribute" -> { attributes.put((String) args[0], args[1]); yield null; }
            case "getAttribute" -> attributes.get(args[0]);
            default -> null;
        });
    }

    static class Store extends JdbcTemplate {
        Map<Long, Map<String, Object>> users = new LinkedHashMap<>();
        Map<Long, String> passwords = new HashMap<>();
        Map<String, Long> sessions = new HashMap<>();
        Set<String> expired = new HashSet<>();
        long next = 1; boolean failCredential;
        @Override public int update(String sql) { return update(sql, new Object[0]); }
        @Override public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO app_user(")) {
                if (users.values().stream().anyMatch(user -> user.get("username").toString().equalsIgnoreCase(args[0].toString()))) throw new DuplicateKeyException("username");
                users.put(next, new LinkedHashMap<>(Map.of("id", next, "username", args[0], "nickname", args[1], "role", "USER", "status", "ENABLED"))); next++;
                users.get(next - 1).put("phone", args[2]); users.get(next - 1).put("email", args[3]); users.get(next - 1).put("bio", args[4]);
            } else if (sql.startsWith("INSERT INTO app_user_credential")) {
                if (failCredential) throw new DataAccessResourceFailureException("test credential write failed");
                passwords.put(((Number) args[0]).longValue(), (String) args[1]);
            } else if (sql.startsWith("INSERT INTO app_user_session")) sessions.put((String) args[0], ((Number) args[1]).longValue());
            else if (sql.startsWith("UPDATE app_user_credential")) passwords.put(((Number) args[1]).longValue(), (String) args[0]);
            else if (sql.contains("WHERE token_hash=?")) sessions.remove(args[0]);
            else if (sql.contains("WHERE user_id=?")) sessions.entrySet().removeIf(entry -> entry.getValue().equals(((Number) args[0]).longValue()));
            else if (sql.contains("WHERE expires_at")) { expired.forEach(sessions::remove); expired.clear(); }
            else throw new AssertionError(sql);
            return 1;
        }
        @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FROM app_user_session")) {
                Long id = sessions.get(args[0]);
                return id == null || expired.contains(args[0]) ? List.of() : List.of(new LinkedHashMap<>(users.get(id)));
            }
            if (sql.contains("c.password_hash")) {
                Optional<Map<String, Object>> user = users.values().stream().filter(row -> row.get("username").toString().equalsIgnoreCase(args[0].toString())).findFirst();
                if (user.isEmpty() || !passwords.containsKey(((Number) user.get().get("id")).longValue())) return List.of();
                Map<String, Object> row = new LinkedHashMap<>(user.get()); row.put("password_hash", passwords.get(((Number) row.get("id")).longValue())); return List.of(row);
            }
            throw new AssertionError(sql);
        }
        @Override public Map<String, Object> queryForMap(String sql, Object... args) {
            if (sql.contains("FROM app_user_credential")) return Map.of("password_hash", passwords.get(((Number) args[0]).longValue()));
            if (sql.contains("FROM app_user WHERE username")) return users.values().stream().filter(row -> row.get("username").equals(args[0])).findFirst().map(LinkedHashMap::new).orElseThrow();
            throw new AssertionError(sql);
        }
    }
    static class Transactions extends TransactionTemplate {
        final Store store;
        Transactions(Store store) { this.store = store; }
        @Override public <T> T execute(TransactionCallback<T> action) {
            Map<Long, Map<String, Object>> users = new LinkedHashMap<>(); store.users.forEach((id, user) -> users.put(id, new LinkedHashMap<>(user)));
            Map<Long, String> passwords = new HashMap<>(store.passwords);
            Map<String, Long> sessions = new HashMap<>(store.sessions);
            Set<String> expired = new HashSet<>(store.expired); long next = store.next;
            try { return action.doInTransaction(new SimpleTransactionStatus()); }
            catch (RuntimeException | Error ex) { store.users = users; store.passwords = passwords; store.sessions = sessions; store.expired = expired; store.next = next; throw ex; }
        }
    }
}
