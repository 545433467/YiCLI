package com.yicli.config;

import java.util.List;

/**
 * 类型化配置中心（P1-5）：把散落在代码里的 YICLI_* 环境变量 / -D 系统属性
 * 收敛成带描述和默认值的 Key 注册表，统一解析顺序：系统属性 > 环境变量 > 默认值。
 */
public final class YiCliEnv {

    public record Key(String name, String envVar, String systemProperty, String description, String defaultValue) {
    }

    public static final Key RENDERER = new Key(
            "renderer", "YICLI_RENDERER", "yicli.renderer",
            "渲染器形态: inline | lanterna | plain", "inline");
    public static final Key LOG_LEVEL = new Key(
            "logLevel", "YICLI_LOG_LEVEL", "yicli.log.level",
            "日志级别: TRACE|DEBUG|INFO|WARN|ERROR", "INFO");
    public static final Key LOG_DIR = new Key(
            "logDir", "YICLI_LOG_DIR", "yicli.log.dir",
            "日志目录", "~/.yicli/logs");
    public static final Key LSP_ENABLED = new Key(
            "lspEnabled", "YICLI_LSP_ENABLED", "yicli.lsp.enabled",
            "write_file 后是否做 LSP 诊断", "true");
    public static final Key LSP_MAX_DIAGNOSTICS = new Key(
            "lspMaxDiagnostics", "YICLI_LSP_MAX_DIAGNOSTICS", "yicli.lsp.max.diagnostics",
            "单次注入诊断上限", "20");
    public static final Key SNAPSHOT_ENABLED = new Key(
            "snapshotEnabled", "YICLI_SNAPSHOT_ENABLED", "yicli.snapshot.enabled",
            "Side-Git 快照开关", "true");
    public static final Key MCP_STARTUP_WAIT_SECONDS = new Key(
            "mcpStartupWaitSeconds", "YICLI_MCP_STARTUP_WAIT_SECONDS", "yicli.mcp.startup.wait.seconds",
            "CLI 首屏等待 MCP 启动的最长秒数", "8");
    public static final Key LLM_RETRY_MAX_ATTEMPTS = new Key(
            "llmRetryMaxAttempts", "YICLI_LLM_RETRY_MAX_ATTEMPTS", "yicli.llm.retry.max.attempts",
            "LLM 调用最大重试次数（1-6）", "3");
    public static final Key LLM_RETRY_BACKOFF_SECONDS = new Key(
            "llmRetryBackoffSeconds", "YICLI_LLM_RETRY_BACKOFF_SECONDS", "yicli.llm.retry.backoff.seconds",
            "LLM 重试基础退避秒数", "2");
    public static final Key NO_STATUSBAR = new Key(
            "noStatusbar", "YICLI_NO_STATUSBAR", "yicli.no.statusbar",
            "inline 模式禁用底部 dock", "false");
    public static final Key SANDBOX_MODE = new Key(
            "sandboxMode", "YICLI_SANDBOX_MODE", "yicli.sandbox.mode",
            "execute_command 沙箱模式: off | docker（默认 off）", "off");
    public static final Key SANDBOX_IMAGE = new Key(
            "sandboxImage", "YICLI_SANDBOX_IMAGE", "yicli.sandbox.image",
            "docker 沙箱镜像（默认含 JDK17，便于编译/测试）", "eclipse-temurin:17-jdk");
    public static final Key TELEMETRY_ENABLED = new Key(
            "telemetryEnabled", "YICLI_TELEMETRY_ENABLED", "yicli.telemetry.enabled",
            "turn/tool 遥测 JSONL 落盘开关", "true");
    public static final Key MCP_AUTO_RESTART = new Key(
            "mcpAutoRestart", "YICLI_MCP_AUTO_RESTART", "yicli.mcp.auto.restart",
            "MCP server 进程崩溃后自动重启", "true");
    public static final Key HITL_TIMEOUT_SECONDS = new Key(
            "hitlTimeoutSeconds", "YICLI_HITL_TIMEOUT_SECONDS", "yicli.hitl.timeout.seconds",
            "HITL 审批超时秒数（0 = 不限时，超时自动拒绝）", "0");
    public static final Key HEADLESS_COMMAND_ALLOWLIST = new Key(
            "headlessCommandAllowlist", "YICLI_HEADLESS_COMMAND_ALLOWLIST", "yicli.headless.command.allowlist",
            "无头模式 execute_command 精确白名单（逗号分隔）", "");
    public static final Key HEADLESS_MCP_ALLOWLIST = new Key(
            "headlessMcpAllowlist", "YICLI_HEADLESS_MCP_ALLOWLIST", "yicli.headless.mcp.allowlist",
            "无头模式 MCP 工具白名单（工具名或 server 名，逗号分隔）", "");
    public static final Key HITL_APPROVE_ALL_LIMIT = new Key(
            "hitlApproveAllLimit", "YICLI_HITL_APPROVE_ALL_LIMIT", "yicli.hitl.approve.all.limit",
            "全部放行额度（次）：用尽后需重新审批", "10");

    public static final List<Key> ALL = List.of(
            RENDERER, LOG_LEVEL, LOG_DIR, LSP_ENABLED, LSP_MAX_DIAGNOSTICS,
            SNAPSHOT_ENABLED, MCP_STARTUP_WAIT_SECONDS,
            LLM_RETRY_MAX_ATTEMPTS, LLM_RETRY_BACKOFF_SECONDS, NO_STATUSBAR,
            SANDBOX_MODE, SANDBOX_IMAGE, TELEMETRY_ENABLED, MCP_AUTO_RESTART,
            HITL_TIMEOUT_SECONDS, HEADLESS_COMMAND_ALLOWLIST, HEADLESS_MCP_ALLOWLIST,
            HITL_APPROVE_ALL_LIMIT
    );

    private YiCliEnv() {
    }

    public static String get(Key key) {
        String sys = System.getProperty(key.systemProperty());
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String env = System.getenv(key.envVar());
        if (env != null && !env.isBlank()) {
            return env;
        }
        return key.defaultValue();
    }

    public static boolean getBool(Key key) {
        return Boolean.parseBoolean(get(key));
    }

    public static int getInt(Key key, int fallback) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
