# YiCLI 项目全解析

> 一款面向商业使用的 Java Agent CLI 产品，对标 Claude Code，由沉默王二主导，历经 23 期迭代演进。

---

## 目录

1. [项目概览](#1-项目概览)
2. [技术栈](#2-技术栈)
3. [整体架构](#3-整体架构)
4. [ReAct Agent（第1期）](#4-react-agent第1期)
5. [Plan+DAG 规划执行（第2期）](#5-plandag-规划执行第2期)
6. [Memory 记忆系统（第3期）](#6-memory-记忆系统第3期)
7. [RAG 检索增强生成（第4期）](#7-rag-检索增强生成第4期)
8. [Multi-Agent 多代理协作（第5期）](#8-multi-agent-多代理协作第5期)
9. [HITL 人机审批（第6期）](#9-hitl-人机审批第6期)
10. [并行工具调用（第7期）](#10-并行工具调用第7期)
11. [多模型适配（第8期）](#11-多模型适配第8期)
12. [联网能力（第9期）](#12-联网能力第9期)
13. [MCP 协议（第10期）](#13-mcp-协议第10期)
14. [长上下文工程（第12期）](#14-长上下文工程第12期)
15. [Chrome DevTools MCP（第13期）](#15-chrome-devtools-mcp第13期)
16. [CDP 会话复用（第14期）](#16-cdp-会话复用第14期)
17. [Skill 技能系统（第15期）](#17-skill-技能系统第15期)
18. [TUI 产品化（第16期）](#18-tui-产品化第16期)
19. [LSP 诊断注入（第17期）](#19-lsp-诊断注入第17期)
20. [Side-Git 快照（第18期）](#20-side-git-快照第18期)
21. [Prompt 分层架构（第19期）](#21-prompt-分层架构第19期)
22. [Runtime API（第20期）](#22-runtime-api第20期)
23. [图片输入（第21期）](#23-图片输入第21期)
24. [微信 iLink 通道（第23期）](#24-微信-ilink-通道第23期)
25. [安全策略层](#25-安全策略层)
26. [总结](#26-总结)

---

## 1. 项目概览

| 字段 | 值 |
|------|-----|
| 项目名 | YiCLI |
| 定位 | 面向商业使用的 Java Agent CLI，对标 Claude Code |
| 语言 | Java 17+ |
| 构建工具 | Maven（shade 插件打 fat JAR） |
| 产物 | `yicli-1.0-SNAPSHOT.jar` |
| 已交付期数 | 23 期 |
| 当前 Banner 版本 | v16.1.0 |

**演进路线（23期）：**

```
ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL
→ 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级
→ 长上下文 → Chrome DevTools → CDP 会话复用 → Skill
→ TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层
→ Runtime API → 图片输入 → （第22期）→ 微信 iLink 通道
```

---

## 2. 技术栈

| 模块 | 技术选型 |
|------|----------|
| 终端交互 | JLine 4.0.0（LineReader / Highlighter / Completer / Status dock）|
| 备用 TUI | Lanterna 3.1.3 |
| JSON | Jackson 2.16.0 |
| HTTP | OkHttp3 4.12.0 |
| 向量数据库 | SQLite 3.49.1.0 + Java 余弦相似度（无外部向量DB依赖）|
| Java AST 解析 | JavaParser 3.28.0 |
| 中文分词 | jieba-analysis 1.0.2 |
| HTML 解析 | JSoup 1.18.1 |
| Git 操作 | JGit 7.6.0（无需系统 git）|
| 二维码 | ZXing 3.5.3（终端 ASCII 二维码）|
| 日志 | Logback 1.5.18 |
| 测试 | JUnit 5.10.2 + Mockito 5.11.0 + OkHttp MockWebServer |

**支持的 LLM 提供商（7家）：**

| Provider | Client 类 | 特性 |
|----------|-----------|------|
| 智谱 GLM | `GLMClient` | 默认 200k 上下文窗口 |
| DeepSeek | `DeepSeekClient` | 强制 HTTP/1.1 避免 SSE 重置；1M 上下文；thinking 模式 |
| StepFun | `StepClient` | 256k 上下文；检测 `STEP_API_KEY` 时自动启用 step_search MCP |
| Moonshot Kimi | `KimiClient` | 256k 上下文；thinking 模式 |
| Free LLM API | `FreeLlmApiClient` | 128k 保守预算 |
| 讯飞星辰 | `XfyunMaaSClient` | LoRA 微调支持；工具不透传 |
| Agnes | `AgnesClient` | OpenAI 兼容；默认 1M 上下文 |

---

## 3. 整体架构

### 三条主执行路径

```
用户输入
   │
   ├─→ [ReAct]          Agent.java              ← 默认模式
   ├─→ [Plan+Execute]   PlanExecuteAgent.java   ← /plan 命令
   └─→ [Multi-Agent]    AgentOrchestrator.java  ← /team 命令
         │
         └─ 三条路径共享：
              ToolRegistry      （11个内置工具 + MCP动态工具）
              MemoryManager     （短期+长期记忆）
              SnapshotService   （Side-Git 快照）
```

### 11 个内置工具

| 工具名 | 功能 |
|--------|------|
| `read_file` | 读取文件内容 |
| `write_file` | 写入文件 |
| `list_dir` | 列出目录 |
| `glob_files` | 文件 glob 匹配 |
| `grep_code` | 代码搜索（优先 ripgrep，回退 Java）|
| `execute_command` | 执行 shell 命令（60s 超时）|
| `create_project` | 创建新项目骨架 |
| `search_code` | RAG 语义检索（模糊场景辅助）|
| `web_search` | 网络搜索 |
| `web_fetch` | 抓取 URL 内容 |
| `revert_turn` | 回滚上一轮操作 |

### 目录结构概览

```
src/main/java/com/yicli/
├── agent/       ReAct / Plan / Orchestrator
├── cli/         CLI 入口、命令解析、补全、高亮
├── memory/      记忆管理（短期+长期+压缩）
├── rag/         向量索引、语义检索
├── llm/         7家 LLM Client
├── plan/        DAG 规划
├── mcp/         MCP 协议（stdio + HTTP）
├── render/      终端渲染（inline TUI）
├── lsp/         LSP 诊断
├── snapshot/    Side-Git 快照
├── browser/     Chrome DevTools Protocol
├── prompt/      Prompt 分层组装
├── web/         联网搜索 + HTML 提取
├── skill/       Skill 注册与加载
├── hitl/        人机审批
├── policy/      安全策略（路径/命令守卫）
├── wechat/      微信 iLink 通道
├── runtime/     Runtime HTTP API + 持久化任务
├── image/       图片输入解析
└── context/     上下文预算管理
```

---

## 4. ReAct Agent（第1期）

**解决的问题：** 让 LLM 不只是回答问题，而是能通过"思考→调用工具→观察结果→继续思考"的循环完成真实任务。

**核心文件：**
- `agent/Agent.java` — ReAct 主循环
- `tool/ToolRegistry.java` — 工具注册与分发

**工作原理：**

```
用户输入
  ↓
[Reason] LLM 分析任务，决定调用哪个工具
  ↓
[Act]    调用工具（read_file / grep_code / execute_command ...）
  ↓
[Observe] 把工具结果填回对话历史
  ↓
循环直到 LLM 输出最终答案（不再调用工具）
```

**关键设计：**
- 工具调用统一走 `ToolRegistry.executeTools()`，三条执行路径复用
- `ripgrep` 优先用于 `grep_code`，未安装时 Java 自动接管
- 结果受 `max_results / head_limit / max_chars` 预算约束

---

## 5. Plan+DAG 规划执行（第2期）

**解决的问题：** ReAct 对多步骤、有依赖关系的复杂任务容易走弯路；先规划后执行提升复杂任务成功率。

**核心文件：**
- `plan/Planner.java` — LLM 驱动的任务拆解
- `plan/ExecutionPlan.java` — DAG 图表示
- `plan/Task.java` — 单任务节点（含依赖关系）
- `agent/PlanExecuteAgent.java` — 计划执行引擎

**工作原理：**

```
/plan 命令
  ↓
Planner 向 LLM 提问，生成任务列表（含依赖）
  ↓
ExecutionPlan 构建 DAG（有向无环图）
  ↓
用户确认计划（Enter执行 / ESC取消 / I补充重规划）
  ↓
按拓扑顺序执行：无依赖任务并行，有依赖任务等前置
  ↓
每个任务节点内部仍走 ReAct 工具调用循环
```

---

## 6. Memory 记忆系统（第3期）

**解决的问题：** LLM 无状态，历史超窗口后遗忘；跨会话的关键事实需要持久化。

**核心文件：**
- `memory/MemoryManager.java` — 统一读写门面
- `memory/ConversationMemory.java` — 短期记忆（当前会话）
- `memory/LongTermMemory.java` — 长期记忆（跨会话持久化）
- `memory/ConversationHistoryCompactor.java` — 对话历史自动压缩
- `memory/MemoryRetriever.java` — 相关记忆检索注入
- `memory/TokenBudget.java` — Token 预算控制

### 两层记忆

| 层次 | 存储位置 | 生命周期 | 触发方式 |
|------|----------|----------|----------|
| 短期记忆 | 内存 | 当前会话 | 自动 |
| 长期记忆 | 本地文件 | 跨会话 | `/save` 或用户明确说"记住" |

### 对话历史压缩

- 自动阈值：大窗口 = `window - 20k - 13k`（200k 窗口约 167k 触发）
- `/compact` 手动触发，保留最近 1 个 user 轮次和工具边界

### 项目级记忆（PAI.md）

- `PAI.md` 启动时自动注入 system prompt，适合团队共享规则
- `/init` 命令基于当前项目自动生成精简 `PAI.md`

---

## 7. RAG 检索增强生成（第4期）

**解决的问题：** 大型代码库无法全量入上下文；需要语义化找到"和当前问题最相关的代码片段"。

**核心文件：**
- `rag/CodeIndex.java` — 索引编排（分块→嵌入→存储）
- `rag/CodeChunker.java` — 语义分块（文件/类/方法粒度）
- `rag/CodeAnalyzer.java` — Java AST 解析，提取代码关系图
- `rag/EmbeddingClient.java` — 向量生成（支持 Ollama + 远程 API）
- `rag/VectorStore.java` — SQLite 持久化向量存储
- `rag/CodeRetriever.java` — 混合检索（语义 + 关键词）
- `rag/CodeRelation.java` — 代码依赖关系图谱

### 索引构建流程

```
/index 命令
  ↓
CodeChunker 按文件/类/方法切分代码块
  ↓
CodeAnalyzer 用 JavaParser 解析 AST，提取类关系
  ↓
EmbeddingClient 为每个 chunk 生成向量
  ↓
VectorStore 把 (chunk_id, content, vector) 存入 SQLite
```

### 检索流程（search_code 工具）

```
自然语言问题
  ↓
EmbeddingClient 生成查询向量
  ↓
VectorStore 余弦相似度 Top-K 检索
  ↓
CodeRetriever 合并语义 + 关键词结果
  ↓
格式化为 <code_chunk> 块注入上下文
```

### 与传统搜索分工

| 场景 | 推荐工具 |
|------|----------|
| 精确符号定位（函数名/类名）| `grep_code` + `read_file` |
| 模糊自然语言查询 | `search_code`（RAG）|
| 不知道关键词，只知道意图 | `search_code`（RAG）|

---

## 8. Multi-Agent 多代理协作（第5期）

**解决的问题：** 单个 Agent 处理复杂任务时质量不稳定；引入多角色分工——规划、执行、审查相互独立，提升输出质量。

**核心文件：**
- `agent/AgentOrchestrator.java` — 编排器，协调子代理
- `agent/SubAgent.java` — 子代理实现
- `agent/AgentRole.java` — 角色定义（PLANNER / WORKER / REVIEWER）
- `agent/AgentMessage.java` — 代理间消息传递

**工作原理：**

```
/team 命令
  ↓
Planner 角色：拆解任务，生成子任务列表
  ↓
Worker 角色：逐个执行子任务（内部仍走 ReAct 循环）
  ↓
Reviewer 角色：审查执行结果，打分并给出反馈
  ↓
未通过审查 → 携带反馈重试（最多 2 次）
  ↓
冲突自动解决，最终合并结果
```

**关键设计：**
- 三个角色共享 `ToolRegistry`，但 system prompt 各自定制
- 审查未通过时携带 Reviewer 反馈重新分配给 Worker，不是简单重跑
- `AgentBudget` 控制每个子代理的 Token 消耗上限

---

## 9. HITL 人机审批（第6期）

**解决的问题：** Agent 有可能执行危险操作（删文件、运行恶意命令）；在危险操作执行前插入人工确认。

**核心文件：**
- `hitl/HitlToolRegistry.java` — 拦截层，包裹 ToolRegistry
- `hitl/ApprovalPolicy.java` — 危险工具规则定义
- `hitl/TerminalHitlHandler.java` — 终端交互式审批
- `hitl/SwitchableHitlHandler.java` — 动态开关

**拦截顺序：**

```
工具调用请求
  ↓
HitlToolRegistry（是否需要审批？）
  ↓
ToolRegistry（实际工具执行）
  ↓
PathGuard / CommandGuard（策略层二次检查）
```

**三级危险等级：**

| 等级 | 工具 | 默认行为 |
|------|------|----------|
| 高危 | `execute_command` | 必须审批 |
| 中危 | `write_file` / `create_project` | 必须审批 |
| 低危 | `read_file` / `grep_code` 等 | 放行 |

**审批决策选项：** 批准 / 全部放行（当前 server 维度）/ 拒绝 / 跳过 / 修改参数后执行

- HITL 默认关闭，`/hitl on` 开启，`/hitl off` 关闭

---

## 10. 并行工具调用（第7期）

**解决的问题：** LLM 一次返回多个工具调用时，串行执行浪费时间；并行执行显著加速多工具任务。

**核心文件：**
- `tool/ToolRegistry.java` — `executeTools()` 并行批量执行
- `agent/AgentBudget.java` — 超时与取消兜底

**设计：**
- 默认最多 4 个并发（`CompletableFuture` 线程池）
- 结果**按原始 tool_call 顺序**回灌，保证消息历史协议合法
- 单个 `execute_command` 保留 60 秒独立超时
- 三条路径（ReAct / Plan / Multi-Agent）统一走 `executeTools()`，无重复逻辑

---

## 11. 多模型适配（第8期）

**解决的问题：** 国内 LLM 提供商 API 各不相同，接入成本高；需要一个统一抽象层支持运行时切换。

**核心文件：**
- `llm/LlmClient.java` — 接口定义
- `llm/LlmClientFactory.java` — 工厂（按 provider 名称创建）
- 各 `*Client.java` — 7 家 provider 瘦实现

**关键差异处理：**

| Provider | 特殊处理 |
|----------|----------|
| DeepSeek | 强制 HTTP/1.1（避免 SSE 流被 HTTP/2 重置）|
| DeepSeek | `reasoning_content` 必须随历史带回 |
| DeepSeek | `supportsImageInput()=false`，图片自动降级为文本 |
| Kimi | thinking 模式 reasoning 写日志展示 |
| XunFei | LoRA 微调支持，工具列表不透传 |
| StepFun | 检测到 `STEP_API_KEY` 自动内置 step_search MCP |

- `/model <name>` 命令运行时切换 provider 和模型
- 配置持久化到 `~/.yicli/config.json`

---

## 12. 联网能力（第9期）

**解决的问题：** LLM 知识有截止日期，无法获取实时信息；需要 web_search 和 web_fetch 工具补充时效性数据。

**核心文件：**
- `web/SearchProvider.java` — 搜索抽象接口
- `web/SearchProviderFactory.java` — 工厂（三种实现）
- `web/WebFetcher.java` — URL 内容抓取
- `web/HtmlExtractor.java` — JSoup HTML→Markdown 正文提取
- `web/NetworkPolicy.java` — 安全策略（屏蔽内网/loopback）

**三种搜索实现：**

| 实现 | 特点 | 费用 |
|------|------|------|
| 智谱 Web Search | 默认，与 GLM 共用 Key | 0.01–0.05 元/次 |
| SerpAPI | 国际通用付费 | 按量计费 |
| SearXNG | 开源自托管 | 免费 |

**安全策略：**
- 屏蔽 `file://` / 内网 / loopback
- 30 秒超时；5MB 响应上限；每分钟 30 次限流
- SPA/防爬墙站点返回空正文 + 提示，自动 fallback 到浏览器 MCP

**StepSearch 优先路由：**
当模型为 `step-3.7-flash*` 且 step_search MCP 已就绪时，内置 `web_search` / `web_fetch` 优先走 StepSearch MCP，失败时自动回退。

---

## 13. MCP 协议（第10期）

**解决的问题：** 工具能力需要可扩展；Model Context Protocol 让外部工具服务以标准化方式接入 Agent。

**核心文件：**
- `mcp/McpServerManager.java` — Server 生命周期管理（8s 启动超时）
- `mcp/McpClient.java` — JSON-RPC 客户端
- `mcp/transport/` — stdio 子进程 + Streamable HTTP 两种传输
- `mcp/resources/` — MCP resource 缓存
- `mcp/mention/` — `@server:uri` mention 展开

**工作原理：**

```
启动时读取 ~/.yicli/mcp.json + .yicli/mcp.json
  ↓
McpServerManager 启动各 server（stdio 或 HTTP）
  ↓
MCP 工具自动注册为 mcp__{server}__{tool}
  ↓
ToolRegistry 对 Agent 透明暴露 MCP 工具
  ↓
工具调用走 JSON-RPC，结果回传 Agent
```

**关键设计：**
- 配置合并：项目级按 server 名覆盖用户级
- `${VAR}` 支持系统环境变量、`.env`、`~/.env`
- 参数 schema 自动清洗 `$ref / anyOf / 超长 description`
- 所有 MCP 工具默认走 HITL 审批，参数脱敏（token/key/password）
- MCP resources 注册为虚拟工具 `mcp__{server}__list_resources` / `read_resource`
- 支持被动通知：`tools/list_changed` / `resources/updated`
- CLI 命令：`/mcp` / `/mcp restart` / `/mcp logs` / `/mcp disable` / `/mcp enable` / `/mcp resources` / `/mcp prompts`

---

## 14. 长上下文工程（第12期）

**解决的问题：** 不同模型上下文窗口差异巨大（128k～1M）；需要动态感知窗口大小，智能分配 Token 预算，充分利用长窗口。

**核心文件：**
- `context/ContextProfile.java` — 窗口大小与预算配置
- `context/ContextMode.java` — short / balanced / long 三种模式
- `agent/AgentBudget.java` — 动态预算计算（`80% * maxContextWindow`）
- `llm/LlmClient.java` — `maxContextWindow()` / `supportsPromptCaching()` 接口

**三种上下文模式：**

| 模式 | 压缩策略 | RAG topK | 适用场景 |
|------|----------|----------|----------|
| short | 积极压缩 | 5 | 小窗口模型 |
| balanced | 默认压缩 | 10 | 通用场景 |
| long | 跳过压缩 | 20 | 1M 窗口大模型 |

**关键设计：**
- 长上下文模式自动把 MCP resources URI/描述索引注入 system prompt（不注入正文，避免噪音）
- Token / cached input / 估算成本 / 耗时进入底部状态栏，不占用正文输出区
- `/context` 命令查看当前模式、prompt cache 模式、RAG topK 等状态

---

## 15. Chrome DevTools MCP（第13期）

**解决的问题：** `web_fetch` 对 SPA / JS 渲染 / 防爬墙站点无效；需要真实浏览器来处理动态页面。

**核心文件：**
- `browser/BrowserSession.java` — 浏览器会话管理
- `browser/BrowserGuard.java` — 权限与安全检查
- `browser/SensitivePagePolicy.java` — 敏感页面规则

**工作原理：**
- 默认接入 Google 官方 `chrome-devtools-mcp@latest`，注册为 `mcp__chrome-devtools__*` 工具
- 启动时若 `~/.yicli/mcp.json` 不存在，自动创建含 `--isolated=true` 的模板配置
- 常用工具：`navigate_page` / `take_snapshot` / `click` / `fill_form`
- 图片类型结果作为图片输入附加到下一轮；DeepSeek 等不支持图片的 provider 自动降级为文本

**读取优先级：** `take_snapshot` > `take_screenshot`（快照含结构信息，截图仅图片）

---

## 16. CDP 会话复用（第14期）

**解决的问题：** 浏览器隔离模式无法访问需要登录的页面；需要复用用户已有的登录态 Chrome 实例。

**核心文件：**
- `browser/BrowserConnector.java` — CDP 端口探活与连接
- `browser/BrowserMode.java` — isolated / shared 模式切换
- `browser/BrowserAuditMetadata.java` — 审计元数据

**工作原理：**

```
/browser connect [port]
  ↓
探活 127.0.0.1:<port>/json/version
  ↓
成功 → 切换 chrome-devtools 为 --autoConnect 模式
  ↓
复用已有登录态 Chrome（不再使用临时 profile）
  ↓
Agent 遇到登录页时自动调用 browser_connect 切 shared 模式
```

**安全约束：**
- 切换 shared/isolated 都清空当前 server 维度的全部放行，防止旧信任跨模式延续
- shared 模式下 `close_page` 只能关闭 YiCLI 自己创建的 tab
- 敏感页面命中规则后，`click / fill_form / evaluate_script` 等改写工具必须单步审批

---

## 17. Skill 技能系统（第15期）

**解决的问题：** 各种垂直场景（web 访问、代码审查、安全测试等）的"思考方式"需要按需注入，而不是全量塞进 system prompt 浪费 Token。

**核心文件：**
- `skill/SkillRegistry.java` — Skill 注册与加载
- `skill/Skill.java` — Skill 接口
- `skill/SkillContextBuffer.java` — Skill 内容暂存缓冲
- `skill/SkillIndexFormatter.java` — 索引段格式化

**Skill 目录结构：**

```
<skill-name>/
  SKILL.md          # 决策手册（5KB 截断注入）
  references/       # 按需读取的参考文档
  scripts/          # 可选的可执行依赖
```

**三层加载位置（优先级递增）：**

```
jar 内置 < ~/.yicli/skills/<name>/ < <project>/.yicli/skills/<name>/
```

**工作流程：**

```
启动时
  ↓
SkillRegistry 扫描三层位置，注入 name + description 到 system prompt 索引段
  （上限 20 个 / 索引段 ≤ 4KB）
  ↓
Agent 运行时
  ↓
LLM 看到匹配 description → 调用 load_skill(name) 工具
  ↓
SkillContextBuffer 存入 SKILL.md 正文
  ↓
下一轮 user message 前自动前置注入 Skill 内容
```

---

## 18. TUI 产品化（第16期）

**解决的问题：** 纯文本输出体验差；需要可折叠的工具调用块、底部状态栏、实时 Thinking 区域等 IDE 级别的终端交互。

**核心文件：**
- `render/InlineRenderer.java` — JLine 4 集成渲染器
- `render/inline/BottomStatusBar.java` — JLine Status dock 底部状态栏
- `render/inline/FoldableBlock.java` — 可折叠工具调用块
- `cli/YiCliHighlighter.java` — 输入实时语法高亮
- `cli/YiCliCompleter.java` — 上下文智能补全
- `cli/YiCliHistory.java` — 持久化输入历史

**底部状态栏两行内容：**

```
第一行：模式（ReAct/Plan/Team）+ MCP/Skill 摘要
第二行：Auto Model / 模型名 / phase / ctx% / Token / cost / elapsed / cwd
```

**关键交互特性：**
- 实时高亮：slash 命令、`@` 引用、`@image:`、危险 shell 片段
- 智能补全：`/model` provider、`/mcp` 子命令、`/skill`、`@path`、MCP resource URI
- 输入历史持久化到 `~/.yicli/history/input.history`，自动过滤密钥/base64/长输入
- ReAct 执行期间：固定高度 live thinking 区显示灰色竖线 reasoning 预览
- `/clear` 清空短期记忆；`/compact` 手动压缩对话历史

---

## 19. LSP 诊断注入（第17期）

**解决的问题：** Agent 修改代码后不知道是否引入了编译错误或类型错误；需要 IDE 级别的静态诊断反馈。

**核心文件：**
- `lsp/LspManager.java` — LSP Server 生命周期（支持 TS/Rust/Python/Go/Java/Ruby/C++）
- `lsp/LspDiagnosticFormatter.java` — 诊断结果格式化

**工作原理：**
- 用户执行 `/code init` 初始化，在项目根生成 `.kiro/settings/lsp.json`
- LspManager 启动对应语言的 Language Server
- Agent 写文件后自动获取 LSP 诊断（错误/警告），注入下一轮上下文
- 删除 `.kiro/settings/lsp.json` 即可禁用，重新 `/code init` 启用

---

## 20. Side-Git 快照（第18期）

**解决的问题：** Agent 修改文件后如果结果不满意，需要一键回滚；标准 git 仓库可能被 Agent 操作污染，需要独立的安全快照机制。

**核心文件：**
- `snapshot/SideGitManager.java` — 基于 JGit 的独立 git 仓库（无需系统 git）
- `snapshot/SnapshotService.java` — 快照服务门面
- `snapshot/TurnSnapshot.java` — 单轮快照元数据
- `snapshot/RestoreResult.java` — 回滚结果

**工作原理：**

```
每轮 Agent 执行前
  ↓
SnapshotService 调用 SideGitManager 创建 commit（Side-Git 仓库）
  ↓
Agent 执行工具调用（可能修改文件）
  ↓
用户不满意时
  ↓
/snapshot list    查看快照历史
/snapshot restore <id>  回滚到指定轮次
  ↓
SideGitManager 用 JGit checkout 恢复文件
```

**关键设计：**
- Side-Git 仓库与项目 git 仓库完全独立，不污染项目历史
- 使用 JGit 7.6.0，无需系统安装 git
- `revert_turn` 内置工具也触发同一套回滚逻辑

---

## 21. Prompt 分层架构（第19期）

**解决的问题：** 硬编码的 system prompt 难以维护；不同执行模式（ReAct/Planner/SubAgent）需要不同指令，且需要按需注入 PAI.md、Skill、记忆、日期等动态内容。

**核心文件：**
- `prompt/PromptAssembler.java` — 多层组装引擎
- `prompt/PromptContext.java` — 上下文数据容器
- `prompt/PromptRepository.java` — 提示词模板存储
- `prompt/PromptMode.java` — REACT / PLANNER / SUBAGENT 模式枚举
- `prompt/ProjectMemoryLoader.java` — PAI.md 加载器

**Prompt 分层结构：**

```
system prompt
  ├── 1. 基础指令层（角色定义、工具使用规范）
  ├── 2. 模式层（ReAct / Planner / SubAgent 差异化指令）
  ├── 3. 项目记忆层（PAI.md 注入，有字符预算）
  ├── 4. 工具描述层（内置工具 + MCP 动态工具）
  ├── 5. Skill 索引层（name + description，≤ 4KB）
  ├── 6. 长期记忆层（检索到的相关记忆片段）
  └── 7. 动态信息层（当前日期/时区、上下文模式等）
```

**关键设计：**
- 每轮请求前重新组装，保证动态内容（记忆、Skill）始终最新
- 各层有独立 Token 预算，避免某一层膨胀挤占其他层

---

## 22. Runtime API（第20期）

**解决的问题：** 需要从外部系统（CI/CD、IDE 插件、脚本）异步触发 Agent 任务，并查询执行状态。

**核心文件：**
- `runtime/api/RuntimeApiServer.java` — 内嵌 HTTP API 服务器
- `runtime/task/DurableTaskManager.java` — 持久化任务管理
- `runtime/CancellationContext.java` — 取消令牌

**HTTP API：**

| 端点 | 功能 |
|------|------|
| `POST /tasks` | 提交新任务 |
| `GET /tasks/{id}` | 查询任务状态 |
| `GET /tasks` | 列出所有任务 |
| `DELETE /tasks/{id}` | 取消任务 |

**持久化任务（DurableTaskManager）：**
- 任务状态持久化到本地文件，进程重启后可恢复
- 支持异步后台执行，不阻塞 CLI 交互
- `/cancel` CLI 命令可取消当前正在执行的 Agent run

---

## 23. 图片输入（第21期）

**解决的问题：** 用户需要把截图、设计稿、报错截图直接粘贴给 Agent 分析，而不是手动保存文件再输入路径。

**核心文件：**
- `image/ImageReferenceParser.java` — 图片引用解析（`@image:path` / `@clipboard`）
- `image/ClipboardImage.java` — 系统剪贴板图片读取

**支持的输入方式：**

| 方式 | 语法 | 说明 |
|------|------|------|
| 本地文件 | `@image:/path/to/img.png` | 读取本地图片文件 |
| 剪贴板 | `@clipboard` | 直接读取系统剪贴板中的图片 |
| MCP 图片结果 | 自动 | browser MCP 的 take_snapshot 结果自动附加 |

**Provider 兼容处理：**
- DeepSeek 等不支持图片输入的 provider：图片 ContentPart 自动替换为文本提示，不发送图片块
- 历史消息或工具回灌里的图片同样做降级处理

---

## 24. 微信 iLink 通道（第23期）

**解决的问题：** 用户需要通过微信消息远程触发 Agent，而不必坐在终端前操作。

**核心文件：**
- `wechat/IlinkClient.java` — iLink 协议客户端
- `wechat/WechatMessageLoop.java` — 消息循环处理
- `wechat/WechatQrLogin.java` — 终端 ASCII 二维码登录
- `wechat/WechatAccountStore.java` — 账号信息持久化
- `wechat/WechatCommandMain.java` — `wechat` 子命令入口

**工作原理：**

```
/wechat 命令 或 java -jar ... wechat setup
  ↓
终端显示 ASCII 二维码，用户微信扫码授权
  ↓
账号信息持久化到本地
  ↓
java -jar ... wechat start 前台启动 / 或后台随主进程运行
  ↓
WechatMessageLoop 监听微信消息
  ↓
收到消息 → 触发 Agent 执行 → 结果发回微信
```

**非交互式安全策略（无 HITL 面板）：**

| 工具类型 | 默认策略 |
|----------|----------|
| 只读工具（read_file / grep_code 等）| 默认允许 |
| `execute_command` | 必须精确命中命令白名单 |
| `mcp__*` | 必须命中 MCP 白名单 |
| `revert_turn` / 浏览器会话切换 | 默认拒绝 |
| 文件写入 | 仍受 PathGuard 限定在绑定 workspace 内 |

---

## 25. 安全策略层

**解决的问题：** Agent 有工具调用能力，必须防止路径逃逸、危险命令执行等安全风险。

**核心文件：**
- `policy/PathGuard.java` — 路径守卫（强制限定在项目根内）
- `policy/CommandGuard.java` — 命令黑名单
- `policy/AuditLog.java` — 工具操作审计日志（JSONL 格式）

**拦截链（从外到内）：**

```
用户请求工具调用
  ↓
HitlToolRegistry    （是否需要人工审批？）
  ↓
ToolRegistry        （实际执行）
  ↓
PathGuard           （路径是否在项目根内？）
  ↓
CommandGuard        （命令是否在黑名单？）
  ↓
执行 / 抛出 PolicyException
```

**PathGuard 规则：**
- 所有文件读写操作的路径必须在项目根目录内
- 绝对路径或符号链接逃逸项目根时拒绝执行（不是忽略，是硬拒绝）
- 用户无法通过 HITL 审批绕过 PathGuard

**AuditLog：**
- 所有工具调用（含 MCP）追加到审计日志 JSONL
- MCP 参数自动脱敏：token / key / password / Authorization / Bearer 凭证不写入日志

---

## 26. 总结

YiCLI 是一个从零构建的商业级 Java Agent CLI，其核心价值在于把企业级工程实践系统性地引入 AI Agent 领域：

### 核心设计原则

| 原则 | 体现 |
|------|------|
| **分层解耦** | Prompt 分层、记忆分层、工具注册与执行分离 |
| **安全优先** | PathGuard + HITL + AuditLog 三重防护 |
| **可扩展性** | MCP 协议让工具无限扩展，Skill 系统让能力按需加载 |
| **长上下文友好** | Token 预算管理、自动压缩、多模式适配 |
| **Provider 无关** | 7 家 LLM 统一抽象，运行时切换无感 |

### 功能矩阵

| 能力域 | 关键技术 | 解决的核心问题 |
|--------|----------|----------------|
| 推理执行 | ReAct 循环 | 让 LLM 能完成真实任务 |
| 复杂规划 | DAG + Plan-and-Execute | 多步依赖任务的有序执行 |
| 记忆持久化 | 短期+长期+PAI.md | 跨会话知识积累 |
| 代码理解 | RAG + AST + 向量检索 | 大型代码库语义导航 |
| 多代理协作 | Orchestrator + SubAgent | 规划/执行/审查角色分工 |
| 人机协同 | HITL + 审批策略 | 危险操作人工兜底 |
| 工具扩展 | MCP 协议 | 标准化外部工具接入 |
| 浏览器操作 | Chrome DevTools + CDP | 动态页面自动化 |
| 技能系统 | Skill + ContextBuffer | 垂直能力按需注入 |
| 终端体验 | JLine 4 + TUI | IDE 级别交互体验 |
| 代码诊断 | LSP 集成 | 实时编译错误反馈 |
| 版本安全 | Side-Git + JGit | 无损回滚保障 |
| 远程访问 | 微信 iLink + Runtime API | 多渠道触发 Agent |
| 安全防护 | PathGuard + HITL + AuditLog | 全链路安全审计 |

### CLI 命令速查

| 命令 | 功能 |
|------|------|
| `/plan` | 进入 Plan-and-Execute 模式 |
| `/team` | 进入 Multi-Agent 协作模式 |
| `/model <name>` | 切换 LLM provider/模型 |
| `/save <事实>` | 保存长期记忆 |
| `/memory list/search/delete/clear` | 管理长期记忆 |
| `/index` | 建立 RAG 向量索引 |
| `/search <词>` | 语义检索代码 |
| `/graph` | 查看代码关系图 |
| `/hitl on/off` | 开关人机审批 |
| `/mcp [子命令]` | MCP server 管理 |
| `/browser [子命令]` | 浏览器会话管理 |
| `/skill [子命令]` | Skill 管理 |
| `/snapshot [子命令]` | 快照管理 |
| `/context` | 查看上下文状态 |
| `/compact` | 手动压缩对话历史 |
| `/clear` | 清空短期记忆 |
| `/init` | 生成项目 PAI.md |
| `/export` | 导出当前会话为 Markdown |
| `/cancel` | 取消当前 Agent 运行 |
| `/wechat` | 绑定并启动微信 iLink 通道 |
| `/history clear` | 清空输入历史 |
