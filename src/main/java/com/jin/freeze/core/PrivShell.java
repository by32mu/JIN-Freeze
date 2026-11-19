package com.jin.freeze.core;

import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

/**
 * 需要特权执行的命令统一入口：root 优先，没有 root 时走 Shizuku。
 * 两个通道都不可用时返回明确提示，不再静默失败。
 *
 * 判定口径：
 * - 只缓存「已通过」的结果；失败结果带 2.5s TTL，过期后下次调用会重新探测，
 *   避免授权管理器的弹窗还没点/刚拒绝一次就把整个进程永久钉死在「未授权」。
 * - 每次探测都记下最后的 Result，UI 可区分「超时」「被拒绝」「su 不存在」。
 */
public final class PrivShell {

    public enum Mode {
        ROOT, SHIZUKU, NONE
    }

    public static final class Result {
        public final int code;
        public final String out;
        public final boolean timeout;

        Result(int code, String out, boolean timeout) {
            this.code = code;
            this.out = out;
            this.timeout = timeout;
        }
    }

    private static final Object LOCK = new Object();
    private static final long NONE_TTL_MS = 2500;
    private static volatile Mode cached;
    private static volatile long failedAt;
    private static volatile Result last;
    private static volatile boolean detecting;

    private PrivShell() {
    }

    /** 已探测到的通道；未探测过返回 null（UI 用，不触发阻塞探测）。 */
    public static Mode peek() {
        return cached;
    }

    /** 清掉缓存，下一次 mode() 会真正重新探测（授权完成后调用）。 */
    public static void reset() {
        synchronized (LOCK) {
            cached = null;
            failedAt = 0;
        }
    }

    public static boolean detecting() {
        return detecting;
    }

    /** 最近一次探测的原始结果，用于区分超时 / 拒绝。 */
    public static Result last() {
        return last;
    }

    /** 失败原因的人话说明。 */
    public static String failureHint() {
        Result r = last;
        if (r == null) {
            return "尚未检测";
        }
        if (r.timeout) {
            return "su 响应超时（授权弹窗可能还没确认）";
        }
        if (r.out == null || r.out.isEmpty()) {
            return "su 被拒绝或不可用";
        }
        return r.out;
    }

    /** 在 IO 线程调用：首次会尝试 su，失败再看 Shizuku。 */
    public static Mode mode() {
        return mode(false);
    }

    /** force=true：忽略失败缓存立即重测；已通过的结果不会重复探测。 */
    public static Mode mode(boolean force) {
        Mode m = cached;
        if (m == Mode.ROOT || m == Mode.SHIZUKU) {
            return m;
        }
        synchronized (LOCK) {
            m = cached;
            if (m == Mode.ROOT || m == Mode.SHIZUKU) {
                return m;
            }
            if (!force && m == Mode.NONE
                    && SystemClock.elapsedRealtime() - failedAt < NONE_TTL_MS) {
                return m;
            }
            detecting = true;
            try {
                Result r = runLocal(new String[]{"su", "-c", "id"}, 8);
                last = r;
                if (r.out != null && r.out.contains("uid=0")) {
                    cached = Mode.ROOT;
                } else if (shizukuGranted()) {
                    cached = Mode.SHIZUKU;
                } else {
                    cached = Mode.NONE;
                    failedAt = SystemClock.elapsedRealtime();
                }
            } finally {
                detecting = false;
            }
            return cached;
        }
    }

    public static boolean available() {
        Mode m = mode();
        return m != Mode.NONE;
    }

    public static String label() {
        Mode m = cached;
        if (m == null) {
            return "检测中";
        }
        switch (m) {
            case ROOT:
                return "root";
            case SHIZUKU:
                return "Shizuku";
            default:
                return "未激活";
        }
    }

    public static Result exec(String cmd, int timeoutSeconds) {
        Mode m = mode();
        if (m == Mode.ROOT) {
            return runLocal(new String[]{"su", "-c", cmd}, timeoutSeconds);
        }
        if (m == Mode.SHIZUKU) {
            return runShizuku(cmd, timeoutSeconds);
        }
        return new Result(-1, "未获得 root 或 Shizuku 权限", false);
    }

    private static boolean shizukuGranted() {
        try {
            // 必须以「用户在 Shizuku 弹窗里点过允许」为准：
            // 部分 ROM 把 API_V23 定为安装即授予，checkSelfPermission() 甚至服务端执行都会放行，
            // 但 Shizuku 的「应用管理」并没登记本应用，会出现「显示已授权却还要授权」的矛盾。
            if (!ShizukuGate.authorized()) {
                return false;
            }
            if (!Shizuku.pingBinder()) {
                return false;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            // 再真跑一条 id，确认服务端确实放行
            Result r = runShizuku("id", 4);
            return r.code == 0 && r.out != null && r.out.contains("uid=");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 读流放到独立线程，主线程只等 waitFor 的超时：
     * su 在等授权弹窗时 stdin/stdout 都不会 EOF，先读后等会导致永久卡死。
     */
    private static Result runLocal(String[] argv, int timeoutSeconds) {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            final AtomicReference<Throwable> readError = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (sb) {
                            sb.append(line).append('\n');
                        }
                    }
                } catch (Throwable e) {
                    readError.set(e);
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
            if (readError.get() != null && sb.length() == 0) {
                return new Result(-1, readError.get().getClass().getSimpleName(), false);
            }
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

    /**
     * 等进程退出。不能用 Process#waitFor(long, TimeUnit)：Shizuku 的远程进程把该方法
     * 的服务端异常映射成了 IllegalArgumentException（进程还活着时 exitValue 抛的
     * IllegalThreadStateException 经 Binder 变成 IAE），父类默认实现的 catch 抓不到，
     * 会直接冒泡成「执行失败」，但命令其实已经成功。
     * 这里自己轮询 exitValue()，IAE 与 ITSE 都当成「还没退出」。
     */
    private static boolean awaitExit(Process p, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalArgumentException stillRunning) {
                // IllegalThreadStateException 是它的子类，统一按「进程还活着」处理
            } catch (Throwable t) {
                return false;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false;
            }
            SystemClock.sleep(50);
        }
    }

    private static Result runShizuku(String cmd, int timeoutSeconds) {
        Process p = null;
        try {
            p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            final Process proc = p;
            final StringBuilder err = new StringBuilder();
            Thread errReader = new Thread(() -> {
                try (InputStream es = proc.getErrorStream()) {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(es, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        err.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                }
            });
            errReader.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!awaitExit(p, timeoutSeconds * 1000L)) {
                p.destroy();
                return new Result(-1, sb.toString(), true);
            }
            errReader.join(500);
            if (err.length() > 0) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(err);
            }
            return new Result(p.exitValue(), sb.toString().trim(), false);
        } catch (Throwable e) {
            if (p != null) {
                p.destroy();
            }
            return new Result(-1, "Shizuku 执行失败：" + e.getClass().getSimpleName(), false);
        }
    }
}
