package com.yicli.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 环境体检（P1-5）：一条命令检查 Java、ripgrep、API Key、配置目录、
 * MCP / 权限记忆 / 会话存储等关键前置条件，输出诊断报告。
 */
public final class YiCliDoctor {

    private static final Path HOME_DIR = Path.of(System.getProperty("user.home"), ".yicli");

    private YiCliDoctor() {
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("YiCLI Doctor\n");
        sb.append("============\n\n");

        checkJava(sb);
        checkRipgrep(sb);
        checkApiKeys(sb);
        checkDataDirs(sb);
        checkRenderer(sb);

        return sb.toString();
    }

    private static void checkJava(StringBuilder sb) {
        sb.append("[✓/✗] Java: ").append(System.getProperty("java.version", "unknown"))
                .append(" (").append(System.getProperty("java.home", "?")).append(")\n");
    }

    private static void checkRipgrep(StringBuilder sb) {
        try {
            Process process = new ProcessBuilder("rg", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                sb.append("[✓] ripgrep: 已安装（grep_code 将优先使用）\n");
            } else {
                sb.append("[✗] ripgrep: 未安装或不可用，grep_code 会回退 Java 扫描\n");
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException e) {
            sb.append("[✗] ripgrep: 检测失败（").append(e.getMessage()).append("）\n");
        }
    }

    private static void checkApiKeys(StringBuilder sb) {
        YiCliConfig config = YiCliConfig.load();
        String[] providers = {"glm", "deepseek", "step", "kimi", "freellmapi", "xfyun", "agnes"};
        int configured = 0;
        for (String provider : providers) {
            String apiKey = config.getApiKey(provider);
            if (apiKey != null && !apiKey.isBlank()) {
                configured++;
                sb.append("[✓] API Key: ").append(provider).append(" = ")
                        .append(mask(apiKey)).append("\n");
            }
        }
        if (configured == 0) {
            sb.append("[✗] API Key: 未找到任何可用 Key，请在 .env 或 ~/.yicli/config.json 配置\n");
        }
    }

    private static void checkDataDirs(StringBuilder sb) {
        checkPath(sb, HOME_DIR, "数据目录 ~/.yicli", true);
        checkPath(sb, HOME_DIR.resolve("config.json"), "配置文件 config.json", false);
        checkPath(sb, HOME_DIR.resolve("mcp.json"), "MCP 配置 mcp.json", false);
        checkPath(sb, HOME_DIR.resolve("permissions.json"), "权限记忆 permissions.json", false);
        checkPath(sb, HOME_DIR.resolve("sessions"), "会话目录 sessions", true);
        checkPath(sb, HOME_DIR.resolve("memory"), "长期记忆目录 memory", true);
    }

    private static void checkPath(StringBuilder sb, Path path, String label, boolean dir) {
        boolean ok = dir ? Files.isDirectory(path) : Files.exists(path);
        sb.append(ok ? "[✓] " : "[ ] ").append(label).append(ok ? "" : "（尚未创建，首次使用会自动生成）").append("\n");
    }

    private static void checkRenderer(StringBuilder sb) {
        sb.append("[i] 渲染器: ").append(YiCliEnv.get(YiCliEnv.RENDERER)).append("\n");
        sb.append("[i] HITL 审批: 通过 /hitl on 启用后，危险工具会弹人工确认；")
                .append("批准过的调用会记入权限记忆免打扰\n");
    }

    private static String mask(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }
}
