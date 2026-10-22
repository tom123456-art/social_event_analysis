package com.social.hotspot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private String model = "";
    private String apiKey = "";
    private String requestUrl = "";
    private int timeoutSeconds = 90;
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getApiKey() {
        if (!apiKey.isBlank()) return apiKey;
        java.nio.file.Path file = ProjectPaths.root().resolve("runtime/config/ai/qwen.key");
        if (!java.nio.file.Files.exists(file)) return "";
        try { return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8).trim(); }
        catch (java.io.IOException ex) { throw new IllegalStateException("读取默认模型密钥失败，请检查后端本地配置文件权限。"); }
    }
    public void setApiKey(String value) { apiKey = value; }
    public String getRequestUrl() { return requestUrl; }
    public void setRequestUrl(String value) { requestUrl = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = Math.max(5, Math.min(100, value)); }
    public boolean ready() { return !model.isBlank() && !getApiKey().isBlank() && !requestUrl.isBlank(); }
}
