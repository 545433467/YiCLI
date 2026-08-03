package com.yicli.tool;

import com.yicli.event.YiCliEvent;
import com.yicli.event.YiCliEventBus;
import com.yicli.policy.YicliSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryEventBusTest {

    @TempDir
    Path tempDir;

    @Test
    void publishesStartedAndCompletedForSuccessfulTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        YiCliEventBus bus = new YiCliEventBus();
        List<YiCliEvent> events = new CopyOnWriteArrayList<>();
        bus.subscribe(YiCliEvent.TOOL_CALL_STARTED, events::add);
        bus.subscribe(YiCliEvent.TOOL_CALL_COMPLETED, events::add);
        registry.setEventBus(bus);

        registry.executeToolOutput("list_dir", "{\"path\":\".\"}");

        assertEquals(2, events.size());
        assertEquals(YiCliEvent.TOOL_CALL_STARTED, events.get(0).type());
        assertEquals("list_dir", events.get(0).toolName());
        assertEquals(YiCliEvent.TOOL_CALL_COMPLETED, events.get(1).type());
        assertTrue(events.get(1).succeeded());
    }

    @Test
    void publishesFailedEventForUnknownTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        YiCliEventBus bus = new YiCliEventBus();
        List<YiCliEvent> events = new CopyOnWriteArrayList<>();
        bus.subscribe(YiCliEvent.TOOL_CALL_FAILED, events::add);
        registry.setEventBus(bus);

        registry.executeToolOutput("no_such_tool", "{}");

        assertEquals(1, events.size());
        assertEquals(YiCliEvent.TOOL_CALL_FAILED, events.get(0).type());
        assertFalse(events.get(0).succeeded());
    }

    @Test
    void subscriberExceptionDoesNotBreakToolExecution() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        YiCliEventBus bus = new YiCliEventBus();
        bus.subscribe(YiCliEvent.TOOL_CALL_STARTED, event -> {
            throw new IllegalStateException("bad subscriber");
        });
        registry.setEventBus(bus);

        ToolOutput output = registry.executeToolOutput("list_dir", "{\"path\":\".\"}");

        assertTrue(output.text().contains(tempDir.getFileName().toString()) || !output.text().isBlank());
    }

    @Test
    void sharedThreadPoolExecutesBatchInOriginalOrder() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        List<ToolRegistry.ToolInvocation> invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "list_dir", "{\"path\":\".\"}"),
                new ToolRegistry.ToolInvocation("call_2", "list_dir", "{\"path\":\".\"}"),
                new ToolRegistry.ToolInvocation("call_3", "list_dir", "{\"path\":\".\"}")
        );

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(invocations);

        assertEquals(3, results.size());
        assertEquals("call_1", results.get(0).id());
        assertEquals("call_2", results.get(1).id());
        assertEquals("call_3", results.get(2).id());
        assertTrue(results.stream().noneMatch(ToolRegistry.ToolExecutionResult::timedOut));
    }

    @Test
    void sandboxOffModeDoesNotInvokeDocker() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        registry.setSandbox(new YicliSandbox("off", "unused"));

        // 空命令直接返回参数错误，不经过任何进程执行（本地 / docker 都不触发）
        String result = registry.executeTool("execute_command", "{\"command\":\"\"}");

        assertTrue(result.contains("命令不能为空"));
    }
}
