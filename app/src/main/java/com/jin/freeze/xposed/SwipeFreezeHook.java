package com.jin.freeze.xposed;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 划卡冻结的 Hook 安装点。
 *
 * Hook 点（已在 Android 17 / HyperOS 实测）：
 * - ActivityTaskManagerService.removeTask / removeAllVisibleRecentTasks（AOSP 入口）
 * - ActivityTaskSupervisor.removeTask(Task...)：MIUI 划卡走
 *   ProcessSceneCleaner.handleSwipeKill -> WindowProcessUtils.removeTask ->
 *   ActivityTaskSupervisor.removeTask，不经过 ATMS.removeTask，必须补这个点。
 *
 * 是否生效完全由 /data/system/jin_freeze_list.json 决定（enabled + 名单 + 标签），
 * 所以 Hook 总是装上，开关变化不需要重新注入。
 */
final class SwipeFreezeHook {

    private static final String TAG = "JinSwipeFreeze";
    private static final String ATMS = "com.android.server.wm.ActivityTaskManagerService";
    private static final String ATSS = "com.android.server.wm.ActivityTaskSupervisor";

    private static volatile boolean installed;

    private SwipeFreezeHook() {
    }

    static synchronized void install(ClassLoader classLoader, HookKit kit) {
        if (installed) {
            return;
        }
        installed = true;
        writeHeartbeat(kit);

        Class<?> atms = findClass(ATMS, classLoader);
        if (atms == null) {
            kit.log("找不到 " + ATMS);
        } else {
            int count = 0;
            for (Method method : atms.getDeclaredMethods()) {
                String name = method.getName();
                if ("removeTask".equals(name) || "removeAllVisibleRecentTasks".equals(name)) {
                    if ("removeTask".equals(name)) {
                        hookRemoveTask(kit, method);
                    } else {
                        hookRemoveAll(kit, method);
                    }
                    count++;
                }
            }
            kit.log("ATMS 候选 Hook: " + count);
        }
        hookSupervisorRemoveTask(classLoader, kit);
        kit.log("Hook 安装完成");
    }

    private static void hookSupervisorRemoveTask(ClassLoader classLoader, HookKit kit) {
        Class<?> clazz = findClass(ATSS, classLoader);
        if (clazz == null) {
            kit.log("找不到 " + ATSS + "，只依赖 ATMS Hook");
            return;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"removeTask".equals(method.getName())) {
                continue;
            }
            boolean taskParam = false;
            for (Class<?> type : method.getParameterTypes()) {
                if (type.getName().endsWith(".Task")) {
                    taskParam = true;
                    break;
                }
            }
            if (!taskParam) {
                continue;
            }
            kit.hook(method, (thisObj, args, proceed) -> {
                Object task = null;
                if (args != null) {
                    for (Object arg : args) {
                        if (arg != null && arg.getClass().getName().endsWith(".Task")) {
                            task = arg;
                            break;
                        }
                    }
                }
                JinRecents.TaskInfo info = JinRecents.packageOfTask(task);
                boolean home = JinRecents.isHomeTask(task);
                String reason = removalReason();
                Object result = proceed.run();
                if (reason != null && !home && info != null) {
                    if (JinHookConfig.shouldFreeze(info.pkg)) {
                        JinFreeze.freeze(info.pkg, reason, info.userId);
                    }
                }
                return result;
            });
        }
    }

    private static void hookRemoveTask(HookKit kit, Method method) {
        kit.hook(method, (thisObj, args, proceed) -> {
            JinRecents.TaskInfo info = taskInfoFromArgs(thisObj, args);
            Object result = proceed.run();
            boolean success = !(result instanceof Boolean) || (Boolean) result;
            if (success && info != null) {
                if (JinHookConfig.shouldFreeze(info.pkg)) {
                    JinFreeze.freeze(info.pkg, "swipe", info.userId);
                }
            }
            return result;
        });
    }

    private static void hookRemoveAll(HookKit kit, Method method) {
        kit.hook(method, (thisObj, args, proceed) -> {
            // 删除后任务对象就查不到了，先快照
            List<JinRecents.TaskInfo> tasks = JinRecents.snapshotRecentTasks(thisObj);
            Object result = proceed.run();
            List<String> listed = new ArrayList<>();
            for (JinRecents.TaskInfo info : tasks) {
                if (JinHookConfig.shouldFreeze(info.pkg)) {
                    listed.add(info.pkg);
                }
            }
            if (!listed.isEmpty()) {
                JinFreeze.freezeAll(listed, "clear-all");
            }
            return result;
        });
    }

    /** ROM 上有 removeTask(int)、(int,String)、(Task,String) 等多个重载，参数里可能是 taskId，也可能直接是 Task。 */
    private static JinRecents.TaskInfo taskInfoFromArgs(Object atms, Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg != null && arg.getClass().getName().endsWith(".Task")) {
                    JinRecents.TaskInfo info = JinRecents.packageOfTask(arg);
                    if (info != null) {
                        return info;
                    }
                }
            }
        }
        Integer taskId = JinRecents.taskIdOf(args);
        if (taskId == null) {
            return null;
        }
        return JinRecents.packageOfTask(atms, taskId);
    }

    /**
     * 用调用栈区分场景：划卡 / 一键清理 才算，应用自己 finishAndRemoveTask 之类的内部移除不冻。
     */
    private static String removalReason() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : stack) {
            if (e.getClassName().contains("ProcessSceneCleaner")
                    || "handleSwipeKill".equals(e.getMethodName())) {
                return "swipe";
            }
        }
        for (StackTraceElement e : stack) {
            if ("removeAllVisibleRecentTasks".equals(e.getMethodName())) {
                return "clear-all";
            }
        }
        for (StackTraceElement e : stack) {
            if (e.getClassName().endsWith("ActivityTaskManagerService")
                    && e.getMethodName().startsWith("removeTask")) {
                return "swipe";
            }
        }
        return null;
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 心跳：凚 用它判断「这次开机后 hook 确实进了 system_server」，也是开关提示的判断依据。 */
    private static void writeHeartbeat(HookKit kit) {
        try {
            File file = new File(JinHookConfig.HEARTBEAT_PATH);
            String content = "1 " + System.currentTimeMillis();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, false);
            kit.log("心跳写入成功: " + content);
        } catch (Throwable t) {
            kit.log("心跳写入失败: " + t);
        }
    }
}
