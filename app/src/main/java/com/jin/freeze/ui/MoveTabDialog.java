package com.jin.freeze.ui;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jin.freeze.R;

import java.util.List;

/**
 * 「更改标签页」的统一弹窗：顶部显示应用图标与名称，下面列出所有标签，
 * 当前标签只做标记不参与点击，其余标签点一下即迁入。
 */
public final class MoveTabDialog {

    /** 一个可迁入的标签；current 表示应用当前所在标签。 */
    public static final class Tab {
        public final String id;
        public final String name;
        public final boolean visible;
        public final boolean current;

        public Tab(String id, String name, boolean visible, boolean current) {
            this.id = id;
            this.name = name;
            this.visible = visible;
            this.current = current;
        }
    }

    public interface OnPick {
        void onPick(Tab tab);
    }

    private MoveTabDialog() {
    }

    public static void show(android.content.Context c, CharSequence appLabel, Drawable icon,
                            List<Tab> tabs, OnPick onPick) {
        final Dialog dialog = new Dialog(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));

        // 应用图标放最上面，先确认这次要移动的是哪个应用
        ImageView head = new ImageView(c);
        if (icon != null) {
            head.setImageDrawable(icon);
        } else {
            head.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                Ui.dp(c, 38), Ui.dp(c, 38));
        headLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(head, headLp);

        TextView name = Ui.text(c, appLabel, 14, Ui.TEXT_PRIMARY, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Ui.dp(c, 6);
        nameLp.leftMargin = Ui.dp(c, 16);
        nameLp.rightMargin = Ui.dp(c, 16);
        root.addView(name, nameLp);

        TextView hint = Ui.text(c, "移动到", 12, Ui.TEXT_SECONDARY, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Ui.dp(c, 2);
        hintLp.bottomMargin = Ui.dp(c, 8);
        root.addView(hint, hintLp);

        divider(c, root, 14);
        for (Tab t : tabs) {
            root.addView(buildRow(c, dialog, t, onPick));
        }

        TextView cancel = Ui.text(c, "取消", 13, Ui.TEXT_SECONDARY, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackgroundResource(R.drawable.bg_menu_item);
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 38));
        cancelLp.leftMargin = Ui.dp(c, 8);
        cancelLp.rightMargin = Ui.dp(c, 8);
        cancelLp.topMargin = Ui.dp(c, 2);
        root.addView(cancel, cancelLp);

        dialog.setContentView(root);
        dialog.show();
        Ui.roundDialog(dialog, root, 40);
        if (dialog.getWindow() != null) {
            // 收窄到 300dp：标签名不长，弹窗再宽只会显得空
            int width = Math.min(c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 96),
                    Ui.dp(c, 300));
            dialog.getWindow().setLayout(Math.max(width, Ui.dp(c, 220)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static View buildRow(android.content.Context c, final Dialog dialog,
                                 final Tab t, final OnPick onPick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        row.setBackgroundResource(R.drawable.bg_menu_item);

        ImageView ic = new ImageView(c);
        ic.setImageResource(R.drawable.ic_action_tag);
        ic.setImageTintList(ColorStateList.valueOf(
                t.current ? Ui.TEXT_SECONDARY : Ui.primary(c)));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));

        String title = t.visible ? t.name : t.name + "（已隐藏）";
        TextView label = Ui.text(c, title, 13, t.current ? Ui.TEXT_SECONDARY : Ui.TEXT_PRIMARY, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(c, 10);
        row.addView(label, lp);

        if (t.current) {
            TextView tag = Ui.text(c, "当前", 11, Ui.TEXT_SECONDARY, false);
            row.addView(tag);
            row.setAlpha(0.55f);
        } else {
            row.setClickable(true);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                onPick.onPick(t);
            });
        }

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40));
        rowLp.leftMargin = Ui.dp(c, 5);
        rowLp.rightMargin = Ui.dp(c, 5);
        row.setLayoutParams(rowLp);
        return row;
    }

    private static void divider(android.content.Context c, LinearLayout parent, int insetDp) {
        View line = new View(c);
        line.setBackgroundColor(Ui.color(c, R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.5f)));
        lp.leftMargin = Ui.dp(c, insetDp);
        parent.addView(line, lp);
    }
}
