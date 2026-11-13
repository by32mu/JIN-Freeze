package com.jin.freeze.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
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

    /**
     * 自定义内容弹窗做真圆角：窗口底透明、内容铺 bg_dialog 并按 16dp 裁切、宽度收窄不贴屏边。
     * 需在 {@link Dialog#show()} 之后（onShow 回调里）调用。
     */
    public static void roundDialog(Dialog dialog, View root, int widthInsetDp) {
        roundDialog(dialog, root, widthInsetDp, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 指定高度版本：heightPx 传 WRAP_CONTENT 以外时固定高度，内部滚动区用 weight 撑开。 */
    public static void roundDialog(Dialog dialog, View root, int widthInsetDp, int heightPx) {
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        root.setBackgroundResource(R.drawable.bg_dialog);
        final int radius = dp(root.getContext(), 16);
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        root.setClipToOutline(true);
        int width = root.getContext().getResources().getDisplayMetrics().widthPixels
                - dp(root.getContext(), widthInsetDp);
        w.setLayout(width, heightPx);
    }

    public static int color(Context c, int id) {
        return ContextCompat.getColor(c, id);
    }

    public static int white() {
        return Color.WHITE;
    }
}
