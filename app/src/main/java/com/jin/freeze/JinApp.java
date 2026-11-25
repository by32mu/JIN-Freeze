package com.jin.freeze;

import android.app.Application;

import com.jin.freeze.core.PrivShell;
import com.jin.freeze.core.ShortcutSync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 应用入口：启动时补发动态快捷方式（静态已声明时不重复）。 */
public class JinApp extends Application {

    /**
     * 进程内共用的后台线程：原来每个 Activity 各建一个单线程池，且线程非守护、
     * 永不回收，反复打开面板会累积线程。这里改成进程级单线程守护线程，
     * 语义仍是串行执行，只是不再随 Activity 重复创建。
     */
    public static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jin-io");
        t.setDaemon(true);
        return t;
    });

    /**
     * 点开应用专用的后台线程：列表刷新、图标加载都排在 IO 的串行队列里，
     * 点击后再排到它们后面会白等几百毫秒，所以解冻 + 拉起走这条独立队列。
     */
    public static final ExecutorService OPEN = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jin-open");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onCreate() {
        super.onCreate();
        ShortcutSync.publish(this);
        // 旧版 root 通道的暂停记录 suspender 写成 "root"，系统暂停弹窗点确定会让系统界面崩溃：
        // 升级后一次性按 shell 身份重建（只跑一次，没有暂停记录或不是 root 时直接跳过）
        IO.execute(() -> com.jin.freeze.core.SuspendSupport.repairLegacyRootSuspender(this));
        // MIUIOP(10017) 是 MIUI 的「桌面快捷方式」：只在小米设备且尚未允许时补授。
        // 不再让非小米设备、已授权设备以及每次快捷方式冷启动都白跑一次 su。
        if ("xiaomi".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                && !miuiShortcutAllowed()) {
            IO.execute(() -> PrivShell.exec("appops set " + getPackageName()
                    + " 10017 allow", 8));
        }
    }

    /** 直接读 AppOps 当前状态，避免为了确认权限再启动一次特权进程。 */
    private boolean miuiShortcutAllowed() {
        try {
            android.app.AppOpsManager am = (android.app.AppOpsManager)
                    getSystemService(APP_OPS_SERVICE);
            if (am == null) {
                return false;
            }
            java.lang.reflect.Method m = android.app.AppOpsManager.class.getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            int mode = (int) m.invoke(am, 10017,
                    android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) {
            // 读不到时保留原来的补授逻辑；实际添加快捷方式前还会再次检查。
            return false;
        }
    }
}
