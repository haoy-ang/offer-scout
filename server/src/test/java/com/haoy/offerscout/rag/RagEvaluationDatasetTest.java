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
 * <p>
 * <b>为什么要有 {@code source} 和 {@code expectNoHit} 两个字段</b>：
 * <ul>
 *   <li>{@code source=material}：问题从知识库素材改写而来。与 {@code freeform} 分组对照，
 *       用来检验「改措辞会不会掉召回」—— 实测结论是<b>不会</b>（embedding 是语义匹配），
 *       所以偏置风险在<b>话题覆盖范围</b>，不在措辞。</li>
 *   <li>{@code expectNoHit=true}：素材里压根没有这个话题（Go / Rust / K8s / 算法），
 *       正确行为是**召不到**。没有这类样本，就测不出"阈值=0 时对超纲问题 100% 硬召回"这件事。</li>
 * </ul>
 */
class RagEvaluationDatasetTest {

    private static final String EVAL_FILE_PATH = "eval/rag-eval-set.jsonl";

    /** 从素材改写的题：至少 30 条，保证与历史数据可比 */
    private static final int MIN_MATERIAL = 30;

    /** 自由构造的题：至少 15 条正样本，措辞必须与素材不同源 */
    private static final int MIN_FREEFORM_POSITIVE = 15;

    /** 超纲负样本：至少 3 条 */
    private static final int MIN_NEGATIVE = 3;

    @Test
    @DisplayName("评估集文件存在、结构与分布符合规范（含素材题 / 自由问法 / 超纲负样本）")
    void evalDatasetIntegrityCheck() throws Exception {
        ClassPathResource resource = new ClassPathResource(EVAL_FILE_PATH);
        assertThat(resource.exists())
                .as("评估集文件 %s 必须存在于 classpath", EVAL_FILE_PATH)
                .isTrue();

        int totalCount = 0;
        int material = 0;
        int freeformPositive = 0;
        int negative = 0;
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
                String source = json.getStr("source");
                String question = json.getStr("question");
                boolean expectNoHit = json.getBool("expectNoHit", false);
                List<String> mustContain = json.getBeanList("mustContain", String.class);
                List<String> mustNotContain = json.getBeanList("mustNotContain", String.class);

                assertThat(id).as("第 %d 行必须包含有效的 id", totalCount).isNotNull();
                assertThat(ids.add(id)).as("id=%d 重复", id).isTrue();

                assertThat(category).as("id=%d 必须包含 category", id).isNotBlank();
                categories.add(category);

                assertThat(source)
                        .as("id=%d 的 source 必须是 material 或 freeform（用来分组统计，不能缺）", id)
                        .isIn("material", "freeform");

                assertThat(question).as("id=%d 必须包含 question", id).isNotBlank();
                assertThat(question.length()).as("id=%d 问题描述不能过短", id).isGreaterThanOrEqualTo(10);

                if (expectNoHit) {
                    negative++;
                    assertThat(source).as("id=%d 超纲题应属于 freeform（不来自素材）", id).isEqualTo("freeform");
                    assertThat(category).as("id=%d 超纲题归入「超纲」类目", id).isEqualTo("超纲");
                } else {
                    if ("material".equals(source)) {
                        material++;
                    } else {
                        freeformPositive++;
                    }
                    assertThat(mustContain).as("id=%d 必须包含 mustContain 期望关键词列表", id).isNotEmpty();
                    assertThat(mustNotContain).as("id=%d 必须包含 mustNotContain 噪音关键词列表", id).isNotEmpty();
                }
            }
        }

        assertThat(material)
                .as("素材改写题不足（当前 %d 条，至少 %d 条）—— 少于历史量会导致与旧结果不可比", material, MIN_MATERIAL)
                .isGreaterThanOrEqualTo(MIN_MATERIAL);
        assertThat(freeformPositive)
                .as("自由问法正样本不足（当前 %d 条，至少 %d 条）—— 没有它就算不出同源偏置", freeformPositive, MIN_FREEFORM_POSITIVE)
                .isGreaterThanOrEqualTo(MIN_FREEFORM_POSITIVE);
        assertThat(negative)
                .as("超纲负样本不足（当前 %d 条，至少 %d 条）—— 没有它测不出硬召回", negative, MIN_NEGATIVE)
                .isGreaterThanOrEqualTo(MIN_NEGATIVE);

        assertThat(categories)
                .as("评估集必须完整覆盖 面经、岗位JD、简历写作 三大核心业务场景")
                .contains("面经", "岗位JD", "简历写作");

        assertThat(totalCount).as("总样本数").isEqualTo(material + freeformPositive + negative);
    }
}
