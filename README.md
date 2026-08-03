# YiCLI

面向商业使用的 **Java Agent CLI** 产品，对标 Claude Code。提供 ReAct、Plan-and-Execute、Multi-Agent 三条执行路径，内置 17 个工具，支持 MCP 动态工具、记忆与上下文工程、RAG 代码检索、HITL 人工审批、Side-Git 快照、Skill 系统、Runtime API 与微信通道。

> 项目由 PaiCLI 改名而来；`PaiCLI` / `PaiAgent` / `com.paicli` / `PAICLI_*` 均已统一为 `YiCLI` / `YiAgent` / `com.yicli` / `YICLI_*`。

## 特性

- **三条执行路径**：ReAct（默认，实时搜索代码）、Plan-and-Execute（`/plan`，DAG 规划后执行）、Multi-Agent（`/team`，Planner/Worker/Reviewer 分工）
- **多模型**：GLM / DeepSeek / Step / Kimi / FreeLLMAPI / 讯飞星辰 MaaS / Agnes 共 7 个 provider，运行时 `/model` 切换
- **17 个内置工具**：文件读写、目录、glob、grep（优先 ripgrep）、命令执行、项目创建、RAG 语义检索、联网搜索/抓取、浏览器会话、Skill 加载、长期记忆保存、`task` 子代理分发、`revert_turn`
- **MCP**：stdio + Streamable HTTP，动态注册 `mcp__{server}__{tool}`，支持 resources / prompts / notifications，`${VAR}` 环境变量展开
- **记忆与上下文**：短期记忆、长期记忆（`/save`）、两级上下文压缩、长上下文模式
- **RAG 代码检索**：SQLite + Embedding（Ollama / OpenAI / 智谱），`/index`、`/search`、`/graph`
- **HITL + 策略层**：危险操作人工审批、PathGuard 路径围栏、CommandGuard 命令黑名单、AuditLog 审计；批准过的调用自动记入权限记忆（`/permission`），下次免打扰
- **Side-Git 快照**：JGit 纯 Java 实现，每轮 turn 前后快照，`/restore <N>` 恢复
- **Skill 系统**：SKILL.md + references 专家手册，`load_skill` 按需注入
- **三形态渲染器**：inline 流式 TUI（默认，JLine 底部 dock + 可折叠工具块）/ Lanterna 全屏 / plain 兜底
- **会话与检查点**：退出自动保存会话（`/sessions`），取消或 LLM 失败自动留检查点（`/resume <id>` 续跑）
- **LLM 调用重试**：429 / 5xx / 连接错误指数退避重试，尊重 `Retry-After`，流开始后不重试避免重复内容
- **事件总线**：`YiCliEventBus` 提供 tool_call / turn / app_stop 生命周期事件，作为 hooks 与可观测性的基础
- **Runtime API + 后台任务**：`serve --http` 提供本地 REST 接口，SQLite 持久化任务队列
- **微信 iLink 通道**：扫码绑定，文本消息收发，非交互式默认拒绝策略
- **体检与 eval**：`yicli doctor` 一键检查环境；prompt golden 快照 + LLM 录制/回放支撑回归评测

## 快速开始

### 环境要求

