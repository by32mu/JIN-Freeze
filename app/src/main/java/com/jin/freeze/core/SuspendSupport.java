package com.jin.freeze.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** 旧版 suspender=root 的暂停记录已经重建过（一次性迁移标记，v2 兼容旧标记）。 */
    private static final String KEY_SUSPENDER_REPAIRED = "suspender_shell_repaired_v2";
    /** 迁移尝试次数：失败也不无限重试，最多 3 次。 */
    private static final String KEY_SUSPENDER_TRIES = "suspender_shell_tries";
    private static volatile boolean repairing;

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
     * 所以 root 模式统一走 app_process CLI（suspender=com.android.shell，与 pm 命令身份一致），
     * 只有没有 root 时才走 Shizuku 的 SuspendDialogInfo（suspender 同样是 com.android.shell）。
     * suspender 绝不能写 "root"：系统的暂停弹窗会拿它当包名取资源与上报交互，
     * 不存在的包名会让系统界面进程崩溃（Package root does not exist!）。
     */
    public static PrivShell.Result suspend(Context c, String pkg, String label) {
        if (DhizukuGate.only(c)) {
            return DhizukuGate.suspend(c, pkg, label);
        }
        if (PrivShell.rootLike() && rootSuspend(c, pkg, label)) {
            return new PrivShell.Result(0, "", false);
        }
        if (SuspendDialog.suspend(c, pkg, label)) {
            // binder 转发没有 shell 输出，成败由调用方按 dumpsys 真值复核
            return new PrivShell.Result(0, "", false);
        }
        if (PrivShell.rootLike()) {
            // uid 0 下 pm suspend 的身份是 root，PMS 会把 suspendingPackage 记成不存在的 "root"，
            // 用户点灰色图标时系统界面取不到它的资源直接崩溃（Package root does not exist!）。
            // 宁可这次失败，也绝不降级到 pm。
            Log.w("JinPm", "root suspend failed and no dialog channel: " + pkg);
            return new PrivShell.Result(-1, "suspend failed", false);
        }
        // 不静默降级：对话框通道失败必须留痕，便于区分「ROM 不支持」和「我们没传对」
        Log.w("JinPm", "dialog channel failed for " + pkg + ", fallback to pm suspend (message only)");
        return PrivShell.exec(suspendCommand(pkg, label), 20);
    }

    /**
     * root 通道：app_process 以 uid 0 调 setPackagesSuspendedAsUser 并带 SuspendDialogInfo。
     * 应用名可能带空格/中文，按 Base64(UTF-8) 传参，不经过 shell 引号。
     * suspender 用 CLI 默认值 com.android.shell，和 pm 命令身份一致，之后 pm unsuspend 能撤销。
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
     * root 批量暂停/撤销：一条 su 命令起一个 app_process 进程处理整份名单。
     * 列表很长时逐个起进程太慢（每个要半秒以上），会表现成「转不过去」；
     * 非 root 返回 null，调用方按原来的逐个通道（Shizuku/Dhizuku）处理。
     */
    public static Map<String, Boolean> cliBatch(Context c, Collection<String> pkgs,
                                                Map<String, String> labels, boolean suspended) {
        return cliBatch(c, pkgs, labels, suspended, 0);
    }

    /** 同上，指定用户：分身实例的暂停同样由 com.android.shell 建立，pm 撤不掉。 */
    public static Map<String, Boolean> cliBatch(Context c, Collection<String> pkgs,
                                                Map<String, String> labels, boolean suspended,
                                                int userId) {
        return cliBatch(c, pkgs, labels, suspended, userId, null);
    }

    /** 同上，额外指定 suspender 身份（null=com.android.shell）；调 CLI 的 uid 必须是 0。 */
    public static Map<String, Boolean> cliBatch(Context c, Collection<String> pkgs,
                                                Map<String, String> labels, boolean suspended,
                                                int userId, String caller) {
        if (c == null || pkgs == null || pkgs.isEmpty() || !PrivShell.rootLike()) {
            return null;
        }
        StringBuilder payload = new StringBuilder();
        for (String p : pkgs) {
            String label = labels == null ? null : labels.get(p);
            String b64 = label == null || label.isEmpty() ? "-"
                    : Base64.encodeToString(label.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            payload.append(p).append('|').append(b64).append('\n');
        }
        String arg = Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        StringBuilder cmd = new StringBuilder("CLASSPATH=")
                .append(shellQuote(c.getApplicationInfo().sourceDir))
                .append(" app_process /system/bin com.jin.freeze.lite.SuspendCli -b ")
                .append(arg).append(' ').append(userId);
        if (!suspended) {
            cmd.append(" u");
        }
        if (caller != null && !caller.isEmpty()) {
            cmd.append(' ').append(caller);
        }
        PrivShell.Result r = PrivShell.exec(cmd.toString(), Math.max(30, 10 + pkgs.size()));
        String out = r == null || r.out == null ? "" : r.out;
        Map<String, Boolean> result = new HashMap<>();
        for (String p : pkgs) {
            result.put(p, out.contains("JIN_SUSPEND_OK:" + p));
        }
        return result;
    }

    /**
     * 批量撤销暂停（主用户），返回真值复核后仍处于暂停态的包。
     *
     * 关键：root 跑 pm 的身份是 root，而凚的 root 通道 / 划卡锁屏用的是
     * suspendingPackage=com.android.shell；PMS 只允许 suspender 相同的调用撤销暂停，
     * 所以 pm unsuspend 会打印 "new suspended state: true" 却不生效（静默失败）。
     * 这里先批量 pm（快，覆盖 pm 自己的记录），再按真值把剩下的用一个 CLI 进程撤掉，
     * 最后才逐个按候选 suspender 兜底。
     */
    public static Set<String> unsuspendBatch(Context c, Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) {
            return new HashSet<>();
        }
        if (c != null) {
            prefs(c); // 初始化 appCtx，供 unsuspend / Dhizuku 判断使用
        }
        if (dhizukuOnly()) {
            for (String p : pkgs) {
                unsuspend(p);
            }
            return suspendedAmong(pkgs);
        }
        List<String> list = new ArrayList<>();
        StringBuilder script = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || !p.matches("[A-Za-z0-9._]+")) {
                continue;
            }
            list.add(p);
            script.append("pm unsuspend --user 0 ").append(p).append(" >/dev/null 2>&1; ");
        }
        if (list.isEmpty()) {
            return new HashSet<>();
        }
        PrivShell.exec(script.toString(), Math.max(30, 8 + list.size() * 3));
        Set<String> left = suspendedTruth(list);
        if (left != null && left.isEmpty()) {
            return left;
        }
        if (left == null) {
            left = new LinkedHashSet<>(list);
        }
        // 一个进程批量撤销（处理 com.android.shell 身份的暂停）
        Map<String, Boolean> cli = cliBatch(c, left, null, false);
        Set<String> still = suspendedTruth(new ArrayList<>(left));
        if (still != null) {
            left = still;
        } else if (cli != null) {
            Set<String> remain = new LinkedHashSet<>();
            for (String p : left) {
                if (!Boolean.TRUE.equals(cli.get(p))) {
                    remain.add(p);
                }
            }
            left = remain;
        }
        if (!left.isEmpty() && cli != null) {
            // Shizuku 以 root 身份运行时，暂停记的是本应用包名（com.jin.freeze），
            // 用同一个 CLI 再按这个 suspender 撤一次
            Map<String, Boolean> jin = cliBatch(c, left, null, false, 0, "com.jin.freeze");
            Set<String> afterJin = suspendedTruth(new ArrayList<>(left));
            if (afterJin != null) {
                left = afterJin;
            } else if (jin != null) {
                Set<String> remain = new LinkedHashSet<>();
                for (String p : left) {
                    if (!Boolean.TRUE.equals(jin.get(p))) {
                        remain.add(p);
                    }
                }
                left = remain;
            }
        }
        // 还有残留（hook/旧版本用了别的 suspender）：逐个按候选身份再撤一次
        if (!left.isEmpty()) {
            for (String p : new ArrayList<>(left)) {
                clearForeignSuspension(p);
            }
            Set<String> after = suspendedTruth(new ArrayList<>(left));
            if (after != null) {
                left = after;
            }
        }
        return left;
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
        if (appCtx == null || !PrivShell.rootLike()) {
            return false;
        }
        return rootUnsuspend(appCtx, pkg);
    }

    /**
     * 一次性迁移：旧版本 root 通道把 suspender 写成 "root"，系统的暂停弹窗会拿它当包名
     * 取资源、上报点击交互，点「确定」后系统界面进程直接崩溃
     * （Package root does not exist!）。升级后把仍在暂停的应用按新口径
     * （suspender=com.android.shell）重建一遍，状态保持不变。
     *
     * 关键：只有在真正处理完一轮后才落标记。之前「先落标记再判断 root」，
     * 开机时 su 还没就绪就会永久跳过，旧记录留着重演崩溃。
     */
    public static void repairLegacyRootSuspender(Context c) {
        if (c == null || repairing) {
            return;
        }
        SharedPreferences p = prefs(c);
        if (p.getBoolean(KEY_SUSPENDER_REPAIRED, false)
                || p.getInt(KEY_SUSPENDER_TRIES, 0) >= 3) {
            return;
        }
        Set<String> cached = cached(c);
        // 关键：按整机真值找 suspender=root 的暂停记录，纯靠本地缓存会漏
        //（用户清过数据 / 缓存丢过，系统里仍留着旧记录，点灰色图标还会崩）
        Set<String> legacy = findLegacyRootSuspended();
        if (legacy == null) {
            legacy = cached;
        }
        if (legacy.isEmpty()) {
            p.edit().putBoolean(KEY_SUSPENDER_REPAIRED, true).apply();
            return;
        }
        // 只有 root 通道才会写出不存在的 suspender；su 还没就绪就下次再试，不落标记
        if (PrivShell.mode() != PrivShell.Mode.ROOT) {
            return;
        }
        repairing = true;
        try {
            p.edit().putInt(KEY_SUSPENDER_TRIES, p.getInt(KEY_SUSPENDER_TRIES, 0) + 1).apply();
            boolean allDone = true;
            for (String pkg : legacy) {
                try {
                    if (!Boolean.TRUE.equals(suspendedOne(pkg))) {
                        continue;
                    }
                    if (!unsuspendAnyCaller(c, pkg)) {
                        allDone = false;
                        continue;
                    }
                    String label = labelOf(c, pkg);
                    boolean ok = rootSuspend(c, pkg, label);
                    if (!ok) {
                        PrivShell.exec(suspendCommand(pkg, label), 20);
                        ok = Boolean.TRUE.equals(suspendedOne(pkg));
                    }
                    if (!ok) {
                        allDone = false;
                        continue;
                    }
                    Log.w("JinPm", "暂停身份已重建为 shell: " + pkg);
                } catch (Throwable t) {
                    allDone = false;
                    Log.w("JinPm", "重建暂停身份失败 " + pkg + ": " + t);
                }
            }
            if (allDone) {
                p.edit().putBoolean(KEY_SUSPENDER_REPAIRED, true).apply();
            }
        } finally {
            repairing = false;
        }
    }

    /**
     * 从整机 dumpsys 里找出 suspender 写成 "root" 的暂停记录。
     * dumpsys 片段：Package [pkg] (hash): … Suspend params:
     *   suspendingPackage=&lt;0&gt;root dialogInfo=null …
     * 读不到返回 null（调用方退回本地缓存）。
     */
    private static Set<String> findLegacyRootSuspended() {
        PrivShell.Result r = PrivShell.exec("dumpsys package", 90);
        if (r == null || r.timeout || r.out == null) {
            return null;
        }
        Set<String> out = new HashSet<>();
        String current = null;
        for (String line : r.out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Package [")) {
                int end = t.indexOf(']');
                current = end > 9 ? t.substring(9, end) : null;
                continue;
            }
            if (current == null || !t.startsWith("suspendingPackage=")) {
                continue;
            }
            String v = t.substring("suspendingPackage=".length()).trim();
            int gt = v.indexOf('>');
            String pkg = gt >= 0 ? v.substring(gt + 1) : v;
            int space = pkg.indexOf(' ');
            if (space > 0) {
                pkg = pkg.substring(0, space);
            }
            if (pkg.isEmpty() || "root".equals(pkg)) {
                out.add(current);
            }
        }
        return out;
    }

    /** 按候选 suspender 逐个尝试撤销，直到真值变 false。 */
    private static boolean unsuspendAnyCaller(Context c, String pkg) {
        for (String caller : new String[]{"root", "com.android.shell", "com.jin.freeze"}) {
            PrivShell.Result r = PrivShell.exec(cliCommand(c, pkg, "", "u", caller), 20);
            if (r.code == 0 && r.out != null && r.out.contains("JIN_SUSPEND_OK")
                    && !Boolean.TRUE.equals(suspendedOne(pkg))) {
                return true;
            }
        }
        return false;
    }

    /** 应用名：暂停弹窗正文用；读不到就退回包名。 */
    private static String labelOf(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
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
        // 名单长时逐个 dumpsys 会超过固定 30s，按包数放宽，否则会被截断成「没暂停」
        PrivShell.Result r = PrivShell.exec(script, Math.max(30, 8 + pkgs.size() * 2));
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
        PrivShell.Result r = PrivShell.exec(script + "echo __JIN_DONE__",
                Math.max(30, 8 + pkgs.size() * 2));
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
