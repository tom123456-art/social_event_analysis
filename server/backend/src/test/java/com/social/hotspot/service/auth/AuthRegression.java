package com.social.hotspot.service.auth;
import com.social.hotspot.service.user.UserProfileService;

import com.social.hotspot.security.AuthInterceptor;
import com.social.hotspot.controller.AuthController;
import com.social.hotspot.controller.UserProfileCommunityController;
import com.social.hotspot.exception.AuthException;
import com.social.hotspot.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
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
        store.users.put(1L, new LinkedHashMap<>(Map.of("id", 1L, "username", "admin", "nickname", "Account", "status", "ENABLED")));
        store.passwords.put(1L, new BCryptPasswordEncoder().encode("example-pass-123"));
        AuthService auth = new AuthService(store, new Transactions(store), "");
        reject(AuthException.class, () -> auth.login(Map.of("username", "admin", "password", "incorrect")));
        reject(AuthException.class, () -> auth.login(Map.of("username", "student", "password", "example-pass-123")));
        Map<String, Object> logged = auth.login(Map.of("username", "admin", "password", "example-pass-123"));
        check(!logged.containsKey("role") && !logged.containsKey("password_hash"), "login exposes neither roles nor credentials");
        String token = (String) logged.get("token"), header = "Bearer " + token;
        check(token.matches("[0-9a-f]{64}") && !store.sessions.containsKey(token), "only token digest is stored");
        check("admin".equals(auth.authenticate(header).get("username")), "account authenticates");
        reject(AuthException.class, () -> auth.authenticate("Bearer local-demo-token"));
        AuthInterceptor interceptor = new AuthInterceptor(auth);
        for (String path : List.of("/api/analysis/dashboard", "/api/admin/reviews", "/api/etl/run", "/api/front/profile")) {
            reject(AuthException.class, () -> interceptor.preHandle(request(path, null), null, null));
            check(interceptor.preHandle(request(path, header), null, null), "authenticated account can access " + path);
        }
        UserProfileService profile = new UserProfileService(null) {
            @Override public Map<String, Object> profile(String username) { return Map.of("username", username); }
            @Override public Map<String, Object> updateProfile(String username, Map<String, Object> fields) {
                check(fields.keySet().equals(Set.of("nickname", "phone", "email", "bio")), "profile uses an explicit field whitelist");
                return Map.of("username", username);
            }
        };
        HttpServletRequest front = request("/api/front/profile?username=student", header);
        interceptor.preHandle(front, null, null);
        UserProfileCommunityController controller = new UserProfileCommunityController(profile, null);
        check("admin".equals(controller.profile(front).data().get("username")), "query cannot select another account");
        var update = new com.social.hotspot.controller.ProfileUpdateRequest("Name", "", "", "");
        check("admin".equals(controller.updateProfile(update, front).data().get("username")), "update uses principal");
        var authors = new ArrayList<String>();
        var reviewStore = new com.social.hotspot.service.review.DataQualityReviewStore(null, null, null) {
            @Override public void decide(long reviewId, Map<String, Object> body) { authors.add(String.valueOf(body.get("reviewer"))); }
            @Override public void archive(long reviewId, String actor) { authors.add(actor); }
        };
        var reviews = new com.social.hotspot.controller.DataQualityReviewController(reviewStore, null);
        reviews.decide(1L, Map.of("reviewer", "forged"), front);
        reviews.archive(1L, front);
        check(authors.equals(List.of("admin", "admin")), "review and archive authors cannot be forged");
        store.users.get(1L).put("status", "DISABLED");
        reject(AuthException.class, () -> auth.authenticate(header));
        reject(AuthException.class, () -> auth.login(Map.of("username", "admin", "password", "example-pass-123")));
        store.users.get(1L).put("status", "ENABLED");
        reject(BusinessException.class, () -> AuthService.newPassword("short"));
        reject(BusinessException.class, () -> AuthService.newPassword("中".repeat(30)));
        reject(BusinessException.class, () -> auth.changePassword(1L, Map.of("oldPassword", "wrong", "password", "new-password-123", "confirmPassword", "new-password-123")));
        check(store.sessions.containsKey(AuthService.digest(token)), "failed password change retains sessions");
        auth.changePassword(1L, Map.of("oldPassword", "example-pass-123", "password", "new-password-123", "confirmPassword", "new-password-123"));
        reject(AuthException.class, () -> auth.authenticate(header));
        reject(AuthException.class, () -> auth.login(Map.of("username", "admin", "password", "example-pass-123")));
        String next = (String) auth.login(Map.of("username", "admin", "password", "new-password-123")).get("token");
        store.expired.add(AuthService.digest(next));
        reject(AuthException.class, () -> auth.authenticate("Bearer " + next));
        String last = (String) auth.login(Map.of("username", "admin", "password", "new-password-123")).get("token");
        auth.logout("Bearer " + last);
        reject(AuthException.class, () -> auth.authenticate("Bearer " + last));
        AuthController routes = new AuthController(auth);
        for (int i = 0; i < 20; i++) reject(AuthException.class, () -> routes.login(Map.of(), front));
        reject(AuthException.class, () -> routes.login(Map.of(), front));
        check(Arrays.stream(AuthController.class.getDeclaredMethods()).noneMatch(m -> m.getName().equals("register")), "registration is removed");
        var captured = new ArrayList<String>();
        var analytics = (com.social.hotspot.service.analysis.AnalyticsService) Proxy.newProxyInstance(AuthRegression.class.getClassLoader(), new Class[]{com.social.hotspot.service.analysis.AnalyticsService.class}, (proxy, method, values) -> {
            captured.add(String.valueOf(values[0]));
            return method.getReturnType() == List.class ? List.of() : Map.of();
        });
        var analysis = new com.social.hotspot.controller.AnalyticsController(analytics);
        analysis.dashboard(); analysis.keywordAnalysis(); analysis.sentimentAnalysis();
        analysis.contentPage(null, null, null, null, 1, 5);
        analysis.propagationEvidence("TENCENT_NEWS", null, null, 1);
        analysis.trendContentRankByRange("2026-09-01", "2026-09-30");
        check(captured.size() == 6 && captured.stream().allMatch(com.social.hotspot.config.AnalysisDataset.EVENT_ID::equals), "all analysis endpoints select the server dataset");
        System.out.println("PASS: single-account authentication, no roles/registration, profile ownership, analysis dataset, password/session lifecycle and throttling.");
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
        @Override public int update(String sql) { return update(sql, new Object[0]); }
        @Override public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO app_user_session")) sessions.put((String) args[0], ((Number) args[1]).longValue());
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
            Set<String> expired = new HashSet<>(store.expired);
            try { return action.doInTransaction(new SimpleTransactionStatus()); }
            catch (RuntimeException | Error ex) { store.users = users; store.passwords = passwords; store.sessions = sessions; store.expired = expired; throw ex; }
        }
    }
}
