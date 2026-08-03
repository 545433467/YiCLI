package com.yicli.mcp;

/**
 * MCP server 自动重启退避策略（P2-3）：指数退避，1s → 2s → 4s ... 上限 60s。
 */
public final class AutoRestartPolicy {

    private static final long BASE_DELAY_MS = 1_000L;
    private static final long MAX_DELAY_MS = 60_000L;

    private AutoRestartPolicy() {
    }

    public static long nextDelayMs(int attempt) {
        int shift = Math.min(Math.max(0, attempt - 1), 6);  // 1<<6 = 64s > 60s 上限
        return Math.min(BASE_DELAY_MS << shift, MAX_DELAY_MS);
    }
}
