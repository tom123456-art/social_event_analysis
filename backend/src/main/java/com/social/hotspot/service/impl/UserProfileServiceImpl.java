package com.social.hotspot.service.impl;

import com.social.hotspot.mapper.UserMapper;
import com.social.hotspot.service.UserProfileService;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UserProfileServiceImpl implements UserProfileService {
    private final UserMapper mapper;
    public UserProfileServiceImpl(UserMapper mapper) { this.mapper = mapper; }
    @Override public Map<String, Object> profile(String username) {
        Map<String, Object> user = mapper.userByUsername(username);
        if (user != null) { user.put("role", normalizeRole(user.get("role"))); return user; }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("username", username); item.put("nickname", username); item.put("role", "USER"); item.put("status", "ENABLED");
        mapper.insertUser(item); return item;
    }
    @Override public Map<String, Object> updateProfile(String username, Map<String, Object> item) {
        Map<String, Object> user = profile(username);
        item.put("role", user.getOrDefault("role", "USER")); item.put("status", user.getOrDefault("status", "ENABLED"));
        mapper.updateUser(number(user.get("id")).longValue(), item); return mapper.userByUsername(username);
    }
    private String normalizeRole(Object role) { return "ADMIN".equalsIgnoreCase(String.valueOf(role == null ? "" : role).trim()) ? "ADMIN" : "USER"; }
    private Number number(Object value) { if (value instanceof Number number) return number; return Long.parseLong(String.valueOf(value)); }
}
