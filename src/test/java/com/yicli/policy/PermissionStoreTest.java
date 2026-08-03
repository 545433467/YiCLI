package com.yicli.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void remembersExactNormalizedMatchAcrossInstances() {
        Path file = tempDir.resolve("permissions.json");
        PermissionStore store = new PermissionStore(file);

        store.remember("execute_command", "{\"command\":\"mvn  test\"}", "allow");

        // 空白归一化：不同空格数视为同一条规则
        assertTrue(store.isAllowed("execute_command", "{\"command\":\"mvn test\"}"));
        assertFalse(store.isAllowed("execute_command", "{\"command\":\"mvn clean test\"}"),
                "精确匹配，不允许子串放行");

        // 新实例（模拟重启）仍能读到持久化规则
        PermissionStore reloaded = new PermissionStore(file);
        assertTrue(reloaded.isAllowed("execute_command", "{\"command\":\"mvn test\"}"));
        assertEquals(1, reloaded.list().size());
    }

    @Test
    void incrementsHitCountOnRepeatedMatch() {
        PermissionStore store = new PermissionStore(tempDir.resolve("permissions.json"));

        store.remember("write_file", "{\"path\":\"a.txt\"}", "allow");
        store.remember("write_file", "{\"path\":\"a.txt\"}", "allow");

        assertEquals(2, store.list().get(0).hitCount());
    }

    @Test
    void supportsDenyAndClearAndRemove() {
        PermissionStore store = new PermissionStore(tempDir.resolve("permissions.json"));
        store.remember("execute_command", "{\"command\":\"rm -rf x\"}", "deny");
        store.remember("write_file", "{\"path\":\"a.txt\"}", "allow");

        assertTrue(store.isDenied("execute_command", "{\"command\":\"rm -rf x\"}"));
        assertFalse(store.isAllowed("execute_command", "{\"command\":\"rm -rf x\"}"));

        assertTrue(store.remove("execute_command", "{\"command\":\"rm -rf x\"}"));
        assertFalse(store.isDenied("execute_command", "{\"command\":\"rm -rf x\"}"));

        store.clear();
        assertEquals(0, store.list().size());
    }
}
