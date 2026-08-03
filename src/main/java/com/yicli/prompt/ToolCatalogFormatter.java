package com.yicli.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.yicli.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 ToolRegistry 的实际工具定义渲染成 system prompt 中的 "## Tools" 目录文本。
 * 相比 API 侧 tool schema，这里只保留名称、一句话描述和必填参数，控制 token 开销。
 */
public final class ToolCatalogFormatter {

    private static final int MAX_CATALOG_CHARS = 12_000;

    private ToolCatalogFormatter() {
    }

    public static String format(List<LlmClient.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (LlmClient.Tool tool : tools) {
            String line = formatTool(index++, tool);
            if (sb.length() + line.length() > MAX_CATALOG_CHARS) {
                sb.append("\n... 工具目录已截断（超出字符预算），其余工具请以 API tool schema 为准。");
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatTool(int index, LlmClient.Tool tool) {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(". `").append(tool.name()).append("`");
        String description = singleLine(tool.description());
        if (!description.isEmpty()) {
            sb.append(" - ").append(description);
        }
        List<String> required = requiredParams(tool.parameters());
        if (!required.isEmpty()) {
            sb.append("（必填参数: ").append(String.join(", ", required)).append("）");
        }
        return sb.toString();
    }

    private static List<String> requiredParams(JsonNode parameters) {
        List<String> required = new ArrayList<>();
        if (parameters == null || !parameters.isObject()) {
            return required;
        }
        JsonNode requiredNode = parameters.path("required");
        if (requiredNode.isArray()) {
            for (JsonNode item : requiredNode) {
                String name = item.asText("");
                if (!name.isBlank()) {
                    required.add(name);
                }
            }
        }
        return required;
    }

    private static String singleLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }
}
