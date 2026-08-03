package com.yicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper mapper = new ObjectMapper();

    // SSE 流式接口下，OkHttp 的 readTimeout 是"两次 read 之间的最大间隔"，不是请求总时长。
    // GLM-5.1 在生成大段 reasoning_content 时服务端可能长时间静默，所以默认值放宽到 300s；
    // callTimeout 作为整体兜底，覆盖极端情况下的连接半死状态。
    // 三项均可通过系统属性覆盖，便于不同模型 / 网络环境调优。
    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("yicli.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("yicli.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("yicli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("yicli.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            .build();

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected abstract String getApiUrl();

    protected abstract String getModel();

    protected abstract String getApiKey();

    protected boolean shouldSendReasoningContentInRequestHistory() {
        return false;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;
        int maxAttempts = retryMaxAttempts();
        if (maxAttempts <= 1) {
            return executeChatOnce(messages, tools, streamListener);
        }

        int attempt = 0;
        long baseBackoffMillis = retryBaseBackoffMillis();
        while (true) {
            attempt++;
            try {
                return executeChatOnce(messages, tools, streamListener);
            } catch (RetryableRequestException e) {
                if (attempt >= maxAttempts || Thread.currentThread().isInterrupted()) {
                    throw e.unwrap();
                }
                long delayMillis = Math.min(baseBackoffMillis << Math.min(attempt - 1, 4), 30_000L);
                if (e.retryAfterMillis > 0) {
                    delayMillis = Math.max(delayMillis, e.retryAfterMillis);
                }
                // 加入少量抖动，避免多个客户端同时重试把上游打满
                delayMillis += ThreadLocalRandom.current().nextLong(0, Math.max(1L, delayMillis / 5));
                streamListener.onRetry(attempt, delayMillis, e.getMessage());
                sleepQuietly(delayMillis);
            }
        }
    }

    /**
     * 单次 LLM 请求执行。任何在流开始之前发生的失败（非 2xx、连接错误、首包超时）
     * 都会被包装成 {@link RetryableRequestException} 交给上层重试；流一旦开始输出，
     * 后续错误直接抛出，避免重试导致用户看到重复内容。
     */
    private ChatResponse executeChatOnce(List<Message> messages, List<Tool> tools, StreamListener streamListener) throws IOException {
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools).toString(),
                MediaType.parse("application/json")
        );

        Request.Builder request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .post(body);
        customizeRequest(request);
        Request builtRequest = request.build();

        boolean streamStarted = false;
        try (Response response = httpClient().newCall(builtRequest).execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                int status = response.code();
                String message = "API请求失败: " + status + " - " + truncate(errorBody, 500);
                if (isRetryableStatus(status)) {
                    throw new RetryableRequestException(status, retryAfterMillis(response), message);
                }
                throw new NonRetryableIOException(message);
            }
            if (responseBodyObj == null) {
                throw new NonRetryableIOException("API返回空响应体");
            }

            BufferedSource source = responseBodyObj.source();
            String role = "assistant";
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
            int inputTokens = 0;
            int outputTokens = 0;
            int cachedInputTokens = 0;

            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }

                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }

                JsonNode root = mapper.readTree(payload);
                JsonNode error = root.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    // SSE 显式 error chunk 属于 provider 协议级拒绝（如图片被拒、参数非法），
                    // 重试不会改变结果，直接抛出不可重试异常
                    throw new NonRetryableIOException("API请求失败: " + formatStreamingError(error));
                }
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                    cachedInputTokens = parseCachedInputTokens(usage, cachedInputTokens);
                }

                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode() || delta.isNull()) {
                    delta = choice.path("message");
                }
                if (delta.isMissingNode() || delta.isNull()) {
                    continue;
                }

                String deltaRole = delta.path("role").asText("");
                if (!deltaRole.isEmpty()) {
                    role = deltaRole;
                }

                String reasoningDelta = extractReasoningDelta(delta);
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    streamStarted = true;
                    streamListener.onReasoningDelta(reasoningDelta);
                }

                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    streamStarted = true;
                    streamListener.onContentDelta(contentDelta);
                }

                JsonNode toolCallsNode = delta.path("tool_calls");
                if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                    streamStarted = true;
                }
                mergeToolCallDeltas(toolAccumulators, toolCallsNode);
            }

            List<ToolCall> toolCalls = buildToolCalls(toolAccumulators);
            if (content.isEmpty() && reasoning.isEmpty() && (toolCalls == null || toolCalls.isEmpty())) {
                // 流协议已正常结束但内容为空，属于配置/模型兼容问题，重试无意义
                throw new NonRetryableIOException("API返回空内容，请检查 provider/model 配置或该模型是否支持当前请求参数");
            }

            return new ChatResponse(
                    role,
                    content.toString(),
                    reasoning.toString(),
                    toolCalls,
                    inputTokens,
                    outputTokens,
                    cachedInputTokens
            );
        } catch (NonRetryableIOException e) {
            throw e;
        } catch (RetryableRequestException e) {
            throw e;
        } catch (IOException e) {
            if (streamStarted) {
                throw e;
            }
            // 流开始前的网络错误（连接失败、首包超时等），可安全重试
            throw new RetryableRequestException(0, 0, e.getMessage(), e);
        }
    }

    private static void sleepQuietly(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int retryMaxAttempts() {
        return readIntProperty("yicli.llm.retry.max.attempts", 3, 1, 6);
    }

    private static long retryBaseBackoffMillis() {
        // min=0 允许测试关闭退避；生产默认 2s，重试次数由 max.attempts 封顶，不会形成紧循环
        return readLongProperty("yicli.llm.retry.backoff.seconds", 2L, 0L, 60L) * 1000L;
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429
                || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static long retryAfterMillis(Response response) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter == null || retryAfter.isBlank()) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return seconds > 0 ? seconds * 1000L : 0L;
        } catch (NumberFormatException e) {
            return 0L;  // HTTP 日期格式不解析，直接用退避策略
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private static int readIntProperty(String key, int defaultValue, int min, int max) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long readLongProperty(String key, long defaultValue, long min, long max) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 非 2xx 且不属于可重试状态码，或协议已结束但内容为空：直接失败，不重试。 */
    private static final class NonRetryableIOException extends IOException {
        NonRetryableIOException(String message) {
            super(message);
        }
    }

    /** 可安全重试的请求失败：携带 HTTP 状态码与 Retry-After 提示。 */
    private static final class RetryableRequestException extends IOException {
        final int statusCode;
        final long retryAfterMillis;

        RetryableRequestException(int statusCode, long retryAfterMillis, String message) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfterMillis = retryAfterMillis;
        }

        RetryableRequestException(int statusCode, long retryAfterMillis, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
            this.retryAfterMillis = retryAfterMillis;
        }

        IOException unwrap() {
            Throwable cause = getCause();
            if (cause instanceof IOException ioCause) {
                return ioCause;
            }
            return this;
        }
    }

    private String formatStreamingError(JsonNode error) {
        String message = error.path("message").asText("");
        String code = error.path("code").asText("");
        if (!code.isEmpty() && !message.isEmpty()) {
            return code + " - " + message;
        }
        if (!message.isEmpty()) {
            return message;
        }
        return error.toString();
    }

    private String extractReasoningDelta(JsonNode delta) {
        String reasoningContent = delta.path("reasoning_content").asText("");
        if (!reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        String reasoning = delta.path("reasoning").asText("");
        if (!reasoning.isEmpty()) {
            return reasoning;
        }
        JsonNode details = delta.path("reasoning_details");
        if (details.isArray() && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode detail : details) {
                String text = detail.path("text").asText("");
                if (text.isEmpty()) {
                    text = detail.path("content").asText("");
                }
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        cached = usage.path("input_cache_hit_tokens").asInt(cached);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }
        JsonNode inputDetails = usage.path("input_tokens_details");
        if (!inputDetails.isMissingNode()) {
            cached = inputDetails.path("cached_tokens").asInt(cached);
        }
        return cached;
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", getModel());
        requestBody.put("stream", true);

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            appendMessageContent(msgNode, msg);
            if (shouldSendReasoningContentInRequestHistory()
                    && "assistant".equals(msg.role())
                    && msg.reasoningContent() != null
                    && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }
        customizeRequestBody(requestBody);
        return requestBody;
    }

    protected void customizeRequestBody(ObjectNode requestBody) {
    }

    protected void customizeRequest(Request.Builder request) {
    }

    protected OkHttpClient httpClient() {
        return SHARED_HTTP_CLIENT;
    }

    private void appendMessageContent(ObjectNode msgNode, Message msg) {
        if (msg.hasImageContent() && !supportsImageInput()) {
            appendMessageContent(msgNode, msg.withoutImageContent(
                    "当前 provider/model 不支持图片附件，已省略 {count} 张；请基于文字工具结果继续，必要时改用支持视觉输入的模型。"));
            return;
        }

        if (!msg.hasContentParts()) {
            msgNode.put("content", msg.content());
            return;
        }

        ArrayNode contentArray = msgNode.putArray("content");
        for (LlmClient.ContentPart part : msg.contentParts()) {
            if (part == null) {
                continue;
            }
            if (part.isText()) {
                if (part.text() != null && !part.text().isBlank()) {
                    ObjectNode textNode = contentArray.addObject();
                    textNode.put("type", "text");
                    textNode.put("text", part.text());
                }
                continue;
            }
            if (part.isImage()) {
                String imageUrl = toImageUrl(part);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                ObjectNode imageNode = contentArray.addObject();
                imageNode.put("type", "image_url");
                ObjectNode imageUrlNode = imageNode.putObject("image_url");
                imageUrlNode.put("url", imageUrl);
            }
        }

        if (contentArray.isEmpty()) {
            msgNode.put("content", msg.content());
        }
    }

    protected String toImageUrl(LlmClient.ContentPart part) {
        if ("image_url".equals(part.type())) {
            return part.imageUrl();
        }
        if ("image_base64".equals(part.type())) {
            String mimeType = part.mimeType() == null || part.mimeType().isBlank() ? "image/png" : part.mimeType();
            return "data:" + mimeType + ";base64," + part.imageBase64();
        }
        return null;
    }

    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }

        for (JsonNode tc : toolCallsNode) {
            int index = tc.path("index").asInt(accumulators.size());
            while (accumulators.size() <= index) {
                accumulators.add(new ToolCallAccumulator());
            }

            ToolCallAccumulator acc = accumulators.get(index);
            String id = tc.path("id").asText("");
            if (!id.isEmpty()) {
                acc.id = id;
            }

            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");
            if (!name.isEmpty()) {
                acc.name.append(name);
            }
            String arguments = function.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                acc.arguments.append(arguments);
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.id == null || acc.id.isBlank()) {
                continue;
            }
            toolCalls.add(new ToolCall(
                    acc.id,
                    new ToolCall.Function(acc.name.toString(), acc.arguments.toString())
            ));
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }

    private static final class ToolCallAccumulator {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
