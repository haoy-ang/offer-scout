package com.haoy.offerscout.agent;

import com.haoy.offerscout.common.TimeContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Offer 侦察兵 —— 具体的求职规划 Agent。
 * <p>
 * 它把「检索知识库 + 查时间 + 主动收尾」串成一个自主循环：
 * 用户丢一句模糊的诉求进来，Agent 自己决定查什么、查几次、什么时候给结论。
 * <p>
 * <b>作用域必须是 prototype</b>：{@link BaseAgent} 持有可变状态（state / messageList），
 * 单例 Bean 在并发请求下会互相污染。prototype 保证每次请求拿到一个干净实例，
 * 因此这里必须显式声明作用域，不能沿用 Spring 默认的单例。
 */
@Component
@Scope("prototype")
public class OfferScoutAgent extends ToolCallAgent {

    private static final String SYSTEM_PROMPT = """
            你是「Offer 侦察兵」智能体，专攻互联网技术岗求职，重点方向是 Java 后端。

            ## 你可以调用的工具
            必须主动使用工具获取真实信息，不要凭记忆作答：
            1. searchJobKnowledge —— 检索本地求职知识库，内含各互联网公司的后端面经真题、
               岗位 JD 要求、技术简历写法资料。这是你最核心的信息来源。
            2. getCurrentDateTime —— 获取精确到时分秒的系统时间。系统提示末尾已经告知今天的日期，
               需要精确时间、或想再核对一遍日期时才调用它。
            3. doTerminate —— 任务完成后调用它收尾。

            ## 工作方式
            1. 先判断任务缺哪些关键信息（目标公司、岗位方向、当前进度）。
               缺就先向用户提问，但一次不要问超过三个问题。
            2. 检索关键词要具体：公司名 + 岗位方向 + 技术点。一次检索不够就换关键词再查，
               不要只查一次就下结论。
            3. 输出必须结构化、可执行：用 Markdown 分节，先给结论，再给具体动作。
               禁止出现「多刷题」「加强项目深度」这类没有信息量的建议 ——
               要说清楚复习哪个知识点、简历里哪一句该怎么改。
            4. 引用知识库内容时标注来源文件名。
            5. 不确定的信息要明说不确定，绝不编造面试题或岗位要求。

            ## 输出风格
            简体中文，面向 2027 届正在求职的学生。简洁、直接，不寒暄、不客套。
            """;

    private static final String NEXT_STEP_PROMPT = """
            请判断当前任务是否还需要调用工具：
            - 信息不够 —— 继续调用 searchJobKnowledge 或 getCurrentDateTime；
            - 信息够了 —— 调用 doTerminate 结束资料收集。
              （系统随后会再给你一轮，专门用来输出完整的最终建议，不用在这一轮写长文。）
            不要用完全相同的参数重复调用同一个工具。
            """;

    public OfferScoutAgent(ChatModel dashscopeChatModel, ToolCallback[] offerScoutTools) {
        super(offerScoutTools);
        setName("OfferScout");
        // 提示词末尾补上「今天几号」：模型的知识停在训练截止日，不告诉它日期，
        // 它会拿训练期的年份理解"今年/现在/还剩几个月"。口径统一走 TimeContext。
        setSystemPrompt(SYSTEM_PROMPT + TimeContext.promptSection());
        setNextStepPrompt(NEXT_STEP_PROMPT);
        // 8 步足够完成「检索 → 补充检索 → 产出结论」；再多就是模型在绕圈，纯烧 token
        setMaxSteps(8);
        setChatClient(ChatClient.builder(dashscopeChatModel).build());
    }
}