- Java 17+
- Maven
- 可选：`ripgrep`（`grep_code` 优先使用，未安装自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`

### 构建与运行

```bash
cp .env.example .env        # 填入你自己的 API Key（.env 已被 gitignore，不会上传）
mvn clean package            # 默认跳过测试，优先产出可手工验收 jar
java -jar target/yicli-1.0-SNAPSHOT.jar
yicli doctor                 # 环境体检：Java / ripgrep / API Key / 数据目录
```

首次启动会自动创建 `~/.yicli/` 数据目录（配置、记忆、快照、会话、审计均存放在此）。

## 命令

### 进程级入口

```bash
yicli doctor                                    # 环境体检
yicli serve --http --port 8080                  # 启动 Runtime API（需 PAICLI_RUNTIME_API_KEY，即 YICLI_RUNTIME_API_KEY）
yicli wechat setup                              # 绑定微信 iLink 通道
yicli wechat start                              # 前台启动微信通道
yicli wechat status                             # 查看通道状态
```

### 交互式斜杠命令

| 命令 | 说明 |
|------|------|
| `/plan [任务]` | 下一条 / 本条任务走 Plan-and-Execute |
| `/team [任务]` | 下一条 / 本条任务走 Multi-Agent |
| `/model <provider>` | 切换模型 provider（glm / deepseek / step / kimi / freellmapi / xfyun / agnes） |
| `/hitl on\|off` | 启用 / 关闭人工审批 |
| `/mcp ...` | MCP 状态 / 重启 / 日志 / 禁用 / 启用 / resources / prompts |
| `/policy` | 查看安全策略状态 |
| `/audit [N]` | 查看最近 N 条危险工具审计 |
| `/permission` | 查看已记住的权限规则（`/permission clear` 清空） |
| `/snapshot` / `/restore <N>` | Side-Git 快照查看与恢复 |
| `/memory ...` / `/save` | 长期记忆查看、搜索、删除、保存 |
| `/sessions` | 列出已保存的历史会话 |
| `/resume <sessionId>` | 恢复历史会话继续对话 |
| `/index [路径]` / `/search <查询>` / `/graph <类名>` | RAG 索引与语义检索 |
| `/skill ...` | Skill 列表 / 查看 / 启用 / 禁用 / 重载 |
| `/task` | 查看后台任务 |
| `/clear` / `/compact` | 清空对话 / 手动压缩历史 |
| `/export` | 导出当前会话为 Markdown（含完整 system prompt） |
| `/doctor` | 环境体检 |
| `/config provider <name> ...` | 持久化 provider 配置 |
| `/init` | 生成精简项目级记忆 `PAI.md` |
| `/exit` / `/quit` | 退出 |

## 架构概览

三条主执行路径共享 `ToolRegistry` / `MemoryManager` / `SnapshotService` / `HitlToolRegistry` / `YiCliEventBus`：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

核心模块：

```
src/main/java/com/yicli/
├── agent/       Agent, PlanExecuteAgent, SubAgent, AgentOrchestrator
├── cli/         Main, CliCommandParser, ConfigCommandHandler, SessionExportFormatter
├── llm/         7 个 provider client + 重试网关（AbstractOpenAiCompatibleClient）
├── mcp/         McpServerManager, McpClient, transport, resources, mention
├── memory/      MemoryManager, LongTermMemory, ConversationHistoryCompactor
├── rag/         CodeIndex, CodeRetriever, VectorStore
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── policy/      PathGuard, CommandGuard, AuditLog, PermissionStore
├── event/       YiCliEventBus, YiCliEvent（生命周期事件）
├── session/     SessionManager（会话持久化与恢复）
├── config/      YiCliConfig, YiCliEnv, YiCliDoctor
├── eval/        LlmTraceRecorder, ReplayLlmClient（录制/回放）
├── snapshot/    SideGitManager, SnapshotService
├── skill/       SkillRegistry, SkillContextBuffer
├── prompt/      PromptAssembler（分层组装，工具目录动态注入）, ToolCatalogFormatter
├── render/      Renderer 接口 + inline / lanterna / plain 三实现
├── runtime/     api/（Runtime API）+ task/（DurableTaskManager）
├── web/         SearchProvider, WebFetcher, NetworkPolicy
└── wechat/      iLink 客户端、账户存储、消息循环、非交互策略
```

## 测试

```bash
mvn test -Pquick          # 常规快速回归
mvn test -Pphase16-smoke  # TUI / renderer 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性测试
mvn test -DskipTests=false                  # 全量回归（建议 Linux CI 上执行）
```

Prompt 变更会触发 `PromptGoldenTest` 快照比对；确认是预期变更后执行：

```bash
mvn test -Dtest=PromptGoldenTest -Dyicli.golden.update=true -DskipTests=false
```

## 安全模型

本地 Agent CLI 默认不做容器/VM 沙箱（参考 Claude Code / Codex 的本地模式），安全模型是 **HITL + 路径校验 + 命令黑名单 + 审计** 四层：

1. 文件类工具路径强制限定在项目根内（PathGuard）
2. `execute_command` 黑名单拦截 `sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / `curl|sh` 等
3. 危险工具（write_file / execute_command / create_project / revert_turn / 所有 MCP 工具）默认走 HITL 审批
4. 批准过的调用按 (tool, 参数) 精确匹配记入 `~/.yicli/permissions.json`，跨会话免打扰；微信通道走非交互式默认拒绝策略

## 已知边界

以下能力在路线图中但尚未交付：容器/VM 沙箱执行器、MCP OAuth + sampling、MCP server 自动重启、插件 API、OpenTelemetry 导出。不要把路线图误读为已交付。

## 演进历史（摘要）

已交付 23 期：ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 通道文本 MVP。

产品化重构（P0/P1）已交付：LLM 调用重试网关、装配层拆分与工具目录动态注入、事件总线、共享线程池、会话管理 `/sessions` `/resume`、Agent 检查点、`task` 子代理工具、权限记忆、类型化配置中心 + `doctor`、prompt golden + 录制/回放 eval 框架。
