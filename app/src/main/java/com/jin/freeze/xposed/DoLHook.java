package com.jin.freeze.xposed;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 活体解冻（DoL）：照抄 Thanox 的 ActivityStartHookRegistry。
 *
 * Hook 点：com.android.server.wm.ActivityStarter.executeRequest（hookBefore），
 * args[0] 是 ActivityStarter.Request，字段：intent / userId / resultTo / callingPackage / activityInfo。
 *
 * 逻辑（与 Thanox 一致）：
 * - activityInfo 已解析且带 FLAG_SUSPENDED（suspend 冻结）→ 清掉该标记后放行；
 * - activityInfo 为空（disable-user 冻结导致解析失败）→ 先启用应用，最多等 300ms，
 *   再调用 Request.resolveActivity(supervisor) 重新解析，让这次启动能继续。
 * 只解冻、不回冻，回冻交给划卡/锁屏两条通道。
 */
final class DoLHook {

    private static final String ACTIVITY_STARTER = "com.android.server.wm.ActivityStarter";
    private static final int FLAG_SUSPENDED = 0x40000000;
    private static final long ENABLE_TIMEOUT_MS = 300L;

    private DoLHook() {
    }

    static void install(ClassLoader classLoader, HookKit kit) {
        Class<?> starter = findClass(ACTIVITY_STARTER, classLoader);
        if (starter == null) {
            kit.log("DoL：找不到 " + ACTIVITY_STARTER);
            return;
        }
        Method target = findMostArgs(starter, "executeRequest");
        if (target == null) {
            kit.log("DoL：找不到 executeRequest");
            return;
        }
        kit.hook(target, (thisObj, args, proceed) -> {
            try {
                handle(thisObj, args, kit);
            } catch (Throwable t) {
                kit.log("DoL 异常: " + t);
            }
            return proceed.run();
        });
        kit.log("DoL Hook: executeRequest");
    }

    private static void handle(Object starterObj, Object[] args, HookKit kit) throws Exception {
        if (args == null || args.length == 0 || args[0] == null) {
            return;
        }
        if (!JinHookConfig.dolEnabled() && !JinHookConfig.silentOpenEnabled()) {
            return;
        }
        boolean silent = JinHookConfig.silentOpenEnabled();
        Object request = args[0];
        Intent intent = (Intent) getField(request, "intent");
        if (intent == null || intent.getComponent() == null) {
            return;
        }
        int userId = (Integer) getField(request, "userId");
        String pkg = intent.getComponent().getPackageName();
        List<Integer> users = JinHookConfig.listedUsers(pkg);
        if (users.isEmpty()) {
            return;
        }
        ActivityInfo activityInfo = (ActivityInfo) getField(request, "activityInfo");
        if (activityInfo != null && activityInfo.applicationInfo != null) {
            ApplicationInfo app = activityInfo.applicationInfo;
            if ((app.flags & FLAG_SUSPENDED) != 0) {
                // 只放行这一次：清掉启动用的 suspend 标记
                app.flags &= ~FLAG_SUSPENDED;
                kit.log("DoL 清除 suspend 标记: " + pkg);
                if (silent) {
                    // 静默打开：真正取消暂停，应用回后台也不会再自动暂停
                    int targetUser = users.contains(userId) ? userId : users.get(0);
                    JinFreeze.unfreezeNow(pkg, targetUser);
                    kit.log("静默打开解暂停: " + pkg);
                }
            }
            return;
        }
        // 解析不到 ActivityInfo：多半是 disable-user 冻结，先启用再重新解析
        int targetUser = users.contains(userId) ? userId : users.get(0);
        kit.log("DoL 解冻并重新解析: " + pkg + " user=" + targetUser);
        JinFreeze.unfreezeNow(pkg, targetUser);
        long deadline = SystemClock.elapsedRealtime() + ENABLE_TIMEOUT_MS;
        while (!JinFreeze.isEnabledNow(pkg, targetUser)
                && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20);
        }
        Object supervisor = getField(starterObj, "mSupervisor");
        Method resolve = null;
        for (Method method : request.getClass().getDeclaredMethods()) {
            if ("resolveActivity".equals(method.getName()) && method.getParameterCount() == 1) {
                resolve = method;
                break;
            }
        }
        if (resolve != null && supervisor != null) {
            resolve.setAccessible(true);
            resolve.invoke(request, supervisor);
            kit.log("DoL resolveActivity 完成: " + pkg);
        }
    }

    /** 同名重载取参数最多的那个（与 Thanox XposedHelpersExt.findMethodWithMostArgs 一致）。 */
    private static Method findMostArgs(Class<?> clazz, String name) {
        Method best = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            if (best == null || method.getParameterCount() > best.getParameterCount()) {
                best = method;
            }
        }
        return best;
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = null;
        Class<?> clazz = target.getClass();
        while (clazz != null && field == null) {
            try {
                field = clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        return field.get(target);
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
