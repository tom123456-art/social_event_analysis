package com.social.hotspot.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class AnalyticsCache {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsCache.class);
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String PREFIX = "social-hotspot:analytics:dashboard:category-evidence-v1:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AnalyticsCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }
    public Map<String, Object> getDashboard(String eventId) {
        try {
            String value = redis.opsForValue().get(key(eventId));
            return value == null ? null : objectMapper.readValue(value, Map.class);
        } catch (Exception ex) {
            log.warn("Redis read failed: {}", ex.getMessage());
            return null;
        }
    }

    public void putDashboard(String eventId, Map<String, Object> dashboard) {
        try {
            redis.opsForValue().set(key(eventId), objectMapper.writeValueAsString(dashboard), TTL);
        } catch (Exception ex) {
            log.warn("Redis write failed: {}", ex.getMessage());
        }
    }

    public void evictEvent(String eventId) {
        try {
            redis.delete(key(eventId));
        } catch (Exception ex) {
            log.warn("Redis eviction failed: {}", ex.getMessage());
        }
    }

    private String key(String eventId) {
        return PREFIX + (eventId == null || eventId.isBlank() ? "unknown" : eventId);
    }
}
