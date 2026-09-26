package com.social.hotspot.common;

import java.time.OffsetDateTime;

/** 统一接口响应对象：所有接口通过 code、message、data 返回结果。 */
public record ApiResponse<T>(int code, String message, T data, OffsetDateTime timestamp) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "操作成功", data, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(500, message, null, OffsetDateTime.now());
    }
}