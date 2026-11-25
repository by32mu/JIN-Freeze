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

        /** 是否显示冻结白名单入口：只有冻结列表需要。 */
        default boolean whitelistEnabled() {
            return false;
        }

        /** 当前应用是否已在冻结白名单里：决定显示「添加到白名单」还是「移出白名单」。 */
        default boolean whitelisted() {
            return false;
        }

        /** 加入或移出冻结白名单（按当前状态取反）。 */
        default void toggleWhitelist() {
            // 默认空实现：不涉及白名单的页面不用 override
        }

        /** 是否显示「快捷移除图标」：只有半屏面板需要。 */
        default boolean quickRemoveEnabled() {
            return false;
        }

        /** 当前是否已打开快捷移除：决定显示「快捷移除图标」还是「退出快捷移除」。 */
        default boolean quickRemoveOn() {
            return false;
        }

        /** 打开 / 关闭快捷移除（按当前状态取反）。 */
        default void toggleQuickRemove() {
            // 默认空实现
        }

        /** 进入移动图标模式（点一下选中、再点目标位置放置）。 */
        default void startMoveMode() {
            // 默认空实现：不支持排序的页面不用 override
        }

        /** 是否显示「重命名」：只有冻结列表支持给应用改显示名。 */
        default boolean renameEnabled() {
            return false;
        }

        /** 隐藏应用模式：冻结入口显示成「隐藏 / 取消隐藏」。 */
        default boolean hideMode() {
            return false;
        }

        /** 当前是否真的处于系统隐藏态（区别于普通冻结）。 */
        default boolean hidden() {
            return false;
        }

        /** 隐藏 / 取消隐藏：与冻结、解冻相互独立。 */
        default void toggleHide() {
        }

        /** 重命名列表中的应用（只改显示名，不动包名与系统设置）。 */
        default void renameApp() {
            // 默认空实现：不支持重命名的页面不用 override
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
                WallpaperSource.sheetBgOn(c)
                        ? WallpaperSource.sheetBitmap(c, wallpaperMode) : null;
        final boolean onWallpaper = wallpaper != null;
        final int wpDim = WallpaperSource.brightnessPct(c, wallpaperMode);
        final int fg = onWallpaper ? Ui.wallpaperFg(wallpaper, wpDim) : Ui.TEXT_PRIMARY;
        final int iconFg = onWallpaper ? Ui.wallpaperFg(wallpaper, wpDim) : Ui.primary(c);
        final int lineColor = onWallpaper ? Ui.wallpaperDivider(wallpaper, wpDim) : Ui.color(c, R.color.divider);
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
            cardBg = Ui.wallpaperWithScrim(wallpaper, wpDim);
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
        boolean hidden = actions.hidden();
        addItem(c, content, sheet, frozen ? R.drawable.ic_sc_unfreeze : R.drawable.ic_sc_freeze,
                frozen ? "解冻" : "冻结", actions::toggleFreeze, fg, iconFg);
        // 隐藏与冻结/解冻相互独立：开了隐藏模式，或当前就是隐藏态时，额外给一行
        if (actions.hideMode() || hidden) {
            addItem(c, content, sheet,
                    hidden ? R.drawable.ic_qp_eye : R.drawable.ic_qp_eye_off,
                    hidden ? "取消隐藏" : "隐藏", actions::toggleHide, fg, iconFg);
        }
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
        // 重命名：放在多选下面，只改列表里的显示名称
        if (actions.renameEnabled()) {
            addItem(c, content, sheet, R.drawable.ic_action_edit, "重命名",
                    actions::renameApp, fg, iconFg);
        }
        addItem(c, content, sheet, R.drawable.ic_action_app_info, "在其他应用中配置", actions::appSettings, fg, iconFg);
        if (actions.pinHomeEnabled()) {
            addItem(c, content, sheet, R.drawable.ic_action_home, "添加到桌面", actions::pinHome, fg, iconFg);
        }
        // 冻结白名单：在「添加到桌面」下面；已在白名单时同一枚盾牌图标，勾换成叉
        if (actions.whitelistEnabled()) {
            boolean inWhite = actions.whitelisted();
            addItem(c, content, sheet,
                    inWhite ? R.drawable.ic_action_whitelist_off : R.drawable.ic_action_whitelist,
                    inWhite ? "移出白名单" : "添加到白名单",
                    actions::toggleWhitelist, fg, iconFg);
        }
        // 批量移除应用（原快捷移除）：半屏专属，放在「从列表移除」上面，
        // 打开后图标常驻红标、底部冻结按钮变成「完成」勾选
        if (actions.quickRemoveEnabled()) {
            boolean on = actions.quickRemoveOn();
            addItem(c, content, sheet, R.drawable.ic_action_batch_remove,
                    on ? "退出批量移除" : "批量移除应用",
                    actions::toggleQuickRemove, fg, iconFg);
        }
        if (actions.listRemoveEnabled() && actions.inList()) {
            addItem(c, content, sheet, R.drawable.ic_minus, "从列表移除",
                    actions::removeFromList, fg, iconFg);
            divider(c, content, 18, lineColor);
            addItem(c, content, sheet, R.drawable.ic_action_circle_unlock, "移除列表并解冻",
                    actions::removeAndUnfreeze, fg, iconFg);
        }

        ScrollView sv = new ScrollView(c);
        // 去掉右侧滚动条：菜单内容变长时那条竖线既突兀又多出一条「尾巴」
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(outer);
        sheet.setContentView(sv);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        // show 之后 BottomSheet 还会再套一层，用 onShow 再兜一次底，确保容器不留方角白底
        sheet.setOnShowListener(d -> makeSheetTransparent(sheet));

        // 选项框位置：半屏/全屏分别记忆，空白处按住拖动整块平移
        final android.content.SharedPreferences sp =
                c.getSharedPreferences("freeze_list", Context.MODE_PRIVATE);
        final boolean fullMode = wallpaperMode == WallpaperSource.MODE_FULL;
        final String dxKey = fullMode ? "sheet_dx_full" : "sheet_dx_panel";
        final String dyKey = fullMode ? "sheet_dy_full" : "sheet_dy_panel";
        final float savedDx = sp.getFloat(dxKey, 0f);
        final float savedDy = sp.getFloat(dyKey, 0f);
        final View[] dragTarget = new View[]{null};
        sheet.getBehavior().addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    dragTarget[0] = bottomSheet;
                    bottomSheet.setTranslationX(savedDx);
                    bottomSheet.setTranslationY(savedDy);
                }
            }

            @Override
            public void onSlide(View bottomSheet, float slideOffset) {
            }
        });
        // 标题行、卡片内边距、左右留白都算空白区
        attachSheetDrag(c, header, dragTarget, sp, dxKey, dyKey);
        attachSheetDrag(c, content, dragTarget, sp, dxKey, dyKey);
        attachSheetDrag(c, outer, dragTarget, sp, dxKey, dyKey);

        sheet.show();
        makeSheetTransparent(sheet);

        // show 之后 BottomSheetBehavior 可能已经是 EXPANDED，不会再回调一次，
        // 这里主动拿到底板并落位，否则拖动时 target 一直为空、怎么拖都没反应
        final View sheetBody = sheet.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheetBody != null) {
            dragTarget[0] = sheetBody;
            sheetBody.setTranslationX(savedDx);
            sheetBody.setTranslationY(savedDy);
            // 入场动画结束时会重置 translationY，动画后再落一次记忆位置
            sheetBody.postDelayed(() -> {
                sheetBody.setTranslationX(sp.getFloat(dxKey, 0f));
                sheetBody.setTranslationY(sp.getFloat(dyKey, 0f));
            }, 350);
        }
    }

    /** 纯文字菜单项：iconRes 为 0 时不显示图标；trail 非空时在右侧显示状态（如「已开启」）。 */
    public static final class TextItem {
        final int iconRes;
        final String text;
        final String trail;
        /** 状态为「关闭」时用次要色显示 trail，避免和已开启一样抢眼。 */
        final boolean trailOff;
        final Runnable action;

        public TextItem(String text, String trail, Runnable action) {
            this(0, text, trail, false, action);
        }

        public TextItem(int iconRes, String text, String trail, Runnable action) {
            this(iconRes, text, trail, false, action);
        }

        public TextItem(int iconRes, String text, String trail, boolean trailOff, Runnable action) {
            this.iconRes = iconRes;
            this.text = text;
            this.trail = trail;
            this.trailOff = trailOff;
            this.action = action;
        }
    }

    /**
     * 纯文字选项单：宽度、圆角、壁纸与背景透明度、拖动位置全部沿用长按图标那张选项框，
     * 宿主形态（半屏 / 全屏）各自取自己的壁纸与透明度。
     */
    public static void showTextMenu(Context c, String title, int wallpaperMode,
                                    int bgAlphaPct, java.util.List<TextItem> items) {
        final BottomSheetDialog sheet = new BottomSheetDialog(c, R.style.Theme_Jin_Sheet);
        final android.graphics.Bitmap wallpaper =
                WallpaperSource.sheetBgOn(c)
                        ? WallpaperSource.sheetBitmap(c, wallpaperMode) : null;
        final boolean onWallpaper = wallpaper != null;
        final int wpDim = WallpaperSource.brightnessPct(c, wallpaperMode);
        final int fg = onWallpaper ? Ui.wallpaperFg(wallpaper, wpDim) : Ui.TEXT_PRIMARY;
        final int trailFg = onWallpaper ? Ui.wallpaperFg(wallpaper, wpDim) : Ui.primary(c);
        final int trailOffFg = onWallpaper ? Ui.wallpaperFgSecondary(wallpaper, wpDim) : Ui.TEXT_SECONDARY;
        final int iconFg = onWallpaper ? Ui.wallpaperFg(wallpaper, wpDim) : Ui.primary(c);
        final int lineColor = onWallpaper ? Ui.wallpaperDivider(wallpaper, wpDim) : Ui.color(c, R.color.divider);

        android.widget.FrameLayout outer = new android.widget.FrameLayout(c);
        outer.setPadding(Ui.dp(c, 26), 0, Ui.dp(c, 26), Ui.dp(c, 2));

        final int bgAlpha = Math.round(255f * Math.max(0, Math.min(100, bgAlphaPct)) / 100f);
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        Drawable cardBg;
        if (onWallpaper) {
            cardBg = Ui.wallpaperWithScrim(wallpaper, wpDim);
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
        header.setPadding(Ui.dp(c, 15), Ui.dp(c, 12), Ui.dp(c, 15), Ui.dp(c, 8));
        TextView headName = Ui.text(c, title, 14, fg, true);
        headName.setSingleLine(true);
        headName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(headName, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(header);
        divider(c, content, 18, lineColor);

        for (TextItem it : items) {
            addTextItem(c, content, sheet, it.iconRes, it.text, it.trail, it.action,
                    fg, iconFg, it.trailOff ? trailOffFg : trailFg);
        }

        ScrollView sv = new ScrollView(c);
        // 纯文字菜单同样去掉滚动条，避免右侧多出一条竖线
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(outer);
        sheet.setContentView(sv);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.setOnShowListener(d -> makeSheetTransparent(sheet));

        final android.content.SharedPreferences sp =
                c.getSharedPreferences("freeze_list", Context.MODE_PRIVATE);
        final boolean fullMode = wallpaperMode == WallpaperSource.MODE_FULL;
        final String dxKey = fullMode ? "sheet_dx_full" : "sheet_dx_panel";
        final String dyKey = fullMode ? "sheet_dy_full" : "sheet_dy_panel";
        final float savedDx = sp.getFloat(dxKey, 0f);
        final float savedDy = sp.getFloat(dyKey, 0f);
        final View[] dragTarget = new View[]{null};
        sheet.getBehavior().addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    dragTarget[0] = bottomSheet;
                    bottomSheet.setTranslationX(savedDx);
                    bottomSheet.setTranslationY(savedDy);
                }
            }

            @Override
            public void onSlide(View bottomSheet, float slideOffset) {
            }
        });
        attachSheetDrag(c, header, dragTarget, sp, dxKey, dyKey);
        attachSheetDrag(c, content, dragTarget, sp, dxKey, dyKey);
        attachSheetDrag(c, outer, dragTarget, sp, dxKey, dyKey);

        sheet.show();
        makeSheetTransparent(sheet);

        final View sheetBody = sheet.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheetBody != null) {
            dragTarget[0] = sheetBody;
            sheetBody.setTranslationX(savedDx);
            sheetBody.setTranslationY(savedDy);
            sheetBody.postDelayed(() -> {
                sheetBody.setTranslationX(sp.getFloat(dxKey, 0f));
                sheetBody.setTranslationY(sp.getFloat(dyKey, 0f));
            }, 350);
        }
    }

    private static void addTextItem(Context c, LinearLayout parent, final BottomSheetDialog sheet,
                                    int iconRes, String text, String trail, final Runnable action,
                                    int fg, int iconFg, int trailFg) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
        row.setBackgroundResource(R.drawable.bg_menu_item);
        row.setClickable(true);
        if (iconRes != 0) {
            ImageView ic = new ImageView(c);
            ic.setImageResource(iconRes);
            ic.setImageTintList(ColorStateList.valueOf(iconFg));
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));
        }
        TextView label = Ui.text(c, text, 13, fg, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (iconRes != 0) {
            lp.leftMargin = Ui.dp(c, 10);
        }
        row.addView(label, lp);
        if (trail != null) {
            row.addView(Ui.text(c, trail, 11, trailFg, false));
        }
        row.setOnClickListener(v -> {
            sheet.dismiss();
            action.run();
        });
        parent.addView(row, rowLp(c));
    }

    /** 空白区域拖动：整块平移选项框，按下即震动（先震动、后允许移动），松手记住当前位置。 */
    private static void attachSheetDrag(Context c, View v, final View[] target,
                                        final android.content.SharedPreferences sp,
                                        final String dxKey, final String dyKey) {
        final int slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        final float[] down = new float[2];
        final float[] start = new float[2];
        final boolean[] moved = new boolean[]{false};
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (target[0] == null) {
                        return false;
                    }
                    down[0] = ev.getRawX();
                    down[1] = ev.getRawY();
                    start[0] = target[0].getTranslationX();
                    start[1] = target[0].getTranslationY();
                    moved[0] = false;
                    // 先把震动打出去，之后才允许跟着手指移动
                    view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS,
                            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                    android.view.ViewParent p = view.getParent();
                    if (p != null) {
                        p.requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - down[0];
                    float dy = ev.getRawY() - down[1];
                    if (!moved[0]) {
                        if (Math.hypot(dx, dy) < slop) {
                            return true;
                        }
                        moved[0] = true;
                    }
                    int sw = c.getResources().getDisplayMetrics().widthPixels;
                    int sh = c.getResources().getDisplayMetrics().heightPixels;
                    View t = target[0];
                    if (t != null) {
                        // 限制在屏幕内，避免拖出屏外找不回来
                        t.setTranslationX(Math.max(-sw * 0.3f, Math.min(sw * 0.3f, start[0] + dx)));
                        t.setTranslationY(Math.max(-sh * 0.45f, Math.min(sh * 0.1f, start[1] + dy)));
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (moved[0] && target[0] != null) {
                        sp.edit().putFloat(dxKey, target[0].getTranslationX())
                                .putFloat(dyKey, target[0].getTranslationY()).apply();
                    }
                    android.view.ViewParent up = view.getParent();
                    if (up != null) {
                        up.requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                default:
                    return false;
            }
        });
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
