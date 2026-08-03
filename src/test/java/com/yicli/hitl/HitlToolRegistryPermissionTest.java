package com.yicli.hitl;

import com.yicli.policy.PermissionStore;
import com.yicli.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlToolRegistryPermissionTest {

    @TempDir
    Path tempDir;

    @Test
    void rememberedAllowRuleSkipsApprovalPrompt() {
        FakeHitlHandler handler = new FakeHitlHandler(true);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(tempDir.toString());
        PermissionStore store = new PermissionStore(tempDir.resolve("permissions.json"));
        registry.setPermissionStore(store);
        String args = "{\"path\":\"hello.txt\",\"content\":\"hi\"}";
        store.remember("write_file", args, "allow");

        ToolOutput output = registry.executeToolOutput("write_file", args);

        assertTrue(output.text().contains("已写入") || output.text().contains("✅") || !output.text().isBlank());
        assertEquals(0, handler.approvalCalls.get(), "已记住的规则不应再弹审批");
        assertTrue(Files.exists(tempDir.resolve("hello.txt")));
    }

    @Test
    void approvedCallIsRememberedForNextTime() {
        FakeHitlHandler handler = new FakeHitlHandler(true);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(tempDir.toString());
        PermissionStore store = new PermissionStore(tempDir.resolve("permissions.json"));
        registry.setPermissionStore(store);
        String args = "{\"path\":\"hello.txt\",\"content\":\"hi\"}";

        registry.executeToolOutput("write_file", args);

        assertEquals(1, handler.approvalCalls.get());
        assertTrue(store.isAllowed("write_file", args), "批准后应记住规则");
    }

    @Test
    void rememberedDenyRuleBlocksWithoutPrompt() {
        FakeHitlHandler handler = new FakeHitlHandler(true);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(tempDir.toString());
        PermissionStore store = new PermissionStore(tempDir.resolve("permissions.json"));
        registry.setPermissionStore(store);
        String args = "{\"path\":\"hello.txt\",\"content\":\"hi\"}";
        store.remember("write_file", args, "deny");

        ToolOutput output = registry.executeToolOutput("write_file", args);

        assertTrue(output.text().contains("策略拒绝"));
        assertEquals(0, handler.approvalCalls.get());
        assertTrue(Files.notExists(tempDir.resolve("hello.txt")));
    }

    private static final class FakeHitlHandler implements HitlHandler {
        private boolean enabled;
        private final AtomicInteger approvalCalls = new AtomicInteger();

        private FakeHitlHandler(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            approvalCalls.incrementAndGet();
            return ApprovalResult.approve();
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
