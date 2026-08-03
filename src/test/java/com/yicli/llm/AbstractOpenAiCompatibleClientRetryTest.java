package com.yicli.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractOpenAiCompatibleClientRetryTest {

    private static final String OK_BODY = """
            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

            data: [DONE]

            """;

    private String previousBackoff;

    @BeforeEach
    void disableBackoffForFastTests() {
        previousBackoff = System.getProperty("yicli.llm.retry.backoff.seconds");
        System.setProperty("yicli.llm.retry.backoff.seconds", "0");
    }

    @AfterEach
    void restoreBackoff() {
        if (previousBackoff == null) {
            System.clearProperty("yicli.llm.retry.backoff.seconds");
        } else {
            System.setProperty("yicli.llm.retry.backoff.seconds", previousBackoff);
        }
    }

    @Test
    void retriesOn429ThenSucceeds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":{\"message\":\"rate limited\"}}"));
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(OK_BODY));

            TestClient client = new TestClient(server.url("/chat/completions").toString());
            AtomicInteger retries = new AtomicInteger();
            LlmClient.StreamListener listener = new LlmClient.StreamListener() {
                @Override
                public void onRetry(int attempt, long delayMillis, String reason) {
                    retries.incrementAndGet();
                }
            };

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("你好")), null, listener);

            assertEquals("ok", response.content());
            assertEquals(2, server.getRequestCount(), "429 后应重试一次");
            assertEquals(1, retries.get());
        }
    }

    @Test
    void retriesOnMalformedStreamBeforeAnyDelta() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"bad json\n\n"));
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(OK_BODY));

            TestClient client = new TestClient(server.url("/chat/completions").toString());
            AtomicInteger retries = new AtomicInteger();

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("你好")), null,
                    new LlmClient.StreamListener() {
                        @Override
                        public void onRetry(int attempt, long delayMillis, String reason) {
                            retries.incrementAndGet();
                        }
                    });

            assertEquals("ok", response.content());
            assertEquals(2, server.getRequestCount(), "流开始前的解析错误应重试");
            assertEquals(1, retries.get());
        }
    }

    @Test
    void doesNotRetryOnAuthError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":{\"message\":\"unauthorized\"}}"));

            TestClient client = new TestClient(server.url("/chat/completions").toString());
            AtomicInteger retries = new AtomicInteger();

            assertThrows(IOException.class, () -> client.chat(
                    List.of(LlmClient.Message.user("你好")), null,
                    new LlmClient.StreamListener() {
                        @Override
                        public void onRetry(int attempt, long delayMillis, String reason) {
                            retries.incrementAndGet();
                        }
                    }));

            assertEquals(1, server.getRequestCount(), "401 属于配置错误，不应重试");
            assertEquals(0, retries.get());
        }
    }

    @Test
    void doesNotRetryOnceStreamAlreadyStarted() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"content":"partial"}}]}

                            data: {"error":{"message":"mid-stream failure"}}

                            data: [DONE]

                            """));

            TestClient client = new TestClient(server.url("/chat/completions").toString());
            AtomicInteger retries = new AtomicInteger();

            assertThrows(IOException.class, () -> client.chat(
                    List.of(LlmClient.Message.user("你好")), null,
                    new LlmClient.StreamListener() {
                        @Override
                        public void onRetry(int attempt, long delayMillis, String reason) {
                            retries.incrementAndGet();
                        }
                    }));

            assertEquals(1, server.getRequestCount(), "流已输出内容后失败，重试会产生重复内容");
            assertEquals(0, retries.get());
        }
    }

    private static final class TestClient extends AbstractOpenAiCompatibleClient {
        private final String apiUrl;

        private TestClient(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        @Override
        protected String getApiUrl() {
            return apiUrl;
        }

        @Override
        protected String getModel() {
            return "retry-test";
        }

        @Override
        public String getModelName() {
            return getModel();
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        protected String getApiKey() {
            return "test-key";
        }
    }
}
