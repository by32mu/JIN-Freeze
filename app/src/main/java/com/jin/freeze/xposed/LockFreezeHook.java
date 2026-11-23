package com.jin.freeze.xposed;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * 锁屏冻结：灭屏后按设置延时冻结名单内应用。
 *
 * - 开关与延时秒数都写在 /data/system/jin_freeze_list.json，名单与标签口径和划卡冻结一致；
 * - 延时 0 秒 = 锁屏立即冻结；1-300 秒用精确闹钟（深度睡眠也能到点）；
 * - 中途解锁（ACTION_USER_PRESENT）取消尚未触发的闹钟；
 * - 接收器常驻注册，改设置只需重写配置，不用重启 system_server。
 */
final class LockFreezeHook {

    private static final String TAG = "JinLockFreeze";
    private static final String ACTION_ALARM = "com.jin.freeze.action.LOCK_FREEZE_ALARM";
    private static final String ACTION_STATUS_PING = "com.jin.freeze.action.STATUS_PING";
    private static final String ACTION_STATUS_PONG = "com.jin.freeze.action.STATUS_PONG";
    private static final int REQUEST_CODE = 0x51F;
    private static final int MAX_ATTEMPTS = 240;

    private static Context appContext;
    private static Handler handler;
    private static boolean screenRegistered;
    private static boolean alarmRegistered;
    private static boolean statusRegistered;

    /** 当前是否处于锁屏状态（灭屏后、解锁前） */
    private static volatile boolean locked;
    /** 尚未触发的冻结闹钟时间点（elapsedRealtime），0 表示没有 */
    private static volatile long pendingAt;

