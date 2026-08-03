package com.yicli.agent;

import com.yicli.llm.GLMClient;
import com.yicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCheckpointTest {

    @Test
    void savesCheckpointWhenLlmCallFails() {
        FailingClient llmClient = new FailingClient();
        Agent agent = new Agent(llmClient);
        AtomicReference<List<LlmClient.Message>> saved = new AtomicReference<>();
        agent.setSessionCheckpointer((history, title) -> {
            saved.set(history);
            return "session_checkpoint_1";
        });

        String result = agent.run("读一下代码");

        assertTrue(result.contains("调用 LLM 失败"));
        assertTrue(result.contains("/resume session_checkpoint_1"), result);
        assertEquals("session_checkpoint_1", agent.getLastCheckpointId());
        assertNotNull(saved.get());
        assertTrue(saved.get().stream().anyMatch(m -> "user".equals(m.role())
                && "读一下代码".equals(m.content())));
    }

    @Test
    void doesNotBreakRunWhenCheckpointSaverFails() {
        FailingClient llmClient = new FailingClient();
        Agent agent = new Agent(llmClient);
        agent.setSessionCheckpointer((history, title) -> {
            throw new IOException("disk full");
        });

        String result = agent.run("读一下代码");

        assertTrue(result.contains("调用 LLM 失败"));
        assertTrue(!result.contains("/resume"), "检查点保存失败不应输出恢复提示: " + result);
    }

    private static final class FailingClient extends GLMClient {
        private FailingClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            throw new IOException("模拟网络故障");
        }
    }
}
