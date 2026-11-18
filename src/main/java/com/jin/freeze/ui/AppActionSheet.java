package com.jin.freeze.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jin.freeze.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * 长按图标弹出的底部操作单：冻结列表与「选择应用」页共用同一套选项与顺序，
 * 避免两处各写一份导致行为漂移。
 */
public final class AppActionSheet {

    /** 调用方把自身能力映射成这组动作；顺序与界面上的行一一对应。 */
    public interface Actions {
        void run();

        void toggleFreeze();

        void moveTab();

        void appSettings();

        void pinHome();

        /** 是否在冻结名单里：决定「从列表移除 / 移除列表并解冻」是否出现。 */
        boolean inList();

        /** 仅从名单移除：不解冻、不卸载，应用本身保持原样。 */
        void removeFromList();

        void removeAndUnfreeze();

        /** 是否显示「更改标签页」：选择应用页不需要这个入口。 */
        default boolean tagMoveEnabled() {
            return true;
        }

        /** 是否显示「添加到桌面」：选择应用页不需要这个入口。 */
        default boolean pinHomeEnabled() {
            return true;
        }

        /** 是否显示「从列表移除 / 移除列表并解冻」：选择应用页只保留运行、冻结/解冻、应用配置。 */
        default boolean listRemoveEnabled() {
            return true;
        }

        /** 是否显示多选按钮：只有真正支持多选批量操作的页面才 override 返回 true。 */
        default boolean multiSelectEnabled() {
            return false;
        }

        /** 是否显示「移动图标」入口：只有支持拖动排序的冻结列表才 override 返回 true。 */
        default boolean moveIconEnabled() {
            return false;
        }

        /** 进入移动图标模式（点一下选中、再点目标位置放置）。 */
        default void startMoveMode() {
            // 默认空实现：不支持排序的页面不用 override
        }

        /** 从本次长按进入多选态（ActionSheet 自己 dismiss，调用方负责进入 selectMode）。 */
        default void startMultiSelect() {
            // 默认空实现：不支持多选的页面不用 override
        }
    }

    private AppActionSheet() {
    }

    public static void show(Context c, String label, Drawable icon, boolean frozen, Actions actions) {
        show(c, label, icon, frozen, WallpaperSource.MODE_PANEL, 100, actions);
    }

    public static void show(Context c, String label, Drawable icon, boolean frozen,
                            int bgAlphaPct, Actions actions) {
        show(c, label, icon, frozen, WallpaperSource.MODE_PANEL, bgAlphaPct, actions);
    }