    private static final BroadcastReceiver SCREEN_RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                onUnlocked();
            }
        }
    };

    private static final BroadcastReceiver ALARM_RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_ALARM.equals(intent.getAction())) {
                return;
            }
            if (!trustedSender(this)) {
                android.util.Log.w(TAG, "忽略非系统发送的冻结闹钟广播");
                return;
            }
            onAlarmFired();
        }
    };

    private LockFreezeHook() {
    }

    /** 设置页探测模块是否在线：收到 ping 回 pong，只用于权限行展示。 */
    private static final BroadcastReceiver STATUS_RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_STATUS_PING.equals(intent.getAction())) {
                return;
            }
            Intent reply = new Intent(ACTION_STATUS_PONG)
                    .setPackage("com.jin.freeze")
                    // 顺带告诉 App：预启动动画的白/黑底板已被透明化，冷启动不用再套实色遮罩
                    // （键名与 App 侧 SwipeFreeze.EXTRA_PRESTARTUP_FIXED 一致，模块内不引用 App 类）
                    .putExtra("prestartup_fixed", PreStartupHook.installed)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(reply);
        }
    };

    static void install(ClassLoader classLoader, HookKit kit) {
        Context context = systemContext(kit);
        if (context == null) {
            kit.log("锁屏冻结：取不到 system Context，放弃安装");
            return;
        }
        appContext = context;
        handler = new Handler(Looper.getMainLooper());
        // onSystemServerStarting 阶段 IActivityManager 还没 publish，直接注册会 NPE，
        // 轮询到 AMS 就绪后再注册（与划卡冻结同一时机）。
        registerWhenReady(kit, 0);
    }

    private static void registerWhenReady(HookKit kit, int attempt) {
        if (amsPublished() && tryRegister(kit)) {
            kit.log("锁屏冻结已注册");
            return;
        }
        if (attempt >= MAX_ATTEMPTS) {
            kit.log("锁屏冻结：等待 AMS 就绪超时，未注册");
            return;
        }
        handler.postDelayed(() -> registerWhenReady(kit, attempt + 1), 500);
    }

    private static boolean tryRegister(HookKit kit) {
        if (!screenRegistered) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                // 灭屏/解锁是系统受保护广播；显式给 EXPORTED 兼容 targetSdk 34+ 的注册要求
                appContext.registerReceiver(SCREEN_RECEIVER, filter, null, handler,
                        Context.RECEIVER_EXPORTED);
                screenRegistered = true;
            } catch (Throwable t) {
                kit.log("注册屏幕广播失败: " + t);
            }
        }
        if (!alarmRegistered) {
            try {
                // 闹钟广播由系统（uid 1000）发出，跨 uid 投递要求显式 EXPORTED
                appContext.registerReceiver(ALARM_RECEIVER,
                        new IntentFilter(ACTION_ALARM), null, handler,
                        Context.RECEIVER_EXPORTED);
                alarmRegistered = true;
            } catch (Throwable t) {
                kit.log("注册闹钟广播失败: " + t);
            }
        }
        if (!statusRegistered) {
            try {
                appContext.registerReceiver(STATUS_RECEIVER,
                        new IntentFilter(ACTION_STATUS_PING), null, handler,
                        Context.RECEIVER_EXPORTED);
                statusRegistered = true;
            } catch (Throwable t) {
                kit.log("注册状态广播失败: " + t);
            }
        }
        return screenRegistered && alarmRegistered && statusRegistered;
    }

    /** ActivityManager.getService() 是 @hide，反射取；非空说明 IActivityManager 已 publish。 */
    private static boolean amsPublished() {
        try {
            Object service = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
            return service != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void onScreenOff() {
        locked = true;
        JinHookConfig.Snapshot snap = JinHookConfig.snapshot();
        boolean on = snap != null && snap.lockEnabled;
        int delay = snap == null ? 0 : snap.lockDelaySeconds;
        if (!on) {
            cancelAlarm("锁屏冻结开关关闭");
            return;
        }
        if (delay <= 0) {
            // 「立即冻结」档：锁屏即冻结，不设闹钟
            cancelAlarm("立即档，无需闹钟");
            freezeAll("lock-immediate");
            return;
        }
        scheduleAlarm(delay);
    }

    private static void onUnlocked() {
        locked = false;
        cancelAlarm("解锁取消未到点的定时冻结");
    }

    private static void onAlarmFired() {
        pendingAt = 0L;
        JinHookConfig.Snapshot snap = JinHookConfig.snapshot();
        if (snap == null || !snap.lockEnabled) {
            return;
        }
        if (!locked) {
            return;
        }
        freezeAll("lock-timeout");
    }

    private static void freezeAll(String reason) {
        List<String> packages = JinHookConfig.freezablePackages();
        if (packages.isEmpty()) {
            return;
        }
        JinFreeze.freezeAll(packages, reason);
    }

    private static void scheduleAlarm(int seconds) {
        AlarmManager alarmManager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime() + seconds * 1000L;
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt, alarmPendingIntent());
            pendingAt = triggerAt;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "设置精确闹钟失败", t);
        }
    }

    private static void cancelAlarm(String reason) {
        if (pendingAt == 0L) {
            return;
        }
        pendingAt = 0L;
        AlarmManager alarmManager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        try {
            alarmManager.cancel(alarmPendingIntent());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "取消闹钟失败", t);
        }
    }

    private static PendingIntent alarmPendingIntent() {
        Intent intent = new Intent(ACTION_ALARM).setPackage(appContext.getPackageName());
        return PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 只接受系统（ui 1000）或本进程发出的广播。 */
    private static boolean trustedSender(BroadcastReceiver receiver) {
        try {
            Object uid = BroadcastReceiver.class.getMethod("getSendingUid").invoke(receiver);
            if (!(uid instanceof Integer)) {
                return true;
            }
            int value = (Integer) uid;
            if (value < 0) {
                return true;
            }
            return value == 1000 || value == android.os.Process.myUid();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 反射取 ActivityThread.getSystemContext()：system_server 里常驻的 Context。 */
    private static Context systemContext(HookKit kit) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object current = activityThread.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Method method =
                    activityThread.getDeclaredMethod("getSystemContext");
            method.setAccessible(true);
            return (Context) method.invoke(current);
        } catch (Throwable t) {
            kit.log("反射获取 system Context 失败: " + t);
            return null;
        }
    }
}
