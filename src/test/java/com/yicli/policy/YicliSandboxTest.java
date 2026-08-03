package com.yicli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YicliSandboxTest {

    @Test
    void disabledByDefaultAndFallsBackToLocal() {
        YicliSandbox sandbox = new YicliSandbox("off", "some-image");

        assertFalse(sandbox.enabled());
        assertEquals("off", sandbox.mode());
    }

    @Test
    void dockerModeEnabledWithConfiguredImage() {
        YicliSandbox sandbox = new YicliSandbox("docker", "eclipse-temurin:17-jdk");

        assertTrue(sandbox.enabled());
        assertEquals("eclipse-temurin:17-jdk", sandbox.image());
    }

    @Test
    void buildsDockerRunCommandWithProjectMount() {
        List<String> args = YicliSandbox.dockerCommand(
                "E:/my-project", "eclipse-temurin:17-jdk", "mvn -q test");

        assertEquals(List.of(
                "docker", "run", "--rm",
                "-v", "E:/my-project:/workspace",
                "-w", "/workspace",
                "eclipse-temurin:17-jdk",
                "sh", "-c", "mvn -q test"
        ), args);
    }

    @Test
    void normalizesModeCaseAndBlankImage() {
        YicliSandbox sandbox = new YicliSandbox("  DOCKER  ", "  ");

        assertTrue(sandbox.enabled());
        assertEquals("eclipse-temurin:17-jdk", sandbox.image());
    }
}
