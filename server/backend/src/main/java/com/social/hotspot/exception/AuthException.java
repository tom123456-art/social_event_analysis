package com.social.hotspot.exception;

public class AuthException extends RuntimeException {
    /** 保存供统一异常处理器返回的 HTTP 状态码。 */
    private final int status;
    /** 创建带有认证失败状态码和提示信息的异常。 */
    public AuthException(int status, String message) { super(message); this.status = status; }
    /** 返回当前认证异常对应的 HTTP 状态码。 */
    public int status() { return status; }
}
