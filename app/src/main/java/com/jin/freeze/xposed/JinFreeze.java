package com.jin.freeze.xposed;

import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    /** 自动冻结流水：system_server 直接写，凚 用 root 读进诊断包。 */
    static final String OPS_PATH = "/data/system/jin_freeze_ops.log";
    private static final Object OPS_LOCK = new Object();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jin-swipe-freeze");
        t.setDaemon(true);
        return t;
    });

    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static volatile Object ipm;

    private JinFreeze() {
    }

    /** 批量冻结：每条按自己的用户号执行；整批只落一次盘（1 行汇总 + 失败明细）。 */
    static void freezeAll(Collection<JinHookConfig.Instance> instances, String reason) {
        if (instances == null || instances.isEmpty()) {
            return;
        }
        final List<JinHookConfig.Instance> list = new ArrayList<>(instances);
        EXECUTOR.execute(() -> {
            int done = 0;
            List<String> failed = new ArrayList<>();
            for (JinHookConfig.Instance instance : list) {
                if (instance == null || instance.pkg == null || instance.pkg.isEmpty()
                        || SELF_PKG.equals(instance.pkg) || "android".equals(instance.pkg)) {
                    continue;
                }
                String key = instance.key();
                if (!IN_FLIGHT.add(key)) {
                    continue;
                }
                boolean ok;
                try {
                    ok = setDisabledUser(instance.pkg, instance.userId);
                } catch (Throwable t) {
                    ok = false;
                } finally {
                    IN_FLIGHT.remove(key);
                }
                if (ok) {
                    done++;
                } else {
                    failed.add(key);
                }
            }
            appendOps(reason, list.size(), done, failed);
        });
    }

    static void freeze(String pkg, String reason, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        if (SELF_PKG.equals(pkg) || "android".equals(pkg)) {
            return;
        }
        final String key = userId == 0 ? pkg : pkg + "@" + userId;
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        EXECUTOR.execute(() -> {
            boolean ok;
            try {
                ok = setDisabledUser(pkg, userId);
            } catch (Throwable t) {
                ok = false;
            } finally {
                IN_FLIGHT.remove(key);
            }
            appendOps(reason, 1, ok ? 1 : 0, ok ? null : Collections.singletonList(key));
        });
    }

    /** 追加一行自动冻结流水（批量时是汇总行），超过 48KB 时丢掉前半段。 */
    private static void appendOps(String reason, int total, int done, List<String> failed) {
        synchronized (OPS_LOCK) {
            try {
                File f = new File(OPS_PATH);
                if (f.length() > 48 * 1024) {
                    byte[] all = new byte[(int) f.length()];
                    FileInputStream in = new FileInputStream(f);
                    int off = 0;
                    while (off < all.length) {
                        int n = in.read(all, off, all.length - off);
                        if (n <= 0) {
                            break;
                        }
                        off += n;
                    }
                    in.close();
                    String text = new String(all, 0, off, StandardCharsets.UTF_8);
                    int cut = text.indexOf('\n', text.length() / 2);
                    if (cut > 0) {
                        text = text.substring(cut + 1);
                    }
                    FileOutputStream rewrite = new FileOutputStream(f, false);
                    rewrite.write(text.getBytes(StandardCharsets.UTF_8));
                    rewrite.close();
                }
                StringBuilder line = new StringBuilder();
                line.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date())).append(" | ").append(reason)
                        .append(" | 共 ").append(total).append("，成功 ").append(done);
                if (failed != null && !failed.isEmpty()) {
                    line.append("，失败: ").append(joinLimited(failed, 400));
                }
                line.append('\n');
                FileOutputStream out = new FileOutputStream(f, true);
                out.write(line.toString().getBytes(StandardCharsets.UTF_8));
                out.close();
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
            } catch (Throwable t) {
                android.util.Log.w("JinFreeze", "写自动冻结流水失败: " + t);
            }
        }
    }

    /** 拼失败包名，超长截断，避免单行过大。 */
    private static String joinLimited(List<String> list, int max) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() + s.length() + 1 > max) {
                sb.append("…");
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
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
}
