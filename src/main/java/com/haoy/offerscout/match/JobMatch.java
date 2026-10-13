package com.haoy.offerscout.match;

import java.util.List;

/**
 * 岗位匹配结果（结构化输出）。
 * <p>
 * 和 {@code ResumeDiagnosis} 一样，这个 record 会被 {@code ChatClient.call().entity(JobMatch.class)}
 * 自动转成 JSON Schema 塞进提示词，让大模型照着格式吐 JSON，再由 Spring AI 反序列化回来。
 * <p>
 * <b>和「简历诊断」的区别</b>：诊断回答的是「这份简历本身好不好」，匹配回答的是
 * 「这份简历离这个岗位差多远」。所以多出了 {@code strengths}（命中点）和 {@code gaps}（差距点）
 * 两个字段 —— 它们才是匹配的核心价值：把「JD 要什么」和「你有什么 / 缺什么」一一对出来。
 * <p>
 * 评分同样用 int 不用浮点，理由同诊断：大模型对小数精度没有意义，反而容易吐类型错配的 JSON。
 */
public record JobMatch(
        /** 匹配度 0~100，越高越值得投 */
        int overallScore,
        /** 一句话总评：整体值不值得投 + 最致命的短板 */
        String summary,
        /** 命中点：JD 里要求的、你简历里确实具备的 */
        List<MatchPoint> strengths,
        /** 差距点：JD 里要求的、你简历里缺失或明显不足的 */
        List<MatchPoint> gaps,
        /** 分维度匹配度 */
        List<DimensionScore> dimensions,
        /** 逐条改进建议（按优先级排序） */
        List<Suggestion> suggestions
) {

    /**
     * 一条「要求 vs 现状」的对照。
     *
     * @param requirement JD 里的具体要求（尽量原样引用）
     * @param detail      你简历里对应的证据（命中）或现状（缺失/不足）
     */
    public record MatchPoint(
            String requirement,
            String detail
    ) {
    }

    /** 单一维度的匹配度。name 是维度名，score 0~100，comment 是简短点评 */
    public record DimensionScore(
            String name,
            int score,
            String comment
    ) {
    }

    /**
     * 一条改进建议。
     *
     * @param priority 优先级，取值 high / medium / low
     * @param problem  问题定位 —— 具体到「哪个要求没覆盖」或「哪段经历写得太弱」
     * @param fix      具体改法 —— 给「补 XX 项目 / 把 XX 改成 XX」这种可执行动作
     */
    public record Suggestion(
            String priority,
            String problem,
            String fix
    ) {
    }
}
