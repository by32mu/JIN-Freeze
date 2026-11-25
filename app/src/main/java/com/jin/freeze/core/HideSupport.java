package com.jin.freeze.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 隐藏应用模式：把一个应用从桌面/其他应用（分享、跳转、第三方登录）里隐藏。
 *
 * 三条通道按现有权限自动选：
 * - root：am force-stop + pm hide --user N；
 * - Shizuku：IPackageManager.setApplicationHiddenSettingAsUser（shizuku 以 root 运行时可用）；
 * - Dhizuku：DevicePolicyManager.setApplicationHidden（仅主用户）。
 *
 * 真值：主用户读 ApplicationInfo.privateFlags 的 HIDDEN 位；分身用户系统不暴露隐藏位，
 * 用本地账本 hide_keys 兜底（与凚列表实例键一致：主用户纯包名，分身 pkg@uid）。
 */
public final class HideSupport {

    public static final String PREFS = "freeze_list";
    public static final String KEY_MODE = "hide_mode";
    public static final String KEY_KEYS = "hide_keys";

    /** ApplicationInfo.PRIVATE_FLAG_HIDDEN */
    private static final int PRIVATE_FLAG_HIDDEN = 1;
    private static final String STUB = "android.content.pm.IPackageManager$Stub";
    private static final String DESC = "android.content.pm.IPackageManager";

    private static volatile Boolean exempted;

    private HideSupport() {
    }

    // ---------------- 开关 ----------------

    public static boolean modeOn(Context c) {
        return prefs(c).getBoolean(KEY_MODE, false);
    }

