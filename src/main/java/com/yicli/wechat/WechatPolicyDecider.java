package com.yicli.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yicli.policy.SensitiveFileRules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class WechatPolicyDecider {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WechatPolicyConfig config;
    private final int maxWritesPerMinute;
    private final Deque<Long> writeTimestamps = new ArrayDeque<>();
    private final Object writeLock = new Object();

    public WechatPolicyDecider(WechatPolicyConfig config) {
        this.config = config;
        this.maxWritesPerMinute = maxWritesPerMinute();
    }

    public WechatPolicyDecision decide(String toolName, String argumentsJson) {
        String name = toolName == null ? "" : toolName.trim();
        if (name.isBlank()) {
            return WechatPolicyDecision.deny("工具名为空");
        }
        if (isReadOnlyBuiltin(name)) {
            return WechatPolicyDecision.allow();
        }
        if ("execute_command".equals(name)) {
            return commandAllowed(argumentsJson);
        }
        if ("write_file".equals(name)) {
            return writeFileAllowed(argumentsJson);
        }
        if ("create_project".equals(name)) {
            return WechatPolicyDecision.allow();
        }
        if ("revert_turn".equals(name)) {
            return WechatPolicyDecision.deny("微信通道 v1 不允许远程回滚快照");
        }
        if ("browser_connect".equals(name) || "browser_disconnect".equals(name)) {
            return WechatPolicyDecision.deny("微信通道 v1 不允许远程切换浏览器会话");
        }
        if (name.startsWith("mcp__")) {
            return mcpAllowed(name);
        }
        return WechatPolicyDecision.deny("微信通道未将该工具列入允许清单: " + name);
    }

    private WechatPolicyDecision commandAllowed(String argumentsJson) {
        String command = extract(argumentsJson, "command");
        if (command.isBlank()) {
            return WechatPolicyDecision.deny("微信通道拒绝空命令");
        }
        for (String allowed : config.commandAllowlist()) {
            String normalized = allowed == null ? "" : allowed.trim();
            if (!normalized.isBlank() && command.trim().equals(normalized)) {
                return WechatPolicyDecision.allow();
            }
        }
        return WechatPolicyDecision.deny("微信通道默认拒绝 execute_command；请在 setup 策略中配置命令白名单后重试");
    }

    private WechatPolicyDecision writeFileAllowed(String argumentsJson) {
        String path = extract(argumentsJson, "path");
        if (path.isBlank()) {
            return WechatPolicyDecision.deny("微信通道拒绝空路径写入");
        }
        if (SensitiveFileRules.isSensitivePath(path)) {
            return WechatPolicyDecision.deny("微信通道拒绝写入敏感文件: " + path);
        }
        String content = extract(argumentsJson, "content");
        if (SensitiveFileRules.containsSecret(content)) {
            return WechatPolicyDecision.deny("微信通道拒绝写入疑似密钥/凭据内容");
        }
        synchronized (writeLock) {
            long now = System.currentTimeMillis();
            while (!writeTimestamps.isEmpty() && now - writeTimestamps.peekFirst() > 60_000L) {
                writeTimestamps.pollFirst();
            }
            if (writeTimestamps.size() >= maxWritesPerMinute) {
                return WechatPolicyDecision.deny(
                        "微信通道写入频率超限（每分钟最多 " + maxWritesPerMinute + " 次）");
            }
            writeTimestamps.addLast(now);
        }
        return WechatPolicyDecision.allow();
    }

    private static int maxWritesPerMinute() {
        String raw = System.getProperty("yicli.wechat.write.per.minute");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("YICLI_WECHAT_WRITE_PER_MINUTE");
        }
        if (raw != null && !raw.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException ignored) {
                // 回退默认值
            }
        }
        return 10;
    }

    private WechatPolicyDecision mcpAllowed(String toolName) {
        for (String allowed : config.mcpAllowlist()) {
            String normalized = allowed == null ? "" : allowed.trim();
            if (!normalized.isBlank() && toolName.equals(normalized)) {
                return WechatPolicyDecision.allow();
            }
            if (!normalized.isBlank() && toolName.startsWith("mcp__" + normalized + "__")) {
                return WechatPolicyDecision.allow();
            }
        }
        return WechatPolicyDecision.deny("微信通道默认拒绝 MCP 工具: " + toolName);
    }

    private static boolean isReadOnlyBuiltin(String name) {
        return switch (name) {
            case "read_file", "list_dir", "glob_files", "grep_code", "search_code",
                    "web_search", "web_fetch", "browser_status" -> true;
            default -> false;
        };
    }

    private static String extract(String json, String field) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
