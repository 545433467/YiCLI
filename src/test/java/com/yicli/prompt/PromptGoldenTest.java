package com.yicli.prompt;

import com.yicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prompt golden 快照（P1-6）：把 AGENT 模式的完整 system prompt（含动态工具目录）
 * 固化为测试资源。任何 prompt / 工具描述变更都会让测试失败，
 * 确认是预期变更后运行 {@code mvn test -Dtest=PromptGoldenTest -Dyicli.golden.update=true} 更新快照。
 */
class PromptGoldenTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/agent-prompt.md");

    @Test
    void agentPromptMatchesGoldenSnapshot() throws Exception {
        String prompt = assembleAgentPrompt();
        String normalized = normalize(prompt);

        if (System.getProperty("yicli.golden.update") != null) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, normalized);
            return;
        }
        if (!Files.exists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, normalized);
            throw new IllegalStateException("golden 文件不存在，已生成 " + GOLDEN + "，请人工确认后提交");
        }

        String golden = normalize(Files.readString(GOLDEN));
        assertEquals(golden, normalized,
                "Agent system prompt 与 golden 不一致。确认是预期变更后运行 "
                        + "mvn test -Dtest=PromptGoldenTest -Dyicli.golden.update=true 更新快照");
    }

    private static String assembleAgentPrompt() {
        ToolRegistry registry = new ToolRegistry();
        String catalog = ToolCatalogFormatter.format(registry.getToolDefinitions());
        return PromptAssembler.createDefault().assemble(PromptMode.AGENT, PromptContext.builder()
                .toolCatalog(catalog)
                .projectMemoryContext("## PAI.md 项目记忆\n- 测试规则")
                .memoryContext("## 相关记忆\n用户偏好中文。")
                .externalContext("## MCP Resources\n- demo://resource")
                .skillIndex("## 可用 Skills\n- web-access")
                .build());
    }

    private static String normalize(String prompt) {
        return prompt
                .replaceAll("(?m)^- 当前日期: .*$", "- 当前日期: <date>")
                .replaceAll("(?m)^- 当前时区: .*$", "- 当前时区: <zone>");
    }
}
