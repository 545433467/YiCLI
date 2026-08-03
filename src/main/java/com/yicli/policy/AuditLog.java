package com.yicli.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yicli.browser.BrowserAuditMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 危险工具调用的结构化审计日志。
 *
 * 落盘策略：
 * - 一行一条 JSON（JSONL 格式），按天分文件 audit-YYYY-MM-DD.jsonl
 * - 默认目录 ~/.yicli/audit，可通过 -Dyicli.audit.dir 或 YICLI_AUDIT_DIR 覆盖
 * - 写入失败只在 stderr 提示，不抛出，避免审计故障影响主流程
 *
 * 设计意图：
 * - 把 Agent 的"实际副作用"变成可回放的事实流
 * - 行为评估、差错复盘、监控告警的统一数据源
 *
 * 接入点：
 * - {@code allow}：危险工具执行成功
 * - {@code deny}：被 HITL 拒绝 / 跳过，或被策略层拦截
 * - {@code error}：工具执行抛异常或超时
 */
public class AuditLog {

    public static final String APPROVER_HITL = "hitl";
    public static final String APPROVER_POLICY = "policy";
    public static final String APPROVER_NONE = "none";
    public static final String APPROVER_MENTION = "mention";

    public static final String OUTCOME_ALLOW = "allow";
    public static final String OUTCOME_DENY = "deny";
    public static final String OUTCOME_ERROR = "error";

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final int MAX_FIELD_CHARS = 1000;

    private final Path auditDir;
    private final Object writeLock = new Object();

    public AuditLog() {
        this(defaultAuditDir());
    }

    public AuditLog(Path auditDir) {
        this.auditDir = auditDir;
    }

    public Path getAuditDir() {
        return auditDir;
    }

    public void record(AuditEntry entry) {
        if (entry == null) return;
        try {
            synchronized (writeLock) {
                Files.createDirectories(auditDir);
                Path file = todayFile();
                String json = mapper.writeValueAsString(entry);
                Files.writeString(file, json + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // 审计失败不能影响主流程
            System.err.println("⚠️ 审计日志写入失败: " + e.getMessage());
        }
    }

    /**
     * 读取今天审计文件最近 n 条记录，按写入顺序返回（最新的在末尾）。
     */
    public List<AuditEntry> readRecent(int n) {
        if (n <= 0) return List.of();
        Path file = todayFile();
        if (!Files.exists(file)) return List.of();

        try {
            List<String> lines = Files.readAllLines(file);
            int from = Math.max(0, lines.size() - n);
            List<AuditEntry> entries = new ArrayList<>();
            for (int i = from; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                try {
                    entries.add(mapper.readValue(line, new TypeReference<AuditEntry>() {}));
                } catch (Exception ignored) {
                    // 单行格式错误跳过，不影响其他记录
                }
            }
            return entries;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path todayFile() {
        return auditDir.resolve("audit-" + LocalDate.now().format(DATE_FMT) + ".jsonl");
    }

    private static Path defaultAuditDir() {
        String prop = System.getProperty("yicli.audit.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("YICLI_AUDIT_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".yicli", "audit");
    }

    private static String truncate(String s) {
        if (s == null) return null;
        String sanitized = sanitize(s);
        return sanitized.length() <= MAX_FIELD_CHARS ? sanitized : sanitized.substring(0, MAX_FIELD_CHARS) + "...(truncated)";
    }

    static String sanitize(String s) {
        if (s == null) return null;
        String sanitized = s.replaceAll("(?i)Bearer\\s+[^\\s\"'}]+", "Bearer ***");
        sanitized = sanitized.replaceAll(
                "(?i)(\"?(?:token|key|password|secret|authorization)\"?\\s*[:=]\\s*\")([^\"]+)(\")",
                "$1***$3");
        sanitized = sanitized.replaceAll(
                "(?i)(\\b(?:token|key|password|secret|authorization)\\b\\s*[:=]\\s*)([^\\s,}]+)",
                "$1***");
        return sanitized;
    }

    /**
     * 审计前的参数脱敏：
     * - write_file：content 只保留长度摘要，不落盘文件正文（可能含密钥/内网地址/真实代码）
     * - MCP 工具：按 JSON 字段名结构化掩码（token/key/secret/password/authorization/credential 等）
     * - 其余工具：走原有正则脱敏 + 截断
     */
    public static String redactArgs(String tool, String args) {
        if (args == null) {
            return null;
        }
        if ("write_file".equals(tool)) {
            try {
                ObjectNode node = (ObjectNode) mapper.readTree(args);
                JsonNode content = node.get("content");
                if (content != null) {
                    node.put("content", "[content 已脱敏: " + content.asText("").length() + " 字符]");
                }
                return sanitize(node.toString());
            } catch (Exception ignored) {
                // 非法 JSON 走通用脱敏
            }
        }
        if (tool != null && tool.startsWith("mcp__")) {
            try {
                return sanitize(maskSensitiveFields(mapper.readTree(args)).toString());
            } catch (Exception ignored) {
                // 非法 JSON 走通用脱敏
            }
        }
        return sanitize(args);
    }

    private static JsonNode maskSensitiveFields(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (isSensitiveFieldName(key)) {
                    if (value.isTextual()) {
                        out.put(key, "***");
                    } else {
                        out.set(key, maskSensitiveFields(value));
                    }
                } else {
                    out.set(key, maskSensitiveFields(value));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            node.forEach(item -> out.add(maskSensitiveFields(item)));
            return out;
        }
        return node.deepCopy();
    }

    private static boolean isSensitiveFieldName(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("key")
                || k.contains("token")
                || k.contains("apikey") || k.contains("api_key") || k.contains("api-key")
                || k.contains("secret")
                || k.contains("password") || k.contains("passwd")
                || k.contains("authorization") || k.contains("auth")
                || k.contains("credential") || k.contains("bearer");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditEntry(
            String timestamp,
            String tool,
            String args,
            String outcome,
            String reason,
            String approver,
            long durationMs,
            BrowserAuditMetadata metadata
    ) {
        public static AuditEntry allow(String tool, String args, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ALLOW, null, APPROVER_NONE, durationMs, null);
        }

        public static AuditEntry allow(String tool, String args, long durationMs, BrowserAuditMetadata metadata) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ALLOW, null, APPROVER_NONE, durationMs, metadata);
        }

        public static AuditEntry allowByMention(String tool, String args, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ALLOW, null, APPROVER_MENTION, durationMs, null);
        }

        public static AuditEntry denyByHitl(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_DENY, reason, APPROVER_HITL, durationMs, null);
        }

        public static AuditEntry denyByPolicy(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_DENY, reason, APPROVER_POLICY, durationMs, null);
        }

        public static AuditEntry denyByPolicy(String tool, String args, String reason, long durationMs,
                                              BrowserAuditMetadata metadata) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_DENY, reason, APPROVER_POLICY, durationMs, metadata);
        }

        public static AuditEntry error(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ERROR, reason, APPROVER_NONE, durationMs, null);
        }

        public static AuditEntry error(String tool, String args, String reason, long durationMs,
                                       BrowserAuditMetadata metadata) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ERROR, reason, APPROVER_NONE, durationMs, metadata);
        }
    }
}
