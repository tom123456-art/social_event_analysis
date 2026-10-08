package com.social.hotspot.service;

import java.util.Map;

public interface UserProfileService {
    Map<String, Object> profile(String username);
    Map<String, Object> updateProfile(String username, Map<String, Object> item);
}
