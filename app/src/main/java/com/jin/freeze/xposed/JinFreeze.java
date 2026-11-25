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
    /** 暂停冻结用的 suspender：和 shell/pm 身份一致，App 侧 pm unsuspend 与静默打开都能撤销。 */
    private static final String SUSPEND_CALLER = "com.android.shell";
    private static final String SUSPEND_DIALOG_INFO = "android.content.pm.SuspendDialogInfo";
    private static final String SUSPEND_TITLE = "应用已被凚冻结";
    private static final String SUSPEND_MESSAGE = "该应用已被凚暂停，左下角打开应用。";
    private static final String SUSPEND_BUTTON = "打开应用";
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
                    ok = freezeOne(instance.pkg, instance.userId);
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
                ok = freezeOne(pkg, userId);
            } catch (Throwable t) {
                ok = false;
            } finally {
                IN_FLIGHT.remove(key);
            }
            appendOps(reason, 1, ok ? 1 : 0, ok ? null : Collections.singletonList(key));
        });
    }

    /**
     * 冻结一条：勾了「保留桌面图标（转暂停）」就用 suspend，否则保持原来的 disable-user。
     * 划卡/锁屏和列表必须同一种冻结方式，否则桌面图标会被停用摘掉。
     */
    private static boolean freezeOne(String pkg, int userId) {
        if (!JinHookConfig.suspendMode()) {
            return setDisabledUser(pkg, userId);
        }
        boolean ok = setSuspended(pkg, userId, true, SUSPEND_CALLER);
        // suspend 不会结束已有进程，补一次 force-stop 让冻结立即生效
        forceStop(pkg, userId);
        return ok;
    }

    /** 暂停不会杀进程，冻结后补一刀（IActivityManager.forceStopPackage）。 */
    private static void forceStop(String pkg, int userId) {
        try {
            Object am = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
            if (am != null) {
                am.getClass().getMethod("forceStopPackage", String.class, int.class)
                        .invoke(am, pkg, userId);
            }
        } catch (Throwable t) {
            android.util.Log.w("JinSwipeFreeze", "forceStop(" + pkg + ") 失败: " + t);
        }
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

    /** 活体解冻（DoL）：同步把应用恢复成正常状态，供 startActivity Hook 在放行前调用。 */
    static boolean unfreezeNow(String pkg, int userId) {
        if (pkg == null || pkg.isEmpty() || SELF_PKG.equals(pkg) || "android".equals(pkg)) {
            return false;
        }
        try {
            boolean enabled = setState(pkg, userId, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            // 保留桌面图标（suspend 冻结）时还要把挂起状态解除，否则启动仍会被系统拦下。
            // 优先走 system_server 内部的 PackageManagerInternal（不受 suspender 包名限制），
            // 拿不到再按可能的暂停方逐个走 binder（JinLite=shell、Shizuku=com.android.shell、CLI=root）。
            if (!unfreezeViaInternal(pkg, userId)) {
                for (String caller : new String[]{"root", "com.android.shell", SELF_PKG}) {
                    setSuspended(pkg, userId, false, caller);
                }
            }
            return enabled;
        } catch (Throwable t) {
            android.util.Log.w("JinSwipeFreeze", "DoL 解冻失败 " + pkg + ": " + t);
            return false;
        }
    }

    /** DoL 等待启用完成用：直读 IPackageManager 的组件启用状态。 */
    static boolean isEnabledNow(String pkg, int userId) {
        Object manager = packageManager();
        if (manager == null) {
            return false;
        }
        try {
            Method method = manager.getClass()
                    .getMethod("getApplicationEnabledSetting", String.class, int.class);
            int state = (Integer) method.invoke(manager, pkg, userId);
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * system_server 内部通道：LocalServices 拿到 PackageManagerInternal 直接调 setPackagesSuspended，
     * 不经过 binder 的调用方校验，也就不受「只能由 suspendingPackage 撤销」的限制。
     * 只在 system_server 进程里可用，拿不到就返回 false 让调用方走 binder。
     */
    private static boolean unfreezeViaInternal(String pkg, int userId) {
        try {
            Class<?> localServices = Class.forName("com.android.server.LocalServices");
            Class<?> pmi = Class.forName("android.content.pm.PackageManagerInternal");
            Object service = localServices.getMethod("getService", Class.class).invoke(null, pmi);
            if (service == null) {
                return false;
            }
            Method target = null;
            for (Method m : service.getClass().getMethods()) {
                if ("setPackagesSuspended".equals(m.getName())
                        && m.getParameterTypes().length >= 4) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                android.util.Log.w("JinSwipeFreeze", "PackageManagerInternal 没有 setPackagesSuspended");
                return false;
            }
            Class<?>[] types = target.getParameterTypes();
            int lastInt = -1;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) {
                    lastInt = i;
                }
            }
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Class<?> type = types[i];
                if (type == String[].class) {
                    args[i] = new String[]{pkg};
                } else if (type == boolean.class) {
                    args[i] = false;
                } else if (type == int.class) {
                    args[i] = i == lastInt ? userId : 0;
                } else if (type == String.class) {
                    args[i] = CALLER;
                } else {
                    args[i] = null;
                }
            }
            target.setAccessible(true);
            target.invoke(service, args);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("JinSwipeFreeze", "PackageManagerInternal 解暂停失败: " + t);
            return false;
        }
    }

    /** 反射构造系统暂停弹窗：标题 / 正文 / 左下「打开应用」+ 撤销暂停并启动。 */
    private static Object buildDialogInfo() {
        try {
            Class<?> infoCls = Class.forName(SUSPEND_DIALOG_INFO);
            Class<?> builderCls = Class.forName(SUSPEND_DIALOG_INFO + "$Builder");
            Object builder = builderCls.getConstructor().newInstance();
            builderCls.getMethod("setTitle", String.class).invoke(builder, SUSPEND_TITLE);
            builderCls.getMethod("setMessage", String.class).invoke(builder, SUSPEND_MESSAGE);
            builderCls.getMethod("setNeutralButtonText", String.class).invoke(builder, SUSPEND_BUTTON);
            int action = infoCls.getField("BUTTON_ACTION_UNSUSPEND").getInt(null);
            builderCls.getMethod("setNeutralButtonAction", int.class).invoke(builder, action);
            return builderCls.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            android.util.Log.w("JinSwipeFreeze", "SuspendDialogInfo 构造失败: " + t);
            return null;
        }
    }

    /** 解除/设置 suspend 冻结；签名随版本变化，按参数类型填充，失败只记日志。 */
    private static boolean setSuspended(String pkg, int userId, boolean suspended, String caller) {
        Object manager = packageManager();
        if (manager == null) {
            return false;
        }
        // 优先带 SuspendDialogInfo 的 AsUser 版本；没有才退回旧的 setPackagesSuspended
        if (invokeSetSuspended(manager, "setPackagesSuspendedAsUser", pkg, userId, suspended, caller)) {
            return true;
        }
        return invokeSetSuspended(manager, "setPackagesSuspended", pkg, userId, suspended, caller);
    }

    private static boolean invokeSetSuspended(Object manager, String methodName, String pkg,
                                              int userId, boolean suspended, String caller) {
        for (Method candidate : manager.getClass().getMethods()) {
            if (!methodName.equals(candidate.getName())) {
                continue;
            }
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length < 5 || types[0] != String[].class || types[1] != boolean.class) {
                continue;
            }
            int userIndex = -1;
            for (int i = types.length - 1; i >= 2; i--) {
                if (types[i] == int.class) {
                    userIndex = i;
                    break;
                }
            }
            if (userIndex < 0) {
                continue;
            }
            Object[] args = new Object[types.length];
            for (int i = 2; i < types.length; i++) {
                Class<?> type = types[i];
                if (type == int.class) {
                    // 9 参：i==5 是 flags，其余 int 是 suspendingUserId / targetUserId
                    args[i] = types.length >= 9 && i == 5 ? 0 : userId;
                } else if (type == String.class) {
                    args[i] = caller;
                } else if (SUSPEND_DIALOG_INFO.equals(type.getName())) {
                    // 暂停时要带弹窗信息，系统弹窗才有左下角「打开应用」
                    args[i] = suspended ? buildDialogInfo() : null;
                } else {
                    args[i] = null;
                }
            }
            args[0] = new String[]{pkg};
            args[1] = suspended;
            args[userIndex] = userId;
            try {
                candidate.setAccessible(true);
                candidate.invoke(manager, args);
                return true;
            } catch (Throwable t) {
                android.util.Log.w("JinSwipeFreeze", methodName + "(" + pkg + ", "
                        + caller + ") 失败: " + t);
            }
        }
        return false;
    }

    private static boolean setDisabledUser(String pkg, int userId) {
        return setState(pkg, userId, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
    }

    private static boolean setState(String pkg, int userId, int state) {
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
                state,
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
