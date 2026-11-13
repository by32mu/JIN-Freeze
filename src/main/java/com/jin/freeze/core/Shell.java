package com.jin.freeze.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 通过 su 执行命令的极简封装，无后台常驻。 */
public final class Shell {

    private Shell() {
    }

    public static final class Result {
        public final int code;
        public final String out;
        /** 是否因为超时被强制结束。 */
        public final boolean timeout;

        Result(int code, String out, boolean timeout) {
            this.code = code;
            this.out = out;
            this.timeout = timeout;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    public static Result exec(String cmd) {
        return exec(cmd, 20);
    }

    public static Result exec(String cmd, int timeoutSeconds) {
        return run(new String[]{"su", "-c", cmd}, timeoutSeconds);
    }

    /** 不经过 su，以本应用身份执行：用于 su 未授权时采集非 root 信号。 */
    public static Result execPlain(String cmd, int timeoutSeconds) {
        return run(new String[]{"sh", "-c", cmd}, timeoutSeconds);
    }

    private static Result run(String[] argv, int timeoutSeconds) {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            // 读流必须异步：su 等授权弹窗时不会 EOF，先读后等会永久卡住
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (sb) {
                            sb.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroy();
                String out;
                synchronized (sb) {
                    out = sb.toString();
                }
                return new Result(-1, out, true);
            }
            reader.join(500);
            String out;
            synchronized (sb) {
                out = sb.toString();
            }
            return new Result(p.exitValue(), out.trim(), false);
        } catch (Exception e) {
            if (p != null) {
                p.destroy();
            }
            return new Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    /** 命令是否可用（例如 magisk / ksud）。 */
    public static boolean commandExists(String name) {
        Result r = exec("command -v " + name + " >/dev/null 2>&1; echo $?", 10);
        return "0".equals(r.out.trim());
    }
}
