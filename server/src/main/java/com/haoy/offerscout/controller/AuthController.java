package com.haoy.offerscout.controller;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.auth.JwtService;
import com.haoy.offerscout.auth.RequireLogin;
import com.haoy.offerscout.auth.TokenBlacklist;
import com.haoy.offerscout.auth.UserContext;
import com.haoy.offerscout.auth.dto.AuthResponse;
import com.haoy.offerscout.auth.dto.LoginRequest;
import com.haoy.offerscout.auth.dto.RegisterRequest;
import com.haoy.offerscout.ratelimit.RateLimit;
import com.haoy.offerscout.user.User;
import com.haoy.offerscout.user.UserService;
import com.haoy.offerscout.user.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录鉴权接口。
 * <p>
 * <b>这两个接口特别需要限流</b>，而且原因和 AI 接口不同：
 * AI 接口限流是防"烧钱"，登录接口限流是防**暴力破解**和批量注册。
 * 没有限流的登录接口等于给攻击者留了一条无限次试密码的通道。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final TokenBlacklist tokenBlacklist;

    public AuthController(UserService userService, JwtService jwtService, TokenBlacklist tokenBlacklist) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.tokenBlacklist = tokenBlacklist;
    }

    /**
     * 注册。注册成功直接返回 token，省掉"注册完再登录一次"的多余往返。
     * <p>
     * 限流 5 次/分钟：正常的注册行为不会超过这个频率，但批量注册脚本会。
     */
    @PostMapping("/register")
    @RateLimit(limit = 5, windowSeconds = 60, message = "注册过于频繁，请稍后再试。")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(
                request.username(),
                request.password(),
                StrUtil.blankToDefault(request.nickname(), request.username()));
        return buildAuthResponse(user);
    }

    /**
     * 登录。
     * <p>
     * 限流 10 次/分钟。注意这里限的是"每个 IP 10 次"，
     * 真实系统还会叠加"每个账号连续失败 N 次就锁定一段时间"，
     * 否则攻击者换 IP 就能绕过 —— 单一维度限流都不是完整的防护。
     */
    @PostMapping("/login")
    @RateLimit(limit = 10, windowSeconds = 60, message = "登录尝试过于频繁，请稍后再试。")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userService.authenticate(request.username(), request.password());
        return buildAuthResponse(user);
    }

    /**
     * 登出。
     * <p>
     * JWT 是无状态的，服务端本来"忘不掉"已签发的 token。
     * 这里把它记进 Redis 黑名单（TTL = 该 token 的剩余有效期），
     * 从而让登出真正生效。详见 {@link TokenBlacklist}。
     */
    @PostMapping("/logout")
    @RequireLogin
    public Map<String, Object> logout() {
        UserContext.Principal principal = UserContext.get();
        tokenBlacklist.blacklist(principal.jti(), principal.remainingSeconds());
        return Map.of("success", true);
    }

    /** 当前登录用户信息。前端刷新页面后用它可以判断"本地 token 还有效吗" */
    @GetMapping("/me")
    @RequireLogin
    public UserVO me() {
        return userService.findById(UserContext.requireUserId());
    }

    private AuthResponse buildAuthResponse(User user) {
        return new AuthResponse(
                jwtService.issue(user),
                jwtService.getExpireSeconds(),
                UserVO.of(user));
    }
}
