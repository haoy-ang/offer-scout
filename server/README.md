# Offer 侦察兵（offer-scout）

> 一个基于 Spring AI 的智能求职助手：帮求职者做简历诊断、岗位匹配、模拟面试和求职知识问答。

![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?style=flat-square)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-6DB33F?style=flat-square)
![MySQL](https://img.shields.io/badge/MySQL-8-4479A1?style=flat-square)
![Redis](https://img.shields.io/badge/Redis-8-DC382D?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

**状态**：功能主干已完成 —— 后端单测 47 个全过（含真实模型集成测试）；测试按「依赖什么外部服务」分三档，CI 流水线已就位。
配套前端在独立仓库 `offer-scout-web`（Vue 3 + Vite + TS）。

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
| 登录鉴权 | JWT 无状态登录 + BCrypt 密码哈希 + 会话归属到用户（含越权防护） | ✅ 已实现 |
| 简历诊断 | 上传 PDF 简历，解析后按目标岗位给出评分与逐条修改建议 | ✅ 已实现 |
| 岗位匹配 | 简历对照 JD，算出「离这个岗位差多远」并逐条对出命中与差距 | ✅ 已实现 |
| 模拟面试 | AI 扮演面试官多轮追问，结束给结构化复盘与通过结论 | ✅ 已实现 |

## 技术栈

- Java 21 + Spring Boot 3.4
- Spring AI 1.0 + Spring AI Alibaba（DashScope / qwen-plus / text-embedding-v3）
- RAG 知识库检索增强（Markdown 文档切分 + 向量检索 + 磁盘缓存）
- ReAct 自主规划智能体（工具调用循环 + 状态机）
- MySQL 8 + Spring JDBC（对话记忆持久化、会话元数据、用户表）
- Redis 8（接口限流 + 检索结果缓存 + JWT 登出黑名单）
- JWT（jjwt 0.12）+ BCrypt 密码哈希（只引 `spring-security-crypto`，不引整套 Spring Security）
- SSE 流式输出
- Vue 3 + Vite（前端，独立仓库 `offer-scout-web`）

## 接口一览

所有接口挂在 `/api` 下（`server.servlet.context-path`）。「限流」列是每个接口的额度（同一 IP，60 秒窗口）。

### 鉴权

| 接口 | 说明 | 需登录 | 限流 |
|---|---|---|---|
| `POST /api/auth/register` | 注册（成功直接返回 token） | 否 | 5/min |
| `POST /api/auth/login` | 登录 | 否 | 10/min |
| `POST /api/auth/logout` | 登出（把当前 token 的 jti 记进 Redis 黑名单） | 是 | 不限 |
| `GET /api/auth/me` | 当前登录用户信息 | 是 | 不限 |

> 登录/注册接口的限流不是"防烧钱"，而是**防暴力破解与批量注册**——没有限流的登录接口，
> 等于给攻击者留了一条无限次试密码的通道。

### 业务

| 接口 | 说明 | 需登录 | 限流 |
|---|---|---|---|
| `GET /api/health/check` | 健康检查 | 否 | 不限 |
| `GET /api/ai/job/chat/sync` | 普通对话（同步，带多轮记忆） | 是 | 20/min |
| `GET /api/ai/job/chat/sse` | 普通对话（SSE 流式） | 是 | 20/min |
| `GET /api/ai/job/chat/rag` | 知识库问答（RAG 检索增强） | 是 | 10/min |
| `GET /api/ai/job/chat/rag/sse` | 知识库问答（SSE 流式） | 是 | 10/min |
| `GET /api/ai/agent/run` | 求职规划 Agent（同步，跑完整循环后返回结论） | 是 | 3/min |
| `GET /api/ai/agent/run/sse` | 求职规划 Agent（SSE，逐步推送思考与工具调用） | 是 | 3/min |
| `GET /api/ai/conversation/list` | 当前用户的会话列表（按最后活跃时间倒序） | 是 | 60/min |
| `GET /api/ai/conversation/{id}/messages` | 某个会话的聊天记录（仅限本人） | 是 | 不限 |
| `DELETE /api/ai/conversation/{id}` | 删除会话（元数据与消息一起清，仅限本人） | 是 | 不限 |
| `POST /api/ai/resume/analyze` | 简历诊断（`multipart/form-data`，传 `file` 或 `text`，可选 `targetJob`） | 是 | 5/min |
| `POST /api/ai/match/analyze` | 岗位匹配（`multipart/form-data`，简历 `file`/`text` + 必填 `jd`） | 是 | 5/min |
| `POST /api/ai/interview/start` | 开始模拟面试，返回会话 ID 与面试官开场白（`role` 可选 / `type` = tech\|hr\|mixed） | 是 | 5/min |
| `GET /api/ai/interview/chat/sse` | 回答一轮，面试官追问（SSE 流式，带 `sessionId`） | 是 | 30/min |
| `POST /api/ai/interview/report` | 结束面试，生成结构化复盘（带 `sessionId`） | 是 | 5/min |

需登录的接口统一用请求头 `Authorization: Bearer <token>` 携带凭证；
未携带或凭证失效时返回 `401` + `{"code":401,"message":"请先登录"}`。

接口文档：<http://localhost:8124/api/swagger-ui.html>

## 知识库

素材放在 `src/main/resources/document/`，支持 `.md`，每次 `---` 分隔线或标题会切出一个独立检索片段。

```
document/
├── 小林coding-后端面经汇总-Java方向.md   # 50 家公司的后端面经真题（约 1000+ 片段）
├── Java后端岗位JD汇总-2027校招.md        # 15 条 2027 届真实岗位 JD，按方向分三档
└── 技术简历写法精要.md                   # 简历写法与面试追问链条，按诊断单条上限拆节
```

> 面经素材来自第三方（小林coding 等），仅本地学习使用、**不随仓库分发**（见 `.gitignore`）。
> 克隆后如需完整的知识库检索体验，请自行补充一份同名文件；仓库内自制的 JD 与简历写法素材可直接使用。

**向量库缓存**：首次启动会对全部片段调用 Embedding 接口并落盘到 `.vector-cache/`。
缓存 key 由「文档内容 + 文件名 + embedding 模型名」摘要算出，改任何一篇素材都会自动失效重建，
不需要手动清理，也不会读到过期向量。

> 排查经验：如果某次启动突然开始全量重新向量化（日志里刷满 `Calling EmbeddingModel`），
> 优先怀疑两件事 —— ① 素材内容变了；② **`spring.ai.dashscope.embedding.options.model` 没读到**，
> 导致缓存 key 里的模型名退化成默认值 `unknown`，摘要自然对不上。
> 第二种情况的典型元凶是 yml 缩进（见「踩坑记录」）。

## 快速开始

1. 申请阿里云百炼 API Key：<https://bailian.console.aliyun.com/>
2. 准备 MySQL 8（默认 `localhost:3306`）。**数据库不用手动建** ——
   连接串里有 `createDatabaseIfNotExist=true`，表结构由 `src/main/resources/db/schema.sql` 自动初始化。
3. 准备 Redis（默认 `localhost:6379`，无密码）。用于接口限流计数、检索结果缓存、JWT 登出黑名单。
4. 配置密钥与数据库连接——两种方式任选其一：
   - 设置环境变量 `DASHSCOPE_API_KEY`（以及可选的 `JWT_SECRET`）
   - 或直接编辑 `src/main/resources/application-local.yml`（含 DashScope Key、MySQL 账号密码、JWT 密钥）
5. 启动：

```bash
./mvnw spring-boot:run
```

> 首次启动需要对知识库做全量向量化（1000+ 片段），会慢几分钟；之后命中缓存，秒起。
> MySQL 与 Redis 都是核心依赖，任一未启动应用都会启动失败。

## 测试

测试按「依赖什么外部服务」分成三档，用 JUnit 5 的 `@Tag` 标注。
这样同一套用例既能本地全量跑，也能在 CI 里只跑**不需要密钥**的那部分。

| 档位 | 标签 | 依赖 | 用例 |
|---|---|---|---|
| 纯 JVM | （无标签） | 无 —— 手动 `new`，不启 Spring | `StructuredOutputRetryTest`、`ConversationServiceOwnershipTest`、`JobDocumentLoaderTest` |
| 需要基础设施 | `requires-infra` | MySQL / Redis | `RateLimitInterceptorTest`、`RedisCacheServiceTest`、`JobKnowledgeToolCacheTest`、`OfferScoutApplicationTests` |
| 需要真实大模型 | `requires-ai` | 有效 DashScope API Key | `JobAppTest`、`JobRagTest`、`OfferScoutAgentTest`、`ConversationPersistenceTest` |

三种跑法：

```bash
# ① 只跑纯 JVM 档：秒级、不依赖任何服务、不花钱 —— 改完逻辑先跑这个
./mvnw test -DexcludedGroups=requires-ai,requires-infra

# ② 跑 ① + ②（CI 用的就是这条）：需要本机 MySQL / Redis
./mvnw test -DexcludedGroups=requires-ai

# ③ 全量：会真实调用大模型、消耗额度，按需再跑
./mvnw test
```

### 为什么这么分

- **`requires-ai` 必须能一键排除。** 只要有一个用例需要真实密钥，整个流水线就绑死了 secret 和额度 ——
  而本项目的大模型调用是**按量计费**的，不能让每次 push 都花钱。排除掉它，CI 才能对每个 PR 免费跑。
- **`requires-infra` 用 service container 兜住，不跳过。** Redis / MySQL 在 CI 里由容器提供
  （见 `.github/workflows/ci.yml`），所以这一档在流水线里是**照常执行**的。跳过它，CI 就只剩个编译检查，价值大打折扣。
- **纯 JVM 档刻意不启 Spring 上下文。** 像重试逻辑这种纯函数，手工 `new` 就能完整覆盖，
  没必要为了测一个 `for` 循环去加载向量库和 MySQL —— 快，而且不依赖环境。

> **CI 怎么做到不需要任何密钥**：流水线设 `SPRING_PROFILES_ACTIVE=ci`，加载
> `src/test/resources/application-ci.yml` —— 一份只连 localhost、只用**占位密钥**的配置。
> 它放在 `src/test/resources`，只在跑测试时上 classpath，不会进打包产物。
>
> ⚠️ 占位密钥**不会让调用快速失败**。DashScope 的调用套在 Spring AI 的 `RetryTemplate` 里，
> 401（`InvalidApiKey`）同样会被重试并退避 —— 实测一条"误入 CI 的 `requires-ai` 用例"跑了
> 2 分半才报错（日志里是 `Retry error. Retry count: 1`）。所以核对"分档有没有生效"时，
> 要看 `Tests run` 的**数字**，不能只看"跑完了"。

## 数据存储

| 表 | 归属 | 作用 |
|---|---|---|
| `SPRING_AI_CHAT_MEMORY` | Spring AI 框架读写 | 对话消息正文 |
| `conversation` | 本项目维护 | 会话元数据（归属用户、标题、消息数、活跃时间） |
| `app_user` | 本项目维护 | 业务用户（用户名、BCrypt 密文、昵称） |

两处容易踩的坑，都已在本项目里处理：

1. **Spring AI 1.0.0 没带 MySQL 的建表脚本**（只有 hsqldb / mariadb / postgresql / sqlserver）。
   平台自动探测出 `mysql` 后会去找 `schema-mysql.sql`，找不到直接启动失败。
   → 配置 `initialize-schema: never`，建表交给 `db/schema.sql`。
2. **MySQL 8 不支持 `CREATE INDEX IF NOT EXISTS`**（那是 MariaDB 语法）。
   → 索引写进 `CREATE TABLE` 的 `KEY` 子句，配合 `IF NOT EXISTS` 天然幂等。

另外 `MessageWindowChatMemory` 是**滑动窗口**：保存时会用裁剪后的窗口覆盖数据库，
所以 `SPRING_AI_CHAT_MEMORY` 里只保留最近 `maxMessages` 条，不是全量聊天记录。

### 给已存在的库补列（`SchemaMigrator`）

`CREATE TABLE IF NOT EXISTS` 只对**全新的库**有效：老库上 `conversation` 表已经存在，
后来新增的 `user_id` 列不会被补上。而 MySQL 8 **没有** `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
（那是 MariaDB 的扩展），纯 SQL 脚本里做不到幂等。

→ 项目用 `config/SchemaMigrator`（`ApplicationRunner`）在启动时查 `information_schema`，
缺什么补什么，反复启动只有第一次会真的改表。正式项目的正解是 Flyway / Liquibase，本项目暂不引入。

> ⚠️ 生产环境不要用应用启动来自动改表结构。大表 `ALTER` 可能锁表几十分钟，
> 把 DDL 和发布绑在一起会导致发布失败甚至雪崩。正确做法是先单独执行 DDL（必要时用 gh-ost），
> 确认无风险再发代码。这里这么做只是因为本地库表小、无流量。

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

## 登录鉴权（JWT）

### 整体流程

```
注册/登录  →  服务端签发 JWT（HS256，subject = userId，jti = UUID）
          →  前端存起来，之后每个请求带 Authorization: Bearer <token>
          →  AuthInterceptor 校验签名与有效期 → 把身份放进 UserContext（ThreadLocal）
          →  业务代码从 UserContext 取 userId
登出      →  把该 token 的 jti 写进 Redis 黑名单，TTL = token 剩余有效期
```

### 几个关键取舍

**1. 为什么用 JWT 而不是 Session？**
Session 要把会话状态存在服务端（内存或 Redis），水平扩容时要么做会话粘滞、要么把 Session 外置。
JWT 把「谁、何时签发、何时过期」**签名后交给客户端保存**，服务端只验证签名、不用存 —— 天然适合无状态扩容。

**2. 但"无状态"带来一个直接后果：签发出去的 token 在过期前无法撤回。**
用户点了登出，服务端并没有任何地方可以"删掉"这个 token。解法是配合 Redis 做**黑名单**：
登出时把 `jti` 记进 Redis 并设置 TTL = 剩余有效期，校验时先查黑名单。
TTL 一到自动删除，Redis 不会无限增长 —— 因为黑名单只需要记住"还没过期的已作废 token"。

**3. 为什么手写拦截器而不是引整套 Spring Security？**
Spring Security 的强项是「过滤器链 + 方法级鉴权 + 一整套默认防护」，生产项目应该用它。
本项目接口少、无表单提交（CSRF 不适用）、无 Session（会话固定攻击不适用），
引整套框架要额外处理 SecurityFilterChain 顺序、默认 403 而非 401、Swagger 放行等一堆细节，
调试成本高于收益。**每一行都自己写的价值是：JWT 鉴权流程能被完整讲清楚。**
代价也要认：CSRF 防护、安全响应头、并发会话控制这些默认保护，本项目是没有的。

**4. 密码用 BCrypt，不用 MD5 / SHA。**
MD5/SHA 是"快"哈希，显卡一秒能撞几十亿次，配彩虹表基本等于明文。
BCrypt 内置可调工作因子（慢）+ 自带盐（免疫彩虹表），校验时用 `matches(明文, 密文)` 重算比对。

**5. 登录失败时「用户不存在」和「密码错误」返回同一句提示。**
如果提示不同，攻击者就能靠报错差异批量枚举出哪些用户名真实存在（**用户名枚举**）。
安全上宁可让用户多试一次，也不泄露账号是否存在。

### 越权防护：会话归属

会话 ID 是**前端传上来的**，只按 ID 查就等于"知道 ID 就能读" —— 这类漏洞叫
**IDOR（不安全的直接对象引用）**，是接口层最常见的一类越权。防护落在三处：

| 场景 | 做法 |
|---|---|
| 会话列表 | `WHERE user_id = ?`，只可能列出自己的 |
| 读/删他人会话 | 先校验归属 → `403 无权访问该会话`（删不存在的会话则是幂等的 `200 false`） |
| **往他人会话里写** | `INSERT IGNORE` 在 ID 已存在时静默跳过，**必须回头确认归属**，否则用户 A 的消息会被追加进用户 B 的会话记忆，下一轮 B 提问时模型会把 A 的话当成 B 自己说过的上下文 |

> 归属校验统一放在 `ConversationService` 里，而不是散在各个 Controller ——
> 这样"忘了判"的成本从"每个新增接口都可能漏"降到"只有一个地方要维护"。

### 两个必须记住的 ThreadLocal 坑

`UserContext` 是 ThreadLocal 实现的，它有两个经典陷阱（本项目都处理了）：

1. **用完必须清理。** Tomcat 用线程池、线程会复用，不在 `afterCompletion` 里 `remove()`，
   下一个请求就会"继承"上一个用户的身份 —— 这是 ThreadLocal 最经典的越权漏洞。
2. **换线程就丢。** 异步链路（`doOnComplete` 跑在 Reactor 线程、`CompletableFuture.runAsync` 跑在线程池）
   读不到请求线程上的 ThreadLocal。所以身份要么**在请求线程上先取出来、显式传参**
   （`JobApp` 的 `userId` 参数就是这么来的），要么确保鉴权在异步开始前已经完成
   （`@RequireLogin` 在 `preHandle` 阶段就拦下了未登录请求，Agent 的异步线程里不需要再判一次）。

## 简历诊断

上传一份简历（PDF 或粘贴文本），从**技术栈清晰度、项目经历深度、量化表达、表达与结构**四个维度逐项打分，
并给出「把 XX 改成 XX」级别的可执行修改建议。核心链路只有三步：

```
上传 PDF ──PagePdfDocumentReader(PdfBox)──▶ 提取纯文本
        │                                    │
        └──（无 PDF 时）直接粘贴文本 ◀────────┘
                    │
                    ▼
        知识库检索「简历写法」参考 ──▶ 拼进提示词
                    │
                    ▼
        ChatClient.call().entity(ResumeDiagnosis.class) ──▶ 结构化 JSON 返回
```

几个关键设计：

1. **结构化输出，而不是让模型吐一段评测文字。** `ResumeDiagnosis` 是 `record` + 嵌套 `record`，
   `.entity(ResumeDiagnosis.class)` 会把它自动转成 JSON Schema 塞进提示词，模型照着吐 JSON，
   Spring AI 再反序列化回来。字段名、类型、层级在编译期就定死，前端不用猜结构。
2. **评分用 `int` 不用浮点。** 大模型对"72.4 分"没有精度可言，反而容易吐 `"72"` 或 `72.0` 造成类型不匹配。
   整数约束它输出整数，解析更稳。
3. **裸 `ChatClient`，不带记忆 Advisor。** 简历诊断是无状态的一次性调用，绝不能复用挂会话记忆的
   `JobApp` Client——否则上一份简历的内容会泄漏进下一份诊断里。
4. **PDF 只提取文本层。** PdfBox 拿不到扫描件/图片型 PDF 的正文，提取为空时返回明确的 400 提示，
   而不是返回一份空诊断。接口同时接受「粘贴文本」作为这条路的兜底。
5. **检索失败不阻断诊断。** 知识库召回失败时降级为"按通用简历标准诊断"，而不是整个接口 500。

## 岗位匹配

拿一份简历对照一个岗位 JD，算出「离这个岗位差多远」。链路和简历诊断同构（复用 `ResumeParser` 解析简历），
区别在两点：

1. **不检索知识库**。诊断需要「一份好简历长什么样」这个外部标准，所以去召回写法资料；
   匹配的标准就是 **JD 本身**（它要什么技能、什么经验，白纸黑字写着），直接逐条对照即可，
   省一次 embedding 调用。
2. **输出多了「命中点 / 差距点」**（`strengths` / `gaps`）。这才是匹配的核心价值：
   把 JD 里的每一条要求，和你简历里「有 / 没有」的证据一一对出来，而不是只给一个总分。
   差距点尤其要写透「缺了它对这个岗位意味着什么」，而不是一句"缺这个技能"。

## 模拟面试

和前面两个功能最大的不同：**它是本项目第一个真正的多轮交互**。诊断和匹配都是"一份材料进、一份报告出"，
面试则要求面试官"记得住"前面问过什么、候选人答过什么 —— 这才是追问的前提。

```
开始 ──▶ 面试官开场 + 第 1 问（写入记忆）
           │
回答一轮 ──▶ 面试官带记忆追问（SSE 流式，继续写记忆）   ← 循环 6~8 轮
           │
结束 ──▶ 从记忆抽出整场记录 ──▶ 结构化复盘（能不能过 + 四维打分 + 短板怎么补）
```

**1. 靠 `ChatMemory` 而不是自己拼历史。** 每一轮都用同一个 `sessionId` 把消息写进
`MessageWindowChatMemory`（落 MySQL），下一轮自动把历史带进上下文。面试官因此能"记得"你上一题
答过什么。`MessageWindowChatMemory` 是滑动窗口（收窄到最近 N 条），一场面试十几轮远没到上限。

**2. 两个 `ChatClient` 分工，是刻意的。**
- `interviewClient`：**挂了** `MessageChatMemoryAdvisor`，负责开场和逐轮追问。
- `reviewClient`：**裸的**，负责复盘。复盘输入就是"整场记录"本身，再叠一层记忆只会把历史重复喂一遍。
这和简历诊断"绝不复用带记忆的 Client"是同一个道理，只是反过来用。

**3. 面试官人设是动态的，所以每次现场拼 system prompt。** 它依赖岗位方向和面试类型（技术面 / HR 面 /
综合面），不能做成 `defaultSystem`。拼接同样用 `String.replace` 换 `{{TYPE}}`、`{{ROLE}}`，
不用 `formatted()`（见踩坑 6：提示词里的字面 `%` 会被当格式符）。

**4. 面试中不评价，复盘才评价。** 提示词里明确要求面试官只提问、不解答、不给建议 ——
否则模型很容易"边问边教"，一场面试就变成了答疑，失去模拟的价值。所有评估集中到一次复盘里。

**5. 越权防护：没业务表可查归属时，把归属编进 ID。** 面试会话不落 `conversation` 表
（它是一次性、不需要列在档案里的），所以没法像普通会话那样 `WHERE user_id = ?`。
做法是让 `sessionId = itv-<userId>-<uuid>`，服务端只认「前缀 == 当前用户」的 ID，否则 `403`。
这仍然是 IDOR 防护，只是判据从"查库"换成了"查 ID 本身"。

**6. 复盘结构里多一个 `verdict`。** `InterviewReport` 除了分维度打分，还直接给出
「建议通过 / 待定 / 不建议通过」——这才是模拟面试最有价值的产出：不是"你哪里差"，而是"这场能不能过"。

> 复盘的输入是整场对话转写。这里有个细节：开场的"提示语"是以 USER 身份发出去的，
> 会被记忆记下来，拼记录时必须**把它排除**，否则会被当成"候选人说的第一句话"污染评估。

## 踩坑记录

### 1. yml 缩进：顶级键插错位置，子键会静默"换爹"

YAML 只靠缩进表达层级。JWT 配置最初被插在 `spring.data.redis` 和 `spring.ai` 之间，
而 `offerscout:` 写在第 0 列 —— **这一行直接结束了上面的 `spring:` 块**，
于是紧跟其后、仍然缩进 2 格的 `ai:` 被当成了 `offerscout` 的子键：

```yaml
spring:
  data:
    redis: ...
offerscout:        # ← 顶级键：spring 块到此结束
  jwt: ...
  ai:              # ← 本该是 spring.ai，现在变成了 offerscout.ai
    dashscope: ...
```

后果：`spring.ai.dashscope.api-key` 凭空消失，启动报 `DashScope API key must be set`。
**最阴的是 yml 本身语法没毛病、不报解析错误，只是键悄悄换了位置。**

> 约定：**自定义配置块一律放在文件末尾**，不要夹在 `spring:` 中间。

### 2. `@JsonIgnore` 只是减少噪音，不是安全手段

`Conversation` 记录里的 `userId` 标了 `@JsonIgnore`（前端不需要它，它一定等于当前登录用户）。
但真正防越权的是 SQL 里的 `WHERE user_id = ?`，**不是序列化时藏字段** ——
把"藏字段"当成权限控制，是很多项目里真实存在的误解。

### 3. `delete` 的 404 vs 幂等

DELETE 在 HTTP 语义里应当**幂等**：删一个已经不存在的东西，结果和删掉它是同一个状态。
报 404 只会制造麻烦（两个标签页同时删、前端重试、测试清理都会撞上）。
但「会话存在却不属于你」必须是 **403** —— 那是明确的越权尝试，不能悄悄当成功。

### 4. 给已有库补列时的两个 MySQL 事实

- MySQL 8 **没有** `ADD COLUMN IF NOT EXISTS`（MariaDB 才有）→ 只能查 `information_schema` 再决定。
- `CREATE INDEX IF NOT EXISTS` 同理不可用。

### 5. 鉴权上线前的历史会话会"消失"

`conversation.user_id` 为 `NULL` 的行代表鉴权上线前遗留的无主会话。
它们**没有被删除**，只是按 `WHERE user_id = ?` 查不出来。项目刻意**不自动认领**——
所有权不该猜。要收编的话手动执行一次：

```sql
-- 把无主会话全部划给某个用户（把 1 换成 app_user.id）
UPDATE conversation SET user_id = 1 WHERE user_id IS NULL;
```

### 6. `String.formatted()` 会把提示词里的字面 `%` 当格式占位符

简历诊断的 system 提示词里有一句「性能提升 **X%**、处理量 Y…」。用 `""".formatted(reference)` 拼接时，
`formatted()` 会把**整段文本里所有的 `%`** 都当格式说明符解析：`X%` 后面的「、」不是合法转换符，
直接抛 `UnknownFormatConversionException`（提示词里真正的 `%s` 占位符反而成了无辜群众）。

教训：**含中文 / 含 `%` 的提示词不要用 `String.formatted()`**，改用 `replace` 换一个不会出现在正文里的
token（本项目用 `{{REFERENCE}}`）。这个坑在写 prompt 模板时几乎必踩。

### 7. `consumes = MULTIPART_FORM_DATA_VALUE` 会让「非 multipart 请求」在鉴权前就 415

`@PostMapping(consumes = MULTIPART_FORM_DATA_VALUE)` 的 `consumes` 是**路由匹配条件**，
请求的 Content-Type 不匹配时，`RequestMappingHandlerMapping` 直接判不中这个 handler，
拦截器（含鉴权拦截器）根本不会执行。所以一个**没带 multipart 头、也没带 token** 的请求拿到的是
`415 Unsupported Media Type`，而不是预期的 `401`。

这不是安全漏洞（415 同样是拒绝），但验证鉴权链路时容易误判"鉴权失效了"。
真实前端永远用 `FormData` 上传（自动带 multipart 头），不受影响；写脚本验证时记得也给空请求挂个
`new FormData()`。

## 目录结构

```
offer-scout/
├── src/main/java/com/haoy/offerscout/
│   ├── OfferScoutApplication.java   启动类
│   ├── controller/                  HTTP / SSE 接口层
│   ├── app/                         求职助手应用层（对话）
│   ├── chat/                        会话元数据与记忆管理（含归属校验）
│   ├── auth/                        JWT 签发校验、拦截器、登出黑名单、UserContext
│   ├── user/                        用户注册登录与仓储
│   ├── resume/                      简历 PDF 解析 + 结构化诊断（ResumeParser/ResumeService/ResumeDiagnosis）
│   ├── match/                       岗位匹配（JobMatchService/JobMatch，复用 resume.ResumeParser）
│   ├── interview/                   模拟面试（InterviewService 多轮追问 + InterviewReport 结构化复盘）
│   ├── common/                      业务异常与全局异常处理
│   ├── cache/                       Redis 缓存封装
│   ├── ratelimit/                   接口限流（注解 + 拦截器 + Lua 计数）
│   ├── config/                      安全 / 记忆 / MVC 拦截器 / Schema 迁移等配置
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

前端 `offer-scout-web/src/`：

```
├── api/client.ts        统一接口封装（带凭证、401 处理、SSE 解析）
├── stores/session.ts    登录态（token + 当前用户）
├── router/index.ts      路由表 + 登录守卫
├── views/               对话 / 知识库问答 / 作战规划 / 会话档案 / 简历诊断 / 岗位匹配 / 模拟面试 / 登录
└── components/          左栏、消息卡、输入框
```

## 说明

- `application-local.yml` 已加入 `.gitignore`，请勿把 API Key 写进其他会被提交的文件。
- `JWT_SECRET` 环境变量在生产环境必须改成随机值（`openssl rand -base64 48`）；
  HS256 要求密钥至少 256 bit，配置里的占位值仅为本地开发方便。
- `.vector-cache/` 也已忽略：体积大且可由 `document/` 重建。
- `OfferScoutAgent` 是 **prototype** 作用域。它有可变状态（执行状态、消息上下文），
  绝不能做成单例在并发请求间共享。
