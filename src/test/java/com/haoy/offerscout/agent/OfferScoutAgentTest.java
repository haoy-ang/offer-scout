package com.haoy.offerscout.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Agent 集成测试。
 * <p>
 * 注意：会真实调用大模型并触发多轮工具调用，消耗的额度比普通对话高得多，别频繁跑。
 */
@SpringBootTest
class OfferScoutAgentTest {

    @Resource
    private ObjectProvider<OfferScoutAgent> agentProvider;

    /**
     * 验证 prototype 作用域：每次取出来的都必须是新对象。
     * 这条断言是在守住「Agent 有可变状态、绝不能跨请求共享」这个前提。
     */
    @Test
    void testPrototypeScope() {
        OfferScoutAgent first = agentProvider.getObject();
        OfferScoutAgent second = agentProvider.getObject();

        Assertions.assertNotSame(first, second,
                "Agent 必须是 prototype，否则并发请求会互相污染状态");
        Assertions.assertEquals(3, first.getToolCount(), "应该注册了 3 个工具（知识库检索 / 时间 / 终止）");
    }

    /**
     * 端到端跑一个真实求职任务，观察 Agent 是否会自己检索知识库、然后产出结构化建议。
     */
    @Test
    void testRunJobTask() {
        OfferScoutAgent agent = agentProvider.getObject();

        String answer = agent.run("我想投字节跳动的 Java 后端实习，帮我梳理一下该重点准备什么");

        System.out.println("===== Agent 执行步骤 =====");
        agent.getStepLog().forEach(System.out::println);
        System.out.println("===== 最终回答 =====");
        System.out.println(answer);

        Assertions.assertNotNull(answer, "最终回答不能为空");
        Assertions.assertFalse(answer.isBlank(), "最终回答不能是空白");
    }
}
