package com.jin.freeze.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 系统应用冻结的降级通道：
 * ROM 拒绝对系统应用执行 pm disable-user（Cannot disable miui core packages /
 * Cannot disable system packages）时，改用 pm suspend 让应用无法后台运行。
 *
 * 状态真值：暂停态读 dumpsys package &lt;包名&gt; 里的 suspended=；
 * 批量只在「本机暂停过」的缓存集合里做一次 shell 批量查询，
 * 不对全量应用逐包 dumpsys，避免拖慢列表刷新。
 */
public final class SuspendSupport {

    private static final String PREFS = "freeze_list";
    /** 本机通过降级通道暂停过的包，仅用于后续查真值与展示。 */
    private static final String KEY_SUSPENDED = "suspended_pkgs";

    private SuspendSupport() {
    }

    /** pm disable-user 的返回是否属于这两类 ROM 拦截。 */
    public static boolean romBlocked(String out) {
        String low = out == null ? "" : out.toLowerCase(Locale.ROOT);
        return low.contains("cannot disable miui core packages")
                || low.contains("cannot disable system packages");
    }

    /**
     * 暂停一个包，并尽量把系统暂停提示框换成凚自己的文案。
     *
     * 优先走 SuspendDialogInfo（Shizuku 转发 binder）：只有它能设标题和左下按钮；
     * 拿不到 Shizuku（纯 root / 未授权）时退回 pm suspend --dialogMessage，
     * 此时标题和按钮是系统固定文案，正文仍是凚的。
     */
    public static PrivShell.Result suspend(Context c, String pkg, String label) {
        if (SuspendDialog.suspend(c, pkg, label)) {
            // binder 转发没有 shell 输出，成败由调用方按 dumpsys 真值复核
            return new PrivShell.Result(0, "", false);
        }
        // 不静默降级：对话框通道失败必须留痕，便于区分「ROM 不支持」和「我们没传对」
        Log.w("JinPm", "dialog channel failed for " + pkg + ", fallback to pm suspend (message only)");
        return PrivShell.exec(suspendCommand(pkg, label), 20);
    }

    /**
     * pm suspend 的脚本形态：带上凚的正文，替换系统默认的「由 shell 所管理」。
     * 应用名可能含空格和中文标点，统一用单引号包住并转义单引号本身。
     */
    public static String suspendCommand(String pkg, String label) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "pm suspend --user 0 " + pkg;
        }
        return "pm suspend --user 0 --dialogMessage "
                + shellQuote(SuspendDialog.message(label)) + " " + pkg;
    }

    /** POSIX shell 单引号转义：' → '\''。 */
    private static String shellQuote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    public static PrivShell.Result unsuspend(String pkg) {
        return PrivShell.exec("pm unsuspend --user 0 " + pkg, 20);
    }

    public static Set<String> cached(Context c) {
        return new HashSet<>(prefs(c).getStringSet(KEY_SUSPENDED, Collections.emptySet()));
    }

    /** 记下本机暂停成功的包。 */
    public static void remember(Context c, Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) {
            return;
        }
        Set<String> now = new HashSet<>(cached(c));
        now.addAll(pkgs);
        prefs(c).edit().putStringSet(KEY_SUSPENDED, now).apply();
    }

    public static void forget(Context c, String pkg) {
        Set<String> now = new HashSet<>(cached(c));
        if (now.remove(pkg)) {
            prefs(c).edit().putStringSet(KEY_SUSPENDED, now).apply();
        }
    }

    /** 用真值覆盖缓存（列表刷新时把已经不再暂停的清掉）。 */
    public static void replace(Context c, Collection<String> verified) {
        Set<String> next = new HashSet<>(verified);
        if (!next.equals(cached(c))) {
            prefs(c).edit().putStringSet(KEY_SUSPENDED, next).apply();
        }
    }

    /**
     * 单个包的真值：TRUE=已暂停、FALSE=未暂停、null=没读到。
     * 只给单条操作/成败判定用；列表刷新走 {@link #suspendedAmong} 的批量口径。
     */
    public static Boolean suspendedOne(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) {
            return null;
        }
        PrivShell.Result r = PrivShell.exec("dumpsys package " + pkg
                + " 2>/dev/null | grep -m1 -o 'suspended=[a-z]*'", 20);
        if (r.timeout || r.out == null) {
            return null;
        }
        if (r.out.contains("suspended=true")) {
            return Boolean.TRUE;
        }
        if (r.out.contains("suspended=false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 批量读真值：一条 shell 循环里逐个 dumpsys package，输出「包名 suspended=true/false」。
     * 调用方只传缓存里的暂停集合（通常 0~几个），所以开销可控。
     */
    public static Set<String> suspendedAmong(Collection<String> pkgs) {
        Set<String> result = new HashSet<>();
        if (pkgs == null || pkgs.isEmpty()) {
            return result;
        }
        StringBuilder cmd = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || !p.matches("[A-Za-z0-9._]+")) {
                continue;
            }
            cmd.append("echo -n '").append(p).append(" '; ")
                    .append("dumpsys package ").append(p)
                    .append(" 2>/dev/null | grep -m1 -o 'suspended=[a-z]*' || true; ");
        }
        if (cmd.length() == 0) {
            return result;
        }
        PrivShell.Result r = PrivShell.exec(cmd.toString(), 30);
        if (r.out == null) {
            return result;
        }
        for (String line : r.out.split("\n")) {
            String t = line.trim();
            int i = t.lastIndexOf(' ');
            if (i <= 0) {
                continue;
            }
            if ("suspended=true".equals(t.substring(i + 1))) {
                result.add(t.substring(0, i));
            }
        }
        return result;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
