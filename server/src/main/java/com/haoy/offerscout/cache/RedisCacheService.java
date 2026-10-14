package com.haoy.offerscout.cache;

import cn.hutool.crypto.digest.DigestUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 缓存读写封装。
 * <p>
 * <b>为什么不直接用 Spring Cache 的 {@code @Cacheable}？</b>它很省事，但键是框架按规则拼的、
 * 序列化方式也藏在配置里，出问题时不好排查（最典型的坑：默认 {@code RedisTemplate} 用 JDK 序列化，
 * 用 redis-cli 看到的 key 是一串 {@code \xac\xed...} 乱码，根本认不出是什么）。
 * 这里直接用 {@code StringRedisTemplate} 手工拼键，键名和过期时间都写在调用处，肉眼可查、可维护。
 * <p>
 * <b>一条铁律：缓存的所有异常都要吞掉。</b>Redis 是加速件不是必需品，它挂掉时业务必须能退回"直接查库/直接调接口"，
 * 最多就是慢一点，绝不能因为缓存不可用导致整个功能不可用。
 */
@Component
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /** 统一前缀，方便在 redis-cli 里用 KEYS offerscout:cache:* 一眼看清本项目的缓存 */
    private static final String KEY_PREFIX = "offerscout:cache:";

    /** 键的指纹长度：32 个十六进制字符（128 bit），碰撞概率可忽略，也不会让 key 太长 */
    private static final int FINGERPRINT_LENGTH = 32;

    private final StringRedisTemplate redisTemplate;

    public RedisCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + key));
        } catch (Exception e) {
            log.warn("读缓存失败，按未命中处理：key={}", key, e);
            return Optional.empty();
        }
    }

    public void put(String key, String value, Duration ttl) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key, value, ttl);
        } catch (Exception e) {
            log.warn("写缓存失败，忽略：key={}", key, e);
        }
    }

    public void evict(String key) {
        try {
            redisTemplate.delete(KEY_PREFIX + key);
        } catch (Exception e) {
            log.warn("删缓存失败，忽略：key={}", key, e);
        }
    }

    /**
     * 把任意长度的原始键收敛成定长指纹。
     * <p>
     * 不直接把语句本身当 key，有两个原因：
     * 一是用户输入可能很长、含空格和特殊字符，Redis 的键虽然允许但不好读也不好统计；
     * 二是保持键长度稳定，避免同一个业务产生长短不一的 key。
     */
    public static String fingerprint(String raw) {
        return DigestUtil.sha256Hex(raw).substring(0, FINGERPRINT_LENGTH);
    }
}
