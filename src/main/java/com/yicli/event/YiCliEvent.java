package com.yicli.event;

import java.time.Instant;

/**
 * 进程内生命周期事件。事件总线只做观察者通知，不承担安全拦截；
 * HITL / 策略层仍在 ToolRegistry 调用链内同步执行。
 */
public record YiCliEvent(
        String type,
        String toolName,
        String toolArgs,
        String result,
        boolean succeeded,
        Instant timestamp,
        int inputTokens,
        int outputTokens,
        int cachedInputTokens
) {
    public static final String TOOL_CALL_STARTED = "tool_call_started";
    public static final String TOOL_CALL_COMPLETED = "tool_call_completed";
    public static final String TOOL_CALL_FAILED = "tool_call_failed";
    public static final String TURN_STARTED = "turn_started";
    public static final String TURN_ENDED = "turn_ended";
    public static final String APP_STOP = "app_stop";

    public static YiCliEvent toolStarted(String toolName, String toolArgs) {
        return new YiCliEvent(TOOL_CALL_STARTED, toolName, toolArgs, null, true, Instant.now(), 0, 0, 0);
    }

    public static YiCliEvent toolFinished(String toolName, String toolArgs, String result, boolean succeeded) {
        return new YiCliEvent(
                succeeded ? TOOL_CALL_COMPLETED : TOOL_CALL_FAILED,
                toolName, toolArgs, result, succeeded, Instant.now(), 0, 0, 0);
    }

    public static YiCliEvent plain(String type) {
        return new YiCliEvent(type, null, null, null, true, Instant.now(), 0, 0, 0);
    }

    public static YiCliEvent turnEnded(int inputTokens, int outputTokens, int cachedInputTokens) {
        return new YiCliEvent(TURN_ENDED, null, null, null, true, Instant.now(),
                inputTokens, outputTokens, cachedInputTokens);
    }
}
