package com.haoy.offerscout.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：把异常统一翻译成 `{code, message}` 的 JSON。
 * <p>
 * 为什么需要它？没有这层的话，前端拿到的是 Spring 默认的错误结构
 * （`{timestamp, status, error, path}`，`message` 常年为空），
 * 表单校验失败时用户只能看到"请求失败 400"，不知道是哪个字段填错了。
 * <p>
 * <b>刻意不写 {@code @ExceptionHandler(Exception.class)} 兜底</b>：
 * SSE 接口在客户端主动断开时，会抛出 `AsyncRequestNotUsableException` 这类异常，
 * 兜底处理器会把它当"服务器错误"记成 error 日志，把日志刷满噪音。
 * 未预期的异常交给 Spring 默认处理（照样返回 500），不额外制造干扰。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 上传体积上限，直接读 multipart 的配置值。
     * <p>
     * 不写死"10MB"是为了让提示文案和真正的限制**只有一个来源** —— 哪天把限制改成 20MB，
     * 用户看到的报错会自动跟着变，不会出现"提示说 10MB、实际放到 20MB"这种对不上的情况。
     */
    private final String maxFileSize;

    public GlobalExceptionHandler(@Value("${spring.servlet.multipart.max-file-size:1MB}") String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        log.warn("业务异常：{} - {}", e.getStatus(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(body(e.getStatus().value(), e.getMessage()));
    }

    /**
     * 上传体积超限。
     * <p>
     * Spring Boot 的默认处理是不接这个异常的，于是超限会掉进框架默认错误页 ——
     * 用户看到的是一段英文状态信息，完全不知道"是文件太大"。这里翻译成 413 + 明确指引。
     * <p>
     * 用 413（Payload Too Large）而不是 400：这是语义更准确的状态码，
     * 前端也能据此和"参数填错"区分开（虽然本项目前端只是把 message 透出来）。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传体积超限（上限 {}）：{}", maxFileSize, e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body(413, "文件过大，请压缩到 " + maxFileSize + " 以内再上传。"));
    }

    /**
     * 参数校验失败（`@Valid` + `@NotBlank` 这类注解触发）。
     * 只取第一条错误返回 —— 表单场景下，一次告诉用户一个明确的问题，比甩一堆报错更好用。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage())
                .orElse("参数不合法");
        return ResponseEntity.badRequest().body(body(400, message));
    }

    private Map<String, Object> body(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
