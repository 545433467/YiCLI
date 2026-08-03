package com.yicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yicli.llm.LlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LLM 交互录制器（P1-6）：包装任意 LlmClient，把每次 chat 的请求与响应
 * 记录为可回放的交换记录。用于离线 eval：真实跑一轮后保存 JSONL，
 * 改 prompt / 工具逻辑后再用 ReplayLlmClient 重放比对工具调用序列。
 */
public class LlmTraceRecorder implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient delegate;
    private final List<RecordedExchange> exchanges = new CopyOnWriteArrayList<>();

    public record RecordedExchange(List<LlmClient.Message> request, List<LlmClient.Tool> tools,
                                   LlmClient.ChatResponse response) {
    }

    public LlmTraceRecorder(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        ChatResponse response = delegate.chat(messages, tools, listener);
        exchanges.add(new RecordedExchange(
                messages == null ? List.of() : List.copyOf(messages),
                tools == null ? List.of() : List.copyOf(tools),
                response));
        return response;
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public int maxContextWindow() {
        return delegate.maxContextWindow();
    }

    @Override
    public boolean supportsPromptCaching() {
        return delegate.supportsPromptCaching();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public boolean supportsImageInput() {
        return delegate.supportsImageInput();
    }

    @Override
    public String promptCacheMode() {
        return delegate.promptCacheMode();
    }

    public List<RecordedExchange> exchanges() {
        return List.copyOf(exchanges);
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (RecordedExchange exchange : exchanges) {
            sb.append(MAPPER.writeValueAsString(exchange)).append('\n');
        }
        Files.writeString(file, sb.toString());
    }
}
