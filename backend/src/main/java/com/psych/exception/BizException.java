package com.psych.exception;

/**
 * 业务异常：参数没问题，但业务规则不允许（如用户已存在、密码错误）。
 * 抛出后由全局异常处理器转成友好的 JSON 提示。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
