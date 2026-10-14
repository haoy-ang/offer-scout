package com.haoy.offerscout.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 * <p>
 * 这里**不校验**用户名格式和密码长度：登录只需要判断"能不能对上"，
 * 加格式校验反而会泄露信息（比如提示"用户名格式不对"，就能推断出库里的用户名规则）。
 * 校验规则只应该出现在注册侧。
 */
public record LoginRequest(

        @NotBlank(message = "请输入用户名")
        String username,

        @NotBlank(message = "请输入密码")
        String password
) {
}
