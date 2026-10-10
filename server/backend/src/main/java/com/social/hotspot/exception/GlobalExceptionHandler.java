package com.social.hotspot.exception;

import com.social.hotspot.common.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
/** 全局异常处理器：将后端异常转换成统一的中文接口提示。 */
public class GlobalExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ApiResponse.fail("上传文件过大，请检查文件大小后重新上传。");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(messageOrDefault(ex, "请求参数不正确，请检查后重试。"));
    }

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException ex) {
        return ApiResponse.fail(messageOrDefault(ex, "业务处理失败，请稍后重试。"));
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception ex) {
        return ApiResponse.fail("系统处理失败，请稍后重试；如问题持续存在，请查看后端服务日志。");
    }

    private String messageOrDefault(Exception ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }
}