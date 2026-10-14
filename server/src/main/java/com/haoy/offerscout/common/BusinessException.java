package com.haoy.offerscout.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：表示"请求本身没问题，但业务规则不允许"。
 * <p>
 * 和普通 {@link RuntimeException} 的区别在于它**携带 HTTP 状态码** ——
 * 调用方（特别是前端）需要靠状态码区分"我哪里填错了"(4xx) 和"服务端挂了"(5xx)。
 * 比如用户名重复应该返回 409，密码错误应该返回 401，
 * 全都返回 500 会让前端没法给出正确的提示。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
