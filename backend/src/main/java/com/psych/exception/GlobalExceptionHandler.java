package com.psych.exception;

import com.psych.dto.ApiResponse;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把异常统一翻译成 ApiResponse JSON，
 * 这样前端拿到的永远是结构一致的响应，不会直接看到堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 业务异常：取我们自己写的提示语
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.fail(e.getMessage());
    }

    // 参数校验异常：取第一条字段错误信息（如“请输入正确的手机号”）
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return ApiResponse.fail(message);
    }

    // 兜底：其他未预期异常，避免把堆栈抛给前端
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception e) {
        return ApiResponse.fail("服务器开小差了：" + e.getMessage());
    }
}
