package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.BusinessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/front")
/** 前台业务接口：提供个人资料、用户互动和内容投稿接口。 */
public class FrontBusinessController {
    private final BusinessService service;

    public FrontBusinessController(BusinessService service) {
        this.service = service;
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(
            @RequestParam(name = "username", defaultValue = "student") String username) {
        return ApiResponse.ok(service.profile(username));
    }

    @PutMapping("/profile/{username}")
    public ApiResponse<Map<String, Object>> updateProfile(
            @PathVariable("username") String username,
            @RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.updateProfile(username, item));
    }

    @GetMapping("/interactions")
    public ApiResponse<List<Map<String, Object>>> interactions(
            @RequestParam(name = "username", defaultValue = "student") String username) {
        return ApiResponse.ok(service.userInteractions(username, 30));
    }

    @PostMapping("/interactions")
    public ApiResponse<Map<String, Object>> addInteraction(@RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.addInteraction(item));
    }

    @GetMapping("/submissions")
    public ApiResponse<List<Map<String, Object>>> submissions(
            @RequestParam(name = "username", defaultValue = "student") String username) {
        return ApiResponse.ok(service.userSubmissions(username, 30));
    }

    @PostMapping("/submissions")
    public ApiResponse<Map<String, Object>> addSubmission(@RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.addSubmission(item));
    }
}
