package com.yicli.telemetry;

import com.yicli.event.YiCliEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnTelemetryTest {

    @TempDir
    Path tempDir;

    @Test
    void collectsTurnAndToolSpansFromEvents() {
        TurnTelemetry telemetry = new TurnTelemetry(tempDir, true);

        telemetry.onEvent(YiCliEvent.plain(YiCliEvent.TURN_STARTED));
        telemetry.onEvent(YiCliEvent.toolStarted("grep_code", "{\"pattern\":\"x\"}"));
        telemetry.onEvent(YiCliEvent.toolFinished("grep_code", "{\"pattern\":\"x\"}", "ok", true));
        telemetry.onEvent(YiCliEvent.toolFinished("write_file", "{}", "denied", false));
        telemetry.onEvent(YiCliEvent.turnEnded(100, 50, 10));

        String summary = telemetry.todaySummary();
        assertTrue(summary.contains("轮次: 1"));
        assertTrue(summary.contains("工具调用: 2"));
        assertTrue(summary.contains("input=100 output=50 cached=10"));
    }

    @Test
    void disabledTelemetryIgnoresEvents() {
        TurnTelemetry telemetry = new TurnTelemetry(tempDir, false);

        telemetry.onEvent(YiCliEvent.plain(YiCliEvent.TURN_STARTED));
        telemetry.onEvent(YiCliEvent.turnEnded(1, 1, 0));

        assertTrue(telemetry.todaySummary().contains("还没有完成的 Agent 轮次"));
    }

    @Test
    void writesJsonlFilePerTurn() throws Exception {
        TurnTelemetry telemetry = new TurnTelemetry(tempDir, true);

        telemetry.onEvent(YiCliEvent.plain(YiCliEvent.TURN_STARTED));
        telemetry.onEvent(YiCliEvent.toolStarted("read_file", "{}"));
        telemetry.onEvent(YiCliEvent.toolFinished("read_file", "{}", "content", true));
        telemetry.onEvent(YiCliEvent.turnEnded(20, 10, 0));

        try (var stream = Files.list(tempDir)) {
            long files = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).count();
            assertEquals(1, files, "每轮结束应追加一条 JSONL 记录");
        }
    }
}
