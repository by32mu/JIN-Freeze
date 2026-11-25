package com.jin.freeze.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.jin.freeze.JinApp;

/**
 * 冻结/解冻失败时补一次权限申请：root → Shizuku → Dhizuku。
 * 已有任意可用通道（root / Shizuku 已授权 / Dhizuku 已授权）时直接回调，不弹任何窗口。
 * 拒绝过 Shizuku 但 Dhizuku 没授权时，仍会继续申请 Shizuku（与启动检查同一套规则）。
 */
public final class PrivRequest {

    public interface Callback {
        void onDone(boolean got);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PrivRequest() {
    }

    public static void ensure(Context ctx, Callback cb) {
        final Context app = ctx.getApplicationContext();
        JinApp.IO.execute(() -> {
            // 已有通道：root / Shizuku 已授权都不再打扰
            if (PrivShell.mode() != PrivShell.Mode.NONE || DhizukuGate.authorized(app)) {
                MAIN.post(() -> {
                    if (cb != null) {
                        cb.onDone(true);
                    }
                });
                return;
            }
            final boolean shizukuReady = ShizukuGate.available(app);
            MAIN.post(() -> {
                if (!shizukuReady) {
                    requestDhizuku(app, cb);
                    return;
                }
                ShizukuGate.requestAnyway(() -> {
                    if (ShizukuGate.authorized()) {
                        PrivShell.reset();
                        if (cb != null) {
                            cb.onDone(true);
                        }
                    } else {
                        // 拒绝了 Shizuku：按顺序转 Dhizuku
                        requestDhizuku(app, cb);
                    }
                });
            });
        });
    }

    private static void requestDhizuku(Context app, Callback cb) {
        if (DhizukuGate.authorized(app)) {
            if (cb != null) {
                cb.onDone(true);
            }
            return;
        }
        if (!DhizukuGate.available(app)) {
            if (cb != null) {
                cb.onDone(false);
            }
            return;
        }
        DhizukuGate.requestAnyway(() -> {
            PrivShell.reset();
            if (cb != null) {
                cb.onDone(DhizukuGate.authorized(app));
            }
        });
    }
}
