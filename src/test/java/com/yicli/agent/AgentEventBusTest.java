package com.yicli.agent;

import com.yicli.event.YiCliEvent;
import com.yicli.event.YiCliEventBus;
import com.yicli.llm.GLMClient;
import com.yicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEventBusTest {

    @Test
    void publishesTurnStartedAndEndedAroundReActRun() {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "你好", null, 20, 10)
        ));
        Agent agent = new Agent(llmClient);
        YiCliEventBus bus = new YiCliEventBus();
        List<YiCliEvent> events = new CopyOnWriteArrayList<>();
        bus.subscribe(YiCliEvent.TURN_STARTED, events::add);
        bus.subscribe(YiCliEvent.TURN_ENDED, events::add);
        agent.setEventBus(bus);

        String result = agent.run("你好");

        assertEquals("你好", result);
        assertEquals(2, events.size(), "一轮 ReAct 应发布 TURN_STARTED 与 TURN_ENDED");
        assertEquals(YiCliEvent.TURN_STARTED, events.get(0).type());
        assertEquals(YiCliEvent.TURN_ENDED, events.get(1).type());
    }

    @Test
    void publishesTurnEndedEvenWhenLlmCallFails() {
        StubGLMClient llmClient = new StubGLMClient(List.of());
        Agent agent = new Agent(llmClient);
        YiCliEventBus bus = new YiCliEventBus();
        List<YiCliEvent> events = new CopyOnWriteArrayList<>();
        bus.subscribe(YiCliEvent.TURN_ENDED, events::add);
        agent.setEventBus(bus);

        String result = agent.run("触发失败");

        assertEquals(1, events.size(), "LLM 调用失败也应发布 TURN_ENDED");
        assertEquals(YiCliEvent.TURN_ENDED, events.get(0).type());
        assertEquals(true, result.contains("失败"));
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
