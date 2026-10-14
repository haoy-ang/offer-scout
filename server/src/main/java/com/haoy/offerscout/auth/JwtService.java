package com.haoy.offerscout.auth;

import com.haoy.offerscout.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 的签发与校验。
 * <p>
 * <b>JWT 到底解决什么问题？</b>传统 Session 方案要把会话状态存在服务端（内存或 Redis），
 * 水平扩容时要么做会话粘滞、要么把 Session 外置。JWT 把「谁、什么时候签发、什么时候过期」
 * 这些信息**签名后交给客户端保存**，服务端只需验证签名，不用存 —— 天然适合无状态的水平扩容。
 * <p>
 * <b>但"无状态"也带来一个直接后果：签发出去的 token 在过期前无法撤回。</b>
 * 用户点了登出，服务端并没有任何地方可以"删掉"这个 token。
 * 项目的解法是配合 Redis 做黑名单（见 {@link TokenBlacklist}）——
 * 用一点点"有状态"换回登出能力，这是 JWT 方案里绕不开的取舍。
 * <p>
 * 校验用 HMAC-SHA256（对称密钥，签发方和校验方是同一个服务，不需要非对称）。
 * 密钥长度必须 ≥ 256 bit，否则 jjwt 会直接抛错 —— 这不是刁难，
 * 短密钥的 HMAC 能被暴力破解，等于签发权泄露。
 */
@Service
@Slf4j
public class JwtService {

    private final SecretKey secretKey;
    private final long expireMillis;
    private final String issuer;

    public JwtService(@Value("${offerscout.jwt.secret}") String secret,
                      @Value("${offerscout.jwt.expire-minutes}") long expireMinutes,
                      @Value("${offerscout.jwt.issuer}") String issuer) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60_000L;
        this.issuer = issuer;
    }

    /**
     * 签发 token。
     *
     * @param user 已通过密码校验的用户
     */
    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                // subject 放用户 ID：它是"这个 token 代表谁"的唯一依据
                .subject(String.valueOf(user.id()))
                // jti（JWT ID）：token 的唯一标识。登出时把它记进黑名单，靠的就是这个值
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .claim("username", user.username())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expireMillis)))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 校验并解析 token。
     * <p>
     * 任何一步失败（签名不对 / 已过期 / issuer 不匹配 / 格式非法）都返回空，
     * 调用方统一按"未登录"处理 —— 不要把"为什么失败"暴露给客户端，
     * 那等于给攻击者提供调试信息。
     */
    public Optional<JwtPayload> parse(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(new JwtPayload(
                    Long.valueOf(claims.getSubject()),
                    claims.getId(),
                    claims.getExpiration().getTime()));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 校验未通过：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** token 有效期（秒），登出写黑名单时要按它设 TTL */
    public long getExpireSeconds() {
        return expireMillis / 1000;
    }

    /**
     * token 里我们关心的三样东西。
     *
     * @param userId    用户 ID（来自 subject）
     * @param jti       token 唯一标识（登出黑名单的 key）
     * @param expiresAt 过期时间戳（毫秒）
     */
    public record JwtPayload(long userId, String jti, long expiresAt) {

        /** 距离过期还有多少秒（已过期返回 0） */
        public long remainingSeconds() {
            long remaining = (expiresAt - System.currentTimeMillis()) / 1000;
            return Math.max(remaining, 0);
        }
    }
}
