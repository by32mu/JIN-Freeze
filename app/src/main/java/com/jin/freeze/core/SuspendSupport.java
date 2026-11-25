package com.jin.freeze.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
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

    private static Context appCtx;

    private SuspendSupport() {
    }

    /** 无 shell（root/Shizuku 都不可用）且已授权 Dhizuku 时，走 DPM 暂停通道。 */
    private static boolean dhizukuOnly() {
        return DhizukuGate.only(appCtx);
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
     * 通道选择与「之后怎么撤销」绑定：系统只允许 suspendingPackage 匹配的调用撤销暂停，
     * 所以 root 模式统一走 app_process CLI（suspender=root，和 pm 命令身份一致），
     * 只有没有 root 时才走 Shizuku 的 SuspendDialogInfo（suspender=com.android.shell）。
     */
    public static PrivShell.Result suspend(Context c, String pkg, String label) {
        if (DhizukuGate.only(c)) {
            return DhizukuGate.suspend(c, pkg, label);
        }
        if (PrivShell.mode() == PrivShell.Mode.ROOT && rootSuspend(c, pkg, label)) {
            return new PrivShell.Result(0, "", false);
        }
        if (SuspendDialog.suspend(c, pkg, label)) {
            // binder 转发没有 shell 输出，成败由调用方按 dumpsys 真值复核
            return new PrivShell.Result(0, "", false);
        }
        // 不静默降级：对话框通道失败必须留痕，便于区分「ROM 不支持」和「我们没传对」
        Log.w("JinPm", "dialog channel failed for " + pkg + ", fallback to pm suspend (message only)");
        return PrivShell.exec(suspendCommand(pkg, label), 20);
    }

    /**
     * root 通道：app_process 以 uid 0 调 setPackagesSuspendedAsUser 并带 SuspendDialogInfo。
     * 应用名可能带空格/中文，按 Base64(UTF-8) 传参，不经过 shell 引号。
     * suspender 用 CLI 默认值 root，和 pm 命令一致，之后 pm unsuspend 才能撤销。
     */
    private static boolean rootSuspend(Context c, String pkg, String label) {
        try {
            String b64 = Base64.encodeToString(
                    (label == null ? "" : label).getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            PrivShell.Result r = PrivShell.exec(
                    cliCommand(c, pkg, b64, null, null), 20);
            if (r.code == 0 && r.out != null && r.out.contains("JIN_SUSPEND_OK")) {
                return true;
            }
            Log.w("JinPm", "root suspend failed: code=" + r.code + " out=" + r.out);
            return false;
        } catch (Throwable t) {
            Log.w("JinPm", "root suspend error: " + t);
            return false;
        }
    }

    /**
     * pm 撤不掉时按候选 suspender 再撤一次：系统只允许 suspendingPackage 一致的调用撤销，
     * 所以逐个试 shell / 凚 / root（JinLite 服务以 shell 身份暂停、旧版本 CLI 以凚身份暂停）。
     */
    private static boolean rootUnsuspend(Context c, String pkg) {
        for (String caller : new String[]{"com.android.shell", "com.jin.freeze", "root"}) {
            PrivShell.Result r = PrivShell.exec(cliCommand(c, pkg, "", "u", caller), 20);
            if (r.code != 0 || r.out == null || !r.out.contains("JIN_SUSPEND_OK")) {
                continue;
            }
            if (!Boolean.TRUE.equals(suspendedOne(pkg))) {
                return true;
            }
        }
        return false;
    }

    /** app_process CLI 命令：CLASSPATH=<apk> app_process /system/bin <SuspendCli> pkg userId label [u] [caller]。 */
    private static String cliCommand(Context c, String pkg, String labelB64,
                                     String mode, String caller) {
        String apk = c.getApplicationInfo().sourceDir;
        StringBuilder cmd = new StringBuilder("CLASSPATH=").append(shellQuote(apk))
                .append(" app_process /system/bin com.jin.freeze.lite.SuspendCli ")
                .append(pkg).append(" 0 ")
                // 空正文也要占位，否则后面的 u / caller 参数会整体前移
                .append(labelB64 == null || labelB64.isEmpty() ? "-" : labelB64);
        if (mode != null) {
            cmd.append(' ').append(mode);
            if (caller != null) {
                cmd.append(' ').append(caller);
            }
        }
        return cmd.toString();
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

    /**
     * 撤销暂停：先走 pm（快），输出仍是「suspended state: true」说明暂停不是 pm 身份建的
     *（JinLite 服务的 shell 身份、旧版 root CLI 的凚身份），再按候选 suspender 用 CLI 撤一次。
     */
    public static PrivShell.Result unsuspend(String pkg) {
        if (dhizukuOnly()) {
            return DhizukuGate.unsuspend(appCtx, pkg);
        }
        PrivShell.Result r = PrivShell.exec("pm unsuspend --user 0 " + pkg, 20);
        if (r.out == null || !r.out.contains("new suspended state: true")) {
            return r;
        }
        if (clearForeignSuspension(pkg)) {
            Log.w("JinPm", "pm unsuspend 未生效，已用 CLI 撤销 " + pkg);
            return new PrivShell.Result(0, "", false);
        }
        return r;
    }

    /**
     * pm 撤不掉的暂停，按候选 suspender（shell / 凚 / root）逐个试到真值变 false。
     * 只有 root 模式可用（要起 app_process）；返回是否已撤销。
     */
    public static boolean clearForeignSuspension(String pkg) {
        if (appCtx == null || PrivShell.mode() != PrivShell.Mode.ROOT) {
            return false;
        }
        return rootUnsuspend(appCtx, pkg);
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
        if (dhizukuOnly()) {
            return DhizukuGate.isSuspended(appCtx, pkg);
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
        if (pkgs == null || pkgs.isEmpty()) {
            return new HashSet<>();
        }
        if (dhizukuOnly()) {
            return DhizukuGate.suspendedAmong(appCtx, pkgs);
        }
        String script = truthScript(pkgs);
        if (script == null) {
            return new HashSet<>();
        }
        PrivShell.Result r = PrivShell.exec(script, 30);
        if (r.out == null) {
            return new HashSet<>();
        }
        return parseTruth(r.out);
    }

    /**
     * 与 {@link #suspendedAmong} 同一份查询，但用哨兵区分「确实没有暂停」和「这次没读到」：
     * 返回 null 表示读失败，调用方不要拿空集合覆盖缓存；正常读到没有暂停项时返回空集合。
     */
    public static Set<String> suspendedTruth(Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) {
            return new HashSet<>();
        }
        if (dhizukuOnly()) {
            return DhizukuGate.suspendedAmong(appCtx, pkgs);
        }
        String script = truthScript(pkgs);
        if (script == null) {
            return new HashSet<>();
        }
        PrivShell.Result r = PrivShell.exec(script + "echo __JIN_DONE__", 30);
        if (r == null || r.timeout || r.out == null || !r.out.contains("__JIN_DONE__")) {
            return null;
        }
        return parseTruth(r.out);
    }

    /** 拼「echo 包名 + dumpsys 暂停字段」脚本；没有合法包名时返回 null。 */
    private static String truthScript(Collection<String> pkgs) {
        StringBuilder cmd = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || !p.matches("[A-Za-z0-9._]+")) {
                continue;
            }
            cmd.append("echo -n '").append(p).append(" '; ")
                    .append("dumpsys package ").append(p)
                    .append(" 2>/dev/null | grep -m1 -o 'suspended=[a-z]*' || true; ");
        }
        return cmd.length() == 0 ? null : cmd.toString();
    }

    /** 解析 truthScript 的输出，只收集 suspended=true 的包名。 */
    private static Set<String> parseTruth(String out) {
        Set<String> result = new HashSet<>();
        for (String line : out.split("\n")) {
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
        if (c != null && appCtx == null) {
            appCtx = c.getApplicationContext();
        }
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
