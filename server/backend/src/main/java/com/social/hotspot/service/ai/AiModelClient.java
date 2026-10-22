package com.social.hotspot.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.config.AiProperties;
import com.social.hotspot.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
public class AiModelClient {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ai-stream-deadline"); thread.setDaemon(true); return thread;
    });
    private final AiProperties config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public AiModelClient(AiProperties config, ObjectMapper json) { this.config = config; this.json = json; }
    public String answer(List<Map<String, String>> messages) {
        return answer(new AiModelSettings("qwen", "通义千问", "阿里云百炼", config.getModel(), config.getRequestUrl(), config.getApiKey(), config.getTimeoutSeconds(), null), messages);
    }
    public String answer(AiModelSettings settings, List<Map<String, String>> messages) {
        if (!settings.configured()) throw new BusinessException("当前模型尚未配置密钥，请配置后端默认模型或添加其他模型。");
        try {
            URI uri = settings.validatedUri();
            Map<String, Object> body = new LinkedHashMap<>(Map.of("model", settings.model(), "messages", messages, "stream", false, "max_tokens", 4096));
            if (settings.temperature() != null) body.put("temperature", settings.temperature());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Authorization", "Bearer " + settings.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new BusinessException("模型认证失败，请检查后端密钥及模型权限。");
            if (response.statusCode() == 429) throw new BusinessException("模型服务请求过于频繁或额度不足，请稍后重试。");
            if (response.statusCode() == 400) throw new BusinessException("模型拒绝了请求，请检查模型 ID 和可选参数是否受支持。");
            if (response.statusCode() == 404) throw new BusinessException("模型或接口不存在，请检查模型 ID 和完整请求地址。");
            if (response.statusCode() != 200) throw new BusinessException("模型服务暂时不可用，请稍后重试。");
            String content = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isEmpty() || content.length() > 100000) throw new BusinessException("模型未返回有效回答，请重新尝试。");
            return content;
        } catch (BusinessException ex) { throw ex;
        } catch (HttpTimeoutException ex) { throw new BusinessException("模型响应超时，请稍后重试。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new BusinessException("模型请求已中断，请重新尝试。");
        } catch (Exception ex) { throw new BusinessException("连接模型服务失败，请检查后端请求地址和网络配置。"); }
    }
    public String stream(AiModelSettings settings, List<? extends Map<String, ?>> messages, Consumer<String> delta) {
        if (!settings.configured()) throw new BusinessException("当前模型尚未配置密钥。");
        try {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("model", settings.model(), "messages", messages, "stream", true, "max_tokens", 4096));
            if (settings.temperature() != null) body.put("temperature", settings.temperature());
            HttpRequest request = HttpRequest.newBuilder(settings.validatedUri()).timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Authorization", "Bearer " + settings.apiKey()).header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() == 401 || response.statusCode() == 403) throw new BusinessException("模型认证失败，请检查密钥及权限。");
                if (response.statusCode() == 429) throw new BusinessException("模型请求过于频繁或额度不足，请稍后重试。");
                if (response.statusCode() != 200) throw new BusinessException("模型服务请求失败，请检查模型名称和请求地址。");
                ScheduledFuture<?> deadline = DEADLINES.schedule(() -> { try { input.close(); } catch (IOException ignored) { } }, settings.timeoutSeconds(), TimeUnit.SECONDS);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    return readStream(reader, delta);
                } finally { deadline.cancel(false); }
            }
        } catch (BusinessException ex) { throw ex;
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new BusinessException("模型请求已中断。");
        } catch (Exception ex) { throw new BusinessException("模型连接中断或响应超时，请重新尝试。"); }
    }
    public Map<String, Object> toolTurn(AiModelSettings settings, List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (!settings.configured()) throw new BusinessException("当前模型尚未配置密钥。");
        try {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("model", settings.model(), "messages", messages,
                    "tools", tools, "tool_choice", "auto", "stream", false, "max_tokens", 2048));
            HttpRequest request = HttpRequest.newBuilder(settings.validatedUri()).timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Authorization", "Bearer " + settings.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) throw new BusinessException("模型请求过于频繁或额度不足，请稍后重试。");
            if (response.statusCode() != 200) throw new BusinessException("模型工具调用失败，请确认该模型支持工具调用且密钥有效。");
            var root = json.readTree(response.body());
            if (!Set.of("stop", "tool_calls").contains(root.path("choices").path(0).path("finish_reason").asText()))
                throw new BusinessException("模型工具规划未完整生成，请缩小问题范围。");
            return json.convertValue(root.path("choices").path(0).path("message"), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (BusinessException ex) { throw ex;
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new BusinessException("模型请求已中断。");
        } catch (Exception ex) { throw new BusinessException("模型工具调用连接失败，请稍后重试。"); }
    }
    public String readStream(BufferedReader reader, Consumer<String> delta) throws IOException {
        StringBuilder answer = new StringBuilder();
        StringBuilder event = new StringBuilder();
        boolean completed = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isEmpty()) {
                if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    event.append(line.substring(5).stripLeading());
                    if (event.length() > 1_000_000) throw new BusinessException("模型响应数据过大。");
                }
                continue;
            }
            if (event.isEmpty()) continue;
            String data = event.toString(); event.setLength(0);
            if ("[DONE]".equals(data)) { completed = true; break; }
            var chunk = json.readTree(data);
            if (chunk.has("error")) throw new BusinessException("模型生成失败，请稍后重试。");
            var choice = chunk.path("choices").path(0);
            String text = choice.path("delta").path("content").asText("");
            if (!text.isEmpty()) {
                answer.append(text);
                if (answer.length() > 100000) throw new BusinessException("模型回答过长，请缩小问题范围。");
                delta.accept(text);
            }
            String reason = choice.path("finish_reason").asText("");
            if (!reason.isEmpty()) {
                if (!"stop".equals(reason)) throw new BusinessException("模型回答未完整生成，请缩小问题范围后重试。");
                completed = true;
            }
        }
        if (!completed) throw new BusinessException("模型连接提前中断，请重试。");
        if (answer.toString().isBlank()) throw new BusinessException("模型未返回有效回答，请重试。");
        return answer.toString();
    }
}
