package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ApiResponse.fail("上传文件过大，请确认后端 multipart.max-file-size 配置已生效：" + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception ex) {
        return ApiResponse.fail("后端处理失败：" + ex.getClass().getSimpleName() + " - " + ex.getMessage());
    }
}
