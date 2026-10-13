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
| 会话持久化 | 对话记忆落 MySQL，重启不丢；会话列表 / 历史 / 删除 | ✅ 已实现 |
| 接口限流 | 基于 Redis 的固定窗口限流，按调用成本分档，超限返回 429 | ✅ 已实现 |
| 检索缓存 | 知识库检索结果按 query 缓存，省掉重复的 Embedding 调用 | ✅ 已实现 |
| 简历诊断 | 上传 PDF 简历，解析后按目标岗位给出评分与逐条修改建议 | ⬜ 规划中 |
| 岗位匹配 | 自主检索岗位要求，与简历做对比，输出匹配度报告 | ⬜ 规划中 |
| 模拟面试 | 扮演技术面试官 / HR，多轮追问并给出复盘 | ⬜ 规划中 |
| 登录鉴权 | 会话归属到用户维度 | ⬜ 规划中 |

## 技术栈

- Java 21 + Spring Boot 3.4
- Spring AI 1.0 + Spring AI Alibaba（DashScope / qwen-plus / text-embedding-v3）
- RAG 知识库检索增强（Markdown 文档切分 + 向量检索 + 磁盘缓存）
- ReAct 自主规划智能体（工具调用循环 + 状态机）
- MySQL 8 + Spring JDBC（对话记忆持久化、会话元数据）
- Redis 8（接口限流 + 检索结果缓存）
- SSE 流式输出
- Vue 3 + Vite（前端，独立仓库 `offer-scout-web`）

## 接口一览

所有接口挂在 `/api` 下（`server.servlet.context-path`）。「限流」列是每个接口的额度（同一 IP，60 秒窗口）。

| 接口 | 说明 | 限流 |
|---|---|---|
| `GET /api/health/check` | 健康检查 | 不限 |
| `GET /api/ai/job/chat/sync` | 普通对话（同步，带多轮记忆） | 20/min |
| `GET /api/ai/job/chat/sse` | 普通对话（SSE 流式） | 20/min |
| `GET /api/ai/job/chat/rag` | 知识库问答（RAG 检索增强） | 10/min |
| `GET /api/ai/job/chat/rag/sse` | 知识库问答（SSE 流式） | 10/min |
| `GET /api/ai/agent/run` | 求职规划 Agent（同步，跑完整循环后返回结论） | 3/min |
| `GET /api/ai/agent/run/sse` | 求职规划 Agent（SSE，逐步推送思考与工具调用） | 3/min |
| `GET /api/ai/conversation/list` | 会话列表（按最后活跃时间倒序） | 60/min |
| `GET /api/ai/conversation/{id}/messages` | 某个会话的聊天记录 | 不限 |
| `DELETE /api/ai/conversation/{id}` | 删除会话（元数据与消息一起清） | 不限 |

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
2. 准备 MySQL 8（默认 `localhost:3306`）。**数据库不用手动建** ——
   连接串里有 `createDatabaseIfNotExist=true`，表结构由 `src/main/resources/db/schema.sql` 自动初始化。
3. 准备 Redis（默认 `localhost:6379`，无密码）。用于接口限流计数与检索结果缓存。
4. 配置密钥与数据库连接——两种方式任选其一：
   - 设置环境变量 `DASHSCOPE_API_KEY`
   - 或直接编辑 `src/main/resources/application-local.yml`（含 DashScope Key 与 MySQL 账号密码）
5. 启动：

```bash
./mvnw spring-boot:run
```

> 首次启动需要对知识库做全量向量化（1000+ 片段），会慢几分钟；之后命中缓存，秒起。
> MySQL 与 Redis 都是核心依赖，任一未启动应用都会启动失败。

## 数据存储

| 表 | 归属 | 作用 |
|---|---|---|
| `SPRING_AI_CHAT_MEMORY` | Spring AI 框架读写 | 对话消息正文 |
| `conversation` | 本项目维护 | 会话元数据（标题、消息数、活跃时间），供会话列表使用 |

两处容易踩的坑，都已在本项目里处理：

1. **Spring AI 1.0.0 没带 MySQL 的建表脚本**（只有 hsqldb / mariadb / postgresql / sqlserver）。
   平台自动探测出 `mysql` 后会去找 `schema-mysql.sql`，找不到直接启动失败。
   → 配置 `initialize-schema: never`，建表交给 `db/schema.sql`。
2. **MySQL 8 不支持 `CREATE INDEX IF NOT EXISTS`**（那是 MariaDB 语法）。
   → 索引写进 `CREATE TABLE` 的 `KEY` 子句，配合 `IF NOT EXISTS` 天然幂等。

另外 `MessageWindowChatMemory` 是**滑动窗口**：保存时会用裁剪后的窗口覆盖数据库，
所以 `SPRING_AI_CHAT_MEMORY` 里只保留最近 `maxMessages` 条，不是全量聊天记录。

