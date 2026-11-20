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
        // 结果提示改用悬浮胶囊（FrozenTip），需要「显示在其他应用上层」才能在后台上屏。
        // 本应用本就走 root/Shizuku，直接 appops 自授予，不用用户去设置里点；
        // MIUIOP(10021) 是 MIUI 的「后台弹出界面」，一并放开，否则后台悬浮窗仍被拦。
        IO.execute(() -> PrivShell.exec("appops set " + getPackageName()
                + " SYSTEM_ALERT_WINDOW allow"
                + "; appops set " + getPackageName() + " 10021 allow", 8));
    }
}
