package com.haoy.offerscout.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 * <p>
 * 参数校验直接写在 DTO 上，配合 Controller 的 {@code @Valid}，
 * 不合法的请求在进入业务代码之前就被挡掉，业务方法里不需要写一堆 if 判空。
 */
public record RegisterRequest(

        @NotBlank(message = "请输入用户名")
        @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$",
                message = "用户名只能包含字母、数字和下划线，长度 3~32 位")
        String username,

        /*
         * 上限 64 位不是随便定的：BCrypt 内部只取密码的前 72 个**字节**。
         * 超过部分会被静默丢弃 —— 也就是说"72 字节之后不同的两个密码会被当成同一个"。
         * 这里是按字符数限制（中文一个字 3 字节，64 个字最多 192 字节，仍可能超），
         * 严格做法是按字节长度校验。实际项目里更常见的做法是：
         * 限制在 64 字符以内 + 前端做提示，并在文档里说明这个特性。
         */
        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 64, message = "密码长度需在 6~64 位之间")
        String password,

        @Size(max = 32, message = "昵称最长 32 位")
        String nickname
) {
}
