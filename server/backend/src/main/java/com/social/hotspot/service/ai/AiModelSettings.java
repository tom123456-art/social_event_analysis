package com.social.hotspot.service.ai;

import com.social.hotspot.exception.BusinessException;
import java.net.*;
import java.util.*;

public record AiModelSettings(String id, String name, String provider, String model, String requestUrl,
                              String apiKey, int timeoutSeconds, Double temperature) {
    public static AiModelSettings from(Map<String, Object> item) {
        String name = required(item, "name", "显示名称", 60);
        String model = required(item, "model", "模型 ID", 120);
        String url = required(item, "requestUrl", "请求地址", 2048);
        String key = required(item, "apiKey", "密钥", 2048);
        if (key.chars().anyMatch(Character::isISOControl)) throw new BusinessException("密钥不能包含换行或控制字符。");
        String provider = Objects.toString(item.get("provider"), "").trim();
        if (provider.length() > 60) throw new BusinessException("厂商名称最多六十字。");
        int timeout = 90;
        Double temperature = null;
        try {
            if (item.get("timeoutSeconds") != null) {
                double value = Double.parseDouble(item.get("timeoutSeconds").toString());
                if (!Double.isFinite(value) || value != Math.rint(value) || value < 5 || value > 100) throw new NumberFormatException();
                timeout = (int) value;
            }
            if (item.get("temperature") != null) {
                temperature = Double.valueOf(item.get("temperature").toString());
                if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) { throw new BusinessException("超时时间须为五至一百秒的整数，温度须为零至二之间的数字。"); }
        return new AiModelSettings(UUID.randomUUID().toString(), name, provider, model, url, key, timeout, temperature);
    }
    private static String required(Map<String, Object> item, String key, String label, int max) {
        String value = Objects.toString(item.get(key), "").trim();
        if (value.isEmpty() || value.length() > max) throw new BusinessException("请填写有效的" + label + "，长度不能超过" + max + "个字符。");
        return value;
    }
    public boolean configured() { return !apiKey.isBlank() && !model.isBlank() && !requestUrl.isBlank(); }
    public Map<String, Object> publicView() {
        return Map.of("id", id, "name", name, "provider", provider, "model", model, "configured", configured());
    }
    public URI validatedUri() {
        try {
            URI uri = URI.create(requestUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new BusinessException("请使用完整的 HTTPS 模型接口地址，不要包含账号、查询参数或非标准端口。");
            // User-entered endpoints must never reach local services or cloud metadata.
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()
                        || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                        || (bytes.length == 4 && ((bytes[0] & 255) == 0 || (bytes[0] & 255) >= 224
                        || ((bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127))))
                    throw new BusinessException("模型接口不能使用本机、内网或保留地址。");
            }
            return uri;
        } catch (BusinessException ex) { throw ex;
        } catch (Exception ex) { throw new BusinessException("无法解析模型请求地址，请检查域名和地址格式。"); }
    }
    @Override public String toString() { return "AiModelSettings[id=" + id + ", model=" + model + "]"; }
}