## 限流与缓存（Redis）

这个项目每个接口背后都是**按量计费**的大模型调用：一次普通对话 = 一次 LLM 请求，
一次 Agent 任务要跑 3~8 步、每步一次完整请求，RAG 还要额外调 embedding。
没有防护的话，一个 `for` 循环就能把额度刷空 —— 所以这两件事在这里不是装饰，是必需品。

### 接口限流：`@RateLimit`

在 Controller 方法上加一个注解即可，拦截逻辑见 `ratelimit/RateLimitInterceptor`：

```java
@GetMapping("/chat/rag")
@RateLimit(limit = 10, windowSeconds = 60)
public String chatWithRag(@RequestParam String message) { ... }
```

三个设计点：

1. **用拦截器而不是 AOP**。限流是 HTTP 层的事：判断维度要拿客户端 IP，拒绝时得直接写状态码和响应体。
   `HandlerInterceptor.preHandle` 天然拿得到 `HttpServletRequest/Response`，比在切面里
   用 `RequestContextHolder` 绕一圈更直接。
2. **计数必须用 Lua 保证原子性**。最直觉的写法是 `INCR` 之后再 `EXPIRE`，但这是两条独立命令 ——
   如果 INCR 成功而 EXPIRE 没执行（进程挂了 / 连接断了），这个 key 就**永不过期，用户被永久限流**。
   把两条命令写进一个 Lua 脚本，Redis 会把它当一条命令原子执行。
3. **Redis 故障时选择放行**。限流是保护措施，保护措施自己出故障时，正确做法是降级为不限流并告警，
   而不是让全站跟着不可用。

超限返回 `429` + `Retry-After` 头 + 中文提示：

```json
{"code":429,"message":"Agent 任务比较耗资源，每分钟最多发起 3 次，请稍后再试。","retryAfter":58}
```

> ⚠️ 这是**固定窗口**计数，窗口边界处最坏可能放过 2 倍请求（前 1 秒打满 + 后 1 秒打满）。
> 要更精确得换成滑动窗口（sorted set 按时间戳记流水，或令牌桶）。
> 对"保护成本"这个目标是够用的，但面试被问到时要知道这个代价。

### 检索缓存：`RedisCacheService`

`JobKnowledgeTool` 的检索结果按 `(query, category)` 缓存 30 分钟，键是内容的 SHA-256 前 32 位：

```
offerscout:cache:rag:search:v1:<fingerprint>     TTL 1800s
```

- **为什么值得缓存**：每次检索都要先把 query 送进 embedding 模型，而 embedding 是**按量计费**的。
  面经类问题天然高度重复（"MySQL 索引失效"会被反复问），答第二遍不该再付一次钱。
- **空结果也缓存**："查不到"同样花了一次 embedding，而且这类问题往往会被反复追问。
- **键里有版本号 `v1`**：改返回格式时把它 +1，老缓存自动失效。否则会有最多 30 分钟的窗口
  返回旧格式文本，排查起来很费劲 —— 这是"缓存格式化后的字符串"必然要付的代价。
- **不直接拿 query 当键**：收敛成定长指纹，避免键过长、含空格和特殊字符。
- **缓存异常全部吞掉**：Redis 是加速件不是必需品，它挂了业务要能退回直接检索，只是慢一点。

> 目前缓存只覆盖 **Agent 的知识库检索**。`/chat/rag` 走的是 `QuestionAnswerAdvisor` 的检索链路，
> 想连它一起缓存，需要用装饰器包一层 `VectorStore`（`CachingVectorStore implements VectorStore`），
> 代价是要处理 `Document` 的序列化。留作后续。

## 目录结构

```
offer-scout/
├── src/main/java/com/haoy/offerscout/
│   ├── OfferScoutApplication.java   启动类
│   ├── controller/                  HTTP / SSE 接口层
│   ├── app/                         求职助手应用层（对话）
│   ├── chat/                        会话元数据与记忆管理
│   ├── cache/                       Redis 缓存封装
│   ├── ratelimit/                   接口限流（注解 + 拦截器 + Lua 计数）
│   ├── config/                      对话记忆、MVC 拦截器等 Bean 配置
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
    ├── application-local.yml        本地配置（含密钥与数据库密码，不入库）
    ├── db/schema.sql                业务表结构
    └── document/                    知识库原始文档
```

## 说明

- `application-local.yml` 已加入 `.gitignore`，请勿把 API Key 写进其他会被提交的文件。
- `.vector-cache/` 也已忽略：体积大且可由 `document/` 重建。
- `OfferScoutAgent` 是 **prototype** 作用域。它有可变状态（执行状态、消息上下文），
  绝不能做成单例在并发请求间共享。
