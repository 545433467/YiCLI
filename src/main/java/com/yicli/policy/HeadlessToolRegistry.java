package com.yicli.policy;

import com.yicli.tool.ToolOutput;
import com.yicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

/**
 * 无头模式工具注册表（P0-4）：Runtime API / 后台任务没有人工审批面板，
 * 所有工具调用先过 {@link HeadlessPolicyDecider}，拒绝即审计并返回策略提示。
 */
public class HeadlessToolRegistry extends ToolRegistry {

    private final HeadlessPolicyDecider decider;

    public HeadlessToolRegistry(HeadlessPolicyDecider decider) {
        this.decider = decider;
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        long start = System.nanoTime();
        HeadlessPolicyDecider.Decision decision = decider == null
                ? HeadlessPolicyDecider.Decision.allow()
                : decider.decide(name, argumentsJson);
        if (!decision.allowed()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(
                    name,
                    AuditLog.redactArgs(name, argumentsJson),
                    decision.reason(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            return ToolOutput.text("无头模式策略拒绝: " + decision.reason());
        }
        return super.doExecuteTool(name, argumentsJson);
    }
}
