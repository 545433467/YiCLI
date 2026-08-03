package com.yicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yicli.llm.LlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * LLM 回放客户端（P1-6）：按录制顺序逐条返回预设响应，
 * 供离线 eval 在没有真实 API 的情况下重放 Agent / SubAgent 交互。
 */
public class ReplayLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Queue<ChatResponse> responses = new ArrayDeque<>();
    private final String modelName;
    private final String providerName;

    public ReplayLlmClient(String providerName, String modelName, Queue<ChatResponse> responses) {
        this.providerName = providerName;
        this.modelName = modelName;
        this.responses.addAll(responses);
    }

    public static ReplayLlmClient fromJsonl(Path file, String providerName, String modelName) throws IOException {
        Queue<ChatResponse> responses = new ArrayDeque<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            LlmTraceRecorder.RecordedExchange exchange =
                    MAPPER.readValue(line, LlmTraceRecorder.RecordedExchange.class);
            if (exchange.response() != null) {
                responses.add(exchange.response());
            }
        }
        return new ReplayLlmClient(providerName, modelName, responses);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        ChatResponse response = responses.poll();
        if (response == null) {
            throw new IOException("回放序列已耗尽，没有更多预设响应");
        }
        return response;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public int maxContextWindow() {
        return 128_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return false;
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public boolean supportsImageInput() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "none";
    }
}
