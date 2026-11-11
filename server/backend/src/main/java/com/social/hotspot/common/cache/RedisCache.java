package com.social.hotspot.common.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 公共 Redis JSON 缓存，业务模块提供 key 和 TTL。 */
@Component
public class RedisCache {
    private static final Logger log = LoggerFactory.getLogger(RedisCache.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // 页面汇总读取入口：把缓存 JSON 还原为字段名到统计结果的映射。
    public Map<String, Object> getMap(String key) {
        return get(key, MAP_TYPE);
    }

    // 未命中、Redis 故障或 JSON 损坏时返回 null，由业务模块回退查库。
    public <T> T get(String key, TypeReference<T> type) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : objectMapper.readValue(value, type);
        } catch (Exception ex) {
            log.warn("Redis read failed for {}: {}", key, ex.getMessage());
            return null;
        }
    }

    // 保存 JSON 并设置过期时间，写入失败不影响业务返回。
    public void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ex) {
            log.warn("Redis write failed for {}: {}", key, ex.getMessage());
        }
    }

    // 删除单个业务缓存，复用批量删除的异常处理。
    public void delete(String key) {
        delete(List.of(key));
    }

    // 按明确的 key 批量失效，不使用通配符扫描 Redis。
    public void delete(Collection<String> keys) {
        if (keys.isEmpty()) return;
        try {
            redis.delete(keys);
        } catch (Exception ex) {
            log.warn("Redis eviction failed: {}", ex.getMessage());
        }
    }
}
