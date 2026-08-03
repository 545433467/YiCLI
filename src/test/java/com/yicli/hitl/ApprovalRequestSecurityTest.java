package com.yicli.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalRequestSecurityTest {

    @Test
    void stripsCsiAnsiSequences() {
        String sanitized = ApprovalRequest.sanitizeTerminalText("a\u001b[31mred\u001b[0m", true);

        assertEquals("ared", sanitized);
        assertFalse(sanitized.contains("\u001b"));
    }

    @Test
    void stripsOscSequencesUntilBellOrStringTerminator() {
        assertEquals("xok", ApprovalRequest.sanitizeTerminalText("x\u001b]0;title\u0007ok", true));
        assertEquals("xok", ApprovalRequest.sanitizeTerminalText("x\u001b]8;;https://evil\u001b\\ok", true));
    }

    @Test
    void dropsControlCharactersButKeepsNewlineWhenRequested() {
        assertEquals("ab\ncd", ApprovalRequest.sanitizeTerminalText("a\u0000b\nc\u0007d", true));
        assertEquals("ab cd", ApprovalRequest.sanitizeTerminalText("a\u0000b\nc\u0007d", false));
    }

    @Test
    void displayTextContainsNoRawEscapeBytesEvenFromMaliciousArgs() {
        String malicious = "{\"command\":\"echo \\u001b[2J\\u001b[31mRED\\u001b[0m && rm -rf x\"}";
        ApprovalRequest request = ApprovalRequest.of("execute_command", malicious, null);

        String display = request.toDisplayText();

        assertFalse(display.contains("\u001b"), "审批框不得输出任何原始 ESC 字节");
        assertTrue(display.contains("RED"), "可见文本应保留");
        assertTrue(display.contains("rm -rf x"), "命令本体应保留供用户判断");
    }
}
