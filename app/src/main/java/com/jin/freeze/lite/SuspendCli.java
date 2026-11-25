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
 * suspender 必须是真实安装的包名：SuspendedAppActivity 会用它
 * getResourcesForApplication()、上报表点击交互；写成 "root" 这类不存在的包会让
 * 系统界面进程直接崩溃（java.lang.IllegalArgumentException: Package root does not exist!）。
 * 默认用 com.android.shell：和 pm suspend 的身份一致，之后 pm unsuspend / Shell
 * 身份都能撤销。
 */
public final class SuspendCli {

    private static final String DEFAULT_SUSPENDER = "com.android.shell";
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
        // 批量模式：-b <base64(pkg|labelB64 每行一条)> <userId> [u] [suspender]
        // 列表很长时逐个起 app_process 太慢，一个进程处理完整批才不会「转不过去」
        if ("-b".equals(args[0]) && args.length >= 3) {
            batch(args);
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
            invokeOne(pm, method, pkg, userId, suspended, suspender, label);
            System.out.println("JIN_SUSPEND_OK");
            System.out.flush();
        } catch (Throwable t) {
            fail(String.valueOf(t));
        }
    }

    /** 批量暂停/撤销：一条命令处理整份名单，每个包仍带自己的弹窗文案。 */
    private static void batch(String[] args) {
        int userId = parseInt(args[2]);
        boolean suspended = args.length < 4 || !"u".equals(args[3]);
        String suspender = args.length > 4 && args[4] != null && !args[4].isEmpty()
                ? args[4] : DEFAULT_SUSPENDER;
        String payload = decode(args[1]);
        boolean allOk = true;
        try {
            Object pm = packageManager();
            Method method = findSuspendedMethod(pm);
            if (method == null) {
                fail("no setPackagesSuspendedAsUser");
                return;
            }
            for (String line : payload.split("\n")) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                int bar = line.indexOf('|');
                String pkg = bar >= 0 ? line.substring(0, bar).trim() : line.trim();
                String label = bar >= 0 ? decode(line.substring(bar + 1)) : "";
                if (pkg.isEmpty()) {
                    continue;
                }
                try {
                    invokeOne(pm, method, pkg, userId, suspended, suspender, label);
                    System.out.println("JIN_SUSPEND_OK:" + pkg);
                } catch (Throwable t) {
                    allOk = false;
                    System.out.println("JIN_SUSPEND_ERR:" + pkg + ":" + t);
                }
            }
            System.out.flush();
        } catch (Throwable t) {
            fail(String.valueOf(t));
        }
        if (!allOk) {
            System.exit(3);
        }
    }

    /** 调一次 setPackagesSuspendedAsUser；suspend 时带 SuspendDialogInfo（弹窗才有「打开应用」）。 */
    private static void invokeOne(Object pm, Method method, String pkg, int userId,
                                  boolean suspended, String suspender, String label) throws Exception {
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
