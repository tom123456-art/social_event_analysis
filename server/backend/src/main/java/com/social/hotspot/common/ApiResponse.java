// 声明包名 com.social.hotspot.common。
package com.social.hotspot.common;

// 引入带时区偏移量的日期时间类型。
import java.time.OffsetDateTime;

/** 统一接口响应对象：所有接口通过 code、message、data 返回结果。 */
// 定义统一接口响应对象记录类型，自动生成构造与只读访问方法。
public record ApiResponse<T>(int code, String message, T data, OffsetDateTime timestamp) {
    // 构造成功响应，业务码为零。
    public static <T> ApiResponse<T> ok(T data) {
        // 构造成功响应和当前时间，零是业务码，不是 HTTP 状态。
        return new ApiResponse<>(0, "操作成功", data, OffsetDateTime.now());
    // 结束当前方法。
    }

    // 构造失败响应，业务码为 500，并非设置 HTTP 状态。
    public static <T> ApiResponse<T> fail(String message) {
        // 构造失败响应和当前时间，500 是业务码，不设置 HTTP 状态。
        return new ApiResponse<>(500, UserMessages.chinese(message, "操作失败，请稍后重试。"), null, OffsetDateTime.now());
    // 结束当前方法。
    }
// 结束记录类型定义。
}
