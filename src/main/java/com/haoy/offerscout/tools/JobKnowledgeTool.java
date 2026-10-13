package com.haoy.offerscout.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * 求职知识库检索工具。
 * <p>
 * 这是 RAG 从「被动注入」升级为「主动检索」的关键一步：
 * 由模型自己决定什么时候去查、拿什么关键词去查，而不是每轮对话都无脑塞几段资料。
 */
@Slf4j
public class JobKnowledgeTool {

    /** 先多捞一些，再按类别过滤，避免过滤后没剩下东西 */
    private static final int FETCH_TOP_K = 8;

    /** 最终最多返回几条给模型 */
    private static final int MAX_RETURN = 4;

    /** 单条资料最多截断到多少字符，防止把上下文撑爆 */
    private static final int MAX_CHARS_PER_DOC = 1200;

    private final VectorStore vectorStore;

    public JobKnowledgeTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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

        log.info("知识库检索: query={}, category={}", query, category);

        List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(FETCH_TOP_K)
                .similarityThreshold(0.0)
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
            if (text.length() > MAX_CHARS_PER_DOC) {
                text = text.substring(0, MAX_CHARS_PER_DOC) + "…（已截断）";
            }
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
