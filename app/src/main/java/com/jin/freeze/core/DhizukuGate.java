package com.jin.freeze.core;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rosan.dhizuku.api.Dhizuku;
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Dhizuku（Device Owner 代理）通道：无 root / Shizuku 时用设备所有者权限
 * 暂停（suspend）/恢复应用。委托 DELEGATION_PACKAGE_ACCESS 后在本进程直接调
 * DevicePolicyManager.setPackagesSuspended(null, ...)，与 Hail 同款接法。
 *
 * 限制：
 * - 只能「暂停」，做不到 pm disable-user 停用（对应凚的暂停态：图标置灰 + 暂停标识）。
 * - 委托权限自 Android 8.0 起，API 24/25 上此通道不可用。
 * - 设备所有者只作用 user 0，分身（userId≠0）不受影响。
 */
public final class DhizukuGate {

    public static final String PKG = "com.rosan.dhizuku";
    private static final String PREFS = "dhizuku_gate";
    private static final String KEY_GRANTED = "authorized";
    private static final String TAG = "JinPm";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context appCtx;

    private DhizukuGate() {
    }

    private static void remember(Context c) {
        if (c != null && appCtx == null) {
            appCtx = c.getApplicationContext();
        }
    }

    /** 用户是否真的在 Dhizuku 弹窗里点过「允许」（与 ShizukuGate 同口径）。 */
    public static boolean authorized(Context c) {
        remember(c);
        return appCtx != null
                && appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GRANTED, false);
    }

    /**
     * 用 Dhizuku 服务端的真实授权状态校正本地记录。
     * 用户在 Dhizuku 应用里撤销授权后，本地布尔值不会自己变，必须主动核对一次，
     * 否则凚会一直显示「Dhizuku 授权」并挡住重新申请 Shizuku。
     * 服务连不上（init 失败）时保持原记录，避免临时不可用被误判成撤销。
     */
    public static void refresh(Context c) {
        remember(c);
        if (appCtx == null || !installed(c)) {
            return;
        }
        try {
            if (!init()) {
                return;
            }
            boolean granted = Dhizuku.isPermissionGranted();
            if (granted != authorized(appCtx)) {
                setAuthorized(granted);
            }
        } catch (Throwable ignored) {
            // 读不到就沿用旧记录
        }
    }

    /** 清掉本地授权记录（长按权限重置时用，下一次请求会重新弹授权框）。 */
    public static void forget(Context c) {
        remember(c);
        setAuthorized(false);
    }

    private static void setAuthorized(boolean granted) {
        if (appCtx != null) {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_GRANTED, granted).apply();
        }
    }

    public static boolean installed(Context c) {
        remember(c);
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Dhizuku 已安装且能连上（不代表已授权给凚）。 */
    public static boolean available(Context c) {
        remember(c);
        return installed(c) && init();
    }

    private static boolean init() {
        try {
            return appCtx != null && Dhizuku.init(appCtx);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Dhizuku 服务连通且凚已被授权（含委托权限可用性）。 */
    private static boolean ready() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        try {
            return init() && Dhizuku.isPermissionGranted() && ensureDelegation();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean ensureDelegation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        try {
            if (hasPackageAccessScope(Dhizuku.getDelegatedScopes())) {
                return true;
            }
            Dhizuku.setDelegatedScopes(
                    new String[]{DevicePolicyManager.DELEGATION_PACKAGE_ACCESS});
            return hasPackageAccessScope(Dhizuku.getDelegatedScopes());
        } catch (Throwable t) {
            Log.w(TAG, "dhizuku setDelegatedScopes failed", t);
            return false;
        }
    }

    private static boolean hasPackageAccessScope(String[] scopes) {
        if (scopes == null) {
            return false;
        }
        for (String s : scopes) {
            if (DevicePolicyManager.DELEGATION_PACKAGE_ACCESS.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本机唯一可用的冻结通道是否就是 Dhizuku（无 root/Shizuku 且已授权）。
     * 此时「冻结」自动降级为暂停语义。
     */
    public static boolean only(Context c) {
        remember(c);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && PrivShell.mode() == PrivShell.Mode.NONE
                && authorized(c)
                && ready();
    }

    /** 向 Dhizuku 发一次授权请求；已授权时直接回调不弹窗。 */
    public static void requestAnyway(Runnable onDone) {
        remember(null);
        try {
            if (!init()) {
                if (onDone != null) {
                    onDone.run();
                }
                return;
            }
            Dhizuku.requestPermission(new DhizukuRequestPermissionListener() {
                @Override
                public void onRequestPermission(int grantResult) {
                    MAIN.post(() -> {
                        setAuthorized(grantResult == PackageManager.PERMISSION_GRANTED);
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            ensureDelegation();
                        }
                        PrivShell.reset();
                        if (onDone != null) {
                            onDone.run();
                        }
                    });
                }
            });
        } catch (Throwable e) {
            if (onDone != null) {
                onDone.run();
            }
        }
    }

    /** 暂停一个包。label 用不上（DPM 通道只有系统默认提示框）。 */
    public static PrivShell.Result suspend(Context c, String pkg, String label) {
        remember(c);
        return setSuspended(pkg, true);
    }

    public static PrivShell.Result unsuspend(Context c, String pkg) {
        remember(c);
        return setSuspended(pkg, false);
    }

    private static PrivShell.Result setSuspended(String pkg, boolean suspended) {
        if (!ready()) {
            return new PrivShell.Result(1, "error: dhizuku not authorized", false);
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) appCtx
                    .getSystemService(Context.DEVICE_POLICY_SERVICE);
            String[] failed = dpm.setPackagesSuspended(null, new String[]{pkg}, suspended);
            if (failed == null || failed.length == 0) {
                return new PrivShell.Result(0, "", false);
            }
            return new PrivShell.Result(1, "error: dhizuku rejected " + pkg, false);
        } catch (Throwable t) {
            Log.w(TAG, "dhizuku setPackagesSuspended failed: " + pkg, t);
            return new PrivShell.Result(1, "error: dhizuku failed " + pkg, false);
        }
    }

    /**
     * 单包暂停真值：TRUE=已暂停、FALSE=未暂停、null=没读到。
     * 读 ApplicationInfo.flags 的 FLAG_SUSPENDED（暂停不隐藏包，能正常查到）。
     */
    public static Boolean isSuspended(Context c, String pkg) {
        remember(c);
        if (appCtx == null || pkg == null) {
            return null;
        }
        try {
            ApplicationInfo ai = appCtx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 批量读真值：返回已暂停的包集合。 */
    public static Set<String> suspendedAmong(Context c, Collection<String> pkgs) {
        remember(c);
        if (pkgs == null || pkgs.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String p : pkgs) {
            if (Boolean.TRUE.equals(isSuspended(c, p))) {
                result.add(p);
            }
        }
        return result;
    }
}
