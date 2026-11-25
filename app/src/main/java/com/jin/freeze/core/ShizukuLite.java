package com.jin.freeze.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.jin.freeze.JinApp;
import com.jin.freeze.lite.IJinLite;
import com.jin.freeze.lite.JinLiteService;

import java.util.HashSet;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 通道的划卡/锁屏冻结：通过 Shizuku.bindUserService 拉起 shell(uid 2000) 进程里的
 * {@link JinLiteService}，并把名单/开关/延时推给它。凚主进程被冻结或退出后服务仍按 daemon 存活。
 */
public final class ShizukuLite {

    /** 与 SwipeFreeze 同一份偏好文件，不新建存储。 */
    private static final String PREFS = "freeze_list";
    private static final String KEY_SWIPE = "sz_swipe_enabled";
    private static final String KEY_LOCK = "sz_lock_enabled";
    private static final String KEY_DELAY = "sz_lock_delay";
    private static final String KEY_KEEP_ICON = "keep_launcher_icon";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context appCtx;
    private static Shizuku.UserServiceArgs args;
    private static IJinLite service;
    private static boolean binding;
    private static volatile long bindAt;
    private static Process daemonLauncher;
    private static volatile String lastError = "";

    private ShizukuLite() {
    }

    private static ServiceConnection connection;

