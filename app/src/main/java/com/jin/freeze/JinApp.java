package com.jin.freeze;

import android.app.Application;

import com.jin.freeze.core.ShortcutSync;

/** 应用入口：启动时补发动态快捷方式（静态已声明时不重复）。 */
public class JinApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ShortcutSync.publish(this);
    }
}
