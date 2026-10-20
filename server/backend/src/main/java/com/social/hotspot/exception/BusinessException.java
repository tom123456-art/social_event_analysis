package com.social.hotspot.exception;

/** 业务异常：用于向前端返回可理解的业务失败原因。 */
// 定义业务异常类。
public class BusinessException extends RuntimeException {
    // 创建业务异常，将提示信息及可选原异常交给父类保存。
    public BusinessException(String message) { super(message); }
    // 创建业务异常，将提示信息及可选原异常交给父类保存。
    public BusinessException(String message, Throwable cause) { super(message, cause); }
}
