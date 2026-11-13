package com.jin.freeze.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 统一处理系统栏：让状态栏/导航栏背景跟随页面深浅色，并实时反色图标。
 * 凚只有半屏面板这一套界面，不再承载主体页面的任务隐藏逻辑；
 * 半屏是否留在最近任务由布局设置里的「隐藏半屏后台」开关决定。
 */
public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // 键盘弹出时按 ime 高度让位，页面内输入框不会被遮挡
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            v.setPadding(bars.left, bars.top, bars.right, bottom);
            return insets;
        });
        applyBarIconAppearance(window);
    }

    private void applyBarIconAppearance(Window window) {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
    }

    /** 供子类快速取应用上下文。 */
    protected Context ctx() {
        return getApplicationContext();
    }
}
