package com.social.hotspot.service;

import java.util.List;
import java.util.Map;

public interface UserManagementService {
    List<Map<String, Object>> users();
    Map<String, Object> saveUser(Map<String, Object> item);
    Map<String, Object> updateUser(Long id, Map<String, Object> item);
    boolean deleteUser(Long id);
}
