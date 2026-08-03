package com.yicli.session;

import com.yicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndRestoresFullMessageHistory() throws Exception {
        SessionManager manager = new SessionManager(tempDir);
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.system("system prompt"),
                LlmClient.Message.user("帮我读 README"),
                LlmClient.Message.assistant("思考中", "我来读", List.of(
                        new LlmClient.ToolCall("call_1",
                                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"))
                )),
                LlmClient.Message.tool("call_1", "文件内容")
        );

        String id = manager.save(history, "帮我读 README");
        List<LlmClient.Message> restored = manager.load(id);

        assertEquals(history.size(), restored.size());
        assertEquals("user", restored.get(1).role());
        assertEquals("思考中", restored.get(2).reasoningContent());
        assertEquals(1, restored.get(2).toolCalls().size());
        assertEquals("read_file", restored.get(2).toolCalls().get(0).function().name());
        assertEquals("call_1", restored.get(3).toolCallId());
        assertEquals("文件内容", restored.get(3).content());
    }

    @Test
    void listsAndDeletesSessions() throws Exception {
        SessionManager manager = new SessionManager(tempDir);
        manager.save(List.of(LlmClient.Message.system("s"), LlmClient.Message.user("第一次任务")), "第一次任务");
        manager.save(List.of(LlmClient.Message.system("s"), LlmClient.Message.user("第二次任务")), "第二次任务");

        List<SessionManager.SessionInfo> sessions = manager.list();
        assertEquals(2, sessions.size());
        assertTrue(sessions.stream().anyMatch(s -> "第一次任务".equals(s.title())));
        assertTrue(sessions.stream().anyMatch(s -> "第二次任务".equals(s.title())));

        String targetId = sessions.stream()
                .filter(s -> "第一次任务".equals(s.title()))
                .findFirst()
                .orElseThrow()
                .sessionId();
        assertTrue(manager.delete(targetId));
        assertEquals(1, manager.list().size());
    }

    @Test
    void rejectsPathTraversalSessionId() {
        SessionManager manager = new SessionManager(tempDir);
        assertThrows(IllegalArgumentException.class, () -> manager.load("../../etc/passwd"));
    }
}