    private static ServiceConnection conn() {
        if (connection == null) {
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    service = IJinLite.Stub.asInterface(binder);
                    binding = false;
                    lastError = "";
                    syncAsync(appCtx);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    service = null;
                    binding = false;
                }
            };
        }
        return connection;
    }

    // ---------------- 开关（Shizuku 专区独立于 LSPosed 通道） ----------------

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY_SWIPE, false);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_SWIPE, on).apply();
    }

    public static boolean lockEnabled(Context c) {
        return prefs(c).getBoolean(KEY_LOCK, false);
    }

    public static void setLockEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_LOCK, on).apply();
    }

    public static int lockDelaySeconds(Context c) {
        int v = prefs(c).getInt(KEY_DELAY, SwipeFreeze.LOCK_DELAY_DEFAULT);
        return Math.max(0, Math.min(SwipeFreeze.LOCK_DELAY_MAX, v));
    }

    public static void setLockDelaySeconds(Context c, int seconds) {
        int v = Math.max(0, Math.min(SwipeFreeze.LOCK_DELAY_MAX, seconds));
        prefs(c).edit().putInt(KEY_DELAY, v).apply();
    }

    public static boolean ready() {
        return service != null;
    }

    public static String lastError() {
        return lastError;
    }

    // ---------------- 绑定 / 同步 ----------------

    /** 启动时如果开关是开的，自动把服务拉起来并同步一次。 */
    public static void autoStart(Context context) {
        final Context app = context.getApplicationContext();
        appCtx = app;
        if (!ShizukuGate.installed(app) || !ShizukuGate.authorized()
                || !ShizukuGate.available(app)) {
            return;
        }
        if (!enabled(app) && !lockEnabled(app)) {
            return;
        }
        bind(app);
        JinApp.IO.execute(() -> push(app));
        // UserService 在本机 ROM 起不来时，切到 Shizuku.newProcess + app_process 的常驻守护
        MAIN.postDelayed(() -> {
            if (service == null && appCtx != null) {
                JinApp.IO.execute(() -> push(appCtx));
            }
        }, 4000L);
    }

    public static void syncAsync(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        appCtx = app;
        if (service == null) {
            bind(app);
        }
        JinApp.IO.execute(() -> push(app));
    }

    private static void bind(final Context app) {
        if (binding || service != null) {
            return;
        }
        if (!ShizukuGate.installed(app) || !ShizukuGate.authorized()
                || !ShizukuGate.available(app)) {
            lastError = "Shizuku 未授权或未运行";
            return;
        }
        binding = true;
        bindAt = android.os.SystemClock.elapsedRealtime();
        MAIN.post(() -> {
            try {
                if (args == null) {
                    args = new Shizuku.UserServiceArgs(
                            new ComponentName(app, JinLiteService.class))
                            .daemon(true)
                            .processNameSuffix("lite")
                            .debuggable(false)
                            // 跟应用版本走：升级后 Shizuku 会重启 lite 服务，避免继续跑旧 dex
                            .version(versionCode(app));
                }
                Shizuku.bindUserService(args, conn());
            } catch (Throwable t) {
                binding = false;
                lastError = String.valueOf(t);
                bindAt = 0;
            }
        });
    }

    private static void push(Context app) {
        Set<String> swipe = SwipeFreeze.packagesForTags(app, SwipeFreeze.selectedTags(app));
        Set<String> lock = SwipeFreeze.packagesForTags(app, SwipeFreeze.lockSelectedTags(app));
        Set<String> white = prefs(app).getStringSet("freeze_whitelist", null);
        Set<String> whitelist = white == null ? new HashSet<>() : new HashSet<>(white);
        whitelist.add(app.getPackageName());
        boolean suspend = prefs(app).getBoolean(KEY_KEEP_ICON, false);
        boolean swipeOn = enabled(app);
        boolean lockOn = lockEnabled(app);
        int delay = lockDelaySeconds(app);

        IJinLite s = service;
        if (s != null) {
            try {
                s.syncConfig(swipe.toArray(new String[0]), lock.toArray(new String[0]),
                        whitelist.toArray(new String[0]), swipeOn, lockOn, delay, suspend);
                lastError = "";
                stopDaemon();
            } catch (Throwable t) {
                lastError = String.valueOf(t);
            }
            return;
        }
        // UserService 没连上：配置写到 /data/local/tmp，交给 app_process 守护
        writeDaemonConfig(app, swipe, lock, whitelist, swipeOn, lockOn, delay, suspend);
        boolean on = swipeOn || lockOn;
        if (!on) {
            stopDaemon();
            return;
        }
        boolean bindTimedOut = bindAt == 0
                || android.os.SystemClock.elapsedRealtime() - bindAt > 3500L;
        if (bindTimedOut) {
            ensureDaemon(app);
        }
    }

    // ---------------- app_process 守护（UserService 起不来时的载体） ----------------

    private static void writeDaemonConfig(Context app, Set<String> swipe, Set<String> lock,
                                          Set<String> whitelist, boolean swipeOn, boolean lockOn,
                                          int delay, boolean suspend) {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("swipe", new org.json.JSONArray(swipe));
            root.put("lock", new org.json.JSONArray(lock));
            root.put("white", new org.json.JSONArray(whitelist));
            root.put("swipeOn", swipeOn);
            root.put("lockOn", lockOn);
            root.put("delay", delay);
            root.put("suspend", suspend);
            String b64 = android.util.Base64.encodeToString(
                    root.toString().getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            PrivShell.exec("mkdir -p /data/local/tmp/jin_lite && echo " + b64
                    + " | base64 -d > /data/local/tmp/jin_lite/config.json", 8);
        } catch (Throwable t) {
            lastError = "写守护配置失败:" + t;
        }
    }

    private static void ensureDaemon(Context app) {
        try {
            if (daemonRunning()) {
                return;
            }
            String apk = app.getPackageCodePath();
            String cmd = "CLASSPATH=" + apk
                    + " nohup app_process /system/bin --nice-name=jin_lite"
                    + " com.jin.freeze.lite.LiteDaemon >/dev/null 2>&1 &";
            daemonLauncher = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        } catch (Throwable t) {
            lastError = "启动守护失败:" + t;
        }
    }

    private static boolean daemonRunning() {
        // 用 jin_lit[e] 防止 pgrep 匹配到自己这条命令
        PrivShell.Result r = PrivShell.exec(
                "if pgrep -f 'jin_lit[e]' >/dev/null 2>&1; then echo yes; fi", 5);
        return r.out != null && r.out.contains("yes");
    }

    private static void stopDaemon() {
        if (daemonLauncher == null && !daemonRunning()) {
            return;
        }
        PrivShell.exec("pkill -f 'jin_lit[e]'", 5);
        daemonLauncher = null;
    }

    /** 读服务自检信息（在 IO 线程调用）。 */
    public static String statusBlocking() {
        IJinLite s = service;
        if (s != null) {
            try {
                return s.status();
            } catch (Throwable t) {
                lastError = String.valueOf(t);
            }
        }
        if (daemonRunning()) {
            PrivShell.Result r = PrivShell.exec(
                    "cat /data/local/tmp/jin_lite/status.txt 2>/dev/null", 5);
            String out = r.out == null ? "" : r.out.trim();
            if (!out.isEmpty()) {
                return out;
            }
        }
        return lastError.isEmpty() ? "服务未连接" : ("未连接：" + lastError);
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int versionCode(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 1;
        }
    }
}
