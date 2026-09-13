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
@RequestMapping("/api/admin")
public class AdminBusinessController {
    private final BusinessService service;

    public AdminBusinessController(BusinessService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> users() {
        return ApiResponse.ok(service.users());
    }

    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> addUser(@RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.saveUser(item));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<Map<String, Object>> updateUser(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> item) {
        return ApiResponse.ok(service.updateUser(id, item));
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Boolean> deleteUser(@PathVariable("id") Long id) {
        return ApiResponse.ok(service.deleteUser(id));
    }

    @GetMapping("/interactions")
    public ApiResponse<List<Map<String, Object>>> interactions(
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        return ApiResponse.ok(service.interactions(Math.min(limit, 200)));
    }

    @DeleteMapping("/interactions/{id}")
    public ApiResponse<Boolean> deleteInteraction(@PathVariable("id") Long id) {
        return ApiResponse.ok(service.deleteInteraction(id));
    }

    @GetMapping("/submissions")
    public ApiResponse<List<Map<String, Object>>> submissions(
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        return ApiResponse.ok(service.submissions(Math.min(limit, 200)));
    }

    @PutMapping("/submissions/{id}/status")
    public ApiResponse<Boolean> updateSubmissionStatus(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> item) {
        String status = String.valueOf(item.getOrDefault("status", "PENDING"));
        return ApiResponse.ok(service.updateSubmissionStatus(id, status));
    }

    @DeleteMapping("/submissions/{id}")
    public ApiResponse<Boolean> deleteSubmission(@PathVariable("id") Long id) {
        return ApiResponse.ok(service.deleteSubmission(id));
    }

    @GetMapping("/contents")
    public ApiResponse<List<Map<String, Object>>> contents(
            @RequestParam(name = "eventId", defaultValue = "public_rss_latest") String eventId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ApiResponse.ok(service.contents(eventId, Math.min(limit, 300)));
    }
}
