# Offer 侦察兵（offer-scout）

> 一个基于 Spring AI 的智能求职助手：帮求职者做简历诊断、岗位匹配、模拟面试和求职知识问答。

## 项目定位

求职过程中最耗时间的不是刷题，而是**信息检索和反复试错**：这个岗位到底适不适合我？我的简历卡在哪？
面试官会追问什么？Offer 侦察兵想做的，是把这些环节交给一个会自主规划的 AI 智能体。

它和普通的"AI 问答机器人"的区别在于——**主动出击**：
不只回答问题，还能自己决定查什么资料、查几次、什么时候给结论，最终输出可执行的行动建议。

## 功能规划

| 模块 | 说明 | 状态 |
|---|---|---|
| 知识问答 | 基于面经、岗位 JD 知识库的检索增强问答（RAG） | ✅ 已实现 |
| 求职规划 Agent | 自主检索知识库 → 产出结构化准备方案（ReAct 循环） | ✅ 已实现 |
| 简历诊断 | 上传 PDF 简历，解析后按目标岗位给出评分与逐条修改建议 | ⬜ 规划中 |
| 岗位匹配 | 自主检索岗位要求，与简历做对比，输出匹配度报告 | ⬜ 规划中 |
| 模拟面试 | 扮演技术面试官 / HR，多轮追问并给出复盘 | ⬜ 规划中 |

## 技术栈

- Java 21 + Spring Boot 3.4
- Spring AI 1.0 + Spring AI Alibaba（DashScope / qwen-plus / text-embedding-v3）
- RAG 知识库检索增强（Markdown 文档切分 + 向量检索 + 磁盘缓存）
- ReAct 自主规划智能体（工具调用循环 + 状态机）
- SSE 流式输出
- Vue 3 + Vite（前端，独立仓库 `offer-scout-web`）

## 接口一览

所有接口挂在 `/api` 下（`server.servlet.context-path`）。

| 接口 | 说明 |
|---|---|
| `GET /api/health/check` | 健康检查 |
| `GET /api/ai/job/chat/sync` | 普通对话（同步，带多轮记忆） |
| `GET /api/ai/job/chat/sse` | 普通对话（SSE 流式） |
| `GET /api/ai/job/chat/rag` | 知识库问答（RAG 检索增强） |
| `GET /api/ai/agent/run` | 求职规划 Agent（同步，跑完整循环后返回结论） |
| `GET /api/ai/agent/run/sse` | 求职规划 Agent（SSE，逐步推送思考与工具调用） |

接口文档：<http://localhost:8124/api/swagger-ui.html>

## 知识库

素材放在 `src/main/resources/document/`，支持 `.md`，每次 `---` 分隔线或标题会切出一个独立检索片段。

```
document/
├── 小林coding-后端面经汇总-Java方向.md   # 50 家公司的后端面经真题（约 1000+ 片段）
├── Java后端岗位JD样本.md                 # 岗位 JD 样本（请替换成你真实投递的岗位）
└── 技术简历写法参考.md                   # 简历写法与面试追问链条
```

**向量库缓存**：首次启动会对全部片段调用 Embedding 接口并落盘到 `.vector-cache/`。
缓存 key 由「文档内容 + 文件名 + embedding 模型名」摘要算出，改任何一篇素材都会自动失效重建，
不需要手动清理，也不会读到过期向量。

## 快速开始

1. 申请阿里云百炼 API Key：<https://bailian.console.aliyun.com/>
2. 配置密钥——两种方式任选其一：
   - 设置环境变量 `DASHSCOPE_API_KEY`
   - 或直接编辑 `src/main/resources/application-local.yml`
3. 启动：

```bash
./mvnw spring-boot:run
```

> 首次启动需要对知识库做全量向量化（1000+ 片段），会慢几分钟；之后命中缓存，秒起。

## 目录结构

```
offer-scout/
├── src/main/java/com/haoy/offerscout/
│   ├── OfferScoutApplication.java   启动类
│   ├── controller/                  HTTP / SSE 接口层
│   ├── app/                         求职助手应用层（对话）
│   ├── rag/                         知识库加载 + 向量库配置
│   ├── tools/                       AI 可调用的工具（知识库检索 / 时间 / 终止）
│   ├── agent/                       ReAct 自主规划智能体
│   │   ├── BaseAgent.java           状态机 + 执行循环
│   │   ├── ReActAgent.java          think / act 抽象
│   │   ├── ToolCallAgent.java       工具调用的 think / act 实现
│   │   ├── OfferScoutAgent.java     具体求职 Agent（prototype 作用域）
│   │   └── model/AgentState.java    代理状态枚举
│   └── advisor/                     自定义 Advisor（日志、增强）
└── src/main/resources/
    ├── application.yml              主配置
    ├── application-local.yml        本地配置（含密钥，不入库）
    └── document/                    知识库原始文档
```

## 说明

- `application-local.yml` 已加入 `.gitignore`，请勿把 API Key 写进其他会被提交的文件。
- `.vector-cache/` 也已忽略：体积大且可由 `document/` 重建。
- `OfferScoutAgent` 是 **prototype** 作用域。它有可变状态（执行状态、消息上下文），
  绝不能做成单例在并发请求间共享。
