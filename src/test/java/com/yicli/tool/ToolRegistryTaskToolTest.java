package com.yicli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTaskToolTest {

    @TempDir
    Path tempDir;

    @Test
    void taskToolDelegatesToConfiguredRunner() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        AtomicInteger calls = new AtomicInteger();
        registry.setSubAgentRunner(description -> {
            calls.incrementAndGet();
            return "子代理结论: " + description;
        });

        String result = registry.executeTool("task",
                "{\"description\":\"探索 src 目录结构\",\"context\":\"只看顶层\"}");

        assertEquals(1, calls.get());
        assertTrue(result.contains("子代理结论: 探索 src 目录结构"));
    }

    @Test
    void taskToolRejectsMissingDescription() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        registry.setSubAgentRunner(description -> "unused");

        String result = registry.executeTool("task", "{\"context\":\"x\"}");

        assertTrue(result.contains("description 不能为空"));
    }

    @Test
    void taskToolLimitsNestedSubAgents() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        AtomicInteger calls = new AtomicInteger();
        registry.setSubAgentRunner(description -> {
            if (calls.incrementAndGet() < 3) {
                return registry.executeTool("task", "{\"description\":\"nested " + calls.get() + "\"}");
            }
            return "deep done";
        });

        String result = registry.executeTool("task", "{\"description\":\"top\"}");

        assertEquals(2, calls.get());
        assertTrue(result.contains("子代理嵌套超过 2 层"),
                "第三层嵌套应被拒绝: " + result);
    }

    @Test
    void taskToolReportsUnconfiguredRunner() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("task", "{\"description\":\"x\"}");

        assertTrue(result.contains("子代理运行器未配置"));
    }
}
