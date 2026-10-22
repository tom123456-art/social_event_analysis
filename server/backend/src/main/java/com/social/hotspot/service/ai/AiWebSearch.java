package com.social.hotspot.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.social.hotspot.config.ProjectPaths;

@Service
public class AiWebSearch {
    private final String key;
    private final ObjectMapper json;
    @Value("${ai.search.key-file:runtime/config/ai/tavily.key}")
    private String keyFile = "";
    public AiWebSearch(@Value("${ai.search.api-key:}") String key, ObjectMapper json) { this.key = key; this.json = json; }
    private String apiKey() {
        if (!key.isBlank() || keyFile.isBlank()) return key;
        Path file = ProjectPaths.root().resolve(keyFile).normalize();
        if (!Files.exists(file)) return "";
        try { return Files.readString(file, StandardCharsets.UTF_8).trim(); }
        catch (java.io.IOException ex) { throw new BusinessException("读取搜索服务密钥失败，请检查后端本地配置文件权限。"); }
    }
    public boolean enabled() { return !apiKey().isBlank(); }
    public List<Map<String, Object>> search(String query) {
        if (!enabled()) throw new BusinessException("联网搜索尚未配置搜索服务密钥，请联系管理员配置。");
        if (query == null || query.isBlank() || query.length() > 300) throw new BusinessException("搜索词须为一至三百字。");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query, "max_results", 5, "search_depth", "basic", "include_answer", false)))).build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new BusinessException("搜索服务认证失败，请检查搜索密钥和账号权限。");
            if (response.statusCode() == 429 || response.statusCode() == 432) throw new BusinessException("搜索服务请求过于频繁或额度不足，请稍后重试。");
            if (response.statusCode() != 200) throw new BusinessException("搜索服务暂时不可用，请检查搜索密钥、额度或稍后重试。");
            List<Map<String, Object>> results = new ArrayList<>();
            for (var item : json.readTree(response.body()).path("results")) {
                String url = item.path("url").asText();
                try { if (!Set.of("https", "http").contains(URI.create(url).getScheme())) continue; } catch (Exception ignored) { continue; }
                String content = item.path("content").asText();
                results.add(Map.of("title", item.path("title").asText(), "url", url, "content", content.substring(0, Math.min(1800, content.length()))));
                if (results.size() == 5) break;
            }
            return results;
        } catch (BusinessException ex) { throw ex;
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new BusinessException("搜索已中断。");
        } catch (Exception ex) { throw new BusinessException("联网搜索失败，请稍后重试。"); }
    }
}
