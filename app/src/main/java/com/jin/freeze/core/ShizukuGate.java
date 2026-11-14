package com.jin.freeze.core;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import rikka.shizuku.Shizuku;

/** Shizuku 的安装检测与授权申请；授权完成后回调一次。 */
public final class ShizukuGate {

    public static final String PKG = "moe.shizuku.privileged.api";
    private static final int REQUEST_CODE = 0x5A17;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Shizuku.OnRequestPermissionResultListener listener;
    private static Runnable pending;

    private ShizukuGate() {
    }

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean granted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 申请 Shizuku 授权；结果（无论允许与否）回调一次，附带重新探测。 */
    public static void request(Activity activity, Runnable onDone) {
        if (granted()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        try {
            if (listener != null) {
                Shizuku.removeRequestPermissionResultListener(listener);
            }
            pending = onDone;
            listener = (requestCode, grantResult) -> MAIN.post(() -> {
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
