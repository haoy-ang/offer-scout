package com.haoy.offerscout.interview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link Verdict#from(String)} 的用例。
 * <p>
 * 这个枚举存在的意义就是「不再靠字符串猜」—— 所以容错层必须用测试钉住，
 * 否则哪天有人"顺手优化"一下判据顺序（把"通过"提到最前面），
 * 「不建议通过」就会被判成 PASS，而这类错误在页面上表现为**配色更乐观**，肉眼极难发现。
 */
class VerdictTest {

    @Test
    @DisplayName("模型守约：直接给枚举名，大小写和空格都不影响")
    void parsesExactEnumNames() {
        assertEquals(Verdict.PASS, Verdict.from("PASS"));
        assertEquals(Verdict.PENDING, Verdict.from("pending"));
        assertEquals(Verdict.REJECT, Verdict.from("  Reject  "));
    }

    @Test
    @DisplayName("模型回了中文：能认出来")
    void parsesChineseLabels() {
        assertEquals(Verdict.PASS, Verdict.from("建议通过"));
        assertEquals(Verdict.PENDING, Verdict.from("待定"));
        assertEquals(Verdict.REJECT, Verdict.from("不建议通过"));
    }

    @Test
    @DisplayName("否定必须先于肯定判断：'不通过'不能被 '通过' 抢先匹配")
    void negativeWinsOverPositive() {
        assertEquals(Verdict.REJECT, Verdict.from("不通过"));
        assertEquals(Verdict.REJECT, Verdict.from("未通过"));
        assertEquals(Verdict.REJECT, Verdict.from("建议不通过"));
        assertEquals(Verdict.REJECT, Verdict.from("本次面试不推荐"));
    }

    @Test
    @DisplayName("空值返回 null —— 交给上游判'这次复盘不可用'并重试，不擅自替模型拍结论")
    void blankReturnsNull() {
        assertNull(Verdict.from(null));
        assertNull(Verdict.from(""));
        assertNull(Verdict.from("   "));
    }

    @Test
    @DisplayName("认不出来的说法判'待定'：宁可低估，不可高估")
    void unknownFallsBackToPending() {
        assertEquals(Verdict.PENDING, Verdict.from("表现一般般吧"));
        assertEquals(Verdict.PENDING, Verdict.from("勉强通过"));
    }

    /**
     * 契约测试：**接口往返之后仍然只有三个值**。
     * <p>
     * 这条比 {@code from()} 的用例更重要 —— 它守住的是前后端之间的约定：
     * 模型可能给中文，但前端拿到的必须是 {@code "PASS"} 这种枚举名。
     * 如果哪天有人把 {@code @JsonValue} 去掉（Jackson 会退化成序列化枚举名，看起来"也一样"），
     * 或者把 {@code @JsonCreator} 删掉（反序列化只认枚举名，模型给中文就整场复盘重试），
     * 这个用例会先红。
     */
    @Test
    @DisplayName("JSON 往返：模型给中文能读进来，输出给前端的是枚举名")
    void jsonRoundTripKeepsContract() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // 模型不守约，回中文 —— 仍然要能读进来，且归到正确的档
        assertEquals(Verdict.REJECT, mapper.readValue("\"不建议通过\"", Verdict.class));
        assertEquals(Verdict.PASS, mapper.readValue("\"PASS\"", Verdict.class));

        // 输出侧：给前端的是枚举名，不是中文（中文改文案不该影响前端判断）
        assertEquals("\"PASS\"", mapper.writeValueAsString(Verdict.PASS));
        assertEquals("\"REJECT\"", mapper.writeValueAsString(Verdict.REJECT));
    }
}
