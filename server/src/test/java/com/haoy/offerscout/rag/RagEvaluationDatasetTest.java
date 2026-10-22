package com.haoy.offerscout.rag;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯 JVM 测试：守住 RAG 评估集的完整性与格式约束（P1-1 / P1-3b）。
 * <p>
 * 保证 CI 流水线无需真实模型或密钥，也能验证评估集没有被误删、损坏或破坏 schema。
 */
class RagEvaluationDatasetTest {

    private static final String EVAL_FILE_PATH = "eval/rag-eval-set.jsonl";

    @Test
    @DisplayName("评估集文件存在、至少包含 30 条样本且符合结构规范")
    void evalDatasetIntegrityCheck() throws Exception {
        ClassPathResource resource = new ClassPathResource(EVAL_FILE_PATH);
        assertThat(resource.exists())
                .as("评估集文件 %s 必须存在于 classpath", EVAL_FILE_PATH)
                .isTrue();

        int totalCount = 0;
        Set<Integer> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                totalCount++;
                JSONObject json = JSONUtil.parseObj(line);

                Integer id = json.getInt("id");
                String category = json.getStr("category");
                String question = json.getStr("question");
                List<String> mustContain = json.getBeanList("mustContain", String.class);
                List<String> mustNotContain = json.getBeanList("mustNotContain", String.class);

                assertThat(id).as("第 %d 行必须包含有效的 id", totalCount).isNotNull();
                assertThat(ids.add(id)).as("id=%d 重复", id).isTrue();

                assertThat(category).as("id=%d 必须包含 category", id).isNotBlank();
                categories.add(category);

                assertThat(question).as("id=%d 必须包含 question", id).isNotBlank();
                assertThat(question.length()).as("id=%d 问题描述不能过短", id).isGreaterThanOrEqualTo(10);

                assertThat(mustContain).as("id=%d 必须包含 mustContain 期望关键词列表", id).isNotEmpty();
                assertThat(mustNotContain).as("id=%d 必须包含 mustNotContain 噪音关键词列表", id).isNotEmpty();
            }
        }

        assertThat(totalCount)
                .as("评估集总样本数必须达到 30 条以上（当前 %d 条）", totalCount)
                .isGreaterThanOrEqualTo(30);

        assertThat(categories)
                .as("评估集必须完整覆盖 面经、岗位JD、简历写作 三大核心业务场景")
                .contains("面经", "岗位JD", "简历写作");
    }
}
