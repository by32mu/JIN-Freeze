package com.jin.freeze.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jin.freeze.R;
import com.jin.freeze.core.ShortcutSync;

/**
 * 老式快捷方式提供者（ACTION_CREATE_SHORTCUT）。
 * 「手势 → 启动快捷方式」这类入口会枚举提供者，再 startActivityForResult 拿结果；
 * 本页弹出三条选择（打开列表 / 一键冻结 / 一键解冻），选中后返回
 * EXTRA_SHORTCUT_INTENT、EXTRA_SHORTCUT_NAME 与位图 EXTRA_SHORTCUT_ICON。
 * 图标必须是位图：外部进程拿不到本应用主题，资源 id 会变黑块。
 */
public class ShortcutProviderActivity extends Activity {

    private static final int IDX_OPEN_LIST = 0;
    private static final int IDX_FREEZE = 1;
    private static final int IDX_UNFREEZE = 2;

    private final CheckBox[] boxes = new CheckBox[3];
    private int selected = IDX_OPEN_LIST;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        String action = incoming == null ? null : incoming.getAction();

        // 手势工具可能直接执行之前返回过的 Intent：原样转给无界面的执行页
        if (QuickLaunchActivity.ACTION_OPEN_FREEZE_LIST.equals(action)
                || QuickLaunchActivity.ACTION_FREEZE_ALL.equals(action)
                || QuickLaunchActivity.ACTION_UNFREEZE_ALL.equals(action)) {
            startActivity(new Intent(this, QuickLaunchActivity.class).setAction(action));
            finish();
            return;
        }
        if (!Intent.ACTION_CREATE_SHORTCUT.equals(action)) {
            android.widget.Toast.makeText(this, "请通过桌面长按或手势工具添加快捷方式",
                    android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        String[] names = {
                getString(R.string.sc_open_list),
                getString(R.string.sc_freeze_all),
                getString(R.string.sc_unfreeze_all)
        };
        int[] icons = {
                R.drawable.ic_shortcut_list,
                R.drawable.ic_shortcut_freeze,
                R.drawable.ic_shortcut_unfreeze
        };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 18), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
            row.setMinimumHeight(Ui.dp(this, 48));
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
            TextView name = new TextView(this);
            name.setText(names[i]);
            name.setTextSize(15);
            name.setTextColor(Ui.primary(this));
            name.setTypeface(Typeface.DEFAULT);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nameLp.leftMargin = Ui.dp(this, 14);
            row.addView(name, nameLp);
            CheckBox cb = new CheckBox(this);
            cb.setClickable(false);
            cb.setFocusable(false);
            row.addView(cb, new LinearLayout.LayoutParams(
                    Ui.dp(this, 28), Ui.dp(this, 28)));
            boxes[i] = cb;
            row.setOnClickListener(v -> select(idx));
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        select(IDX_OPEN_LIST);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择快捷方式")
                .setView(list)
                .setPositiveButton("确定", (d, w) -> deliver(selected))
                .setNegativeButton("取消", (d, w) -> cancel())
                .setOnCancelListener(d -> cancel())
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            android.view.Window w = dialog.getWindow();
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 120);
            w.setAttributes(lp);
            w.setBackgroundDrawableResource(R.drawable.bg_dialog);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                w.getDecorView().setForceDarkAllowed(false);
            }
        }
    }

    private void select(int idx) {
        selected = idx;
        for (int i = 0; i < boxes.length; i++) {
            boxes[i].setChecked(i == idx);
        }
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private void deliver(int which) {
        Intent target;
        String name;
        int icon;
        if (which == IDX_FREEZE) {
            target = new Intent(this, QuickLaunchActivity.class)
                    .setAction(QuickLaunchActivity.ACTION_FREEZE_ALL);
            name = getString(R.string.sc_freeze_all);
            icon = R.drawable.ic_shortcut_freeze;
        } else if (which == IDX_UNFREEZE) {
            target = new Intent(this, QuickLaunchActivity.class)
                    .setAction(QuickLaunchActivity.ACTION_UNFREEZE_ALL);
            name = getString(R.string.sc_unfreeze_all);
            icon = R.drawable.ic_shortcut_unfreeze;
        } else {
            target = new Intent(this, QuickLaunchActivity.class)
                    .setAction(QuickLaunchActivity.ACTION_OPEN_FREEZE_LIST);
            name = getString(R.string.sc_open_list);
            icon = R.drawable.ic_shortcut_list;
        }
        Intent result = new Intent();
        result.putExtra(Intent.EXTRA_SHORTCUT_INTENT, target);
        result.putExtra(Intent.EXTRA_SHORTCUT_NAME, name);
        result.putExtra(Intent.EXTRA_SHORTCUT_ICON, ShortcutSync.renderIcon(this, icon));
        setResult(RESULT_OK, result);
        finish();
    }
}
