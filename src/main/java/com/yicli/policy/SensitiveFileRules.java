package com.yicli.policy;

import java.util.regex.Pattern;

/**
 * 敏感文件规则（P0-3/P0-4 共用）：非交互通道（微信 / 无头任务）拒绝写入
 * 密钥、凭据、配置文件与 git 内部对象，防止远程输入改写危险路径。
 */
public final class SensitiveFileRules {

    private static final Pattern SENSITIVE_PATH = Pattern.compile(
            "(?i)((^|/)\\.env(\\.|$)|(^|/)\\.git/|id_rsa|authorized_keys|\\.pem$|\\.crt$|\\.p12$|\\.pfx$"
                    + "|\\.key$|(^|/)ssh/config|(^|/)aws/credentials|(^|/)gcloud/credentials)");
    private static final Pattern SECRET_CONTENT = Pattern.compile(
            "(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}"
                    + "|Bearer\\s+[A-Za-z0-9._~+/=-]{20,})");

    private SensitiveFileRules() {
    }

    public static boolean isSensitivePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return SENSITIVE_PATH.matcher(path.replace('\\', '/')).find();
    }

    public static boolean containsSecret(String content) {
        return content != null && SECRET_CONTENT.matcher(content).find();
    }
}
