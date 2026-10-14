# Offer 侦察兵（OfferScout）

> 面向 2027 届技术岗求职者的 AI 助手 —— 从「我该怎么准备」到「这场面试我差在哪」，
> 把求职过程中最高频的几个问题，交给一个能自主检索、能结构化输出的 Agent。

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-6DB33F?style=flat-square)
![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?style=flat-square&logo=vuedotjs&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178C6?style=flat-square&logo=typescript&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

## 它能做什么

| 能力 | 说明 |
|---|---|
| 通用对话 | 多轮记忆落 MySQL；可随时开关「检索知识库」 |
| 知识库问答（RAG） | 先检索本地的面经 / 岗位 JD / 简历写法素材，再据此作答 |
| 作战规划 Agent | ReAct 循环自主决定查什么、查几次，SSE 实时推送思考与工具调用，可中途停止 |
| 会话档案 | 历史会话列表、回看、从某次会话继续对话、清理 |
| 简历诊断 | 上传 PDF 或粘贴文本，逐项打分并给出可执行的改法 |
| 岗位匹配 | 简历对照 JD，输出命中点与差距清单 |
| 模拟面试 | AI 面试官多轮追问（技术 / HR / 混合），结束后生成结构化复盘 |

## 技术栈

### 后端 · `server/`

- **Java 21 + Spring Boot 3.4 + Spring AI 1.0**
- 大模型走 **阿里云百炼（DashScope）**：通义千问负责对话与结构化输出，Embedding 模型负责向量化
- **MySQL 8** 持久化会话与消息；**Redis** 承担接口限流、检索结果缓存、JWT 登出黑名单
- **JWT 鉴权** + 会话归属校验（防越权）；**SSE** 流式输出，同步接口保留作为兜底
- 向量库用 Spring AI 的 `SimpleVectorStore`，首次启动构建后落盘缓存到 `.vector-cache/`

包结构：

```
com.haoy.offerscout
├── agent/        ReAct Agent 与工具调用循环（BaseAgent / ToolCallAgent / OfferScoutAgent）
├── app/          对话入口（ChatClient 装配 + 记忆 + 知识库 Advisor）
├── rag/          知识库加载、切分与向量库构建
├── tools/        Agent 可调用的工具（知识库检索 / 日期 / 收尾）
├── resume/ match/ interview/   三个结构化 AI 能力
├── auth/ user/   JWT 鉴权、会话归属、用户
├── chat/         会话与消息持久化
├── cache/ ratelimit/   Redis 缓存与接口限流
├── config/       Spring 配置（记忆窗口、建表迁移、安全、MVC）
├── common/       异常处理、结构化输出重试、时间上下文
└── controller/   REST 入口
```

### 前端 · `web/`

- **Vue 3 + Vite + TypeScript + vue-router**
- **自研 SSE 解析器** —— 浏览器的 `EventSource` 只支持 GET、不能带请求头，也无法优雅中止，
  所以改用 `fetch` + `ReadableStream`，既能带 `Authorization`，也能通过 `AbortSignal` 实现「停止生成」
- **模块级 store 管理跨页状态**（切页不丢结果），刻意不引入 Pinia / Vuex
- 设计系统集中在 `src/styles/base.css`，组件只写 scoped 样式

## 目录结构

```
.
├── server/                        后端（Spring Boot 3.4 + Spring AI）
│   ├── src/main/java/...          见上方包结构
│   ├── src/main/resources/
│   │   ├── document/              知识库素材（Markdown）
│   │   ├── db/schema.sql          建表脚本（启动时自动执行）
│   │   └── application.yml        主配置（密钥走 gitignore 的 application-local.yml）
│   └── src/test/                  测试按依赖分三档：纯 JVM / requires-infra / requires-ai
├── web/                           前端（Vue 3 + Vite + TypeScript）
│   └── src/
│       ├── api/         接口封装（凭证、401/429 处理、SSE 解析）
│       ├── stores/      模块级状态
│       ├── router/      路由表 + 登录守卫
│       ├── views/       页面
│       └── styles/      设计系统
└── .github/workflows/             CI（后端 / 前端各一条，按 paths 过滤）
```

## 快速开始

前置：**JDK 21**、**MySQL 8**、**Redis**，以及一个阿里云百炼 API Key。

**后端**

```bash
cd server

# 配置密钥二选一：
#   ① 设置环境变量 DASHSCOPE_API_KEY
#   ② 编辑 src/main/resources/application-local.yml（含 DashScope Key、MySQL 账号密码、JWT 密钥）

./mvnw spring-boot:run        # Windows 用 mvnw.cmd；默认监听 8124，接口前缀 /api
```

**前端**

```bash
cd web
npm install
npm run dev                   # 默认 5173，/api 已代理到本机 8124
```

> 首次启动后端会为全部知识库片段计算向量并落盘（视素材量约 1~3 分钟），之后启动直接读缓存。
> 想跳过等待可以先把 `document/` 清空，或直接等一次。

更细的说明（限流策略、测试分档、踩坑记录等）见 **`server/README.md`** 与 **`web/README.md`**。

## 关于知识库

`server/src/main/resources/document/` 下是 RAG 的检索素材：

| 文件 | 是否入库 |
|---|---|
| `Java后端岗位JD汇总-2027校招.md` | ✅ 随仓库分发 |
| `技术简历写法精要.md` | ✅ 随仓库分发 |
| 面经汇总（第三方内容） | ❌ 仅本地学习使用，**不随仓库分发**（见 `.gitignore`） |

> 克隆本仓库后如需完整的知识库检索体验，请自行补充一份面经素材（文件名带「面经」两字即可被识别）；
> 仓库内的 JD 与简历写法素材可直接使用，相关功能也能正常运行。

素材切分依赖 Markdown 标题（`##`~`####`）与 `---` 分隔线；**代码块与 `>` 引用块内的内容不会被收录**，
这是加载器有意为之的取舍。

## 一些设计上的取舍

- **Agent Bean 必须是 `prototype`** —— 它持有可变状态（步骤状态、消息列表），
  单例在并发请求下会互相污染。「每次请求一个干净实例」这个约束比省一次对象创建重要得多。
- **Agent 的中止是「步骤粒度」** —— `abort()` 只置一个 `volatile` 标志，主循环在**步与步之间**检查它。
  单步里的模型调用是阻塞的、无法打断，所以从调用 `abort()` 到真正停下，最长还要等当前这步跑完。
- **提示词里的「当前日期」统一收口** —— 后端有 5 处各自构建 `ChatClient` 的提示词，
  靠 `common/TimeContext.promptSection()` 统一注入，避免「有的地方拼了、有的地方漏了」，
  让模型误以为现在是训练数据截止的年份。
- **结构化输出统一走重试兜底** —— 简历诊断、岗位匹配、面试复盘都要求模型返回 JSON，
  `StructuredOutputRetry` 会在解析失败时重试，而不是把半截 JSON 抛给前端。
- **状态清理绑在登录态上，而不是绑定在「登出按钮」上** —— token 过期（401）不经过登出按钮，
  如果只在按钮里清理，换个账号登进来会看到上一个用户的报告。

## License

[MIT](LICENSE)
