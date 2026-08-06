package com.yicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-height transient activity area for model thinking.
 *
 * <p>克制的 Claude Code / Pi 风格：不渲染 spinner、进度条或按键提示，只显示一行
 * 低调的 {@code Thinking... / Working...} + 灰色竖线 reasoning 预览。内容更新或
 * 结束时只重写自己占用的几行，不用独立 JLine {@code Display.update(...)} 或
 * {@code CLEAR_TO_EOS} 向上覆盖 transcript。
 */
final class InlineActivityDisplay implements AutoCloseable {

    private static final int MAX_REASONING_CHARS = 4096;
    private static final int MAX_REASONING_ROWS = 4;
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.italic().faint();
    private static final AttributedStyle QUOTE_STYLE = AttributedStyle.DEFAULT.faint().italic();

    private final Terminal terminal;
    private final PrintStream renderLock;
    private final StringBuilder reasoning = new StringBuilder();
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    private int renderedRows;

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock) {
        this.terminal = terminal;
        this.renderLock = renderLock;
    }

    /** thinking 面板是否正在显示——给 InlineRenderer 决定是否在 status 更新时触发重绘。 */
    synchronized boolean isActive() {
        return active && !closed;
    }

    /** 当 renderer 状态变化时，如果 thinking 正在显示，则刷新 reasoning 预览。 */
    synchronized void refreshIfActive() {
        if (active && !closed) {
            renderLocked();
        }
    }

    synchronized void begin(String label) {
        if (closed) {
            return;
        }
        clearLocked();
        reasoning.setLength(0);
        this.label = (label == null || label.isBlank()) ? "Thinking" : label.trim();
        this.active = true;
        renderLocked();
    }

    synchronized void beginActivity(String label, String detail) {
        if (closed) {
            return;
        }
        clearLocked();
        reasoning.setLength(0);
        this.label = (label == null || label.isBlank()) ? "Working" : label.trim();
        this.active = true;
        if (detail != null && !detail.isBlank()) {
            reasoning.append(detail);
            trimReasoning();
        }
        renderLocked();
    }

    synchronized void appendThinking(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        if (!active) {
            this.label = "Thinking";
            this.active = true;
        }
        reasoning.append(delta);
        trimReasoning();
        renderLocked();
    }

    synchronized void end() {
        if (closed) {
            return;
        }
        active = false;
        reasoning.setLength(0);
        clearLocked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        reasoning.setLength(0);
        clearLocked();
    }

    private void renderLocked() {
        if (!active || closed) {
            return;
        }
        synchronized (renderLock) {
            PrintWriter writer = terminalWriter();
            clearRenderedArea(writer);
            List<AttributedString> lines = buildLines();
            for (int i = 0; i < lines.size(); i++) {
                writer.print(lines.get(i).toAnsi(terminal));
                writer.print(AnsiSeq.CLEAR_TO_EOL);
                if (i < lines.size() - 1) {
                    writer.print('\n');
                }
            }
            renderedRows = lines.size();
            writer.flush();
            terminal.flush();
        }
    }

    private void clearLocked() {
        synchronized (renderLock) {
            PrintWriter writer = terminalWriter();
            clearRenderedArea(writer);
            writer.flush();
            terminal.flush();
        }
    }

    private PrintWriter terminalWriter() {
        PrintWriter writer = terminal.writer();
        if (writer != null) {
            return writer;
        }
        return new PrintWriter(renderLock, true, StandardCharsets.UTF_8);
    }

    private void clearRenderedArea(PrintWriter writer) {
        if (renderedRows <= 0) {
            return;
        }
        if (renderedRows > 1) {
            writer.print(AnsiSeq.moveUp(renderedRows - 1));
        }
        writer.print('\r');
        for (int i = 0; i < renderedRows; i++) {
            writer.print(AnsiSeq.CLEAR_LINE);
            if (i < renderedRows - 1) {
                writer.print('\n');
            }
        }
        if (renderedRows > 1) {
            writer.print(AnsiSeq.moveUp(renderedRows - 1));
        }
        writer.print('\r');
        renderedRows = 0;
    }

    private List<AttributedString> buildLines() {
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        List<AttributedString> lines = new ArrayList<>();
        lines.add(fit("  " + label + "…", cols, STATUS_STYLE));

        List<String> quoteLines = reasoningLines();
        int quoteWidth = Math.max(12, cols - 4);
        int start = Math.max(0, quoteLines.size() - MAX_REASONING_ROWS);
        for (int i = start; i < quoteLines.size(); i++) {
            AttributedString quote = new AttributedString("│ " + quoteLines.get(i), QUOTE_STYLE);
            for (AttributedString part : quote.columnSplitLength(quoteWidth, true, true, terminal)) {
                lines.add(fit("  " + part.toString(), cols, QUOTE_STYLE));
                if (lines.size() > MAX_REASONING_ROWS + 1) {
                    return lines;
                }
            }
        }
        return lines;
    }

    private List<String> reasoningLines() {
        String content = reasoning.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (content.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\\R+")) {
            String normalized = line.replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private AttributedString fit(String text, int cols, AttributedStyle style) {
        AttributedString attributed = new AttributedString(text == null ? "" : text, style);
        if (attributed.columnLength(terminal) <= cols) {
            return attributed;
        }
        if (cols <= 3) {
            return new AttributedString(".".repeat(Math.max(0, cols)), style);
        }
        return new AttributedString(
                attributed.columnSubSequence(0, cols - 3, terminal).toString() + "...",
                style);
    }

    private void trimReasoning() {
        if (reasoning.length() <= MAX_REASONING_CHARS) {
            return;
        }
        reasoning.delete(0, reasoning.length() - MAX_REASONING_CHARS);
    }
}
