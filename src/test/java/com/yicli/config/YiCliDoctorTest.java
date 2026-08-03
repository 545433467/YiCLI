package com.yicli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class YiCliDoctorTest {

    @Test
    void reportContainsCoreDiagnostics() {
        String report = YiCliDoctor.report();

        assertTrue(report.contains("YiCLI Doctor"));
        assertTrue(report.contains("Java:"));
        assertTrue(report.contains("ripgrep:"));
        assertTrue(report.contains("API Key:"));
        assertTrue(report.contains("渲染器:"));
    }
}
