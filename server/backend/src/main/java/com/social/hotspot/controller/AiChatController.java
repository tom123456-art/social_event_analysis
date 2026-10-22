package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.ai.AiChatService;
import com.social.hotspot.service.ai.AiModelRegistry;
import com.social.hotspot.service.ai.AiModelClient;
import com.social.hotspot.service.ai.AiModelSettings;
import com.social.hotspot.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.social.hotspot.service.ai.AiToolAgent agent;
    private final AiChatService chats;
    private final AiModelRegistry models;
    private final AiModelClient client;
    public AiChatController(AiChatService chats, AiModelRegistry models, AiModelClient client) { this.chats = chats; this.models = models; this.client = client; }
    private long user(HttpServletRequest request) { return ((Number) AuthService.current(request).get("id")).longValue(); }
    @GetMapping("/config") public ApiResponse<?> config(HttpServletRequest request) { return ApiResponse.ok(Map.of("models", models.list(user(request)), "capabilities", agent.capabilities())); }
    @PostMapping("/models/test-and-save") public ApiResponse<?> add(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long user = user(request);
        AiModelSettings settings = AiModelSettings.from(body);
        if (models.list(user).stream().anyMatch(item -> settings.name().equalsIgnoreCase(item.get("name").toString())))
            throw new com.social.hotspot.exception.BusinessException("模型显示名称已存在，请更换名称。");
        client.answer(settings, List.of(Map.of("role", "user", "content", "请只回答：连接成功")));
        models.save(user, settings);
        return ApiResponse.ok(settings.publicView());
    }
    @GetMapping("/conversations") public ApiResponse<?> list(HttpServletRequest request) { return ApiResponse.ok(chats.list(user(request))); }
    @PostMapping("/conversations") public ApiResponse<?> create(HttpServletRequest request) { return ApiResponse.ok(chats.create(user(request))); }
    @GetMapping("/conversations/{id}") public ApiResponse<?> detail(@PathVariable("id") String id, HttpServletRequest request) { return ApiResponse.ok(chats.detail(user(request), id)); }
    @DeleteMapping("/conversations/{id}") public ApiResponse<?> delete(@PathVariable("id") String id, HttpServletRequest request) { chats.delete(user(request), id); return ApiResponse.ok(null); }
    @PostMapping("/conversations/{id}/messages") public ApiResponse<?> send(@PathVariable("id") String id, @RequestBody Map<String, String> body, HttpServletRequest request) { return ApiResponse.ok(chats.send(user(request), id, body.get("content"), body.get("modelId"))); }
    @PostMapping(value = "/conversations/{id}/messages/stream", produces = "text/event-stream")
    public void stream(@PathVariable("id") String id, @RequestBody Map<String, String> body,
                       HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        long user = user(request);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        var output = response.getOutputStream();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        java.util.function.BiConsumer<String, Object> event = (name, data) -> {
            try {
                output.write(("event: " + name + "\ndata: " + json.writeValueAsString(data) + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
            } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        };
        try {
            event.accept("start", Map.of("status", "generating"));
            var result = chats.send(user, id, body.get("content"), body.get("modelId"), text -> event.accept("delta", Map.of("content", text)),
                    "true".equals(body.get("webSearch")), message -> event.accept("status", Map.of("message", message)));
            event.accept("done", result);
        } catch (java.io.UncheckedIOException ex) {
            // Client disconnected; the service transaction rolls back incomplete turns.
        } catch (Exception ex) {
            String message = ex instanceof com.social.hotspot.exception.BusinessException ? ex.getMessage() : "生成回答失败，请稍后重试。";
            try { event.accept("error", Map.of("message", message)); } catch (java.io.UncheckedIOException ignored) { }
        }
    }
}
