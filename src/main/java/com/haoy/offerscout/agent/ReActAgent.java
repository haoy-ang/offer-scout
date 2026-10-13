package com.haoy.offerscout.agent;

import com.haoy.offerscout.agent.model.AgentState;
import lombok.extern.slf4j.Slf4j;

/**
 * ReAct（Reasoning + Acting）代理抽象类。
 * <p>
 * 核心思想：把「想」和「做」拆成两个可独立测试的方法，循环执行。
 * <ul>
 *   <li>{@link #think()} 问模型：现在该做什么？要不要调工具？</li>
 *   <li>{@link #act()} 真正把工具跑起来，把结果喂回上下文</li>
 * </ul>
 * 循环直到模型不再要求调用工具（说明它认为可以回答了），或触发终止工具。
 */
@Slf4j
public abstract class ReActAgent extends BaseAgent {

    /**
     * 思考：决定下一步是否需要行动。
     *
     * @return true 表示需要执行 {@link #act()}；false 表示模型已经给出最终回答
     */
    public abstract boolean think();

    /**
     * 行动：执行模型请求的工具调用。
     *
     * @return 本步的执行结果摘要
     */
    public abstract String act();

    @Override
    public String step() {
        try {
            boolean shouldAct = think();
            if (!shouldAct) {
                // 模型不再要求调工具，说明它已经准备好作答 —— 立刻收工。
                // 这里主动置为 FINISHED，而不是等模型自己调用 doTerminate：
                // 否则主循环会带着同样的上下文反复问模型，白烧 token 直到 maxSteps。
                setState(AgentState.FINISHED);
                return "思考完成，无需调用工具，任务结束";
            }
            return act();
        } catch (Exception e) {
            // 单步失败不直接终止整个任务，交给下一轮 think 重新决策
            log.error("[{}] 第 {} 步执行失败", getName(), getCurrentStep(), e);
            return "本步执行失败：" + e.getMessage();
        }
    }
}
