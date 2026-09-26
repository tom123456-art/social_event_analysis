package com.social.hotspot.exception;

/** 业务异常：用于向前端返回可理解的业务失败原因。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
    public BusinessException(String message, Throwable cause) { super(message, cause); }
}
