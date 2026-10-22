package com.haoy.offerscout.tools;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.cache.RedisCacheService;
import com.haoy.offerscout.common.TextTruncator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 求职知识库检索工具。
 * <p>
 * 这是 RAG 从「被动注入」升级为「主动检索」的关键一步：
 * 由模型自己决定什么时候去查、拿什么关键词去查，而不是每轮对话都无脑塞几段资料。
 * <p>
 * <b>为什么这里要加缓存？</b>每次检索都要先把 query 送进 embedding 模型换成向量，而 embedding 是**按量计费**的。
 * 面经类问题天然高度重复（"MySQL 索引失效"、"Redis 缓存穿透" 会被反复问），
 * 同一句话答第二遍还去花钱、花网络往返，纯属浪费。所以检索结果按 query 缓存 30 分钟。
 */
@Slf4j
public class JobKnowledgeTool {

    /** 先多捞一些，再按类别过滤，避免过滤后没剩下东西 */
    private static final int FETCH_TOP_K = 8;

    /** 最终最多返回几条给模型 */
    private static final int MAX_RETURN = 4;

    /** 单条资料最多截断到多少字符，防止把上下文撑爆 */
    private static final int MAX_CHARS_PER_DOC = 1200;

    /**
     * 缓存有效期。
     * <p>
     * 选 30 分钟是个折中：知识库是打包在项目里的静态文档，运行期不会变，
     * 所以理论上可以缓存到进程重启；但留一个过期时间，能让"部署了新素材但忘了清缓存"这种情况自愈。
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /**
     * 缓存键里的版本号。
     * <p>
     * 改这里的返回格式（比如调整分隔符、加字段）时把它 +1，老缓存就自动失效了 ——
     * 否则部署后会有最多 30 分钟窗口，返回的是旧格式的文本，排查起来很费劲。
     * 这是"缓存里存的是格式化后的字符串"必然要付的代价。
     */
    private static final String CACHE_VERSION = "v1";

    private final VectorStore vectorStore;

    private final RedisCacheService cache;

    private final double similarityThreshold;

    public JobKnowledgeTool(VectorStore vectorStore, RedisCacheService cache) {
        this(vectorStore, cache, 0.0);
    }

    public JobKnowledgeTool(VectorStore vectorStore, RedisCacheService cache, double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.cache = cache;
        this.similarityThreshold = similarityThreshold;
    }

    @Tool(description = """
            检索本地求职知识库。知识库包含：各互联网公司后端面经真题、Java 后端岗位 JD 要求、技术简历写法资料。
            当需要回答面试题、了解某家公司的面试风格、对照岗位要求、或改进简历写法时，必须调用本工具拿真实资料，
            不要凭记忆编造题目或要求。""")
    public String searchJobKnowledge(
            @ToolParam(description = "检索问题或关键词，越具体越好。例如：字节跳动 Java 后端一面 MySQL 索引")
            String query,
            @ToolParam(description = "资料类别，可选：interview（面经真题）、jd（岗位要求）、resume（简历写法）、all（不限）。不确定就传 all",
                    required = false)
            String category) {

        String cacheKey = "rag:search:" + CACHE_VERSION + ":"
                + RedisCacheService.fingerprint(query + "|" + StrUtil.blankToDefault(category, "all"));

        Optional<String> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            log.info("知识库检索命中缓存: query={}", query);
            return cached.get();
        }

        log.info("知识库检索未命中缓存，走向量检索: query={}, category={}", query, category);
        String result = doSearch(query, category);

        // 「查不到」也要缓存：空结果同样花了一次 embedding，而且这类问题往往会被反复追问
        cache.put(cacheKey, result, CACHE_TTL);
        return result;
    }

    private String doSearch(String query, String category) {
        List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(FETCH_TOP_K)
                .similarityThreshold(similarityThreshold)
                .build());

        if (found == null || found.isEmpty()) {
            return "知识库未检索到与「" + query + "」相关的内容。请换一组关键词再试，或告知用户知识库暂无该资料。";
        }

        List<Document> selected = filterByCategory(found, category);
        if (selected.isEmpty()) {
            // 过滤后为空说明类别选错了，退回未过滤结果，别让模型空手而归
            selected = found;
        }

        StringBuilder sb = new StringBuilder("检索到 ")
                .append(selected.size())
                .append(" 条相关资料（共命中 ")
                .append(found.size())
                .append(" 条）：\n");

        int index = 1;
        for (Document doc : selected.subList(0, Math.min(MAX_RETURN, selected.size()))) {
            String text = doc.getText() == null ? "" : doc.getText().replaceAll("\\s+", " ").trim();
            // 采用语义友好截断，避免切断句尾结论句
            text = TextTruncator.truncateGracefully(text, MAX_CHARS_PER_DOC, "…（已截断）");
            sb.append("\n【资料 ").append(index++).append("｜来源：")
                    .append(doc.getMetadata().getOrDefault("filename", "未知"))
                    .append("】\n")
                    .append(text)
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 按类别过滤。
     * <p>
     * 这里没有用向量库的 filter expression，而是在召回之后用文件名做一次轻量过滤 ——
     * 原因是我们当前的分类依据就是文件名，逻辑简单、行为可预期，也不依赖不同向量库对过滤语法的支持差异。
     */
    private List<Document> filterByCategory(List<Document> documents, String category) {
        if (category == null || category.isBlank() || "all".equalsIgnoreCase(category.trim())) {
            return documents;
        }
        String keyword = switch (category.trim().toLowerCase()) {
            case "interview" -> "面经";
            case "jd" -> "jd";
            case "resume" -> "简历";
            default -> null;
        };
        if (keyword == null) {
            return documents;
        }
        return documents.stream()
                .filter(d -> String.valueOf(d.getMetadata().getOrDefault("filename", ""))
                        .toLowerCase()
                        .contains(keyword))
                .toList();
    }
}
