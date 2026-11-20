package com.jin.freeze.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import rikka.shizuku.Shizuku;

/** Shizuku 的安装检测与授权申请；授权完成后回调一次。 */
public final class ShizukuGate {

    public static final String PKG = "moe.shizuku.privileged.api";
    private static final int REQUEST_CODE = 0x5A17;
    private static final String PREFS = "shizuku_gate";
    private static final String KEY_GRANTED = "authorized";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Shizuku.OnRequestPermissionResultListener listener;
    private static Runnable pending;
    private static Context appCtx;

    private ShizukuGate() {
    }

    private static void remember(Context c) {
        if (c != null && appCtx == null) {
            appCtx = c.getApplicationContext();
        }
    }

    /**
     * 用户是否真的在 Shizuku 弹窗里点过「允许」。
     * 部分 ROM 把 API_V23 定为安装即授予，checkSelfPermission() 与服务端执行都会放行，
     * 但 Shizuku 的「应用管理」没登记本应用。这里以授权回调为准，保证「显示 Shizuku」
     * 与「Shizuku 列表里有凚」一致。
     */
    public static boolean authorized() {
        return appCtx != null
                && appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GRANTED, false);
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

    /** Shizuku 已安装且服务正在运行（不代表已授权）。 */
    public static boolean available(Context c) {
        remember(c);
        if (!installed(c)) {
            return false;
        }
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 向 Shizuku 发一次授权请求（不看本地判定）。
     * 部分系统把 API_V23 定为安装即授予，checkSelfPermission() 会误报已授权，
     * 只有真正 requestPermission 过，应用才会出现在 Shizuku 的「应用管理」列表里。
     * 已授权时服务端直接回调，不弹窗；未授权才会弹「要允许凚使用 Shizuku 吗？」。
     */
    public static void requestAnyway(Runnable onDone) {
        try {
            if (listener != null) {
                Shizuku.removeRequestPermissionResultListener(listener);
            }
            pending = onDone;
            listener = (requestCode, grantResult) -> MAIN.post(() -> {
                setAuthorized(grantResult == PackageManager.PERMISSION_GRANTED);
                Runnable cb = pending;
                pending = null;
                PrivShell.reset();
                if (cb != null) {
                    cb.run();
                }
            });
            Shizuku.addRequestPermissionResultListener(listener);
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable e) {
            if (onDone != null) {
                onDone.run();
            }
        }
    }
}
