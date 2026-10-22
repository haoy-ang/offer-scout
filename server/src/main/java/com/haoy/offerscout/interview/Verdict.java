package com.haoy.offerscout.interview;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 面试复盘结论。
 * <p>
 * <b>为什么要有这个枚举？</b>因为原先 {@code verdict} 是个自由字符串，
 * 模型被要求输出「建议通过 / 待定 / 不建议通过」，但它并不总是守约 ——
 * 可能写成「不通过」「基本通过」「建议再看看」。前端为了配色只能靠
 * {@code includes("通过")} 这类字符串嗅探猜，猜错的后果是
 * **「没过」显示成「待定」那种错配色，比不配色更误导人**。
 * <p>
 * 现在契约下移到类型系统：后端只认这三个值，前端拿到的就是这三个之一，
 * 不需要猜。配色这类表现层判断从此是 {@code switch}，不是正则。
 * <p>
 * <b>那 {@link #from(String)} 里的中文兜底是不是又把嗅探加回来了？</b>
 * 是，但性质不同 —— 它是**模型不守约时的容错层，全局只此一处**，
 * 而原先是每个视图各猜一遍、口径还不一致。契约在前（prompt 要求输出枚举名），
 * 容错在后（兜底只保证不因为一个词的差异整场复盘作废）。
 */
public enum Verdict {

    /** 建议通过 */
    PASS("建议通过"),

    /** 待定：有明显短板但也并非完全不合格 */
    PENDING("待定"),

    /** 不建议通过 */
    REJECT("不建议通过");

    /** 给人看的中文表述。接口层不返回它（前端有自己的文案映射），但日志和兜底要用 */
    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 序列化成枚举名（PASS / PENDING / REJECT），而不是中文。
     * <p>
     * 理由：接口契约要稳定。中文文案哪天想改（比如「不建议通过」改成「暂不推荐」），
     * 不应该影响前端判断。
     */
    @JsonValue
    public String json() {
        return name();
    }

    /**
     * 反序列化：模型返回什么都能接住。
     * <p>
     * 空值返回 {@code null} —— 让上游的完整性校验（{@code InterviewService#isUsable}）判不过、
     * 触发一次重试，而不是在这里擅自替模型拍一个结论。
     */
    @JsonCreator
    public static Verdict from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();

        // ① 正常路径：模型守约，给的就是枚举名
        switch (text.toUpperCase(Locale.ROOT)) {
            case "PASS" -> {
                return PASS;
            }
            case "PENDING" -> {
                return PENDING;
            }
            case "REJECT" -> {
                return REJECT;
            }
            default -> {
                // 继续走下面的兜底
            }
        }

        // ② 容错路径：模型回了中文或别的说法。
        // 判据顺序仍然是「先否定、后肯定」—— 否则「不通过」会被 "通过" 抢先匹配成 PASS。
        if (text.contains("不建议") || text.contains("不通过") || text.contains("未通过")
                || text.contains("淘汰") || text.contains("不推荐")) {
            return REJECT;
        }
        if (text.contains("待定") || text.contains("再看看") || text.contains("勉强")) {
            return PENDING;
        }
        if (text.contains("通过") || text.contains("推荐")) {
            return PASS;
        }
        // 实在认不出来：判「待定」而不是「通过」。宁可低估，不可高估。
        return PENDING;
    }
}
