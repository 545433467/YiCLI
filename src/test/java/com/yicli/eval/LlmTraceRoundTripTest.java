package com.yicli.eval;

import com.yicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmTraceRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsExchangesAndReplaysResponses() throws Exception {
        StubClient stub = new StubClient();
        LlmTraceRecorder recorder = new LlmTraceRecorder(stub);
        recorder.chat(List.of(LlmClient.Message.user("hi")), null);
        assertEquals(1, recorder.exchanges().size());

        Path file = tempDir.resolve("trace.jsonl");
        recorder.save(file);

        ReplayLlmClient replay = ReplayLlmClient.fromJsonl(file, "test", "mock");
        LlmClient.ChatResponse response = replay.chat(List.of(LlmClient.Message.user("hi")), null);
        assertEquals("ok", response.content());
        assertEquals("test", replay.getProviderName());

        assertThrows(IOException.class,
                () -> replay.chat(List.of(LlmClient.Message.user("hi")), null),
                "回放序列耗尽后应抛出 IOException");
    }

    private static final class StubClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", "ok", null, List.of(), 12, 3);
        }

        @Override
        public String getModelName() {
            return "mock";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
