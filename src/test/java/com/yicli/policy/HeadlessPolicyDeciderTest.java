package com.yicli.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessPolicyDeciderTest {

    @Test
    void allowsReadOnlyToolsByDefault() {
        HeadlessPolicyDecider decider = new HeadlessPolicyDecider();

        assertTrue(decider.decide("read_file", "{\"path\":\"a.txt\"}").allowed());
        assertTrue(decider.decide("grep_code", "{\"pattern\":\"x\"}").allowed());
    }

    @Test
    void deniesWriteToSensitiveFilesAndSecrets() {
        HeadlessPolicyDecider decider = new HeadlessPolicyDecider();

        assertFalse(decider.decide("write_file", "{\"path\":\".env\",\"content\":\"x\"}").allowed());
        assertFalse(decider.decide("write_file",
                "{\"path\":\"config.txt\",\"content\":\"-----BEGIN PRIVATE KEY-----\"}").allowed());
        assertTrue(decider.decide("write_file", "{\"path\":\"notes.md\",\"content\":\"hello\"}").allowed());
    }

    @Test
    void deniesExecuteCommandWithoutAllowlist() {
        HeadlessPolicyDecider decider = new HeadlessPolicyDecider();

        assertFalse(decider.decide("execute_command", "{\"command\":\"git status\"}").allowed());
    }

    @Test
    void allowsWhitelistedCommandAndMcpServer() {
        String oldCmd = System.getProperty("yicli.headless.command.allowlist");
        String oldMcp = System.getProperty("yicli.headless.mcp.allowlist");
        System.setProperty("yicli.headless.command.allowlist", "git status, mvn test");
        System.setProperty("yicli.headless.mcp.allowlist", "chrome-devtools");
        try {
            HeadlessPolicyDecider decider = new HeadlessPolicyDecider();

            assertTrue(decider.decide("execute_command", "{\"command\":\"mvn test\"}").allowed());
            assertFalse(decider.decide("execute_command", "{\"command\":\"mvn test -DskipTests=false\"}").allowed(),
                    "白名单必须精确匹配");
            assertTrue(decider.decide("mcp__chrome-devtools__take_snapshot", "{}").allowed());
            assertFalse(decider.decide("mcp__filesystem__read_file", "{}").allowed());
        } finally {
            restore(oldCmd, "yicli.headless.command.allowlist");
            restore(oldMcp, "yicli.headless.mcp.allowlist");
        }
    }

    @Test
    void deniesRevertAndBrowserSessionSwitching() {
        HeadlessPolicyDecider decider = new HeadlessPolicyDecider();

        assertFalse(decider.decide("revert_turn", "{}").allowed());
        assertFalse(decider.decide("browser_connect", "{}").allowed());
    }

    private static void restore(String old, String key) {
        if (old == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, old);
        }
    }
}
