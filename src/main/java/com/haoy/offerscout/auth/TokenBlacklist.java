package com.haoy.offerscout.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWT 登出黑名单。
 * <p>
 * <b>为什么需要它？</b>JWT 是无状态的 —— 服务端不保存已签发的 token，
 * 所以用户点"登出"时，服务端**没有任何地方可以删掉这个 token**，
 * 它会在有效期内一直可用。这是 JWT 最大的软肋。
 * <p>
 * 补法是：登出时把这个 token 的 {@code jti} 记进 Redis，并设置成
 * 「和 token 剩余有效期一样长」的 TTL。校验时先查黑名单，命中就当作无效。
 * TTL 一到自动删除，Redis 不会无限增长 —— 这是这个设计能成立的关键：
 * <b>黑名单只需要记住"还没过期的已作废 token"，过期后自然没人再校验它了。</b>
 * <p>
 * <b>取舍（面试常问）：</b>这等于给无状态方案加回了"有状态"，也意味着每次请求都要多一次 Redis 查询。
 * 换来的是登出、改密码后踢下线、管理员封禁这些必要能力。真实系统基本都会这么做。
 * <p>
 * <b>⚠️ Redis 挂掉时这里选择「放行」（fail-open）</b>：查不到黑名单就认为 token 有效。
 * 也就是 Redis 故障期间，已登出的 token 会短暂复活。
 * 另一个选择是 fail-closed（一律拒绝），但那会让 Redis 一挂就全员无法登录 ——
 * 用"可用性"换"安全性"的取舍。这里选可用性，但**把日志级别提到 ERROR**，
 * 因为这是安全事件，必须被监控抓到。生产环境应该配合短有效期 token 来压缩这个风险窗口。
 */
@Component
@Slf4j
public class TokenBlacklist {

    private static final String KEY_PREFIX = "offerscout:jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 拉黑一个 token。
     *
     * @param jti        token 的唯一标识
     * @param ttlSeconds 存活时长，取 token 的剩余有效期即可（过期后无需再记）
     */
    public void blacklist(String jti, long ttlSeconds) {
        if (jti == null || ttlSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.error("登出黑名单写入失败，该 token 在过期前仍可用：jti={}", jti, e);
        }
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.error("登出黑名单读取失败，本次按未拉黑放行（安全风险，需关注 Redis 状态）：jti={}", jti, e);
            return false;
        }
    }
}
