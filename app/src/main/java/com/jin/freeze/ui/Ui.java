package com.jin.freeze.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.jin.freeze.R;

/** 复刻宿主卡片式列表的最小 UI 工厂。 */
public final class Ui {

    /** 主题色占位标记：真实颜色按当前深浅色从资源解析，见 {@link #resolve}。 */
    public static final int BLUE = 0x0100FF01;
    public static final int TEXT_PRIMARY = 0x0100FF02;
    public static final int TEXT_SECONDARY = 0x0100FF03;

    private Ui() {
    }

    /** 深色模式：把三个标记色解析成 values / values-night 中的实际颜色。 */
    public static int resolve(Context c, int color) {
        if (color == BLUE) {
            return color(c, R.color.blue);
        }
        if (color == TEXT_PRIMARY) {
            return color(c, R.color.text_primary);
        }
        if (color == TEXT_SECONDARY) {
            return color(c, R.color.text_secondary);
        }
        return color;
    }

    public static int primary(Context c) {
        return color(c, R.color.text_primary);
    }

    public static int secondary(Context c) {
        return color(c, R.color.text_secondary);
    }

    public static int blue(Context c) {
        return color(c, R.color.blue);
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(resolve(c, color));
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    public static LinearLayout card(Context c, int bgRes) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundResource(bgRes);
        int p = dp(c, 12);
        ll.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 8);
        ll.setLayoutParams(lp);
        return ll;
    }

    public static TextView sectionTitle(Context c, String s) {
        TextView t = text(c, s, 14, BLUE, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 6);
        lp.topMargin = dp(c, 14);
        lp.bottomMargin = dp(c, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 状态卡片：左侧标题/说明，右侧若干胶囊按钮。 */
    public static LinearLayout statusCard(Context c, TextView title, TextView subtitle, TextView[] buttons) {
        LinearLayout card = card(c, R.drawable.bg_status);
        // 状态条按参考稿固定：左右 16dp、整体 55dp 高，内容在条内垂直居中
        card.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        card.setMinimumHeight(dp(c, 55));
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        title.setTextSize(17);
        title.setTextColor(resolve(c, TEXT_PRIMARY));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        subtitle.setTextSize(12);
        subtitle.setTextColor(resolve(c, TEXT_SECONDARY));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(c, 4);
        left.addView(title);
        left.addView(subtitle, subLp);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, leftLp);

        for (TextView b : buttons) {
            stylePill(c, b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 8);
            row.addView(b, lp);
        }
        card.addView(row);
        return card;
    }

    public static void stylePill(Context c, TextView b) {
        b.setBackgroundResource(R.drawable.bg_btn_white);
        b.setTextColor(resolve(c, BLUE));
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int px = dp(c, 14);
        int py = dp(c, 7);
        b.setPadding(px, py, px, py);
        b.setGravity(Gravity.CENTER);
    }

    /** 统计卡：左侧标签 + 右侧大数字。 */
    public static LinearLayout statCard(Context c, String label, TextView value, View.OnClickListener click) {
        LinearLayout card = card(c, R.drawable.bg_card);
        if (click != null) {
            card.setClickable(true);
            card.setOnClickListener(click);
        }
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(c, label, 15, TEXT_PRIMARY, false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        value.setTextSize(24);
        value.setTextColor(resolve(c, BLUE));
        value.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(value);
        card.addView(row);
        return card;
    }

    /** 列表项：标题 + 说明 + 右侧箭头。 */
    public static LinearLayout itemCard(Context c, String title, String subtitle, View.OnClickListener click) {
        LinearLayout card = card(c, R.drawable.bg_card);
        card.setClickable(true);
        card.setOnClickListener(click);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(c, title, 16, TEXT_PRIMARY, false));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(c, subtitle, 12, TEXT_SECONDARY, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(c, 5);
            left.addView(sub, lp);
        }
        card.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = new ImageView(c);
        arrow.setImageResource(R.drawable.ic_chevron);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
        al.leftMargin = dp(c, 8);
        card.addView(arrow, al);
        return card;
    }

    public static void addCard(LinearLayout container, View v) {
        container.addView(v);
    }

    /** 弹窗输入框统一处理：打开即聚焦并弹出输入法，键盘不遮挡输入框。 */
    public static void focusInputWhenShown(Dialog dialog, EditText input) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        input.setFocusableInTouchMode(true);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) input.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }

    /** 提示类弹窗的统一圆角（dp），与 bg_dialog 共用同一个 dimen。 */
    public static int promptRadius(Context c) {
        return c.getResources().getDimensionPixelSize(R.dimen.prompt_radius);
    }

    /**
     * 自定义内容弹窗做真圆角：窗口底透明、内容铺 bg_dialog 并按统一圆角裁切、宽度收窄不贴屏边。
     * 需在 {@link Dialog#show()} 之后（onShow 回调里）调用。
     */
    public static void roundDialog(Dialog dialog, View root, int widthInsetDp) {
        roundDialog(dialog, root, widthInsetDp, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 指定高度版本：heightPx 传 WRAP_CONTENT 以外时固定高度，内部滚动区用 weight 撑开。 */
    public static void roundDialog(Dialog dialog, View root, int widthInsetDp, int heightPx) {
        int width = root.getContext().getResources().getDisplayMetrics().widthPixels
                - dp(root.getContext(), widthInsetDp);
        roundDialogWidth(dialog, root, width, heightPx);
    }

    /** 设置页弹窗：二级弹窗打开时先隐藏它，二级弹窗关掉后再恢复显示。 */
    private static Dialog settingsHost;
    private static int settingsChildCount;
    /** 已登记过自动隐藏的弹窗 decor，避免同一弹窗被重复圆角化时重复计数。 */
    private static final java.util.WeakHashMap<View, Boolean> autoHideTracked =
            new java.util.WeakHashMap<>();

    /** 设置页弹窗创建后登记；之后所有经 Ui 圆角化的二级弹窗都会自动隐藏/恢复它。 */
    public static void setSettingsHost(Dialog host) {
        settingsHost = host;
        settingsChildCount = 0;
    }

    /** 设置页弹窗关闭时取消登记。 */
    public static void clearSettingsHost(Dialog host) {
        if (settingsHost == host) {
            settingsHost = null;
            settingsChildCount = 0;
        }
    }

    /** 直接关掉设置页并取消登记（全屏快面板这类从列表调起的浮层用）。 */
    public static void dismissSettingsHost() {
        Dialog host = settingsHost;
        settingsHost = null;
        settingsChildCount = 0;
        if (host != null) {
            try {
                host.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 标记这个弹窗不算设置页的二级弹窗：打开/关闭都不隐藏、不恢复设置页。
     * 必须在圆角化（autoHideSettings）之前调用。
     */
    public static void skipAutoHide(Dialog dialog) {
        Window w = dialog == null ? null : dialog.getWindow();
        View decor = w == null ? null : w.getDecorView();
        if (decor == null) {
            return;
        }
        synchronized (autoHideTracked) {
            autoHideTracked.put(decor, Boolean.TRUE);
        }
    }

    /**
     * 二级弹窗挂载时隐藏设置页，最后一个二级弹窗卸载时恢复设置页。
     * 覆盖「完成 / 取消 / 手势返回」三种关闭方式；嵌套弹窗用计数处理。
     */
    public static void autoHideSettings(Dialog child) {
        final Dialog host = settingsHost;
        Window window = child.getWindow();
        if (host == null || host == child || window == null) {
            return;
        }
        final View decor = window.getDecorView();
        if (decor == null) {
            return;
        }
        synchronized (autoHideTracked) {
            if (autoHideTracked.put(decor, Boolean.TRUE) != null) {
                return;
            }
        }
        final boolean[] counted = {false};
        if (decor.isAttachedToWindow()) {
            counted[0] = true;
            settingsChildCount++;
            if (host.isShowing()) {
                host.hide();
            }
        }
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (settingsHost != host || counted[0]) {
                    return;
                }
                counted[0] = true;
                settingsChildCount++;
                if (host.isShowing()) {
                    host.hide();
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                if (!counted[0]) {
                    return;
                }
                counted[0] = false;
                settingsChildCount = Math.max(0, settingsChildCount - 1);
                if (settingsHost != host || settingsChildCount > 0 || host.isShowing()) {
                    return;
                }
                Context c = host.getContext();
                if (c instanceof Activity) {
                    Activity a = (Activity) c;
                    if (a.isFinishing() || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) {
                        return;
                    }
                }
                host.show();
            }
        });
    }

    /** 直接指定宽度（px）的版本，给需要收窄的小弹窗用。 */
    public static void roundDialogWidth(Dialog dialog, View root, int widthPx, int heightPx) {
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyDialogBg(root);
        final int radius = promptRadius(root.getContext());
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        root.setClipToOutline(true);
        w.setLayout(widthPx, heightPx);
        autoHideSettings(dialog);
    }

    /**
     * 「同步全局」壁纸底：没开同步全局或没设壁纸时返回 null，调用方回落默认 bg_dialog。
     */
    public static android.graphics.drawable.Drawable globalWallpaperBg(Context c) {
        android.graphics.Bitmap bmp = WallpaperSource.globalBitmap(c);
        if (bmp == null) {
            return null;
        }
        return wallpaperWithScrim(bmp, WallpaperSource.globalBrightness(c));
    }

    /**
     * 全应用统一的一套壁纸底：明暗度 0–100 叠黑（100 = 原图），
     * 100–200 叠白提亮（200 = 全白）；出厂 100 就是原图亮度。
     */
    public static android.graphics.drawable.Drawable wallpaperWithScrim(android.graphics.Bitmap bmp, int brightPct) {
        int b = Math.max(0, Math.min(200, brightPct));
        int overlay;
        if (b <= 100) {
            overlay = Math.round(255f * (100 - b) / 100f) << 24;
        } else {
            overlay = (Math.round(255f * (b - 100) / 100f) << 24) | 0x00FFFFFF;
        }
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new WallpaperDrawable(bmp, 0),
                new ColorDrawable(overlay)});
    }

    /**
     * 壁纸底上的正文色：按「壁纸调过明暗度后的实际亮度」自动选白字或黑字，
     * 亮壁纸用黑字、暗壁纸用白字，避免高亮壁纸上白字看不见。
     */
    public static int wallpaperFg(android.graphics.Bitmap bmp, int brightPct) {
        return wallpaperDark(bmp, brightPct) ? Color.WHITE : 0xFF1A1A1A;
    }

    /** 壁纸底上的次要文字色：与正文同一套明暗判断，透明度略低。 */
    public static int wallpaperFgSecondary(android.graphics.Bitmap bmp, int brightPct) {
        return wallpaperDark(bmp, brightPct) ? 0xCCFFFFFF : 0x991A1A1A;
    }

    /** 壁纸底上的分割线色：跟着正文色一起切换。 */
    public static int wallpaperDivider(android.graphics.Bitmap bmp, int brightPct) {
        return wallpaperDark(bmp, brightPct) ? 0x33FFFFFF : 0x1F000000;
    }

    /**
     * 壁纸调过明暗度后是否偏暗：12×12 网格采样取平均相对亮度，
     * 低于 0.30 视为暗底用白字，否则用黑字。
     */
    public static boolean wallpaperDark(android.graphics.Bitmap bmp, int brightPct) {
        if (bmp == null || bmp.isRecycled()) {
            return true;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int stepX = Math.max(1, w / 12);
        int stepY = Math.max(1, h / 12);
        double sum = 0;
        int n = 0;
        for (int y = stepY / 2; y < h; y += stepY) {
            for (int x = stepX / 2; x < w; x += stepX) {
                int p = bmp.getPixel(x, y);
                sum += 0.2126 * linear(((p >> 16) & 0xff) / 255.0)
                        + 0.7152 * linear(((p >> 8) & 0xff) / 255.0)
                        + 0.0722 * linear((p & 0xff) / 255.0);
                n++;
            }
        }
        if (n == 0) {
            return true;
        }
        // 0–100 压黑只剩 b% 亮度；100–200 往白色方向提亮
        int b = Math.max(0, Math.min(200, brightPct));
        double l = sum / n;
        if (b <= 100) {
            l = l * b / 100.0;
        } else {
            l = l + (1 - l) * (b - 100) / 100.0;
        }
        return l < 0.30;
    }

    /** sRGB 分量转线性亮度（相对亮度公式用）。 */
    private static double linear(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * 弹窗/面板底板：开了「同步全局」就用壁纸做底，否则默认 bg_dialog。
     * 壁纸底不是圆角 drawable，按 bg_dialog 同款圆角裁切，换底后保持原有圆角。
     */
    public static void applyDialogBg(View v) {
        android.graphics.drawable.Drawable wp = globalWallpaperBg(v.getContext());
        if (wp != null) {
            v.setBackground(wp);
            final int radius = promptRadius(v.getContext());
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            v.setClipToOutline(true);
        } else {
            v.setBackgroundResource(R.drawable.bg_dialog);
        }
        // 分组展开、动态重建都在布局之后发生：每次布局完都校一遍字色（幂等，只在颜色变化时动）
        if (v.getTag(TAG_TINT_HOOKED) == null) {
            v.setTag(TAG_TINT_HOOKED, Boolean.TRUE);
            v.getViewTreeObserver().addOnGlobalLayoutListener(() -> tintTree(v));
        }
        v.post(() -> tintTree(v));
    }

    /** 记录 TextView 原始文字色，壁纸关掉时按原样还原。 */
    private static final int TAG_ORIG_TEXT_COLOR = 0x0A1B2C40;
    /** 布局监听只挂一次。 */
    private static final int TAG_TINT_HOOKED = 0x0A1B2C42;
    /** 图标原始 tint，壁纸关掉时还原。 */
    private static final int TAG_ORIG_TINT = 0x0A1B2C43;

    /** 普通页面（选择应用等）：开了同步全局时按壁纸明暗统一文字/图标色。 */
    public static void tintPage(View root) {
        tintTree(root);
    }

    /** 一次取好壁纸与明暗度，整棵树共用。 */
    private static void tintTree(View root) {
        Context c = root.getContext();
        android.graphics.Bitmap bmp = WallpaperSource.globalBitmap(c);
        tintForWallpaper(root, c, bmp, WallpaperSource.globalBrightness(c));
    }

    /**
     * 按壁纸明暗统一切换正文/次要文字色（只换主题主/次文字色，蓝色等的强调色保持原样）；
     * 没有壁纸底时还原成原始色。
     */
    private static void tintForWallpaper(View v, Context c, android.graphics.Bitmap bmp, int dim) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Object saved = tv.getTag(TAG_ORIG_TEXT_COLOR);
            if (!(saved instanceof Integer)) {
                saved = tv.getCurrentTextColor();
                tv.setTag(TAG_ORIG_TEXT_COLOR, saved);
            }
            int orig = (Integer) saved;
            int target = orig;
            if (bmp != null) {
                if (orig == color(c, R.color.text_primary)) {
                    target = wallpaperFg(bmp, dim);
                } else if (orig == color(c, R.color.text_secondary)) {
                    target = wallpaperFgSecondary(bmp, dim);
                }
            }
            if (tv.getCurrentTextColor() != target) {
                tv.setTextColor(target);
            }
        } else if (v instanceof ImageView) {
            // 左侧小图标这类用 tint 上色的图标，也一起跟随壁纸
            ImageView iv = (ImageView) v;
            android.content.res.ColorStateList tint = iv.getImageTintList();
            if (tint != null) {
                int cur = tint.getDefaultColor();
                Object savedTint = iv.getTag(TAG_ORIG_TINT);
                if (!(savedTint instanceof Integer)
                        && (cur == color(c, R.color.text_primary)
                        || cur == color(c, R.color.text_secondary))) {
                    iv.setTag(TAG_ORIG_TINT, cur);
                    savedTint = cur;
                }
                if (savedTint instanceof Integer) {
                    int orig = (Integer) savedTint;
                    int target = orig;
                    if (bmp != null) {
                        if (orig == color(c, R.color.text_primary)) {
                            target = wallpaperFg(bmp, dim);
                        } else if (orig == color(c, R.color.text_secondary)) {
                            target = wallpaperFgSecondary(bmp, dim);
                        }
                    }
                    if (cur != target) {
                        iv.setImageTintList(android.content.res.ColorStateList.valueOf(target));
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                tintForWallpaper(g.getChildAt(i), c, bmp, dim);
            }
        }
    }

    /**
     * 富文本（加粗标题、更新日志版本号等）在壁纸底上的主色：
     * 开了同步全局就按壁纸明暗自动取白/黑，否则用主题主色；构建富文本时直接用它。
     */
    public static int wpPrimary(Context c) {
        android.graphics.Bitmap bmp = WallpaperSource.globalBitmap(c);
        return bmp == null ? color(c, R.color.text_primary)
                : wallpaperFg(bmp, WallpaperSource.globalBrightness(c));
    }

    /** 富文本在壁纸底上的次要色：与主色同一套明暗判断。 */
    public static int wpSecondary(Context c) {
        android.graphics.Bitmap bmp = WallpaperSource.globalBitmap(c);
        return bmp == null ? color(c, R.color.text_secondary)
                : wallpaperFgSecondary(bmp, WallpaperSource.globalBrightness(c));
    }

    /** 弹窗卡片是否已套过阴影壳（避免重复 wrap）。 */
    private static final int TAG_SHADOW_SHELL = 0x0A1B2C3F;

    /**
     * 与设置面板同款的弹窗阴影：把弹窗内容卡片包进带 12dp 留白的壳里（上下左右一致），
     * 卡片本体按圆角画 elevation 阴影；窗口底改成透明，阴影才画得出来。
     * 返回留白宽度（px），调用方按 width + pad * 2 设窗口宽度、height + pad * 2 设窗口高度。
     */
    public static int wrapShadow(Dialog dialog) {
        Context c = dialog.getContext();
        int pad = dp(c, 12);
        Window w = dialog.getWindow();
        if (w == null) {
            return pad;
        }
        View decor = w.getDecorView();
        if (decor == null) {
            return pad;
        }
        View content = decor.findViewById(android.R.id.content);
        View card = content;
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            card = ((ViewGroup) content).getChildAt(0);
        }
        if (card == null || !(card.getParent() instanceof ViewGroup)) {
            return pad;
        }
        if (card.getTag(TAG_SHADOW_SHELL) == null) {
            ViewGroup parent = (ViewGroup) card.getParent();
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            int childHeight = lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            parent.removeView(card);
            FrameLayout shell = new FrameLayout(c);
            shell.setClipChildren(false);
            shell.setClipToPadding(false);
            shell.setPadding(pad, pad, pad, pad);
            shell.addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, childHeight));
            if (lp != null) {
                parent.addView(shell, lp);
            } else {
                parent.addView(shell, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            card.setTag(TAG_SHADOW_SHELL, Boolean.TRUE);
            // 窗口底透明，阴影才能画到窗口里
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        boolean night = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        card.setElevation(dp(c, night ? 18 : 10));
        // 只要四周淡淡的环境阴影：点光源阴影（主要落在下方，底部会比顶部明显）不要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            card.setOutlineSpotShadowColor(Color.TRANSPARENT);
        }
        return pad;
    }

    /**
     * 半屏形态：把弹窗整体抬到面板上沿之上（limitY 为面板上沿的屏幕 y），
     * 先等首帧量到真实高度再定位；上方空间不够时收高度，绝压住下面的半屏列表。
     */
    public static void keepAboveBottomLimit(Dialog dialog, int limitY,
                                            int statusBarInset, int gapPx) {
        if (dialog == null || limitY <= 0) {
            return;
        }
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        View decor = w.getDecorView();
        if (decor == null) {
            return;
        }
        decor.post(() -> {
            int h = decor.getHeight();
            if (h <= 0) {
                return;
            }
            int avail = limitY - gapPx - statusBarInset;
            WindowManager.LayoutParams lp = w.getAttributes();
            if (h > avail && avail > dp(decor.getContext(), 170)) {
                lp.height = avail;
                h = avail;
            }
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.max(0, avail - h);
            w.setAttributes(lp);
        });
    }

    /**
     * 失败/短提示小弹窗：比系统 AlertDialog 更窄，圆角卡片，按钮只有一句。
     * 冻结失败、解冻失败、打开失败这类提示统一用它。
     */
    public static android.app.AlertDialog smallDialog(Context c, String title,
                                                      CharSequence message, String button) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        applyDialogBg(card);
        int pad = dp(c, 16);
        card.setPadding(pad, dp(c, 14), pad, dp(c, 6));
        card.addView(text(c, title, 14, TEXT_PRIMARY, true));
        if (message != null && message.length() > 0) {
            TextView m = text(c, message, 12, TEXT_SECONDARY, false);
            m.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(c, 8);
            card.addView(m, mlp);
        }
        TextView b = text(c, button, 12, BLUE, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            b.setBackgroundResource(ripple.resourceId);
        }
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 32));
        blp.gravity = Gravity.END;
        blp.topMargin = dp(c, 4);
        card.addView(b, blp);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(c)
                .setView(card).create();
        b.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        int screen = c.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screen - dp(c, 72), dp(c, 280));
        roundDialogWidth(dialog, card, width, ViewGroup.LayoutParams.WRAP_CONTENT);
        return dialog;
    }

    /**
     * 标准 AlertDialog 的圆角兜底：系统内容面板自带一张不透明方角底，会把主题里
     * bg_dialog 的圆角盖住。这里把窗口底清空，内容面板换成同一张 bg_dialog，并按
     * 同一个圆角裁切，内部按钮的底与触感也一起收进圆角。
     * 需在 {@link Dialog#show()} 之后调用。
     */
    public static void roundAlertDialog(Dialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        View decor = w.getDecorView();
        if (decor == null) {
            return;
        }
        View content = decor.findViewById(android.R.id.content);
        View panel = content;
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            panel = ((ViewGroup) content).getChildAt(0);
        } else if (panel == null && decor instanceof ViewGroup
                && ((ViewGroup) decor).getChildCount() > 0) {
            // 个别 ROM 的对话框没有 android.R.id.content，退一格用 decor 的第一个子 View
            panel = ((ViewGroup) decor).getChildAt(0);
        }
        if (panel == null) {
            return;
        }
        applyDialogBg(panel);
        final int radius = promptRadius(panel.getContext());
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        panel.setClipToOutline(true);
        autoHideSettings(dialog);
    }

    /**
     * pm 失败原因归类：弹框/提示只给人话短句，完整原始输出只进日志，绝不贴栈。
     * 两类 ROM 拦截（小米核心组件、系统应用）单独识别，其余统一短句。
     * 单条、批量、快捷方式批处理共用这一份口径。
     */
    /** 停用与降级通道（pm suspend）都被本机拒绝时的一句兜底：只在这里出现，不贴原始输出。 */
    public static final String NO_FREEZE_CHANNEL = "本机既不允许停用也不允许暂停，无法冻结";

    public static String pmReason(com.jin.freeze.core.PrivShell.Result r, boolean freeze) {
        String out = r == null || r.out == null ? "" : r.out.trim();
        android.util.Log.w("JinPm", (freeze ? "freeze" : "unfreeze") + " failed: code="
                + (r == null ? -1 : r.code) + " timeout=" + (r != null && r.timeout) + "\n" + out);
        return pmReason(out, freeze);
    }

    /** 纯文本版本：批量脚本按包切出来的输出走这里。返回值是一句可直接拼到应用名后面的原因。 */
    public static String pmReason(String out, boolean freeze) {
        String low = out == null ? "" : out.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("cannot disable miui core packages")) {
            return "属小米核心组件，系统禁止停用，无法冻结";
        }
        if (low.contains("cannot disable system packages")) {
            return "本机系统不允许停用系统应用，无法冻结";
        }
        if (low.contains("securityexception") || low.contains("permission denial")) {
            return freeze ? "系统拒绝停用（ROM 限制），无法冻结" : "系统拒绝启用（ROM 限制），无法解冻";
        }
        if (low.contains("timed out") || low.contains("timeout")) {
            return "执行超时，请重试";
        }
        if (low.contains("unknown package") || low.contains("not found")) {
            return "系统里找不到该应用";
        }
        return freeze ? "停用未生效，可能被系统限制" : "启用未生效，可能被系统限制";
    }

    public static int color(Context c, int id) {
        return ContextCompat.getColor(c, id);
    }

    public static int white() {
        return Color.WHITE;
    }
}
