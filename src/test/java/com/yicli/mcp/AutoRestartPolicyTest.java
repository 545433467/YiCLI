package com.yicli.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoRestartPolicyTest {

    @Test
    void backoffGrowsExponentiallyThenCaps() {
        assertEquals(1_000L, AutoRestartPolicy.nextDelayMs(1));
        assertEquals(2_000L, AutoRestartPolicy.nextDelayMs(2));
        assertEquals(4_000L, AutoRestartPolicy.nextDelayMs(3));
        assertEquals(8_000L, AutoRestartPolicy.nextDelayMs(4));
        assertEquals(16_000L, AutoRestartPolicy.nextDelayMs(5));
        assertEquals(32_000L, AutoRestartPolicy.nextDelayMs(6));
        assertEquals(60_000L, AutoRestartPolicy.nextDelayMs(7));
        assertEquals(60_000L, AutoRestartPolicy.nextDelayMs(99));
    }

    @Test
    void clampsWeirdAttemptValues() {
        assertEquals(1_000L, AutoRestartPolicy.nextDelayMs(0));
        assertEquals(1_000L, AutoRestartPolicy.nextDelayMs(-3));
    }
}