    /**
     * @param wallpaperMode 宿主形态：半屏取半屏那张壁纸+模糊度，全屏取全屏那张，两套缓存互不串用
     * @param bgAlphaPct    选项框背景透明度：直接沿用宿主页当前那条背景滑块的值
     *                      （半屏用 panel_surface，全屏用 full_surface），不另算一份。
     */
    public static void show(Context c, String label, Drawable icon, boolean frozen,
                            int wallpaperMode, int bgAlphaPct, Actions actions) {
        // 用自带样式：窗口与 BottomSheet 底板都是透明，否则系统默认的方角白底会盖住卡片圆角
        final BottomSheetDialog sheet = new BottomSheetDialog(c, R.style.Theme_Jin_Sheet);
        // 勾了「同步长按选项框」时用本形态那张壁纸做底（含它自己的模糊度），上面压一层深色 scrim 保证字和图可读
        final android.graphics.Bitmap wallpaper =
                c.getSharedPreferences(WallpaperSource.PREF, Context.MODE_PRIVATE)
                        .getBoolean(WallpaperSource.KEY_SHEET_WALLPAPER, false)
                        ? WallpaperSource.sheetBitmap(c, wallpaperMode) : null;
        final boolean onWallpaper = wallpaper != null;
        final int fg = onWallpaper ? android.graphics.Color.WHITE : Ui.TEXT_PRIMARY;
        final int iconFg = onWallpaper ? android.graphics.Color.WHITE : Ui.primary(c);
        final int lineColor = onWallpaper ? 0x33FFFFFF : Ui.color(c, R.color.divider);
        // 外层留边、内层圆角：左右 26dp；底部贴着半屏面板底边，
        // 卡片下沿略低于底部冻结按钮区域，视觉上从面板底部掀起
        android.widget.FrameLayout outer = new android.widget.FrameLayout(c);
        outer.setPadding(Ui.dp(c, 26), 0, Ui.dp(c, 26), Ui.dp(c, 2));

        // 底色/壁纸按宿主页的背景透明度一起淡出：0 = 全透明，100 = 实心
        final int bgAlpha = Math.round(255f * Math.max(0, Math.min(100, bgAlphaPct)) / 100f);
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        Drawable cardBg;
        if (onWallpaper) {
            cardBg = new android.graphics.drawable.LayerDrawable(new Drawable[]{
                    new WallpaperDrawable(wallpaper, 0),
                    new android.graphics.drawable.ColorDrawable(0x66000000)});
        } else {
            cardBg = androidx.core.content.ContextCompat.getDrawable(c, R.drawable.bg_dialog).mutate();
        }
        cardBg.setAlpha(bgAlpha);
        content.setBackground(cardBg);
        final int radius = Ui.promptRadius(c);
        content.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        content.setClipToOutline(true);
        content.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 6));
        outer.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(c, 15), Ui.dp(c, 9), Ui.dp(c, 15), Ui.dp(c, 7));
        ImageView headIcon = new ImageView(c);
        if (icon != null) {
            headIcon.setImageDrawable(icon);
        } else {
            headIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        header.addView(headIcon, new LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)));
        TextView headName = Ui.text(c, label, 14, fg, true);
        headName.setSingleLine(true);
        headName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = Ui.dp(c, 12);
        header.addView(headName, nameLp);
        content.addView(header);
        divider(c, content, 18, lineColor);

        addItem(c, content, sheet, R.drawable.ic_action_run, "运行", actions::run, fg, iconFg);
        addItem(c, content, sheet, frozen ? R.drawable.ic_sc_unfreeze : R.drawable.ic_sc_freeze,
                frozen ? "解冻" : "冻结", actions::toggleFreeze, fg, iconFg);
        divider(c, content, 18, lineColor);
        if (actions.tagMoveEnabled()) {
            addItem(c, content, sheet, R.drawable.ic_action_tag, "更改标签页", actions::moveTab, fg, iconFg);
        }
        // 移动图标：紧跟在「更改标签页」下方、多选上方
        if (actions.moveIconEnabled()) {
            addItem(c, content, sheet, R.drawable.ic_action_move, "移动图标",
                    actions::startMoveMode, fg, iconFg);
        }
        // 多选入口：紧跟在「更改标签页」下方
        if (actions.multiSelectEnabled()) {
            addItem(c, content, sheet, R.drawable.ic_check_badge, "多选",
                    actions::startMultiSelect, fg, iconFg);
        }
        addItem(c, content, sheet, R.drawable.ic_action_app_info, "在其他应用中配置", actions::appSettings, fg, iconFg);
        if (actions.pinHomeEnabled()) {
            addItem(c, content, sheet, R.drawable.ic_action_home, "添加到桌面", actions::pinHome, fg, iconFg);
        }
        if (actions.listRemoveEnabled() && actions.inList()) {
            addItem(c, content, sheet, R.drawable.ic_minus, "从列表移除",
                    actions::removeFromList, fg, iconFg);
            divider(c, content, 18, lineColor);
            addItem(c, content, sheet, R.drawable.ic_action_circle_unlock, "移除列表并解冻",
                    actions::removeAndUnfreeze, fg, iconFg);
        }

        ScrollView sv = new ScrollView(c);
        sv.addView(outer);
        sheet.setContentView(sv);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        // show 之后 BottomSheet 还会再套一层，用 onShow 再兜一次底，确保容器不留方角白底
        sheet.setOnShowListener(d -> makeSheetTransparent(sheet));
        sheet.show();
        makeSheetTransparent(sheet);
    }

    /** 把 BottomSheet 的窗口、decor、底板全部清成透明，圆角只由内部卡片负责。 */
    public static void makeSheetTransparent(BottomSheetDialog sheet) {
        final android.graphics.drawable.ColorDrawable clear =
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
        if (sheet.getWindow() != null) {
            sheet.getWindow().setBackgroundDrawable(clear);
            sheet.getWindow().setDimAmount(0.42f);
            View decor = sheet.getWindow().getDecorView();
            if (decor != null) decor.setBackground(clear);
        }
        View designSheet = sheet.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (designSheet != null) {
            designSheet.setBackground(clear);
            androidx.core.view.ViewCompat.setBackgroundTintList(
                    designSheet, ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        }
    }

    private static void addItem(Context c, LinearLayout parent, final BottomSheetDialog sheet,
                                int iconRes, String text, final Runnable action,
                                int fg, int iconFg) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        // 条目按压高亮也走圆角，避免方角盖住卡片圆角
        row.setBackgroundResource(R.drawable.bg_menu_item);
        row.setClickable(true);
        ImageView ic = new ImageView(c);
        ic.setImageResource(iconRes);
        ic.setImageTintList(ColorStateList.valueOf(iconFg));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));
        TextView label = Ui.text(c, text, 13, fg, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(c, 10);
        row.addView(label, lp);
        row.setOnClickListener(v -> {
            sheet.dismiss();
            action.run();
        });
        parent.addView(row, rowLp(c));
    }

    private static LinearLayout.LayoutParams rowLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40));
        lp.leftMargin = Ui.dp(c, 4);
        lp.rightMargin = Ui.dp(c, 4);
        return lp;
    }

    private static void divider(Context c, LinearLayout parent, int insetDp, int color) {
        View line = new View(c);
        line.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.5f)));
        lp.leftMargin = Ui.dp(c, insetDp);
        parent.addView(line, lp);
    }
}
