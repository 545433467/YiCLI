package com.yicli.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 权限记忆（P1-4）：把用户批准过的危险工具调用持久化到
 * {@code ~/.yicli/permissions.json}，下次相同调用自动放行，不再重复打扰。
 *
 * <p>匹配语义：按 (tool, 归一化参数) 精确匹配。归一化 = 压缩连续空白并 trim，
 * 避免因参数间空格数不同导致误判；绝不使用子串匹配，防止"批准 mvn 后连 mvn clean 都放行"。
 */
public class PermissionStore {

    private static final Path DEFAULT_FILE = Path.of(System.getProperty("user.home"), ".yicli", "permissions.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public PermissionStore() {
        this(DEFAULT_FILE);
    }

    public PermissionStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(String tool, String pattern, String action, int hitCount, long createdAt) {
    }

    public boolean isAllowed(String toolName, String arguments) {
        return matches(toolName, arguments, "allow");
    }

    public boolean isDenied(String toolName, String arguments) {
        return matches(toolName, arguments, "deny");
    }

    public synchronized void remember(String toolName, String arguments, String action) {
        List<Rule> rules = load();
        String pattern = normalize(arguments);
        List<Rule> updated = new ArrayList<>();
        boolean found = false;
        for (Rule rule : rules) {
            if (rule.tool().equals(toolName) && rule.pattern().equals(pattern)) {
                updated.add(new Rule(rule.tool(), rule.pattern(), action,
                        rule.hitCount() + 1, rule.createdAt()));
                found = true;
            } else {
                updated.add(rule);
            }
        }
        if (!found) {
            updated.add(new Rule(toolName, pattern, action, 1, System.currentTimeMillis()));
        }
        save(updated);
    }

    public synchronized List<Rule> list() {
        return load();
    }

    public synchronized boolean remove(String toolName, String pattern) {
        List<Rule> updated = new ArrayList<>();
        boolean removed = false;
        for (Rule rule : load()) {
            if (rule.tool().equals(toolName) && rule.pattern().equals(normalize(pattern))) {
                removed = true;
            } else {
                updated.add(rule);
            }
        }
        if (removed) {
            save(updated);
        }
        return removed;
    }

    public synchronized void clear() {
        save(List.of());
    }

    private boolean matches(String toolName, String arguments, String action) {
        String pattern = normalize(arguments);
        for (Rule rule : load()) {
            if (action.equals(rule.action())
                    && rule.tool().equals(toolName)
                    && rule.pattern().equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private List<Rule> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(file.toFile());
            ArrayNode rulesNode = root.withArray("rules");
            List<Rule> rules = new ArrayList<>();
            for (var node : rulesNode) {
                Rule rule = MAPPER.treeToValue(node, Rule.class);
                if (rule != null) {
                    rules.add(rule);
                }
            }
            rules.sort(Comparator.comparingLong(Rule::createdAt));
            return rules;
        } catch (IOException e) {
            return List.of();
        }
    }

    private void save(List<Rule> rules) {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rulesNode = root.putArray("rules");
            for (Rule rule : rules) {
                rulesNode.addPOJO(rule);
            }
            MAPPER.writeValue(file.toFile(), root);
        } catch (IOException e) {
            // 权限记忆写失败只影响"免打扰"，不阻断审批主流程
        }
    }

    static String normalize(String arguments) {
        if (arguments == null) {
            return "";
        }
        return arguments.trim().replaceAll("\\s+", " ");
    }

    public String listText() {
        List<Rule> rules = list();
        if (rules.isEmpty()) {
            return "🔓 没有已记住的权限规则\n";
        }
        StringBuilder sb = new StringBuilder("🔑 已记住的权限规则（精确匹配）:\n");
        for (Rule rule : rules) {
            sb.append("  [").append(rule.action()).append("] ")
                    .append(rule.tool()).append(" ").append(rule.pattern())
                    .append("  ×").append(rule.hitCount()).append("\n");
        }
        return sb.toString();
    }
}
