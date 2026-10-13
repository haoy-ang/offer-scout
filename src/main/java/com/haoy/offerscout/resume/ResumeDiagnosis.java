package com.haoy.offerscout.resume;

import java.util.List;

/**
 * 简历诊断结果（结构化输出）。
 * <p>
 * 这个结构会被 {@code ChatClient.call().entity(ResumeDiagnosis.class)} 自动转成 JSON Schema
 * 塞进提示词，让大模型照着格式吐 JSON，再由 Spring AI 反序列化回这个对象。
 * <p>
 * <b>用 record + 嵌套 record 而不是 Map：</b>
 * 结构是接口契约的一部分 —— 字段名、类型、层级一旦用 Map 就失去约束，模型输出随意、
 * 前端解析也容易崩。record 让"诊断报告长什么样"在编译期就定死。
 * <p>
 * <b>为什么评分用 int 而不是浮点？</b> 大模型对"72.4 分"这种精度没有意义，
 * 反而容易输出 `"72"` 或 `72.0` 造成类型不匹配。整数约束它输出整数，解析更稳。
 */
public record ResumeDiagnosis(
        /** 总分 0~100 */
        int overallScore,
        /** 一句话总评：这份简历最突出的优点和最大的短板 */
        String summary,
        /** 分维度评分 */
        List<DimensionScore> dimensions,
        /** 逐条修改建议（按优先级排序） */
        List<Suggestion> suggestions
) {

    /** 单一维度的评分。name 是维度名，score 0~100，comment 是简短点评 */
    public record DimensionScore(
            String name,
            int score,
            String comment
    ) {
    }

    /**
     * 一条修改建议。
     *
     * @param priority 优先级，取值 high / medium / low（用英文避免大模型中文枚举不稳定）
     * @param problem  问题定位 —— 尽量引述简历里的原文或描述现象，别泛泛而谈
     * @param fix      具体改法 —— 给"把 XX 改成 XX"这种可执行动作，而不是"多练、多写"
     */
    public record Suggestion(
            String priority,
            String problem,
            String fix
    ) {
    }
}
