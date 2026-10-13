package com.haoy.offerscout.auth.dto;

import com.haoy.offerscout.user.UserVO;

/**
 * 登录/注册成功后返回给前端的东西。
 *
 * @param token     访问凭证，前端之后要放在 {@code Authorization: Bearer <token>} 里
 * @param expiresIn token 有效期（秒）。前端可以用它做"快过期时提示重新登录"，
 *                  避免用户在写长消息的中途突然被踢出去
 * @param user      用户基本信息（**不含密码**，见 {@link UserVO}）
 */
public record AuthResponse(String token, long expiresIn, UserVO user) {
}
