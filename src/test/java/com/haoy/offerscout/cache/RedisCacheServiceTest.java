package com.haoy.offerscout.cache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缓存服务测试。
 * <p>
 * 同样不启 Spring 上下文 —— 缓存只是「一个 Redis 连接 + 几个方法」，手工 new 更轻。
 */
@Tag("requires-infra")
class RedisCacheServiceTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisCacheService cache;

    @BeforeAll
    static void initRedis() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cache = new RedisCacheService(redisTemplate);
    }

    @AfterAll
    static void closeRedis() {
        connectionFactory.destroy();
    }

    /** 每个用例用独立 key，避免相互干扰（也让测试可以并发跑） */
    private static String randomKey() {
        return "test:" + UUID.randomUUID();
    }

    @Test
    @DisplayName("写入后能读回，并且键上带过期时间")
    void putThenGet() {
        String key = randomKey();
        cache.put(key, "缓存内容", Duration.ofMinutes(5));

        assertThat(cache.get(key)).contains("缓存内容");

        Long ttl = redisTemplate.getExpire("offerscout:cache:" + key, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("未写入的键返回空，而不是报错")
    void missReturnsEmpty() {
        assertThat(cache.get(randomKey())).isEmpty();
    }

    @Test
    @DisplayName("evict 之后读不到了")
    void evictRemovesKey() {
        String key = randomKey();
        cache.put(key, "内容", Duration.ofMinutes(5));
        assertThat(cache.get(key)).isPresent();

        cache.evict(key);

        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    @DisplayName("空值不写入 —— 避免缓存里塞一堆空字符串")
    void doesNotCacheBlankValue() {
        String key = randomKey();
        cache.put(key, "", Duration.ofMinutes(5));

        assertThat(redisTemplate.hasKey("offerscout:cache:" + key)).isFalse();
    }

    @Test
    @DisplayName("指纹：定长、稳定，且不泄露原文")
    void fingerprintIsStableAndFixedLength() {
        String raw = "MySQL 的 MVCC 是怎么实现的？";

        String first = RedisCacheService.fingerprint(raw);
        String second = RedisCacheService.fingerprint(raw);

        assertThat(first).isEqualTo(second).hasSize(32).doesNotContain("MySQL");
        assertThat(RedisCacheService.fingerprint(raw + "x")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("Redis 故障时降级：不抛异常，读返回空、写和删静默失败")
    void degradesGracefullyWhenRedisIsDown() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RuntimeException("模拟 Redis 不可用"));
        when(broken.delete(anyString())).thenThrow(new RuntimeException("模拟 Redis 不可用"));

        RedisCacheService degraded = new RedisCacheService(broken);

        // 这是最关键的保证：缓存不可用时业务必须还能跑，只是慢一点
        assertThat(degraded.get("any")).isEmpty();
        assertThatCode(() -> degraded.put("any", "v", Duration.ofMinutes(1))).doesNotThrowAnyException();
        assertThatCode(() -> degraded.evict("any")).doesNotThrowAnyException();
    }
}
