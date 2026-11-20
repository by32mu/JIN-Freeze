package com.jin.freeze.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「已冻结」/「无需冻结」提示：自绘小胶囊，和搞机助手那边同一套尺寸与配色，
 * 两个应用来回切换时看得出是一家的。
 * 系统 Toast 的字号、内边距、底色都改不了，所以这里用自定义 Toast 视图。
 * 底窗拿不到背后内容，做不了真实高斯模糊，用「半透明底色 + 一圈很淡的亮边」近似。
 */
public final class FrozenTip {

    /** 浅色模式：白色 85% 不透明，近黑文字。 */
    private static final int BG_LIGHT = 0xD9FFFFFF;
    private static final int TEXT_LIGHT = 0xFF1A1A1A;
    private static final int EDGE_LIGHT = 0x14000000;
    /** 深色模式：中性灰 70% 不透明，白字。 */
    private static final int BG_DARK = 0xB34A4F57;
    private static final int TEXT_DARK = 0xFFFFFFFF;
    private static final int EDGE_DARK = 0x33FFFFFF;

    private FrozenTip() {
    }

    public static void show(Context c) {
        show(c, "已冻结");
    }

    /** 同一套自绘提示，只换文案（例如「无需冻结」），字号/尺寸/位置/圆角/时长保持一致。 */
    public static void show(Context c, String text) {
        Context app = c.getApplicationContext();
        // Toast 构造要求当前线程有 Looper：快捷方式批量执行在工作线程里调到这里，
        // 直接 new Toast 会 NPE 崩溃（shortcut-batch 线程），统一投到主线程再建
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> showOnMain(app, text));
    }

    private static void showOnMain(Context app, String text) {
        Toast t = new Toast(app);
        t.setView(buildView(app, text));
        t.setDuration(Toast.LENGTH_SHORT);
        t.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(app, 128));
        t.show();
    }

    /**
     * 快捷方式批量结果提示：批量期间应用已 finish 退到后台，系统 Toast 会被丢掉，
     * 改用 TYPE_APPLICATION_OVERLAY 悬浮胶囊上屏；拿不到悬浮窗权限时退回 Toast。
     * 列表页里的提示仍走 {@link #show}（前台 Toast），不要用这个。
     */
    public static void showCapsule(Context c, String text) {
        Context app = c.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> showCapsuleOnMain(app, text));
    }

    private static void showCapsuleOnMain(Context app, String text) {
        TextView tv = buildView(app, text);
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(app, 128);
        try {
            wm.addView(tv, lp);
            tv.postDelayed(() -> {
                try {
                    wm.removeView(tv);
                } catch (Exception ignored) {
                }
            }, 2000);
            return;
        } catch (Exception e) {
            // 没有悬浮窗权限等：退回 Toast（仅应用在前台时可见）
        }

        Toast t = new Toast(app);
        t.setView(tv);
        t.setDuration(Toast.LENGTH_SHORT);
        t.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(app, 128));
        t.show();
    }

    private static TextView buildView(Context app, String text) {
        boolean night = (app.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        TextView tv = new TextView(app);
        tv.setText(text);
        // 与系统 Toast 同量级：14sp 正文、左右 16dp 上下 10dp，跟在别处看到的提示一样大
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(night ? TEXT_DARK : TEXT_LIGHT);
        // 纯文字：没有图标，文字在底板上居中
        tv.setGravity(Gravity.CENTER);
        int padH = dp(app, 16);
        int padV = dp(app, 10);
        tv.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? BG_DARK : BG_LIGHT);
        bg.setCornerRadius(dp(app, 22));
        bg.setStroke(dp(app, 1), night ? EDGE_DARK : EDGE_LIGHT);
        tv.setBackground(bg);
        return tv;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
