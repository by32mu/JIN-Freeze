package com.jin.freeze;

import android.app.Application;

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

    @Override
    public void onCreate() {
        super.onCreate();
        ShortcutSync.publish(this);
    }
}
