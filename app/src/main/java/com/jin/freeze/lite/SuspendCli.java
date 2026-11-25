package com.jin.freeze.lite;

import java.lang.reflect.Method;

/**
 * root 通道的暂停 CLI：由凚用 su 起一个短命 app_process 进程（uid 0），
 * 调 IPackageManager.setPackagesSuspendedAsUser 并带 SuspendDialogInfo，
 * 这样只有 root（没有 Shizuku）时系统弹窗也有「打开应用」按钮。
 *
 * 用法：
 * su -c "CLASSPATH=<apk> app_process /system/bin com.jin.freeze.lite.SuspendCli <pkg> <userId> <labelBase64> [u] [suspender]"
 * 输出：JIN_SUSPEND_OK / JIN_SUSPEND_ERR:原因
 *
 * suspender 默认 "root"：与 pm 命令的身份一致，之后 pm unsuspend 才能撤销
 *（系统按 suspendingPackage 匹配，不匹配就不给撤）。
 */
public final class SuspendCli {

    private static final String DEFAULT_SUSPENDER = "root";
    private static final String SUSPEND_TITLE = "应用已被凚冻结";
    private static final String SUSPEND_BUTTON = "打开应用";
    private static final String SUSPEND_DIALOG_INFO = "android.content.pm.SuspendDialogInfo";

    private SuspendCli() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 2) {
            fail("args");
            return;
        }
        String pkg = args[0];
        int userId = parseInt(args[1]);
        String label = args.length > 2 ? decode(args[2]) : "";
        boolean suspended = args.length < 4 || !"u".equals(args[3]);
        String suspender = args.length > 4 && args[4] != null && !args[4].isEmpty()
                ? args[4] : DEFAULT_SUSPENDER;
        try {
            Object pm = packageManager();
            Method method = findSuspendedMethod(pm);
            if (method == null) {
                fail("no setPackagesSuspendedAsUser");
                return;
            }
            Object info = suspended ? buildSuspendDialogInfo(label) : null;
            Class<?>[] types = method.getParameterTypes();
            Object[] call = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Class<?> type = types[i];
                if (type == String[].class) {
                    call[i] = new String[]{pkg};
                } else if (type == boolean.class) {
                    call[i] = suspended;
                } else if (type == int.class) {
                    // 9 参：…, flags(0), suspender, suspendingUserId, targetUserId
                    call[i] = types.length >= 9 && i == 5 ? 0 : userId;
                } else if (SUSPEND_DIALOG_INFO.equals(type.getName())) {
                    call[i] = info;
                } else if (type == String.class) {
                    call[i] = suspender;
                } else {
                    call[i] = null;
                }
            }
            method.invoke(pm, call);
            System.out.println("JIN_SUSPEND_OK");
            System.out.flush();
        } catch (Throwable t) {
            fail(String.valueOf(t));
        }
    }

    private static void fail(String why) {
        System.out.println("JIN_SUSPEND_ERR:" + why);
        System.out.flush();
        System.exit(3);
    }

    private static Object packageManager() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "package");
        return Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder);
    }

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
    private static Object buildSuspendDialogInfo(String label) {
        try {
            Class<?> infoCls = Class.forName(SUSPEND_DIALOG_INFO);
            Class<?> builderCls = Class.forName(SUSPEND_DIALOG_INFO + "$Builder");
            Object builder = builderCls.getConstructor().newInstance();
            String name = label == null || label.isEmpty() ? "该应用" : label;
            builderCls.getMethod("setTitle", String.class).invoke(builder, SUSPEND_TITLE);
            builderCls.getMethod("setMessage", String.class)
                    .invoke(builder, name + "已被凚暂停，左下角打开应用。");
            builderCls.getMethod("setNeutralButtonText", String.class).invoke(builder, SUSPEND_BUTTON);
            int action = infoCls.getField("BUTTON_ACTION_UNSUSPEND").getInt(null);
            builderCls.getMethod("setNeutralButtonAction", int.class).invoke(builder, action);
            return builderCls.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String decode(String b64) {
        if (b64 == null || "-".equals(b64)) {
            // "-" 是「正文为空」的占位符，避免空参数在 shell 里消失导致后续参数前移
            return "";
        }
        try {
            return new String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
