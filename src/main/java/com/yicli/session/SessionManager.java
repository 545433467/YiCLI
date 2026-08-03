package com.yicli.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yicli.llm.LlmClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 会话持久化与恢复（P1-1）。
 *
 * <p>与 {@code ConversationSnapshot}（TUI 专用、只存 role/content）不同，
 * 这里按行 JSON 序列化完整的 {@link LlmClient.Message}（含 tool_calls、
 * reasoning_content、contentParts），保证 Agent 能忠实恢复历史继续跑。
 *
 * <p>文件格式：每个会话一个 <id>.jsonl，首行是 {@link SessionHeader}，
 * 之后每行一条 {@link LlmClient.Message}。
 */
public class SessionManager {

    private static final Path DEFAULT_DIR = Path.of(System.getProperty("user.home"), ".yicli", "sessions");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;

    public SessionManager() {
        this(DEFAULT_DIR);
    }

    public SessionManager(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionHeader(String sessionId, String title, long createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionInfo(String sessionId, String title, long createdAt, long lastActiveAt, int messageCount) {
    }

    public String save(List<LlmClient.Message> history, String title) throws IOException {
        Files.createDirectories(dir);
        String sessionId = "session_" + System.currentTimeMillis();
        Path file = dir.resolve(sessionId + ".jsonl");
        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.length() > 60) {
            safeTitle = safeTitle.substring(0, 60);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            writer.write(MAPPER.writeValueAsString(
                    new SessionHeader(sessionId, safeTitle, System.currentTimeMillis())));
            writer.newLine();
            if (history != null) {
                for (LlmClient.Message msg : history) {
                    if (msg == null) {
                        continue;
                    }
                    writer.write(MAPPER.writeValueAsString(msg));
                    writer.newLine();
                }
            }
        }
        return sessionId;
    }

    public List<LlmClient.Message> load(String sessionId) throws IOException {
        Path file = fileOf(sessionId);
        if (!Files.exists(file)) {
            throw new IOException("会话不存在: " + sessionId);
        }
        List<LlmClient.Message> history = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return history;
            }
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                LlmClient.Message msg = MAPPER.readValue(line, LlmClient.Message.class);
                if (msg != null) {
                    history.add(msg);
                }
            }
        }
        return history;
    }

    public List<SessionInfo> list() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SessionInfo> sessions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path path : stream) {
                SessionInfo info = readInfo(path);
                if (info != null) {
                    sessions.add(info);
                }
            }
        }
        sessions.sort(Comparator.comparingLong(SessionInfo::lastActiveAt).reversed());
        return sessions;
    }

    public boolean delete(String sessionId) throws IOException {
        return Files.deleteIfExists(fileOf(sessionId));
    }

    /** 以文本形式列出会话，供 /sessions 命令直接输出。 */
    public String listSessionsText() {
        try {
            List<SessionInfo> sessions = list();
            if (sessions.isEmpty()) {
                return "📭 还没有保存的历史会话\n";
            }
            StringBuilder sb = new StringBuilder("💾 历史会话（/resume <sessionId> 恢复）：\n");
            for (SessionInfo session : sessions) {
                sb.append("  ").append(session.sessionId())
                        .append("  ").append(session.messageCount()).append(" 条")
                        .append("  ").append(session.title()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ 读取会话列表失败: " + e.getMessage() + "\n";
        }
    }

    private SessionInfo readInfo(Path path) {
        String sessionId = path.getFileName().toString().replace(".jsonl", "");
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String firstLine = reader.readLine();
            String title = "会话 " + sessionId;
            long createdAt = 0L;
            int messageCount = 0;
            if (firstLine != null && !firstLine.isBlank()) {
                try {
                    SessionHeader header = MAPPER.readValue(firstLine, SessionHeader.class);
                    title = header.title() == null || header.title().isBlank() ? title : header.title();
                    createdAt = header.createdAt();
                } catch (IOException ignored) {
                    // 首行不是 header（旧格式），按整文件消息数估算
                    messageCount++;
                }
            }
            while (reader.readLine() != null) {
                messageCount++;
            }
            long lastActive = createdAt > 0 ? createdAt : Files.getLastModifiedTime(path).toMillis();
            return new SessionInfo(sessionId, title, createdAt, lastActive, messageCount);
        } catch (IOException e) {
            return null;
        }
    }

    private Path fileOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("..")) {
            throw new IllegalArgumentException("非法的会话 ID: " + sessionId);
        }
        return dir.resolve(sessionId + ".jsonl");
    }
}
