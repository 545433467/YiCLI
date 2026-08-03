package com.yicli.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yicli.config.YiCliEnv;
import com.yicli.event.YiCliEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量遥测（P2-2）：订阅事件总线，把每一轮 ReAct 的 turn span 与 tool span
 * 追加写入 {@code ~/.yicli/telemetry/YYYY-MM-DD.jsonl}，并提供当日摘要。
 *
 * <p>不引入 OTel 依赖；JSONL 格式保持可读、可后续批量导入。
 * 成本估算按每百万 token 单价配置，未配置时只统计 token。
 */
public class TurnTelemetry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;
    private final boolean enabled;
    private final Object lock = new Object();
    private final List<TurnSpan> turns = new ArrayList<>();
    private final Map<String, Long> toolStarts = new HashMap<>();
    private TurnSpanBuilder currentTurn;

    public record ToolSpan(String tool, long durationMs, boolean succeeded, long at) {
    }

    public record TurnSpan(String turnId, long startedAt, long endedAt,
                           int inputTokens, int outputTokens, int cachedInputTokens,
                           List<ToolSpan> tools) {
        public long durationMs() {
            return Math.max(0, endedAt - startedAt);
        }
    }

    public TurnTelemetry() {
        this(Path.of(System.getProperty("user.home"), ".yicli", "telemetry"),
                YiCliEnv.getBool(YiCliEnv.TELEMETRY_ENABLED));
    }

    public TurnTelemetry(Path dir, boolean enabled) {
        this.dir = dir;
        this.enabled = enabled;
    }

    public void onEvent(YiCliEvent event) {
        if (event == null || !enabled) {
            return;
        }
        switch (event.type()) {
            case YiCliEvent.TURN_STARTED -> onTurnStarted();
            case YiCliEvent.TURN_ENDED -> onTurnEnded(event.inputTokens(), event.outputTokens(), event.cachedInputTokens());
            case YiCliEvent.TOOL_CALL_STARTED -> onToolStarted(event.toolName(), event.toolArgs());
            case YiCliEvent.TOOL_CALL_COMPLETED, YiCliEvent.TOOL_CALL_FAILED ->
                    onToolFinished(event.toolName(), event.toolArgs(), event.succeeded());
            default -> {
            }
        }
    }

    private void onTurnStarted() {
        synchronized (lock) {
            currentTurn = new TurnSpanBuilder(
                    "turn_" + System.currentTimeMillis(),
                    System.currentTimeMillis());
            toolStarts.clear();
        }
    }

    private void onToolStarted(String tool, String args) {
        if (tool == null) {
            return;
        }
        synchronized (lock) {
            toolStarts.put(key(tool, args), System.currentTimeMillis());
        }
    }

    private void onToolFinished(String tool, String args, boolean succeeded) {
        if (tool == null) {
            return;
        }
        synchronized (lock) {
            Long started = toolStarts.remove(key(tool, args));
            long duration = started == null ? 0 : System.currentTimeMillis() - started;
            if (currentTurn != null) {
                currentTurn.tools.add(new ToolSpan(tool, duration, succeeded, System.currentTimeMillis()));
            }
        }
    }

    private void onTurnEnded(int input, int output, int cached) {
        synchronized (lock) {
            if (currentTurn == null) {
                return;
            }
            TurnSpan span = currentTurn.build(input, output, cached);
            turns.add(span);
            currentTurn = null;
            toolStarts.clear();
            appendToFile(span);
        }
    }

    private void appendToFile(TurnSpan span) {
        try {
            Files.createDirectories(dir);
            String fileName = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".jsonl";
            Files.writeString(dir.resolve(fileName),
                    MAPPER.writeValueAsString(span) + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 遥测落盘失败不影响主流程
        }
    }

    /** 当日摘要：轮次数、工具调用数、token 合计与可选成本估算。 */
    public String todaySummary() {
        synchronized (lock) {
            if (turns.isEmpty()) {
                return "📊 今日还没有完成的 Agent 轮次\n";
            }
            int turnsCount = turns.size();
            int toolCalls = 0;
            long input = 0;
            long output = 0;
            long cached = 0;
            long totalMs = 0;
            for (TurnSpan turn : turns) {
                toolCalls += turn.tools().size();
                input += turn.inputTokens();
                output += turn.outputTokens();
                cached += turn.cachedInputTokens();
                totalMs += turn.durationMs();
            }
            StringBuilder sb = new StringBuilder("📊 今日遥测（本进程）:\n");
            sb.append("  轮次: ").append(turnsCount)
                    .append("，工具调用: ").append(toolCalls)
                    .append("，总耗时: ").append((totalMs + 999) / 1000).append("s\n");
            sb.append("  tokens: input=").append(input)
                    .append(" output=").append(output)
                    .append(" cached=").append(cached).append("\n");
            double cost = estimateCostUsd(input, output);
            if (cost > 0) {
                sb.append("  估算成本: $").append(String.format("%.4f", cost)).append("\n");
            } else {
                sb.append("  成本: 未配置单价，仅统计 token\n");
            }
            return sb.toString();
        }
    }

    private static double estimateCostUsd(long input, long output) {
        double inRate = rateProperty("yicli.telemetry.cost.input.per.million");
        double outRate = rateProperty("yicli.telemetry.cost.output.per.million");
        return (input / 1_000_000.0) * inRate + (output / 1_000_000.0) * outRate;
    }

    private static double rateProperty(String key) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String key(String tool, String args) {
        return tool + "@" + Integer.toHexString(args == null ? 0 : args.hashCode());
    }

    private static final class TurnSpanBuilder {
        private final String turnId;
        private final long startedAt;
        private final List<ToolSpan> tools = new ArrayList<>();

        private TurnSpanBuilder(String turnId, long startedAt) {
            this.turnId = turnId;
            this.startedAt = startedAt;
        }

        private TurnSpan build(int input, int output, int cached) {
            return new TurnSpan(turnId, startedAt, System.currentTimeMillis(),
                    input, output, cached, List.copyOf(tools));
        }
    }
}
