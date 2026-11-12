package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.auth.AuthService;
import com.social.hotspot.service.user.UserProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 仅查询或修改当前认证账号的资料。 */
@RestController
@RequestMapping("/api/front")
public class UserProfileController {
    private final UserProfileService service;

    public UserProfileController(UserProfileService service) { this.service = service; }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        return ApiResponse.ok(service.profile(String.valueOf(AuthService.current(request).get("username"))));
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(
            @Valid @RequestBody ProfileUpdateRequest item, HttpServletRequest request) {
        return ApiResponse.ok(service.updateProfile(String.valueOf(AuthService.current(request).get("username")), item.fields()));
    }
}
