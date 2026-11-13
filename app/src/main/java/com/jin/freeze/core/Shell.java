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
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroy();
                return new Result(-1, sb.toString(), true);
            }
            return new Result(p.exitValue(), sb.toString().trim(), false);
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
