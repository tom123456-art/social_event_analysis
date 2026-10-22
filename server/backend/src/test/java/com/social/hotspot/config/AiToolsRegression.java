package com.social.hotspot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.exception.BusinessException;
import com.social.hotspot.service.ai.*;
import java.util.*;
import java.util.function.Consumer;

public class AiToolsRegression {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void reject(Runnable action) { try { action.run(); throw new AssertionError("Expected rejection"); } catch (BusinessException expected) { } }
    public static void main(String[] args) {
        ObjectMapper json = new ObjectMapper();
        AiQueryTools query = new AiQueryTools(null, "", "", "");
        var schema = query.schema();
        check(AiSqlPolicy.validate("SELECT platform, COUNT(*) AS total FROM ads_content_hot_rank GROUP BY platform ORDER BY total DESC", schema).endsWith("LIMIT 100"), "automatic row limit");
        check(AiSqlPolicy.validate("SELECT title FROM ads_content_hot_rank WHERE platform='WEIBO' AND publish_time >= '2026-01-01' LIMIT 5", schema).endsWith("LIMIT 5"), "safe filtered query");
        for (String sql : List.of(
                "DELETE FROM ads_content_hot_rank", "SELECT title FROM app_user", "SELECT password_hash FROM app_user_credential",
                "SELECT title FROM ads_content_hot_rank; SELECT title FROM ads_content_hot_rank", "SELECT sleep(5) FROM ads_content_hot_rank",
                "SELECT load_file('/etc/passwd') FROM ads_content_hot_rank", "SELECT title FROM ads_content_hot_rank INTO OUTFILE '/tmp/a'",
                "SELECT title FROM ads_content_hot_rank UNION SELECT username FROM app_user", "SELECT title FROM ads_content_hot_rank -- comment",
                "SELECT title FROM ads_content_hot_rank LIMIT 999", "SELECT author_name AS author_name FROM ads_content_hot_rank",
                "SELECT title AS author_name FROM ads_content_hot_rank WHERE author_name='secret'", "SELECT title FROM ads_content_hot_rank FOR UPDATE",
                "SELECT title FROM ads_content_hot_rank WHERE title=(SELECT username FROM app_user)")) reject(() -> AiSqlPolicy.validate(sql, schema));
        reject(() -> query.sql("SELECT title FROM ads_content_hot_rank"));
        reject(() -> query.query(Map.of("table", "app_user")));
        reject(() -> query.query(Map.of("table", "ads_keyword_rank", "platform", "WEIBO")));
        AiWebSearch search = new AiWebSearch("", json);
        reject(() -> search.search("test"));
        int[] queries = {0}, turns = {0};
        AiQueryTools fakeQuery = new AiQueryTools(null, "", "", "") {
            @Override public Object query(Map<String, Object> arguments) {
                queries[0]++; check(arguments.get("table").equals("ads_content_hot_rank"), "arguments reach query tool");
                return Map.of("matchedRows", 123, "rows", List.of());
            }
        };
        AiModelClient fakeModel = new AiModelClient(new AiProperties(), json) {
            @Override public Map<String, Object> toolTurn(AiModelSettings settings, List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
                if (turns[0]++ == 0) return Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "test-call", "type", "function", "function", Map.of("name", "query_analytics", "arguments", "{\"table\":\"ads_content_hot_rank\"}"))));
                check(messages.stream().anyMatch(item -> "tool".equals(item.get("role")) && item.get("content").toString().contains("123")), "model receives actual tool result");
                return Map.of("role", "assistant", "content", "ready");
            }
            @Override public String stream(AiModelSettings settings, List<? extends Map<String, ?>> messages, Consumer<String> delta) {
                delta.accept("123"); return "123";
            }
        };
        AiToolAgent agent = new AiToolAgent(fakeModel, fakeQuery, search, json);
        List<Map<String, Object>> sources = new ArrayList<>();
        List<String> statuses = new ArrayList<>(), deltas = new ArrayList<>();
        String answer = agent.answer(null, List.of(Map.of("role", "user", "content", "count")), false, sources, deltas::add, statuses::add);
        check(answer.equals("123") && queries[0] == 1 && deltas.equals(List.of("123")), "tool loop and final streaming");
        check(sources.size() == 1 && sources.getFirst().get("kind").equals("database"), "query provenance recorded");
        check(statuses.stream().anyMatch(item -> item.contains("数据库")), "query progress sent");
        reject(() -> agent.answer(null, List.of(Map.of("role", "user", "content", "search")), true, new ArrayList<>(), value -> {}, value -> {}));
        System.out.println("AI tool and SQL safety regressions passed.");
    }
}
