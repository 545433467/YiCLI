package com.yicli.policy;

import com.yicli.config.YiCliEnv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 容器沙箱执行器（P2-1）：把 execute_command 放到 Docker 容器里跑，
 * 以只读挂载项目目录到 /workspace，隔离对宿主机的副作用。
 *
 * <p>默认关闭（off）。开启方式：{@code YICLI_SANDBOX_MODE=docker}。
 * Docker 不可用时由调用方回退本地执行，沙箱永不静默阻断工作流。
 */
public final class YicliSandbox {

    public static final String MODE_OFF = "off";
    public static final String MODE_DOCKER = "docker";

    private static final int MAX_OUTPUT_CHARS = 8_000;

    private final String mode;
    private final String image;

    public YicliSandbox(String mode, String image) {
        this.mode = mode == null || mode.isBlank() ? MODE_OFF : mode.trim().toLowerCase();
        this.image = image == null || image.isBlank() ? "eclipse-temurin:17-jdk" : image.trim();
    }

    public static YicliSandbox fromEnv() {
        return new YicliSandbox(
                YiCliEnv.get(YiCliEnv.SANDBOX_MODE),
                YiCliEnv.get(YiCliEnv.SANDBOX_IMAGE));
    }

    public boolean enabled() {
        return MODE_DOCKER.equals(mode);
    }

    public String mode() {
        return mode;
    }

    public String image() {
        return image;
    }

    /**
     * 在容器中执行命令，返回带 exit code 的输出；进程启动失败返回错误文本。
     */
    public String run(String projectPath, String command, long timeoutSeconds) {
        List<String> args = dockerCommand(projectPath, image, command);
        ExecutorService outputReader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "yicli-sandbox-output");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            process = pb.start();
            Process running = process;
            Future<String> outputFuture = outputReader.submit(() -> readOutput(running));
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "沙箱命令执行超时（" + timeoutSeconds + "秒），已强制终止";
            }
            String output = outputFuture.get(2, TimeUnit.SECONDS);
            return "沙箱命令执行完成 (exit code: " + process.exitValue() + ", image: " + image + ")\n" + output;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "沙箱执行失败: " + e.getMessage();
        } finally {
            outputReader.shutdownNow();
        }
    }

    /** 构造 docker run 参数，独立成方法便于单测。 */
    static List<String> dockerCommand(String projectPath, String image, String command) {
        return List.of(
                "docker", "run", "--rm",
                "-v", projectPath + ":/workspace",
                "-w", "/workspace",
                image,
                "sh", "-c", command
        );
    }

    private static String readOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_OUTPUT_CHARS) {
                    output.append(line).append("\n");
                }
            }
        }
        if (output.length() >= MAX_OUTPUT_CHARS) {
            output.append("...(输出已截断)");
        }
        return output.toString();
    }
}
