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

        /** 是否在冻结名单里：决定「移除列表并解冻」是否出现。 */
        boolean inList();

        void removeAndUnfreeze();
    }

    private AppActionSheet() {
    }

    public static void show(Context c, String label, Drawable icon, boolean frozen, Actions actions) {
        final BottomSheetDialog sheet = new BottomSheetDialog(c);
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_sheet);
        content.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 12));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(c, 20), Ui.dp(c, 14), Ui.dp(c, 20), Ui.dp(c, 12));
        ImageView headIcon = new ImageView(c);
        if (icon != null) {
            headIcon.setImageDrawable(icon);
        } else {
            headIcon.setImageResource(R.drawable.ic_launcher);
        }
        header.addView(headIcon, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        TextView headName = Ui.text(c, label, 16, Ui.TEXT_PRIMARY, true);
        headName.setSingleLine(true);
        headName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = Ui.dp(c, 14);
        header.addView(headName, nameLp);
        content.addView(header);
        divider(c, content, 20);

        addItem(c, content, sheet, R.drawable.ic_action_run, "运行", actions::run);
        addItem(c, content, sheet, frozen ? R.drawable.ic_sc_unfreeze : R.drawable.ic_sc_freeze,
                frozen ? "解冻" : "冻结", actions::toggleFreeze);
        divider(c, content, 20);
        addItem(c, content, sheet, R.drawable.ic_action_tag, "更改标签页", actions::moveTab);
        addItem(c, content, sheet, R.drawable.ic_settings, "在其他应用中配置", actions::appSettings);
        addItem(c, content, sheet, R.drawable.ic_action_home, "添加到桌面", actions::pinHome);
        if (actions.inList()) {
            divider(c, content, 20);
            addItem(c, content, sheet, R.drawable.ic_minus, "移除列表并解冻", actions::removeAndUnfreeze);
        }

        ScrollView sv = new ScrollView(c);
        sv.addView(content);
        sheet.setContentView(sv);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
    }

    private static void addItem(Context c, LinearLayout parent, final BottomSheetDialog sheet,
                                int iconRes, String text, final Runnable action) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 20), 0, Ui.dp(c, 20), 0);
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setClickable(true);
        ImageView ic = new ImageView(c);
        ic.setImageResource(iconRes);
        ic.setImageTintList(ColorStateList.valueOf(Ui.primary(c)));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
        TextView label = Ui.text(c, text, 14, Ui.TEXT_PRIMARY, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(c, 14);
        row.addView(label, lp);
        row.setOnClickListener(v -> {
            sheet.dismiss();
            action.run();
        });
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 52)));
    }

    private static void divider(Context c, LinearLayout parent, int insetDp) {
        View line = new View(c);
        line.setBackgroundColor(Ui.color(c, R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.5f)));
        lp.leftMargin = Ui.dp(c, insetDp);
        parent.addView(line, lp);
    }
}
