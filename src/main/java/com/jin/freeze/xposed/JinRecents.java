package com.jin.freeze.xposed;

import android.content.ComponentName;
import android.content.Intent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 system_server 的最近任务结构里取包名 / userId。
 * 不同版本字段名有差异，按「先方法后字段」逐级回退，取不到就返回 null（调用方不冻结）。
 */
final class JinRecents {

    static final class TaskInfo {
        final String pkg;
        final int userId;

        TaskInfo(String pkg, int userId) {
            this.pkg = pkg;
            this.userId = userId;
        }
    }

    private JinRecents() {
    }

    static Integer taskIdOf(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        return null;
    }

    static Object findTask(Object atms, int taskId) {
        if (atms == null) {
            return null;
        }
        List<Object> containers = new ArrayList<>();
        Object supervisor = field(atms, "mTaskSupervisor");
        addIfNotNull(containers, field(atms, "mRecentTasks"));
        addIfNotNull(containers, field(supervisor, "mRecentTasks"));
        for (Object container : containers) {
            Object task = invoke(container, "getTask", taskId);
            if (task == null) {
                task = invoke(container, "getTask", taskId, 0);
            }
            if (task != null) {
                return task;
            }
            Object tasks = field(container, "mTasks");
            if (tasks instanceof List) {
                for (Object candidate : (List<?>) tasks) {
                    Integer id = taskIdOfTask(candidate);
                    if (id != null && id == taskId) {
                        return candidate;
                    }
                }
            }
        }
        Object list = invoke(supervisor, "getTasks");
        if (list instanceof List) {
            for (Object candidate : (List<?>) list) {
                Integer id = taskIdOfTask(candidate);
                if (id != null && id == taskId) {
                    return candidate;
                }
            }
        }
        Object root = field(atms, "mRootWindowContainer");
        Object task = invoke(root, "anyTaskForId", taskId);
        if (task == null) {
            task = invoke(root, "anyTaskForId", taskId, 0);
        }
        if (task == null) {
            task = invoke(root, "anyTaskForId", taskId, true);
        }
        return task;
    }

    static TaskInfo packageOfTask(Object atms, int taskId) {
        Object task = findTask(atms, taskId);
        if (task == null) {
            return null;
        }
        return packageOfTask(task);
    }

    static TaskInfo packageOfTask(Object task) {
        if (task == null || isHomeTask(task)) {
            return null;
        }
        Intent intent = baseIntent(task);
        String pkg = null;
        if (intent != null && intent.getComponent() != null) {
            pkg = intent.getComponent().getPackageName();
        }
        if (pkg == null) {
            Object real = field(task, "mRealActivity");
            if (real instanceof ComponentName) {
                pkg = ((ComponentName) real).getPackageName();
            }
        }
        if (pkg == null) {
            Object real = field(task, "realActivity");
            if (real instanceof ComponentName) {
                pkg = ((ComponentName) real).getPackageName();
            }
        }
        if (pkg == null && intent != null) {
            pkg = intent.getPackage();
        }
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        return new TaskInfo(pkg, userIdOf(task));
    }

    /** 删除前快照：删完任务对象就散了。 */
    static List<TaskInfo> snapshotRecentTasks(Object atms) {
        Map<String, TaskInfo> result = new LinkedHashMap<>();
        for (Object task : recentTaskList(atms)) {
            TaskInfo info = packageOfTask(task);
            if (info != null && !result.containsKey(info.pkg)) {
                result.put(info.pkg, info);
            }
        }
        return new ArrayList<>(result.values());
    }

    /** WindowConfiguration.ACTIVITY_TYPE_HOME = 2，桌面任务不参与划卡冻结。 */
    static boolean isHomeTask(Object task) {
        Object type = field(task, "mActivityType");
        if (!(type instanceof Integer)) {
            type = invoke(task, "getActivityType");
        }
        return type instanceof Integer && ((Integer) type) == 2;
    }

    private static List<Object> recentTaskList(Object atms) {
        List<Object> result = new ArrayList<>();
        if (atms == null) {
            return result;
        }
        Object supervisor = field(atms, "mTaskSupervisor");
        List<Object> containers = new ArrayList<>();
        addIfNotNull(containers, field(atms, "mRecentTasks"));
        addIfNotNull(containers, field(supervisor, "mRecentTasks"));
        for (Object container : containers) {
            Object tasks = field(container, "mTasks");
            if (tasks instanceof List && !((List<?>) tasks).isEmpty()) {
                for (Object task : (List<?>) tasks) {
                    if (task != null) {
                        result.add(task);
                    }
                }
                return result;
            }
            Object size = invoke(container, "size");
            if (!(size instanceof Integer) || ((Integer) size) <= 0) {
                continue;
            }
            for (int i = 0; i < (Integer) size; i++) {
                Object task = invoke(container, "get", i);
                if (task != null) {
                    result.add(task);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        Object list = invoke(supervisor, "getTasks");
        if (list instanceof List) {
            for (Object task : (List<?>) list) {
                if (task != null) {
                    result.add(task);
                }
            }
        }
        return result;
    }

    private static Intent baseIntent(Object task) {
        Object value = invoke(task, "getBaseIntent");
        if (value instanceof Intent) {
            return (Intent) value;
        }
        String[] names = {"mBaseIntent", "intent", "mIntent"};
        for (String name : names) {
            value = field(task, name);
            if (value instanceof Intent) {
                return (Intent) value;
            }
        }
        return null;
    }

    private static int userIdOf(Object task) {
        Object value = field(task, "mUserId");
        if (!(value instanceof Integer)) {
            value = field(task, "userId");
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        try {
            Class<?> am = Class.forName("android.app.ActivityManager");
            return (Integer) am.getMethod("getCurrentUser").invoke(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Integer taskIdOfTask(Object task) {
        if (task == null) {
            return null;
        }
        Object value = field(task, "mTaskId");
        if (!(value instanceof Integer)) {
            value = field(task, "taskId");
        }
        return value instanceof Integer ? (Integer) value : null;
    }

    private static void addIfNotNull(List<Object> list, Object value) {
        if (value != null) {
            list.add(value);
        }
    }

    static Object field(Object instance, String name) {
        if (instance == null) {
            return null;
        }
        Class<?> clazz = instance.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(instance);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    static Object invoke(Object instance, String name, Object... args) {
        if (instance == null) {
            return null;
        }
        for (Method method : instance.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                continue;
            }
            if (!matches(method.getParameterTypes(), args)) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(instance, args);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean matches(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object arg = args[i];
            if (arg == null) {
                if (type.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (type.isPrimitive()) {
                if (type == int.class && !(arg instanceof Integer)) return false;
                if (type == boolean.class && !(arg instanceof Boolean)) return false;
                if (type == long.class && !(arg instanceof Long)) return false;
                if (type == float.class && !(arg instanceof Float)) return false;
                if (type == double.class && !(arg instanceof Double)) return false;
            } else if (!type.isInstance(arg)) {
                return false;
            }
        }
        return true;
    }
}
