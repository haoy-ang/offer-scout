# Offer 侦察兵（offer-scout）

> 一个基于 Spring AI 的智能求职助手：帮求职者做简历诊断、岗位匹配、模拟面试和求职知识问答。

## 项目定位

求职过程中最耗时间的不是刷题，而是**信息检索和反复试错**：这个岗位到底适不适合我？我的简历卡在哪？
面试官会追问什么？Offer 侦察兵想做的，是把这些环节交给一个会自主规划的 AI 智能体。

它和普通的"AI 问答机器人"的区别在于——**主动出击**：
不只回答问题，还能自己搜岗位、比对简历、给出匹配度报告，并模拟面试官连续追问。

## 功能规划

| 模块 | 说明 |
|---|---|
| 简历诊断 | 上传 PDF 简历，解析后按目标岗位给出评分与逐条修改建议 |
| 岗位匹配 | 自主搜索岗位信息，与简历做对比，输出匹配度报告 |
| 模拟面试 | 扮演技术面试官 / HR，多轮追问并给出复盘 |
| 知识问答 | 基于面经、八股、岗位 JD 知识库的检索增强问答 |

## 技术栈

- Java 21 + Spring Boot 3.4
- Spring AI 1.0 + Spring AI Alibaba（DashScope / qwen-plus）
- RAG 知识库检索增强
- ReAct 自主规划智能体
- SSE 流式输出
- Vue 3 + Vite（前端，独立仓库 `offer-scout-web`）

## 快速开始

1. 申请阿里云百炼 API Key：<https://bailian.console.aliyun.com/>
2. 配置密钥——两种方式任选其一：
   - 设置环境变量 `DASHSCOPE_API_KEY`
   - 或直接编辑 `src/main/resources/application-local.yml`
3. 启动：

```bash
./mvnw spring-boot:run
```

4. 访问接口文档：<http://localhost:8124/api/swagger-ui.html>
5. 健康检查：<http://localhost:8124/api/health/check>

## 目录结构

```
offer-scout/
├── src/main/java/com/haoy/offerscout/
│   ├── OfferScoutApplication.java   启动类
│   ├── controller/                  HTTP / SSE 接口层
│   ├── app/                         求职助手应用层（对话、诊断、匹配）
│   ├── rag/                         知识库检索增强
│   ├── tools/                       AI 可调用的工具
│   ├── agent/                       自主规划智能体
│   └── advisor/                     自定义 Advisor（日志、增强）
└── src/main/resources/
    ├── application.yml              主配置
    ├── application-local.yml        本地配置（含密钥，不入库）
    └── document/                    知识库原始文档
```

## 说明

`application-local.yml` 已加入 `.gitignore`，请勿把 API Key 写进其他会被提交的文件。
