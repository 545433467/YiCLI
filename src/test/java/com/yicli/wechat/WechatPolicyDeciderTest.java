package com.yicli.wechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatPolicyDeciderTest {
    @TempDir
    Path tempDir;

    @Test
    void deniesExecuteCommandByDefault() {
        WechatPolicyDecider decider = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));
        WechatPolicyDecision decision = decider.decide("execute_command", "{\"command\":\"git status\"}");
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("execute_command"));
    }

    @Test
    void allowsOnlyExactWhitelistedCommand() {
        WechatPolicyDecider decider = new WechatPolicyDecider(
                new WechatPolicyConfig(tempDir, List.of("git status"), List.of(), 10, 1000));
        assertTrue(decider.decide("execute_command", "{\"command\":\"git status\"}").allowed());
        assertFalse(decider.decide("execute_command", "{\"command\":\"git status && rm -rf src\"}").allowed());
    }

    @Test
    void deniesMcpByDefaultAndAllowsConfiguredServer() {
        WechatPolicyDecider denied = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));
        assertFalse(denied.decide("mcp__chrome-devtools__take_snapshot", "{}").allowed());

        WechatPolicyDecider allowed = new WechatPolicyDecider(
                new WechatPolicyConfig(tempDir, List.of(), List.of("chrome-devtools"), 10, 1000));
        assertTrue(allowed.decide("mcp__chrome-devtools__take_snapshot", "{}").allowed());
    }

    @Test
    void deniesToolsNotExplicitlyClassified() {
        WechatPolicyDecider decider = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));
        assertFalse(decider.decide("save_memory", "{\"fact\":\"secret\"}").allowed());
        assertFalse(decider.decide("browser_connect", "{}").allowed());
    }

    @Test
    void deniesWriteToSensitiveFile() {
        WechatPolicyDecider decider = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));

        assertFalse(decider.decide("write_file",
                "{\"path\":\".env\",\"content\":\"GLM_API_KEY=x\"}").allowed());
        assertFalse(decider.decide("write_file",
                "{\"path\":\"src/.git/config\",\"content\":\"x\"}").allowed());
        assertFalse(decider.decide("write_file",
                "{\"path\":\"keys/id_rsa\",\"content\":\"x\"}").allowed());
        assertTrue(decider.decide("write_file",
                "{\"path\":\"README.md\",\"content\":\"hello\"}").allowed());
    }

    @Test
    void deniesWriteContainingSecrets() {
        WechatPolicyDecider decider = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));

        assertFalse(decider.decide("write_file",
                "{\"path\":\"notes.txt\",\"content\":\"-----BEGIN RSA PRIVATE KEY-----\"}").allowed());
        assertFalse(decider.decide("write_file",
                "{\"path\":\"notes.txt\",\"content\":\"api key: sk-abcdefghijklmnopqrstuvwxyz\"}").allowed());
    }

    @Test
    void rateLimitsWritesPerMinute() {
        String old = System.getProperty("yicli.wechat.write.per.minute");
        System.setProperty("yicli.wechat.write.per.minute", "2");
        try {
            WechatPolicyDecider decider = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));
            String args = "{\"path\":\"a.txt\",\"content\":\"x\"}";

            assertTrue(decider.decide("write_file", args).allowed());
            assertTrue(decider.decide("write_file", args).allowed());
            assertFalse(decider.decide("write_file", args).allowed(), "超过每分钟限额应拒绝");
        } finally {
            if (old == null) {
                System.clearProperty("yicli.wechat.write.per.minute");
            } else {
                System.setProperty("yicli.wechat.write.per.minute", old);
            }
        }
    }
}
