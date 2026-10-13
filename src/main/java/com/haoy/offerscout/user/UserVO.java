package com.haoy.offerscout.user;

/**
 * 对外的用户视图对象。
 * <p>
 * 为什么不直接返回 {@link User}？因为那会把密码哈希一起序列化出去。
 * 单独定义一个只含「前端需要且安全」字段的对象，让泄露变成"不可能"，
 * 而不是"靠每次记得手动置空密码"。
 */
public record UserVO(Long id, String username, String nickname) {

    public static UserVO of(User user) {
        return new UserVO(user.id(), user.username(), user.nickname());
    }
}
