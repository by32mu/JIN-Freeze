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
 * 「加入标签」的统一弹窗：顶部显示应用图标与名称，下面列出所有标签。
 * 点一下勾选即加入，再点一下取消即移出；一个应用可以同时属于多个标签，
 * 勾选后弹窗不关闭，方便连续勾选，点「完成」退出。
 */
public final class MoveTabDialog {

    /** 一个可勾选的标签；current 表示应用当前是否属于该标签，弹窗会就地翻转。 */
    public static final class Tab {
        public final String id;
        public final String name;
        public final boolean visible;
        public boolean current;

        public Tab(String id, String name, boolean visible, boolean current) {
            this.id = id;
            this.name = name;
            this.visible = visible;
            this.current = current;
        }
    }

    public interface OnToggle {
        /** 收到时 current 已经是点击后的状态；回调里可以再改回去表示拒绝这次勾选。 */
        void onToggle(Tab tab);
    }

    private MoveTabDialog() {
    }

    public static void show(android.content.Context c, CharSequence appLabel, Drawable icon,
                            List<Tab> tabs, OnToggle onToggle) {
        final Dialog dialog = new Dialog(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));

        // 应用图标放最上面，先确认这次要处理的是哪个应用
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

        TextView hint = Ui.text(c, "加入标签（可多选，再点取消）", 12, Ui.TEXT_SECONDARY, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Ui.dp(c, 2);
        hintLp.bottomMargin = Ui.dp(c, 8);
        root.addView(hint, hintLp);

        divider(c, root, 14);
        for (Tab t : tabs) {
            root.addView(buildRow(c, t, onToggle));
        }

        TextView done = Ui.text(c, "完成", 13, Ui.primary(c), true);
        done.setGravity(Gravity.CENTER);
        done.setBackgroundResource(R.drawable.bg_menu_item);
        done.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 38));
        doneLp.leftMargin = Ui.dp(c, 8);
        doneLp.rightMargin = Ui.dp(c, 8);
        doneLp.topMargin = Ui.dp(c, 2);
        root.addView(done, doneLp);

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

    private static View buildRow(android.content.Context c, final Tab t,
                                 final OnToggle onToggle) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        row.setBackgroundResource(R.drawable.bg_menu_item);

        ImageView ic = new ImageView(c);
        ic.setImageResource(R.drawable.ic_action_tag);
        ic.setImageTintList(ColorStateList.valueOf(
                t.current ? Ui.primary(c) : Ui.TEXT_SECONDARY));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));

        String title = t.visible ? t.name : t.name + "（已隐藏）";
        TextView label = Ui.text(c, title, 13, Ui.TEXT_PRIMARY, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(c, 10);
        row.addView(label, lp);

        // 右端勾选标记：已加入的标签显示圆形对勾
        final ImageView check = new ImageView(c);
        check.setImageResource(R.drawable.ic_check_badge);
        check.setVisibility(t.current ? View.VISIBLE : View.GONE);
        row.addView(check, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));

        row.setClickable(true);
        row.setOnClickListener(v -> {
            t.current = !t.current;
            onToggle.onToggle(t);
            // 回调可能拒绝这次勾选（比如用户应用不能进系统应用标签），按最终状态刷新
            check.setVisibility(t.current ? View.VISIBLE : View.GONE);
            ic.setImageTintList(ColorStateList.valueOf(
                    t.current ? Ui.primary(c) : Ui.TEXT_SECONDARY));
        });

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
