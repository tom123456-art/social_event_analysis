// 声明包名 com.social.hotspot.exception。
package com.social.hotspot.exception;

// 引入统一接口响应对象。
import com.social.hotspot.common.ApiResponse;
// 引入统一中文提示过滤器。
import com.social.hotspot.common.UserMessages;
// 引入日志工具，保留开发者排查所需的完整异常。
import org.slf4j.Logger;
// 引入日志工厂。
import org.slf4j.LoggerFactory;
// 引入异常处理方法注解。
import org.springframework.web.bind.annotation.ExceptionHandler;
// 引入全局异常处理注解。
import org.springframework.web.bind.annotation.RestControllerAdvice;
// 引入上传超限异常。
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.apache.catalina.connector.ClientAbortException;
import jakarta.servlet.http.HttpServletRequest;

// 统一处理控制器异常，减少重复错误处理。
@RestControllerAdvice
/** 全局异常处理器：将后端异常转换成统一的中文接口提示。 */
// 定义全局异常处理器类。
public class GlobalExceptionHandler {
    @ExceptionHandler(AuthException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> handleAuth(AuthException ex) {
        return org.springframework.http.ResponseEntity.status(ex.status()).body(ApiResponse.fail(ex.getMessage()));
    }
    // 完整异常只记录到后端日志，不返回浏览器。
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // A failed response connection cannot receive a second JSON error response.
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleDisconnectedResponse(Exception ex, HttpServletRequest request) {
        LOG.warn("HTTP response connection unavailable: {} {}; {}", request.getMethod(),
                request.getRequestURI(), ex.getMessage());
    }
    // 指定下面的方法处理括号中类型的异常。
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    // 转换上传大小超限异常。
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        // 返回统一失败响应，说明本次校验或处理错误。
        return ApiResponse.fail("上传文件过大，请检查文件大小后重新上传。");
    // 结束当前方法。
    }

    // 指定下面的方法处理括号中类型的异常。
    @ExceptionHandler(IllegalArgumentException.class)
    // 转换参数错误。
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        // 在日志中保留原始参数错误。
        LOG.warn("请求参数校验失败", ex);
        // 返回统一失败响应，说明本次校验或处理错误。
        return ApiResponse.fail(messageOrDefault(ex, "请求参数不正确，请检查后重试。"));
    // 结束当前方法。
    }

    // 指定下面的方法处理括号中类型的异常。
    @ExceptionHandler(BusinessException.class)
    // 转换业务异常。
    public ApiResponse<Void> handleBusiness(BusinessException ex) {
        // 记录原业务异常，用户只接收中文摘要。
        LOG.warn("业务处理失败", ex);
        // 返回统一失败响应，说明本次校验或处理错误。
        return ApiResponse.fail(messageOrDefault(ex, "业务处理失败，请稍后重试。"));
    // 结束当前方法。
    }

    // 指定下面的方法处理括号中类型的异常。
    @ExceptionHandler(Exception.class)
    // 隐藏内部异常细节，返回通用失败提示。
    public ApiResponse<Void> handleException(Exception ex) {
        // 记录完整技术细节用于定位问题。
        LOG.error("后端请求处理失败", ex);
        // 返回统一失败响应，说明本次校验或处理错误。
        return ApiResponse.fail("系统处理失败，请稍后重试；如问题持续存在，请查看后端服务日志。");
    // 结束当前方法。
    }

    // 配置和任务状态错误可返回经过过滤的中文说明。
    @ExceptionHandler(IllegalStateException.class)
    // 单独处理业务运行状态错误。
    public ApiResponse<Void> handleIllegalState(IllegalStateException ex) {
        // 将完整失败信息留在日志中。
        LOG.warn("任务状态或配置异常", ex);
        // 用户只接收安全的中文摘要。
        return ApiResponse.fail(messageOrDefault(ex, "任务暂时无法执行，请检查配置或联系管理员。"));
    // 结束状态异常处理。
    }

    // 优先采用异常说明，否则使用备用提示。
    private String messageOrDefault(Exception ex, String fallback) {
        // 按问号前的条件，选择问号后或冒号后的返回值。
        return UserMessages.chinese(ex.getMessage(), fallback);
    // 结束当前方法。
    }
// 结束类定义。
}
