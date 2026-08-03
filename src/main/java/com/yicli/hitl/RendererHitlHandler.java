package com.yicli.hitl;

import com.yicli.render.Renderer;

import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 与 {@link Renderer} 协作的 HITL 处理器：
 * 状态（启用开关、全部放行集合）由本类维护，
 * 实际审批 UI 委托给 {@link Renderer#promptApproval(ApprovalRequest)}。
 *
 * <p>这样切换渲染器形态（plain / inline / lanterna）只需要换一个 Renderer 实例，
 * 不影响审批状态语义。
 */
public final class RendererHitlHandler implements HitlHandler {

    private final Renderer renderer;
    private volatile boolean enabled;
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> approveAllRemaining = new ConcurrentHashMap<>();

    public RendererHitlHandler(Renderer renderer, boolean enabled) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();

        if (!sensitivePerCall && isApprovedAllByTool(request.toolName())) {
            if (consumeApproveAll(request.toolName())) {
                renderer.stream().println("  [HITL] " + request.toolName()
                        + " 已全部放行（剩余 " + remaining(request.toolName()) + " 次），自动通过");
                return ApprovalResult.approveAll();
            } else {
                renderer.stream().println("  [HITL] " + request.toolName()
                        + " 的全部放行额度已用完，后续操作将重新审批");
            }
        }
        if (!sensitivePerCall && isApprovedAllByServer(mcpServer)) {
            if (consumeApproveAll(mcpServer)) {
                renderer.stream().println("  [HITL] MCP server " + mcpServer
                        + " 已全部放行（剩余 " + remaining(mcpServer) + " 次），自动通过");
                return ApprovalResult.approveAllByServer();
            } else {
                renderer.stream().println("  [HITL] MCP server " + mcpServer
                        + " 的全部放行额度已用完，后续操作将重新审批");
            }
        }

        ApprovalResult result = renderer.promptApproval(request);
        if (result == null) {
            return ApprovalResult.reject("渲染器返回 null");
        }
        if (result.isApprovedAllForTool()) {
            approvedAllByTool.add(request.toolName());
            approveAllRemaining.put(request.toolName(), TerminalHitlHandler.approveAllLimit());
        } else if (result.isApprovedAllForServer() && mcpServer != null) {
            approvedAllByServer.add(mcpServer);
            approveAllRemaining.put(mcpServer, TerminalHitlHandler.approveAllLimit());
        }
        return result;
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllByServer.contains(serverName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
        approvedAllByServer.clear();
        approveAllRemaining.clear();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null) {
            approvedAllByServer.remove(serverName);
            approveAllRemaining.remove(serverName);
        }
    }

    private int remaining(String key) {
        Integer left = approveAllRemaining.get(key);
        return left == null ? TerminalHitlHandler.approveAllLimit() : left;
    }

    private boolean consumeApproveAll(String key) {
        Integer left = approveAllRemaining.get(key);
        if (left == null) {
            return true;
        }
        if (left <= 1) {
            approveAllRemaining.remove(key);
            approvedAllByTool.remove(key);
            approvedAllByServer.remove(key);
            return false;
        }
        approveAllRemaining.put(key, left - 1);
        return true;
    }
}
