package com.social.hotspot.service.impl;

import com.social.hotspot.mapper.UserMapper;
import com.social.hotspot.service.UserManagementService;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class UserManagementServiceImpl implements UserManagementService {
    private final UserMapper mapper;
    public UserManagementServiceImpl(UserMapper mapper) { this.mapper = mapper; }
    @Override public List<Map<String, Object>> users() { return mapper.users().stream().peek(item -> item.put("role", normalizeRole(item.get("role")))).toList(); }
    @Override public Map<String, Object> saveUser(Map<String, Object> item) { item.put("role", normalizeRole(item.get("role"))); item.putIfAbsent("status", "ENABLED"); mapper.insertUser(item); return item; }
    @Override public Map<String, Object> updateUser(Long id, Map<String, Object> item) { item.put("role", normalizeRole(item.get("role"))); item.putIfAbsent("status", "ENABLED"); mapper.updateUser(id, item); return mapper.userById(id); }
    @Override public boolean deleteUser(Long id) { return mapper.deleteUser(id) > 0; }
    private String normalizeRole(Object role) { return "ADMIN".equalsIgnoreCase(String.valueOf(role == null ? "" : role).trim()) ? "ADMIN" : "USER"; }
}
