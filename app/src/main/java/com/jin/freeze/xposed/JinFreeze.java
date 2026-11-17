package com.jin.freeze.xposed;

import android.content.pm.PackageManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 冻结实现：system_server 内直接调 IPackageManager.setApplicationEnabledSetting，
 * 等价于 root 执行 pm disable-user --user 0 <pkg>。
 */
final class JinFreeze {

    private static final String CALLER = "com.jin.freeze";
    private static final String SELF_PKG = "com.jin.freeze";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jin-swipe-freeze");
        t.setDaemon(true);
        return t;
    });

    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static volatile Object ipm;

    private JinFreeze() {
    }

    static void freezeAll(Collection<String> packages, String reason) {
        if (packages == null) {
            return;
        }
        for (String pkg : packages) {
            freeze(pkg, reason, currentUserId());
        }
    }

    static void freeze(String pkg, String reason, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        if (SELF_PKG.equals(pkg) || "android".equals(pkg)) {
            return;
        }
        if (!IN_FLIGHT.add(pkg)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                setDisabledUser(pkg, userId);
            } finally {
                IN_FLIGHT.remove(pkg);
            }
        });
    }

    private static Object packageManager() {
        Object local = ipm;
        if (local != null) {
            return local;
        }
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            local = appGlobals.getMethod("getPackageManager").invoke(null);
            ipm = local;
        } catch (Throwable t) {
            android.util.Log.w("JinSwipeFreeze", "获取 IPackageManager 失败", t);
        }
        return local;
    }

    private static boolean setDisabledUser(String pkg, int userId) {
        Object manager = packageManager();
        if (manager == null) {
            return false;
        }
        Method method = null;
        for (Method candidate : manager.getClass().getMethods()) {
            if ("setApplicationEnabledSetting".equals(candidate.getName())
                    && candidate.getParameterCount() >= 5
                    && candidate.getParameterCount() <= 6) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            android.util.Log.w("JinSwipeFreeze", "找不到 setApplicationEnabledSetting");
            return false;
        }
        Object[] raw = {
                pkg,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                0,
                userId,
                CALLER,
                0,
        };
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object value = i < raw.length ? raw[i] : 0;
            Class<?> type = types[i];
            if (type == long.class && value instanceof Number) {
                args[i] = ((Number) value).longValue();
            } else if (type == int.class && value instanceof Number) {
                args[i] = ((Number) value).intValue();
            } else {
                args[i] = value;
            }
        }
        try {
            method.setAccessible(true);
            method.invoke(manager, args);
            return true;
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException
                    ? ((InvocationTargetException) t).getTargetException() : t;
            android.util.Log.w("JinSwipeFreeze", "setApplicationEnabledSetting(" + pkg + ") 失败: "
                    + cause.getClass().getName() + ": " + cause.getMessage());
            return false;
        }
    }

    private static int currentUserId() {
        try {
            Class<?> am = Class.forName("android.app.ActivityManager");
            return (Integer) am.getMethod("getCurrentUser").invoke(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
