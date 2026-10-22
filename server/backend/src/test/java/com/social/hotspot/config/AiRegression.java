package com.social.hotspot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.exception.BusinessException;
import com.social.hotspot.service.ai.*;
import com.social.hotspot.service.analysis.AnalyticsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.nio.file.*;

public class AiRegression {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        AiModelClient parser = new AiModelClient(new AiProperties(), json);
        List<String> pieces = new ArrayList<>();
        String events = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        String assembled = parser.readStream(new java.io.BufferedReader(new java.io.StringReader(events)), pieces::add);
        check(assembled.equals("Hello world") && pieces.size() == 2, "stream delivers separate deltas");
        try {
            parser.readStream(new java.io.BufferedReader(new java.io.StringReader("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")), value -> {});
            throw new AssertionError("incomplete stream accepted");
        } catch (BusinessException expected) { }
        Store store = new Store(); Model model = new Model(json);
        AnalyticsService analytics = (AnalyticsService) Proxy.newProxyInstance(AnalyticsService.class.getClassLoader(), new Class<?>[]{AnalyticsService.class},
                (proxy, method, values) -> Map.of("overview", Map.of("content_count", 123), "keywordRank", List.of(Map.of("keyword", "test"))));
        TransactionTemplate transactions = new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) { return action.doInTransaction(new SimpleTransactionStatus()); }
        };
        AiProperties config = new AiProperties();
        config.setModel("qwen-plus"); config.setApiKey("secret"); config.setRequestUrl("https://example.invalid/v1/chat/completions");
        Path directory = Files.createTempDirectory("ai-model-regression-");
        AiModelRegistry registry = new AiModelRegistry(config, json, directory);
        AiChatService service = new AiChatService(store, transactions, model, analytics, json, registry);
        String id = service.create(1L).get("id").toString();
        check(service.list(1L).size() == 1 && service.list(2L).isEmpty(), "history is user scoped");
        reject(() -> service.detail(2L, id)); reject(() -> service.delete(2L, id));
        reject(() -> service.send(2L, id, "hello", "qwen")); reject(() -> service.send(1L, id, " ", "qwen"));
        reject(() -> service.send(1L, id, "x".repeat(2001), "qwen"));
        Map<String, Object> answer = service.send(1L, id, "first question", "qwen");
        check(((List<?>) answer.get("messages")).size() == 2, "save both messages");
        check(model.context.getFirst().get("content").contains("123"), "model sees analysis snapshot");
        check(model.context.getLast().get("content").equals("first question"), "model sees current question");
        check(answer.get("title").equals("first question"), "first question becomes title");
        check(((List<?>) ((Map<?, ?>) ((List<?>) answer.get("messages")).get(1)).get("sources")).size() == 2, "sources survive serialization");
        service.send(1L, id, "follow up", "qwen");
        check(model.context.size() == 4 && model.context.get(1).get("content").equals("first question"), "follow up includes history");
        model.fail = true; reject(() -> service.send(1L, id, "failed turn", "qwen"));
        check(((List<?>)service.detail(1L, id).get("messages")).size() == 4, "model failure saves no half turn"); model.fail = false;
        for (int i = 0; i < 10; i++) service.send(1L, id, "question " + i, "qwen");
        check(model.context.size() == 22, "context limited to twenty historical messages");
        AiModelSettings custom = AiModelSettings.from(new LinkedHashMap<>(Map.of("name", "A model", "model", "a-chat", "apiKey", "custom-secret", "requestUrl", "https://example.invalid/v1/chat/completions")));
        registry.save(1L, custom);
        check(registry.list(1L).size() == 2 && registry.list(2L).size() == 1, "models are user scoped");
        check(!new String(Files.readAllBytes(directory.resolve("models.enc")), java.nio.charset.StandardCharsets.UTF_8).contains("custom-secret"), "saved config is encrypted");
        AiModelRegistry reloaded = new AiModelRegistry(config, json, directory);
        check(reloaded.resolve(1L, custom.id()).apiKey().equals("custom-secret"), "configuration survives restart");
        reject(() -> reloaded.resolve(2L, custom.id()));
        reject(() -> registry.save(1L, custom));
        service.send(1L, id, "use another model", custom.id());
        check(model.selected.id().equals(custom.id()), "selected model used for actual request");
        check(service.detail(1L, id).get("modelId").equals(custom.id()), "conversation remembers selected model");
        String publicConfig = registry.list(1L).toString();
        check(!publicConfig.contains("secret") && !publicConfig.contains("example.invalid"), "public config exposes no secret or endpoint");
        reject(() -> AiModelSettings.from(Map.of()));
        reject(() -> new AiModelSettings("bad", "bad", "", "bad", "https://127.0.0.1/chat", "secret", 90, null).validatedUri());
        service.delete(1L, id); check(service.list(1L).isEmpty(), "delete removes history");
        reject(() -> new AiModelClient(new AiProperties(), json).answer(List.of()));
        Files.delete(directory.resolve("models.enc")); Files.delete(directory.resolve("models.key")); Files.delete(directory);
        System.out.println("AI regressions passed.");
    }
    static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    static void reject(Runnable action) { try { action.run(); throw new AssertionError("Expected rejection"); } catch (BusinessException expected) { } }
    static class Model extends AiModelClient {
        List<Map<String, String>> context; boolean fail; AiModelSettings selected;
        Model(ObjectMapper json) { super(new AiProperties(), json); }
        @Override public String answer(AiModelSettings settings, List<Map<String, String>> values) {
            selected = settings; context = values; if (fail) throw new BusinessException("模型服务暂时不可用。"); return "test answer";
        }
    }
    static class Store extends JdbcTemplate {
        Map<String, Object> conversation;
        @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("WHERE user_id")) return conversation != null && conversation.get("user_id").equals(args[0]) ? List.of(new LinkedHashMap<>(conversation)) : List.of();
            return conversation != null && conversation.get("id").equals(args[0]) && conversation.get("user_id").equals(args[1]) ? List.of(new LinkedHashMap<>(conversation)) : List.of();
        }
        @Override public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO ai_conversation")) conversation = new LinkedHashMap<>(Map.of("id", args[0], "user_id", args[1], "title", "new", "messages", "[]", "modelId", "qwen"));
            else if (sql.startsWith("UPDATE ai_conversation")) { conversation.put("title", args[0]); conversation.put("messages", args[1]); conversation.put("modelId", args[2]); }
            else if (sql.startsWith("DELETE")) {
                if (conversation == null || !conversation.get("id").equals(args[0]) || !conversation.get("user_id").equals(args[1])) return 0;
                conversation = null;
            } else throw new AssertionError(sql);
            return 1;
        }
    }
}
