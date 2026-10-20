package com.social.hotspot.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

// 注册为 Spring 通用组件。
@Component
// 定义概览缓存组件，封装 Redis 的读取、写入与失效处理。
public class  AnalyticsCache {
    // 定义并初始化日志对象。
    private static final Logger log = LoggerFactory.getLogger(AnalyticsCache.class);
    // 设置概览缓存有效期为 30 分钟。
    private static final Duration TTL = Duration.ofMinutes(30);
    // 定义并初始化缓存键前缀及规则版本；保存 Redis 读写对象；保存 JSON 转换对象。
    private static final String PREFIX = "social-hotspot:analytics:dashboard:binary-sentiment-v1:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // 构造热点概览缓存组件，接收所需组件并保存依赖。
    public AnalyticsCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }
    // 读取 Redis，故障时返回空值以查询数据库。
    public Map<String, Object> getDashboard(String eventId) {
        try {
            // 读取待处理值。
            String value = redis.opsForValue().get(key(eventId));
            // 缓存缺失时返回空值，命中时把 JSON 还原为键值映射。
            return value == null ? null : objectMapper.readValue(value, Map.class);
        } catch (Exception ex) {
            // 记录缓存故障，不中断主要查询。
            log.warn("Redis read failed: {}", ex.getMessage());
            // 返回空值。
            return null;
        }
    }

    // 序列化概览写入 Redis 并设置有效期。
    public void putDashboard(String eventId, Map<String, Object> dashboard) {
        try {
            // 将概览转为 JSON 写入 Redis 并设置有效期。
            redis.opsForValue().set(key(eventId), objectMapper.writeValueAsString(dashboard), TTL);
        } catch (Exception ex) {
            // 记录缓存故障，不中断主要查询。
            log.warn("Redis write failed: {}", ex.getMessage());
        }
    }

    // 删除旧概览缓存。
    public void evictEvent(String eventId) {
        try {
            // 删除旧概览缓存，让下次查询读取新数据。
            redis.delete(key(eventId));
        } catch (Exception ex) {
            // 记录缓存故障，不中断主要查询。
            log.warn("Redis eviction failed: {}", ex.getMessage());
        }
    }

    // 拼接缓存键，空事件标识使用 unknown。
    private String key(String eventId) {
        // 按问号前的条件，选择问号后或冒号后的返回值。
        return PREFIX + (eventId == null || eventId.isBlank() ? "unknown" : eventId);
    }
}
