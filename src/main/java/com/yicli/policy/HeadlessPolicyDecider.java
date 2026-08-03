package com.yicli.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 无头执行策略（P0-4）：Runtime API / 后台任务没有人工审批面板，
 * 采用与微信通道一致的非交互默认拒绝：
 * - 只读工具默认允许
 * - write_file 仅允许非敏感路径、内容不含密钥
 * - create_project 允许（受 PathGuard 限制）
 * - execute_command 必须精确命中命令白名单
 * - mcp__* 必须命中 MCP 白名单
 * - revert_turn / 浏览器会话切换默认拒绝
 */
public class HeadlessPolicyDecider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> READ_ONLY_TOOLS = List.of(
            "read_file", "list_dir", "glob_files", "grep_code", "search_code",
            "web_search", "web_fetch", "browser_status", "load_skill", "save_memory"
    );
    private final List<String> commandAllowlist;
    private final List<String> mcpAllowlist;

    public HeadlessPolicyDecider() {
        this.commandAllowlist = allowlist(
                System.getProperty("yicli.headless.command.allowlist"),
                System.getenv("YICLI_HEADLESS_COMMAND_ALLOWLIST"));
        this.mcpAllowlist = allowlist(
                System.getProperty("yicli.headless.mcp.allowlist"),
                System.getenv("YICLI_HEADLESS_MCP_ALLOWLIST"));
    }

    public Decision decide(String toolName, String argumentsJson) {
        String name = toolName == null ? "" : toolName.trim();
        if (name.isBlank()) {
            return Decision.deny("工具名为空");
        }
        if (READ_ONLY_TOOLS.contains(name)) {
            return Decision.allow();
        }
        if ("execute_command".equals(name)) {
            return commandAllowed(argumentsJson);
        }
        if ("write_file".equals(name)) {
            return writeFileAllowed(argumentsJson);
        }
        if ("create_project".equals(name)) {
            return Decision.allow();
        }
        if ("revert_turn".equals(name)) {
            return Decision.deny("无头模式不允许远程回滚快照");
        }
        if ("browser_connect".equals(name) || "browser_disconnect".equals(name)) {
            return Decision.deny("无头模式不允许远程切换浏览器会话");
        }
        if (name.startsWith("mcp__")) {
            return mcpAllowed(name);
        }
        return Decision.deny("无头模式未将该工具列入允许清单: " + name);
    }

    private Decision commandAllowed(String argumentsJson) {
        String command = extract(argumentsJson, "command");
        if (command.isBlank()) {
            return Decision.deny("无头模式拒绝空命令");
        }
        for (String allowed : commandAllowlist) {
            if (!allowed.isBlank() && command.trim().equals(allowed)) {
                return Decision.allow();
            }
        }
        return Decision.deny("无头模式默认拒绝 execute_command；请配置 YICLI_HEADLESS_COMMAND_ALLOWLIST 白名单");
    }

    private static Decision writeFileAllowed(String argumentsJson) {
        String path = extract(argumentsJson, "path");
        if (path.isBlank()) {
            return Decision.deny("无头模式拒绝空路径写入");
        }
        if (SensitiveFileRules.isSensitivePath(path)) {
            return Decision.deny("无头模式拒绝写入敏感文件: " + path);
        }
        if (SensitiveFileRules.containsSecret(extract(argumentsJson, "content"))) {
            return Decision.deny("无头模式拒绝写入疑似密钥/凭据内容");
        }
        return Decision.allow();
    }

    private Decision mcpAllowed(String toolName) {
        for (String allowed : mcpAllowlist) {
            if (!allowed.isBlank() && toolName.equals(allowed)) {
                return Decision.allow();
            }
            if (!allowed.isBlank() && toolName.startsWith("mcp__" + allowed + "__")) {
                return Decision.allow();
            }
        }
        return Decision.deny("无头模式默认拒绝 MCP 工具: " + toolName);
    }

    private static String extract(String argumentsJson, String field) {
        try {
            JsonNode root = MAPPER.readTree(argumentsJson);
            JsonNode node = root.get(field);
            return node == null || node.isNull() ? "" : node.asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> allowlist(String... sources) {
        for (String source : sources) {
            if (source != null && !source.isBlank()) {
                return Arrays.stream(source.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
            }
        }
        return List.of();
    }

    public record Decision(boolean allowed, String reason) {
        static Decision allow() {
            return new Decision(true, null);
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
