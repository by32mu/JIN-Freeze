package com.jin.freeze.lite;

import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shizuku UserService：以 shell(uid 2000) 身份常驻，负责「划卡冻结」与「锁屏冻结」。
 *
 * 实现与 Thanox lite 对齐：
 * 1. 划卡冻结：直接继承 android.app.TaskStackListener，用
 *    ActivityManager.getService().registerTaskStackListener(...) 注册；
 *    onTaskCreated / onTaskMovedToFront 维护 taskId → 包名映射，
 *    onTaskRemoved(taskId) 延时 200ms 后查表冻结（不解析 Parcel、不轮询、不查任务列表）。
 * 2. 锁屏冻结：服务进程内注册 SCREEN_OFF，按设定延时冻结。
 * 3. 冻结：进程 uid 已是 shell，直接调 IPackageManager 的
 *    setApplicationEnabledSetting / setPackagesSuspended，再 forceStopPackage，
 *    不再 fork pm 命令。
 * 4. 配置由凚主进程通过 AIDL 推过来；凚被冻结/杀进程不影响本服务。
 */
public class JinLiteService extends IJinLite.Stub {

    private static final String SELF_PKG = "com.jin.freeze";
    private static final long TASK_REMOVE_DELAY_MS = 200L;
    /** 与 SuspendDialog 一致：暂停弹窗的标题 / 正文 / 左下按钮。 */
    private static final String SUSPEND_TITLE = "应用已被凚冻结";
    private static final String SUSPEND_MESSAGE = "该应用已被凚暂停，左下角打开应用。";
    private static final String SUSPEND_BUTTON = "打开应用";
    private static final String SUSPEND_DIALOG_INFO = "android.content.pm.SuspendDialogInfo";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jin-lite-freeze");
        t.setDaemon(true);
        return t;
    });
    /** taskId → 包名：onTaskCreated/onTaskMovedToFront 时补全，onTaskRemoved 时查表。 */
    private final Map<Integer, String> taskPkgs =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private volatile Set<String> swipePkgs = Collections.emptySet();
    private volatile Set<String> lockPkgs = Collections.emptySet();
    private volatile Set<String> whitelist = Collections.emptySet();
    private volatile boolean swipeEnabled;
    private volatile boolean lockEnabled;
    private volatile int lockDelaySeconds;
    private volatile boolean useSuspend;
    /** 用户标记为隐藏的实例键：划卡/锁屏不冻结它们（隐藏与冻结相互独立）。 */
    private volatile Set<String> hiddenPkgs = Collections.emptySet();

    private volatile String screenStatus = "未注册";
    private volatile String listenerStatus = "未注册";
    private volatile String lastEvent = "";

    private Runnable pendingLockFreeze;
    /** 上一次屏幕点亮状态：照 Thanox DeviceStateLooper，每秒轮询 isInteractive 判灭屏。 */
    private volatile boolean lastInteractive = true;
    private final Runnable screenPoll = new Runnable() {
        @Override
        public void run() {
            boolean interactive = isInteractive();
            if (interactive != lastInteractive) {
                lastInteractive = interactive;
                android.util.Log.i("JinLite", "screen interactive=" + interactive);
                if (interactive) {
                    cancelPendingLockFreeze();
                } else {
                    onScreenOff();
                }
            }
            handler.postDelayed(this, 1000L);
        }
    };

    public JinLiteService() {
        this(currentApplication());
    }

    public JinLiteService(Context context) {
        this.context = context != null ? context : currentApplication();
        initScreenPoll();
        initTaskListener();
    }

    // ---------------- AIDL ----------------

    @Override
    public void syncConfig(String[] swipePkgs, String[] lockPkgs, String[] whitelist,
                           String[] hiddenPkgs,
                           boolean swipeEnabled, boolean lockEnabled, int lockDelaySeconds,
                           boolean useSuspend) {
        this.swipePkgs = toSet(swipePkgs);
        this.lockPkgs = toSet(lockPkgs);
        this.whitelist = toSet(whitelist);
        this.hiddenPkgs = toSet(hiddenPkgs);
        this.swipeEnabled = swipeEnabled;
        this.lockEnabled = lockEnabled;
        this.lockDelaySeconds = Math.max(0, Math.min(300, lockDelaySeconds));
        this.useSuspend = useSuspend;
        if (!lockEnabled && pendingLockFreeze != null) {
            handler.removeCallbacks(pendingLockFreeze);
            pendingLockFreeze = null;
        }
        lastEvent = "config:swipe=" + this.swipePkgs.size() + ",lock=" + this.lockPkgs.size()
                + ",delay=" + this.lockDelaySeconds + ",suspend=" + this.useSuspend;
        android.util.Log.i("JinLite", "syncConfig swipeOn=" + swipeEnabled + " lockOn=" + lockEnabled
                + " swipe=" + this.swipePkgs.size() + " lock=" + this.lockPkgs.size()
                + " delay=" + this.lockDelaySeconds);
    }

    @Override
    public String status() {
        return "listener=" + listenerStatus + ";screen=" + screenStatus
                + ";swipe=" + swipePkgs.size() + ";lock=" + lockPkgs.size()
                + ";delay=" + lockDelaySeconds + ";last=" + lastEvent;
    }

    // ---------------- 划卡冻结（照 Thanox） ----------------

    private void initTaskListener() {
        try {
            Object am = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
            if (am == null) {
                listenerStatus = "失败:无 ActivityManager";
                return;
            }
            Class<?> listenerCls = Class.forName("android.app.ITaskStackListener");
            TaskStackListener listener = new TaskStackListener() {
                @Override
                public void onTaskCreated(int taskId, ComponentName componentName) {
                    if (componentName != null && componentName.getPackageName() != null) {
                        taskPkgs.put(taskId, componentName.getPackageName());
                    }
                }

                @Override
                public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                    rememberTask(taskInfo);
                }

                @Override
                public void onTaskRemoved(int taskId) {
                    handleTaskRemoved(taskId);
                }
            };
            am.getClass().getMethod("registerTaskStackListener", listenerCls)
                    .invoke(am, listener);
            listenerStatus = "已注册";
            android.util.Log.i("JinLite", "registerTaskStackListener OK");
        } catch (Throwable t) {
            listenerStatus = "失败:" + t;
            android.util.Log.w("JinLite", "registerTaskStackListener 失败", t);
        }
    }

    private void rememberTask(ActivityManager.RunningTaskInfo info) {
        if (info == null) {
            return;
        }
        ComponentName cn = info.baseActivity != null ? info.baseActivity : info.topActivity;
        if (cn != null && cn.getPackageName() != null) {
            taskPkgs.put(info.taskId, cn.getPackageName());
        }
    }

    /** 划卡：taskId 已被系统移除，200ms 后按名单决定是否冻结（与 Thanox 一致）。 */
    private void handleTaskRemoved(int taskId) {
        String pkg = taskPkgs.remove(taskId);
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        final String target = pkg;
        handler.postDelayed(() -> {
            if (!swipeEnabled) {
                android.util.Log.i("JinLite", "划卡跳过（开关关）: " + target);
                return;
            }
            if (SELF_PKG.equals(target) || whitelist.contains(target)
                    || !swipePkgs.contains(target)) {
                android.util.Log.i("JinLite", "划卡跳过（不在名单/白名单）: " + target);
                return;
            }
            lastEvent = "task_removed:" + target;
            android.util.Log.i("JinLite", "划卡冻结: " + target);
            freezePkgs(Collections.singleton(target));
        }, TASK_REMOVE_DELAY_MS);
    }

    // ---------------- 锁屏冻结（照 Thanox：每秒轮询 isInteractive） ----------------

    private void initScreenPoll() {
        lastInteractive = isInteractive();
        screenStatus = "轮询中";
        handler.postDelayed(screenPoll, 1000L);
    }

    private boolean isInteractive() {
        try {
            if (context != null) {
                android.os.PowerManager pm = (android.os.PowerManager)
                        context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    return pm.isInteractive();
                }
            }
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "power");
            Object power = Class.forName("android.os.IPowerManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            return (Boolean) power.getClass().getMethod("isInteractive").invoke(power);
        } catch (Throwable t) {
            return true;
        }
    }

    private void onScreenOff() {
        if (!lockEnabled) {
            return;
        }
        final Set<String> targets = new HashSet<>(lockPkgs);
        if (targets.isEmpty()) {
            return;
        }
        cancelPendingLockFreeze();
        pendingLockFreeze = () -> {
            pendingLockFreeze = null;
            if (!lockEnabled) {
                return;
            }
            lastEvent = "screen_off:freeze=" + targets.size();
            freezePkgs(targets);
        };
        if (lockDelaySeconds <= 0) {
            handler.post(pendingLockFreeze);
        } else {
            handler.postDelayed(pendingLockFreeze, lockDelaySeconds * 1000L);
        }
    }

    private void cancelPendingLockFreeze() {
        if (pendingLockFreeze != null) {
            handler.removeCallbacks(pendingLockFreeze);
            pendingLockFreeze = null;
        }
    }

    // ---------------- 冻结执行（直接 Binder，不 fork pm） ----------------

    private void freezePkgs(Set<String> keys) {
        final boolean suspend = useSuspend;
        final Set<String> targets = new HashSet<>(keys);
        targets.remove(SELF_PKG);
        targets.removeAll(whitelist);
        if (targets.isEmpty()) {
            return;
        }
        io.execute(() -> {
            for (String key : targets) {
                int userId = userId(key);
                String pkg = packagePart(key);
                // 隐藏与冻结相互独立：用户标记隐藏的应用，划卡/锁屏不改它
                if (hiddenPkgs.contains(key) || hiddenPkgs.contains(pkg)) {
                    continue;
                }
                // 状态保持：已隐藏/暂停/停用的应用各按原状态，冻结入口不改变它们
                if (alreadyFrozen(pkg, userId)) {
                    continue;
                }
                android.util.Log.i("JinLite", "freeze pkg=" + pkg + " user=" + userId
                        + " suspend=" + (suspend && userId == 0));
                if (suspend && userId == 0) {
                    setSuspended(pkg, userId, true);
                } else {
                    setEnabled(pkg, userId, false);
                }
                forceStop(pkg, userId);
            }
        });
    }

    /** 已隐藏 / 已暂停 / 已停用：都属于「已经冻住」，划卡/锁屏不再重复处理。 */
    private boolean alreadyFrozen(String pkg, int userId) {
        try {
            Object pm = packageManager();
            Method info = findMethod(pm, "getApplicationInfo", 2, 3);
            Method state = findMethod(pm, "getApplicationEnabledSetting", 2, 3);
            if (info == null || state == null) {
                return false;
            }
            Object app = info.invoke(pm, adapt(new Object[]{pkg, MATCH_UNINSTALLED, userId},
                    info.getParameterTypes()));
            if (app == null) {
                return true; // 查不到说明已经不在可用状态，跳过
            }
            int enabled = (Integer) state.invoke(pm, adapt(new Object[]{pkg, userId},
                    state.getParameterTypes()));
            if (enabled != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    && enabled != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                return true;
            }
            int flags = readInt(app, "flags");
            if ((flags & FLAG_SUSPENDED) != 0) {
                return true;
            }
            return (readInt(app, "privateFlags") & FLAG_HIDDEN) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ApplicationInfo 的 int 字段（privateFlags 是隐藏字段，先找 public 字段再退到 getter）。 */
    private static int readInt(Object app, String name) {
        try {
            return app.getClass().getField(name).getInt(app);
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field field = app.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(app);
        } catch (Throwable ignored) {
        }
        try {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            return (Integer) app.getClass().getMethod(getter).invoke(app);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 隐藏应用模式的系统隐藏位（ApplicationInfo.privateFlags & 1）。 */
    private static final int FLAG_HIDDEN = 1;
    /** ApplicationInfo.FLAG_SUSPENDED：暂停冻结。 */
    private static final int FLAG_SUSPENDED = 1 << 30;
    /** 查隐藏应用必须带 MATCH_UNINSTALLED_PACKAGES，否则系统查不到被隐藏的包。 */
    private static final long MATCH_UNINSTALLED = 0x00002000L;

    /** IPackageManager.setApplicationEnabledSetting(pkg, state, 0, userId, callingPackage, 0)。 */
    private void setEnabled(String pkg, int userId, boolean enabled) {
        try {
            Object pm = packageManager();
            int state = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            Method method = findMethod(pm, "setApplicationEnabledSetting", 5, 6);
            if (method == null) {
                lastEvent = "无 setApplicationEnabledSetting";
                return;
            }
            method.invoke(pm, adapt(new Object[]{pkg, state, 0, userId, SELF_PKG, 0},
                    method.getParameterTypes()));
        } catch (Throwable t) {
            lastEvent = "setEnabled 失败:" + t;
            android.util.Log.w("JinLite", "setEnabled(" + pkg + ") 失败", t);
        }
    }

    /**
     * IPackageManager.setPackagesSuspendedAsUser(..., SuspendDialogInfo, ...)。
     *
     * 必须带 SuspendDialogInfo：不带时系统用默认弹窗，只有「确定」，
     * 左下角没有「打开应用」（中性按钮 + BUTTON_ACTION_UNSUSPEND）。
     * 参数按运行期真实签名摆放（7 参：…, info, callingPackage, userId；
     * 9 参：…, info, flags, suspendingPackage, suspendingUserId, targetUserId）。
     */
    private void setSuspended(String pkg, int userId, boolean suspended) {
        try {
            Object pm = packageManager();
            Method method = findSuspendedMethod(pm);
            if (method == null) {
                setEnabled(pkg, userId, false);
                return;
            }
            String name = method.getName();
            boolean asUser = "setPackagesSuspendedAsUser".equals(name);
            Object info = suspended ? buildSuspendDialogInfo() : null;
            // 本服务以 shell(2000) 或 root(0) 运行；root 用本应用包名，shell 必须报 com.android.shell
            String caller = android.os.Process.myUid() == 0 ? SELF_PKG : "com.android.shell";
            Class<?>[] types = method.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Class<?> type = types[i];
                if (type == String[].class) {
                    args[i] = new String[]{pkg};
                } else if (type == boolean.class) {
                    args[i] = suspended;
                } else if (type == int.class) {
                    // 9 参版本：i==5 是 flags，其余 userId 位置都填目标用户
                    args[i] = types.length >= 9 && i == 5 ? 0 : userId;
                } else if (SUSPEND_DIALOG_INFO.equals(type.getName())) {
                    args[i] = info;
                } else if (type == String.class) {
                    // 旧签名 setPackagesSuspended 的第 5 位是 dialogMessage，不是 caller
                    args[i] = !asUser && i == 4 ? SUSPEND_MESSAGE : caller;
                } else {
                    args[i] = null;
                }
            }
            method.invoke(pm, args);
        } catch (Throwable t) {
            lastEvent = "setSuspended 失败:" + t;
        }
    }

    /** 优先带 SuspendDialogInfo 的 AsUser 版本，没有才退回旧的 setPackagesSuspended。 */
    private static Method findSuspendedMethod(Object pm) {
        Method fallback = null;
        for (Method m : pm.getClass().getMethods()) {
            if ("setPackagesSuspendedAsUser".equals(m.getName())) {
                return m;
            }
            if ("setPackagesSuspended".equals(m.getName()) && fallback == null) {
                fallback = m;
            }
        }
        return fallback;
    }

    /** 反射构造系统暂停弹窗：标题 / 正文 / 左下「打开应用」+ 撤销暂停并启动。 */
    private static Object buildSuspendDialogInfo() {
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
            // 拿不到 SuspendDialogInfo 就退回系统默认弹窗，至少暂停仍然生效
            android.util.Log.w("JinLite", "SuspendDialogInfo 构造失败", t);
            return null;
        }
    }

    /** 停用/暂停不会立刻结束已有进程，补一次 force-stop（IActivityManager.forceStopPackage）。 */
    private void forceStop(String pkg, int userId) {
        try {
            Object am = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
            if (am == null) {
                return;
            }
            am.getClass().getMethod("forceStopPackage", String.class, int.class)
                    .invoke(am, pkg, userId);
        } catch (Throwable t) {
            lastEvent = "forceStop 失败:" + t;
            android.util.Log.w("JinLite", "forceStop(" + pkg + ") 失败", t);
        }
    }

    private static Object packageManager() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "package");
        return Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Method findMethod(Object target, String name, int min, int max) {
        for (Method method : target.getClass().getMethods()) {
            if (name.equals(method.getName())
                    && method.getParameterCount() >= min
                    && method.getParameterCount() <= max) {
                return method;
            }
        }
        return null;
    }

    private static Object[] adapt(Object[] raw, Class<?>[] types) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object value = i < raw.length ? raw[i] : 0;
            if (types[i] == int.class && value instanceof Integer) {
                args[i] = value;
            } else if (types[i] == long.class && value instanceof Number) {
                args[i] = ((Number) value).longValue();
            } else if (types[i] == boolean.class && value instanceof Boolean) {
                args[i] = value;
            } else if (value instanceof Integer && types[i] != int.class) {
                args[i] = value;
            } else {
                args[i] = value;
            }
        }
        return args;
    }

    private static int userId(String key) {
        int at = key.lastIndexOf('@');
        if (at > 0) {
            try {
                return Integer.parseInt(key.substring(at + 1).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String packagePart(String key) {
        int at = key.lastIndexOf('@');
        return at > 0 ? key.substring(0, at) : key;
    }

    private static Set<String> toSet(String[] arr) {
        Set<String> set = new HashSet<>();
        if (arr != null) {
            for (String s : arr) {
                if (s != null && !s.isEmpty()) {
                    set.add(s);
                }
            }
        }
        set.remove(SELF_PKG);
        return set;
    }

    /** 构造参数缺失时兜底：Shizuku 会优先调用 (Context) 构造，这里只防 null。 */
    private static Context currentApplication() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
