package com.haoy.offerscout.tools;

import com.haoy.offerscout.cache.RedisCacheService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库检索工具的缓存行为测试。
 * <p>
 * <b>核心验证点只有一个</b>：命中缓存时，绝不能再走向量库。
 * 这里用 Mockito 替换掉 {@code VectorStore}，通过「方法被调用了几次」来证明缓存真的生效 ——
 * 只看返回值是看不出区别的（有没有缓存，返回的都是同一段文本）。
 */
class JobKnowledgeToolCacheTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisCacheService cache;

    private VectorStore vectorStore;

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

    @BeforeEach
    void reset() {
        Set<String> keys = redisTemplate.keys("offerscout:cache:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        vectorStore = mock(VectorStore.class);
    }

    private JobKnowledgeTool tool() {
        return new JobKnowledgeTool(vectorStore, cache);
    }

    private static Document doc(String text, String filename) {
        return new Document(text, Map.of("filename", filename));
    }

    @Test
    @DisplayName("同一个问题问第二遍：只查一次向量库")
    void secondIdenticalSearchHitsCache() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("MySQL 索引失效的常见场景……", "小林coding-后端面经汇总-Java方向.md")));

        JobKnowledgeTool tool = tool();
        String first = tool.searchJobKnowledge("MySQL 索引失效", "interview");
        String second = tool.searchJobKnowledge("MySQL 索引失效", "interview");

        assertThat(second).isEqualTo(first);
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("问题不同：各查一次，不共用缓存")
    void differentQueriesAreCachedSeparately() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("内容", "Java后端岗位JD样本.md")));

        JobKnowledgeTool tool = tool();
        tool.searchJobKnowledge("MySQL 索引失效", "all");
        tool.searchJobKnowledge("Redis 缓存穿透", "all");

        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("类别不同也算不同的键：同样的词查面经和查 JD 是两次检索")
    void categoryIsPartOfCacheKey() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("内容", "Java后端岗位JD样本.md")));

        JobKnowledgeTool tool = tool();
        tool.searchJobKnowledge("Java 后端", "interview");
        tool.searchJobKnowledge("Java 后端", "jd");

        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("「查不到」也要缓存：空结果同样花了 embedding 的钱")
    void emptyResultIsCachedToo() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        JobKnowledgeTool tool = tool();
        String first = tool.searchJobKnowledge("库里根本没有的问题", "all");
        String second = tool.searchJobKnowledge("库里根本没有的问题", "all");

        assertThat(first).contains("未检索到");
        assertThat(second).isEqualTo(first);
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("缓存确实写进了 Redis，并且带 TTL（不会永久占内存）")
    void cacheEntryLandsInRedisWithTtl() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("内容", "技术简历写法参考.md")));

        tool().searchJobKnowledge("简历项目怎么写", "all");

        Set<String> keys = redisTemplate.keys("offerscout:cache:rag:search:*");
        assertThat(keys).isNotNull().hasSize(1);

        Long ttl = redisTemplate.getExpire(keys.iterator().next(), java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(Duration.ofMinutes(30).toSeconds());
    }

    @Test
    @DisplayName("缓存不可用时降级为每次直查，但功能不能坏")
    void degradesWhenCacheIsDown() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RuntimeException("模拟 Redis 不可用"));
        when(broken.delete(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("模拟 Redis 不可用"));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("内容", "Java后端岗位JD样本.md")));

        JobKnowledgeTool tool = new JobKnowledgeTool(vectorStore, new RedisCacheService(broken));
        String first = tool.searchJobKnowledge("同一个问题", "all");
        String second = tool.searchJobKnowledge("同一个问题", "all");

        // 缓存挂了 → 每次都真的去查，但结果依然正确
        assertThat(first).isNotEmpty().isEqualTo(second);
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }
}
