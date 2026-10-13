package com.haoy.offerscout.user;

import java.time.LocalDateTime;

/**
 * 业务用户。
 * <p>
 * <b>注意 {@code password} 字段存的是 BCrypt 密文，不是明文。</b>
 * 这个对象只在服务端内部流转（注册、登录校验），
 * <b>绝对不要把它直接返回给前端</b> —— 对外一律用 {@link UserVO}。
 * 这是最容易犯的安全疏漏之一：实体类加了 getter 就顺手序列化进了响应体，
 * 于是密码哈希和内部字段一起泄露出去。
 */
public record User(
        Long id,
        String username,
        String password,
        String nickname,
        LocalDateTime createdAt
) {
}
