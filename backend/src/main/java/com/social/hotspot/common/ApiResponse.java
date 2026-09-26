package com.social.hotspot.common;

import java.time.OffsetDateTime;

public record ApiResponse<T>(int code, String message, T data, OffsetDateTime timestamp) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "操作成功", data, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(500, message, null, OffsetDateTime.now());
    }
}