    public static void setModeOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_MODE, on).apply();
    }

    /**
     * 当前环境能否真正隐藏（只做快速判断，不触发 PrivShell 的阻塞探测）：
     * root、以 root 运行的 Shizuku、或已授权的 Dhizuku 才显示「隐藏应用模式」。
     */
    public static boolean available(Context c) {
        try {
            if (shizukuRoot()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (new File("/system/bin/su").canExecute()
                    || new File("/system/xbin/su").canExecute()
                    || new File("/sbin/su").canExecute()
                    || new File("/su/bin/su").canExecute()
                    || new File("/debug_ramdisk/su").canExecute()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return DhizukuGate.only(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 是否已授权且以 root 身份运行（普通 adb/shell 模式返回 false）。 */
    private static boolean shizukuRoot() {
        try {
            return rikka.shizuku.Shizuku.pingBinder()
                    && rikka.shizuku.Shizuku.checkSelfPermission()
                    == PackageManager.PERMISSION_GRANTED
                    && rikka.shizuku.Shizuku.getUid() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 实例键 ----------------

    public static String key(String pkg, int userId) {
        return userId == 0 ? pkg : pkg + "@" + userId;
    }

    public static String pkgOf(String key) {
        int at = key == null ? -1 : key.lastIndexOf('@');
        return at > 0 ? key.substring(0, at) : key;
    }

    public static int userIdOf(String key) {
        int at = key == null ? -1 : key.lastIndexOf('@');
        if (at <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(key.substring(at + 1).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    // ---------------- 保护名单 ----------------

    /** 凚自身、SystemUI、当前桌面不允许隐藏，避免把手机变砖。 */
    public static boolean isProtected(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        if (pkg.equals(c.getPackageName()) || pkg.equals("com.android.systemui")) {
            return true;
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------- 读真值 ----------------

    /** 系统层是否真的隐藏（不含「为了打开而临时取消隐藏」的意图记录）。 */
    public static boolean systemHiddenOne(Context c, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (userId != 0) {
            return ledger(c).contains(key(pkg, userId));
        }
        try {
            exemptHiddenApi();
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            Field f = ApplicationInfo.class.getField("privateFlags");
            return (f.getInt(ai) & PRIVATE_FLAG_HIDDEN) != 0;
        } catch (Throwable t) {
            return ledger(c).contains(pkg);
        }
    }

    /** TRUE=已隐藏、FALSE=未隐藏、null=没读到（调用方按账本兜底）。 */
    public static Boolean hiddenOne(Context c, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (userId != 0) {
            // 分身隐藏位系统不对外暴露，账本是唯一真值
            return ledger(c).contains(key(pkg, userId));
        }
        try {
            exemptHiddenApi();
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            Field f = ApplicationInfo.class.getField("privateFlags");
            int flags = f.getInt(ai);
            // 隐藏位 + 账本：打开应用时只临时取消系统隐藏位，账本保留「用户想隐藏」的意图
            return (flags & PRIVATE_FLAG_HIDDEN) != 0 || ledger(c).contains(pkg);
        } catch (Throwable t) {
            Set<String> keys = ledger(c);
            return keys.contains(pkg) ? Boolean.TRUE : null;
        }
    }

    /** 批量读真值：返回已隐藏的实例键。读不到的按账本。 */
    public static Set<String> hiddenAmong(Context c, Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<>();
        Set<String> book = null;
        for (String k : keys) {
            if (k == null || k.isEmpty()) {
                continue;
            }
            if (book == null) {
                book = ledger(c);
            }
            Boolean hidden = hiddenOne(c, pkgOf(k), userIdOf(k));
            if (hidden == null) {
                hidden = book.contains(k) || book.contains(pkgOf(k));
            }
            if (hidden) {
                out.add(k);
            }
        }
        return out;
    }

    // ---------------- 写 ----------------

    public static final class Result {
        public final boolean ok;
        public final String message;
        /** 诊断用：实际走的通道（root / shizuku / dhizuku），空表示未进入执行。 */
        public final String channel;
        /** 诊断用：实际下发的命令或调用描述。 */
        public final String cmd;

        Result(boolean ok, String message) {
            this(ok, message, "", "");
        }

        Result(boolean ok, String message, String channel, String cmd) {
            this.ok = ok;
            this.message = message == null ? "" : message;
            this.channel = channel == null ? "" : channel;
            this.cmd = cmd == null ? "" : cmd;
        }
    }

    public static Result hide(Context c, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return new Result(false, "包名为空");
        }
        if (isProtected(c, pkg)) {
            return new Result(false, "凚自身、系统界面或当前桌面不能隐藏");
        }
        return apply(c, pkg, userId, true, false);
    }

    public static Result unhide(Context c, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return new Result(false, "包名为空");
        }
        return apply(c, pkg, userId, false, false);
    }

    /**
     * 为了打开应用临时取消系统隐藏位：不动「用户想隐藏」的账本，
     * 列表继续按隐藏显示；之后点一键冻结会恢复隐藏，而不是改成冻结。
     */
    public static Result unhideForLaunch(Context c, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return new Result(false, "包名为空");
        }
        return apply(c, pkg, userId, false, true);
    }

    private static Result apply(Context c, String pkg, int userId, boolean hidden, boolean keepIntent) {
        PrivShell.Mode mode = PrivShell.mode();
        Result r;
        if (mode == PrivShell.Mode.ROOT) {
            r = rootApply(c, pkg, userId, hidden);
        } else if (mode == PrivShell.Mode.SHIZUKU) {
            r = shizukuApply(c, pkg, userId, hidden);
        } else if (DhizukuGate.only(c)) {
            r = dhizukuApply(c, pkg, userId, hidden);
        } else {
            return new Result(false, "隐藏需要 root、Shizuku 或 Dhizuku 授权");
        }
        if (r.ok && (hidden || !keepIntent)) {
            remember(c, pkg, userId, hidden);
        }
        return r;
    }

    private static Result rootApply(Context c, String pkg, int userId, boolean hidden) {
        // 隐藏不要先 am force-stop：那会让划卡/锁屏冻结抢在隐藏生效前把应用停用，
        // 结果变成「隐藏 + 冻结」。pm hide 自己会结束进程，等它生效后，
        // 划卡通道 200ms 后回读真值时已能看到隐藏位，从而跳过冻结。
        String cmd = hidden
                ? "pm hide --user " + userId + " " + pkg
                : "pm unhide --user " + userId + " " + pkg;
        PrivShell.Result r = PrivShell.exec(cmd, 20);
        if (r == null) {
            return new Result(false, "root 命令没有返回", "root", cmd);
        }
        String out = r.out == null ? "" : r.out;
        boolean bad = out.contains("Exception") || out.contains("Error:") || out.contains("error:");
        if (r.code != 0 || bad) {
            return new Result(false, firstLine(out), "root", cmd);
        }
        // 只看系统隐藏位：账本里的「隐藏意图」不参与这次真值校验，
        // 否则带意图的应用永远撤不掉（会被误判成系统没接受）
        Boolean truth = systemHiddenOne(c, pkg, userId);
        if (truth != null && truth != hidden) {
            return new Result(false, "系统未接受隐藏状态变更", "root", cmd);
        }
        return new Result(true, "", "root", cmd);
    }

    private static Result shizukuApply(Context c, String pkg, int userId, boolean hidden) {
        String cmd = "IPackageManager.setApplicationHiddenSettingAsUser(" + pkg + ", "
                + hidden + ", " + userId + ")";
        try {
            exemptHiddenApi();
            IBinder service = rikka.shizuku.SystemServiceHelper.getSystemService("package");
            if (service == null) {
                return new Result(false, "取不到 package 服务", "shizuku", cmd);
            }
            Object pm = Class.forName(STUB)
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, new rikka.shizuku.ShizukuBinderWrapper(service));
            Method target = null;
            for (Method m : Class.forName(DESC).getMethods()) {
                if ("setApplicationHiddenSettingAsUser".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                return new Result(false, "当前系统没有 setApplicationHiddenSettingAsUser",
                        "shizuku", cmd);
            }
            target.invoke(pm, pkg, hidden, userId);
            return new Result(true, "", "shizuku", cmd);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            String msg = String.valueOf(cause.getMessage());
            if (cause instanceof SecurityException || msg.contains("MANAGE_USERS")) {
                return new Result(false, "Shizuku(shell) 缺少 MANAGE_USERS，隐藏需 root 通道或 Dhizuku",
                        "shizuku", cmd);
            }
            return new Result(false, firstLine(msg), "shizuku", cmd);
        }
    }

    private static Result dhizukuApply(Context c, String pkg, int userId, boolean hidden) {
        String cmd = "DPM.setApplicationHidden(" + pkg + ", " + hidden + ")";
        if (userId != 0) {
            return new Result(false, "Dhizuku 通道只支持主用户，分身请用 root/Shizuku",
                    "dhizuku", cmd);
        }
        try {
            Object dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) {
                return new Result(false, "Dhizuku 未授权", "dhizuku", cmd);
            }
            Method m = Class.forName("android.app.admin.DevicePolicyManager")
                    .getMethod("setApplicationHidden", ComponentName.class, String.class, boolean.class);
            Object changed = m.invoke(dpm, null, pkg, hidden);
            if (Boolean.FALSE.equals(changed)) {
                return new Result(false, "Dhizuku 拒绝执行（检查包访问权限）", "dhizuku", cmd);
            }
            return new Result(true, "", "dhizuku", cmd);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return new Result(false, firstLine(String.valueOf(cause.getMessage())), "dhizuku", cmd);
        }
    }

    /** 用户标记为隐藏的实例键（账本）；划卡/锁屏/快捷方式据此跳过或改走隐藏。 */
    public static Set<String> hiddenIntentKeys(Context c) {
        if (c == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(ledger(c));
    }

    /** 只记账「用户想隐藏」，不改系统隐藏位。
     * 打开隐藏应用时用来保住意图：即使系统隐藏位被临时取消，列表仍然显示 ×。
     */
    public static void markHiddenIntent(Context c, String pkg, int userId, boolean hidden) {
        if (c == null || pkg == null || pkg.isEmpty()) {
            return;
        }
        remember(c, pkg, userId, hidden);
    }

    // ---------------- 进程是否还在跑 ----------------

    /** 通过 /proc 判断应用进程是否存活，用于「一键冻结只隐藏运行中」。 */
    public static boolean isRunning(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        File[] pids = new File("/proc").listFiles();
        if (pids == null) {
            return false;
        }
        for (File pid : pids) {
            if (!pid.isDirectory()) {
                continue;
            }
            String name = pid.getName();
            if (name.isEmpty() || name.charAt(0) < '0' || name.charAt(0) > '9') {
                continue;
            }
            FileInputStream in = null;
            try {
                in = new FileInputStream(new File(pid, "cmdline"));
                byte[] buf = new byte[256];
                int n = in.read(buf);
                if (n <= 0) {
                    continue;
                }
                String cmd = new String(buf, 0, n).trim();
                int zero = cmd.indexOf('\0');
                if (zero >= 0) {
                    cmd = cmd.substring(0, zero);
                }
                if (cmd.equals(pkg) || cmd.startsWith(pkg + ":")) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 别的进程 /proc 读不到就跳过
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    // ---------------- 账本 ----------------

    private static Set<String> ledger(Context c) {
        Set<String> keys = prefs(c).getStringSet(KEY_KEYS, null);
        return keys == null ? Collections.<String>emptySet() : keys;
    }

    private static void remember(Context c, String pkg, int userId, boolean hidden) {
        String key = key(pkg, userId);
        Set<String> next = new HashSet<>(ledger(c));
        boolean changed = hidden ? next.add(key) : next.remove(key);
        if (changed) {
            prefs(c).edit().putStringSet(KEY_KEYS, next).apply();
        }
    }

    // ---------------- 工具 ----------------

    private static synchronized void exemptHiddenApi() {
        if (exempted != null) {
            return;
        }
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.setHiddenApiExemptions("L");
            exempted = Boolean.TRUE;
        } catch (Throwable t) {
            exempted = Boolean.FALSE;
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        int nl = t.indexOf('\n');
        if (nl > 0) {
            t = t.substring(0, nl);
        }
        return t.length() > 160 ? t.substring(0, 160) : t;
    }
}
