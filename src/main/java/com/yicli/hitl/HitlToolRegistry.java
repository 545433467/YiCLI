package com.yicli.hitl;

import com.yicli.browser.BrowserCheckResult;
import com.yicli.policy.AuditLog;
import com.yicli.policy.PermissionStore;
import com.yicli.tool.ToolOutput;
import com.yicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 *
 * HITL 拒绝 / 跳过路径会写一行 audit（approver=hitl），HITL 通过后由父类 ToolRegistry 写
 * allow / policy-deny / error，HITL 审批与策略拦截共用同一份 ~/.yicli/audit/ 文件。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;
    private volatile PermissionStore permissionStore;
    private final long approvalTimeoutSeconds;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        this(hitlHandler, approvalTimeoutSeconds());
    }

    public HitlToolRegistry(HitlHandler hitlHandler, long approvalTimeoutSeconds) {
        super();
        this.hitlHandler = hitlHandler;
        this.approvalTimeoutSeconds = Math.max(0, approvalTimeoutSeconds);
    }

    public void setPermissionStore(PermissionStore permissionStore) {
        this.permissionStore = permissionStore;
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        // HITL 未启用或该工具不需要审批，直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.doExecuteTool(name, argumentsJson);
        }
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return super.doExecuteTool(name, argumentsJson);
        }
        if (browserCheck.requiresPerCallApproval()) {
            return executeAfterExplicitApproval(name, argumentsJson, browserCheck.sensitiveNotice());
        }
        if (permissionStore != null && permissionStore.isDenied(name, argumentsJson)) {
            return ToolOutput.text("🛡️ 策略拒绝: 该操作此前已被用户拒绝并记住（/permission 可查看）");
        }
        if (permissionStore != null && permissionStore.isAllowed(name, argumentsJson)) {
            return super.doExecuteTool(name, argumentsJson);
        }
        String mcpServer = ApprovalPolicy.mcpServerName(name);
        if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
            return super.doExecuteTool(name, argumentsJson);
        }

        return executeAfterExplicitApproval(name, argumentsJson, null);
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice) {
        long start = System.nanoTime();
        String callerContext = currentCallerContext();
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, null, callerContext, sensitiveNotice);
        ApprovalResult result = requestApprovalWithTimeout(request, start);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, AuditLog.redactArgs(name, argumentsJson), reason, elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, AuditLog.redactArgs(name, argumentsJson), "用户跳过", elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被跳过");
        }

        // 批准（含修改参数）- 使用 effectiveArguments 获取最终参数；父类执行路径会负责 allow audit
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        if (permissionStore != null && result.isApproved()
                && !result.isApprovedAll() && !result.isApprovedAllForServer()) {
            permissionStore.remember(name, effectiveArgs, "allow");
        }
        return super.doExecuteTool(name, effectiveArgs);
    }

    /**
     * 审批超时兜底：配置 YICLI_HITL_TIMEOUT_SECONDS > 0 时，审批等待超过该时长
     * 自动按拒绝处理（fail-safe），避免终端失联 / 自动化场景永久挂起。
     */
    private ApprovalResult requestApprovalWithTimeout(ApprovalRequest request, long startNanos) {
        if (approvalTimeoutSeconds <= 0) {
            return hitlHandler.requestApproval(request);
        }
        try {
            return CompletableFuture.supplyAsync(() -> hitlHandler.requestApproval(request))
                    .get(approvalTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    request.toolName(),
                    AuditLog.redactArgs(request.toolName(), request.arguments()),
                    "审批超时（" + approvalTimeoutSeconds + " 秒）自动拒绝",
                    elapsedMillis(startNanos)));
            return ApprovalResult.reject("审批超时自动拒绝");
        } catch (Exception e) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    request.toolName(),
                    AuditLog.redactArgs(request.toolName(), request.arguments()),
                    "审批调用异常: " + e.getMessage(),
                    elapsedMillis(startNanos)));
            return ApprovalResult.reject("审批调用异常: " + e.getMessage());
        }
    }

    private static long approvalTimeoutSeconds() {
        String raw = System.getProperty("yicli.hitl.timeout.seconds");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("YICLI_HITL_TIMEOUT_SECONDS");
        }
        if (raw != null && !raw.isBlank()) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                // 回退默认值
            }
        }
        return 0L;
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
