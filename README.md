# Offer 侦察兵 · 前端（offer-scout-web）

> Offer 侦察兵（`offer-scout`）的 Web 客户端 —— Vue 3 + Vite + TypeScript。

![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?style=flat-square)
![Vite](https://img.shields.io/badge/Vite-6-646CFF?style=flat-square)
![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178C6?style=flat-square)
![ESLint](https://img.shields.io/badge/ESLint-10-4B32C3?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

后端仓库为 `offer-scout`（Java 21 + Spring Boot 3.4 + Spring AI）。**前端必须配合后端运行**，
所有接口都走 `/api`，开发期由 Vite 代理到 `localhost:8124`。

## 页面

| 路由 | 页面 | 说明 |
|---|---|---|
| `/chat` | 对话 | 带多轮记忆的通用求职咨询 |
| `/rag` | 知识库问答 | 先检索面经/岗位资料再作答 |
| `/agent` | 作战规划 | 自主检索并产出结构化准备方案 |
| `/archive` | 会话档案 | 历史会话记录与清理 |
| `/resume` | 简历诊断 | 上传简历，AI 逐项打分并给出改法 |
| `/match` | 岗位匹配 | 简历对照 JD，算出离这个岗位差多远 |
| `/interview` | 模拟面试 | AI 面试官多轮追问，结束给结构化复盘 |
| `/login` | 登录 | 不套左栏外壳的独立版式 |

左栏导航直接由路由表的 `meta.no` / `meta.label` 生成 —— **新增页面只需加一条路由，导航会自动出现**。

## 开发

```bash
npm install

# 先启动后端（另一个仓库），监听 8124
npm run dev          # 默认 5173，通过 Vite 代理访问 /api
```

## 质量校验

```bash
npm run type-check   # vue-tsc，类型检查（不含 lint 能覆盖的问题）
npm run lint         # ESLint（扁平配置）
npm run format:check # Prettier 格式检查
npm run build        # 产物构建
```

CI（`.github/workflows/ci.yml`）会依次跑上面这四步。顺序刻意**由快到慢**：类型检查最先失败时，
不必等一次完整构建才知道哪里错了。

## 几个刻意的设计

- **不用 Pinia / Vuex。** 需要跨组件共享的只有「token + 当前用户」两个字段，
  用 `stores/session.ts`（`ref` + `localStorage`）就够了。约束：**这个文件不 import 任何 api 模块**，
  否则会和 `api/client.ts` 形成循环依赖。
- **自己写 SSE 解析器，不用浏览器的 `EventSource`。** 它只支持 GET、不能自定义请求头（带不了
  `Authorization`）、并且流正常结束时也会触发 `error`，很难区分「结束」和「失败」。
  改用 `fetch` + `ReadableStream`，既能带请求头，也能通过 `AbortSignal` 实现「停止生成」。
  解析器还要兼容 Spring `SseEmitter` 只给首行加 `data:` 前缀的行为（详见 `api/client.ts` 注释）。
- **鉴权统一收口在 `api/client.ts`。** 每个调用点不需要记得加 `Authorization` 头，
  401 会被统一处理成「清凭证 + 跳登录页（带 redirect）」。登录/注册接口标了 `anonymous`，
  因为密码错误同样是 401，不能被误判成「会话过期」。
- **设计方向「侦察简报 / 档案」。** 暖纸底、墨黑字、朱砂强调，质感来自版式、留白和纸纹，
  而不是渐变与圆角气泡 —— 目的是让它看起来不像又一个通用 AI 聊天框。样式基线在 `styles/base.css`。

## 目录

```
src/
├── api/client.ts        统一接口封装（凭证、401 处理、SSE 解析）
├── stores/session.ts    登录态（token + 当前用户）
├── router/index.ts      路由表 + 登录守卫
├── styles/base.css      设计系统（CSS 变量 / 纸纹底 / Markdown 排版 / 动效）
├── utils/markdown.ts    markdown-it 渲染 + 复制 + 时间格式化
├── components/          SideRail / MessageCard / MessageComposer
└── views/               8 个页面
```
