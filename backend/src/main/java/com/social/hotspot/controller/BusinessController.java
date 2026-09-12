package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.BusinessService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api")
public class BusinessController {
    private final BusinessService service;

    public BusinessController(BusinessService service) {
        this.service = service;
    }

    @GetMapping("/front/profile")
    public ApiResponse<Map<String, Object>> profile(@RequestParam(name = "username", defaultValue = "student") String username) {
        return ApiResponse.ok(service.profile(username));
    }

    @PutMapping("/front/profile/{username}")
    public ApiResponse<Map<String, Object>> updateProfile(@PathVariable("username") String username, @RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.updateProfile(username, item));
    }

    @GetMapping("/front/interactions")
    public ApiResponse<List<Map<String, Object>>> myInteractions(@RequestParam(name = "username", defaultValue = "student") String username) {
        return ApiResponse.ok(service.userInteractions(username, 30));
    }

    @PostMapping("/front/interactions")
    public ApiResponse<Map<String, Object>> addInteraction(@RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.addInteraction(item));
    }

    @GetMapping("/front/submissions")
    public ApiResponse<List<Map<String, Object>>> mySubmissions(@RequestParam(name = "username", defaultValue = "student") String username) {
        return ApiResponse.ok(service.userSubmissions(username, 30));
    }

    @PostMapping("/front/submissions")
    public ApiResponse<Map<String, Object>> addSubmission(@RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.addSubmission(item));
    }

    @GetMapping("/admin/users")
    public ApiResponse<List<Map<String, Object>>> users() {
        return ApiResponse.ok(service.users());
    }

    @PostMapping("/admin/users")
    public ApiResponse<Map<String, Object>> addUser(@RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.saveUser(item));
    }

    @PutMapping("/admin/users/{id}")
    public ApiResponse<Map<String, Object>> updateUser(@PathVariable("id") Long id, @RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.updateUser(id, item));
    }

    @DeleteMapping("/admin/users/{id}")
    public ApiResponse<Boolean> deleteUser(@PathVariable("id") Long id) {
        return ApiResponse.ok(service.deleteUser(id));
    }

    @GetMapping("/admin/interactions")
    public ApiResponse<List<Map<String, Object>>> interactions(@RequestParam(name = "limit", defaultValue = "80") int limit) {
        return ApiResponse.ok(service.interactions(Math.min(limit, 200)));
    }

    @DeleteMapping("/admin/interactions/{id}")
    public ApiResponse<Boolean> deleteInteraction(@PathVariable("id") Long id) {
        return ApiResponse.ok(service.deleteInteraction(id));
    }

    @GetMapping("/admin/submissions")
    public ApiResponse<List<Map<String, Object>>> submissions(@RequestParam(name = "limit", defaultValue = "80") int limit) {
        return ApiResponse.ok(service.submissions(Math.min(limit, 200)));
    }

    @PutMapping("/admin/submissions/{id}/status")
    public ApiResponse<Boolean> updateSubmissionStatus(@PathVariable("id") Long id, @RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.updateSubmissionStatus(id, String.valueOf(item.getOrDefault("status", "PENDING"))));
    }

    @DeleteMapping("/admin/submissions/{id}")
    public ApiResponse<Boolean> deleteSubmission(@PathVariable("id") Long id) {
        return ApiResponse.ok(service.deleteSubmission(id));
    }

    @GetMapping("/admin/contents")
    public ApiResponse<List<Map<String, Object>>> contents(@RequestParam(name = "eventId", defaultValue = "public_rss_latest") String eventId,
                                                           @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ApiResponse.ok(service.contents(eventId, Math.min(limit, 300)));
    }

}
