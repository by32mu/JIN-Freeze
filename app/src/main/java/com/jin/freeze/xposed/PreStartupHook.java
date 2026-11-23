package com.jin.freeze.xposed;

import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 预启动底板透明化（system_server 内）。
 *
 * HyperOS 从桌面图标启动应用时，会先播一段「预启动动画」，并在应用窗口下方铺两块实色底板：
 * - 「Pre Starting surface」：{@code PreStartupWindowController.writeSolidColorOnSurface} 上色；
 * - 任务里的「preSplashStarting」：{@code PreStartingManager.makePreColorIfNeeded} 建色层，
 *   颜色取不到缓存时默认日间纯白、夜间纯黑。
 * 凚 的页面是透明窗口，半透明背景会把这层白/黑透出来，观感就是「先闪一块白底，再变成设置的透明度」。
 *
 * 处理方式：只把这两块 Surface 的 alpha 压成 0，**不改颜色**
 * （改成透明色时系统会按不透明黑铺开，反而变黑底）。
 *
 * 这个 hook 只跟「消除启动白底」开关走，不干预半屏的任何过渡动画。
 */
final class PreStartupHook {

    private static final String TAG = "JinPreStartup";
    private static final String PKG = "com.jin.freeze";
    private static final String CONTROLLER = "com.android.server.wm.PreStartupWindowController";
    private static final String MANAGER = "com.android.server.wm.PreStartingManager";

    /** makePreColorIfNeeded 里创建的那块色层：由 updatePreSurfaceLayerZ 顺手带进来。 */
    private static final ThreadLocal<SurfaceControl> PRE_COLOR_SURFACE = new ThreadLocal<>();

    /** hook 是否装上：随状态探测的 pong 回给 App，装上时页面不再需要「冷启动遮罩」。 */
    static volatile boolean installed;

    private PreStartupHook() {
    }

    static void install(ClassLoader classLoader, HookKit kit) {
        boolean color = hookPreColor(classLoader, kit);
        boolean layer = hookUpdateLayerZ(classLoader, kit);
        boolean surface = hookSolidColor(classLoader, kit);
        installed = color && layer && surface;
        kit.log("预启动底板：色层=" + color + "，层级=" + layer + "，实色=" + surface
                + (installed ? "（已透明化）" : "（未完全生效）"));
    }

    /** 「preSplashStarting」色层：建好后把整层 alpha 压成 0。 */
    private static boolean hookPreColor(ClassLoader classLoader, HookKit kit) {
        Class<?> manager = findClass(MANAGER, classLoader);
        if (manager == null) {
            kit.log("找不到 " + MANAGER);
            return false;
        }
        boolean ok = false;
        for (Method method : manager.getDeclaredMethods()) {
            if (!"makePreColorIfNeeded".equals(method.getName())
                    || method.getParameterCount() != 4) {
                continue;
            }
            ok |= kit.hook(method, (thisObj, args, proceed) -> {
                if (!JinHookConfig.noLaunchBg() || !isSelfActivity(args)) {
                    return proceed.run();
                }
                PRE_COLOR_SURFACE.remove();
                try {
                    return proceed.run();
                } finally {
                    SurfaceControl surface = PRE_COLOR_SURFACE.get();
                    PRE_COLOR_SURFACE.remove();
                    hide(surface);
                }
            });
        }
        return ok;
    }

    /** 捕获 makePreColorIfNeeded 传给 updatePreSurfaceLayerZ 的那块色层。 */
    private static boolean hookUpdateLayerZ(ClassLoader classLoader, HookKit kit) {
        Class<?> manager = findClass(MANAGER, classLoader);
        if (manager == null) {
            return false;
        }
        boolean ok = false;
        for (Method method : manager.getDeclaredMethods()) {
            if (!"updatePreSurfaceLayerZ".equals(method.getName())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 5 || types[2] != SurfaceControl.class) {
                continue;
            }
            ok |= kit.hook(method, (thisObj, args, proceed) -> {
                if (PRE_COLOR_SURFACE.get() == null && args != null
                        && args[2] instanceof SurfaceControl) {
                    PRE_COLOR_SURFACE.set((SurfaceControl) args[2]);
                }
                return proceed.run();
            });
        }
        return ok;
    }

    /** 「Pre Starting surface」的实色：本应用写完底色后把整层 alpha 压成 0。 */
    private static boolean hookSolidColor(ClassLoader classLoader, HookKit kit) {
        Class<?> controller = findClass(CONTROLLER, classLoader);
        if (controller == null) {
            kit.log("找不到 " + CONTROLLER);
            return false;
        }
        boolean ok = false;
        for (Method method : controller.getDeclaredMethods()) {
            if (!"writeSolidColorOnSurface".equals(method.getName())) {
                continue;
            }
            ok |= kit.hook(method, (thisObj, args, proceed) -> {
                Object result = proceed.run();
                if (JinHookConfig.noLaunchBg() && isSelfArgs(args)) {
                    hide(findSurface(args));
                }
                return result;
            });
        }
        return ok;
    }

    /** makePreColorIfNeeded 的首参是 ActivityRecord，按包名判断是不是本应用。 */
    private static boolean isSelfActivity(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return false;
        }
        Object record = args[0];
        try {
            for (Class<?> c = record.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField("packageName");
                    field.setAccessible(true);
                    Object value = field.get(record);
                    if (value instanceof String) {
                        return PKG.equals(value);
                    }
                } catch (NoSuchFieldException ignored) {
                    // 继续往父类找
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的 toString 兜底
        }
        return record.toString().contains(PKG);
    }

    /** 参数里带包名的那次调用才处理，避免影响别的应用。 */
    private static boolean isSelfArgs(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof String && ((String) arg).contains(PKG)) {
                return true;
            }
        }
        return false;
    }

    private static SurfaceControl findSurface(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof SurfaceControl) {
                return (SurfaceControl) arg;
            }
        }
        return null;
    }

    /** 只压 alpha：颜色、圆角、层级全部保持系统原样。 */
    private static void hide(SurfaceControl surface) {
        if (surface == null) {
            return;
        }
        try {
            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            transaction.setAlpha(surface, 0f);
            transaction.apply();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "压预启动底板透明失败: " + t);
        }
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
