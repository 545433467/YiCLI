package com.yicli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YiCliEnvTest {

    @Test
    void fallsBackToDefaultWhenNothingConfigured() {
        String old = System.getProperty(YiCliEnv.RENDERER.systemProperty());
        System.clearProperty(YiCliEnv.RENDERER.systemProperty());
        try {
            assertEquals("inline", YiCliEnv.get(YiCliEnv.RENDERER));
        } finally {
            if (old != null) {
                System.setProperty(YiCliEnv.RENDERER.systemProperty(), old);
            }
        }
    }

    @Test
    void systemPropertyTakesPrecedence() {
        String old = System.getProperty(YiCliEnv.RENDERER.systemProperty());
        System.setProperty(YiCliEnv.RENDERER.systemProperty(), "plain");
        try {
            assertEquals("plain", YiCliEnv.get(YiCliEnv.RENDERER));
        } finally {
            if (old == null) {
                System.clearProperty(YiCliEnv.RENDERER.systemProperty());
            } else {
                System.setProperty(YiCliEnv.RENDERER.systemProperty(), old);
            }
        }
    }

    @Test
    void parsesBooleanAndIntWithFallback() {
        String old = System.getProperty(YiCliEnv.LSP_ENABLED.systemProperty());
        System.setProperty(YiCliEnv.LSP_ENABLED.systemProperty(), "false");
        try {
            assertFalse(YiCliEnv.getBool(YiCliEnv.LSP_ENABLED));
        } finally {
            if (old == null) {
                System.clearProperty(YiCliEnv.LSP_ENABLED.systemProperty());
            } else {
                System.setProperty(YiCliEnv.LSP_ENABLED.systemProperty(), old);
            }
        }

        assertEquals(20, YiCliEnv.getInt(YiCliEnv.LSP_MAX_DIAGNOSTICS, 20));
        assertTrue(YiCliEnv.getInt(YiCliEnv.LSP_MAX_DIAGNOSTICS, 20) > 0);
    }
}
