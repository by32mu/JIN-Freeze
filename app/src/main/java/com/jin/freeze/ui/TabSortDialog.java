package com.jin.freeze.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jin.freeze.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签排序弹窗：只保留上下拖动这一种手势，其他操作全部屏蔽。
 * 拖动过程只动弹窗里的临时顺序，点「完成」才回写。
 */
public final class TabSortDialog {

    public static final class Tab {
        public final String id;
        public final String name;

        public Tab(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public interface OnDone {
        void onDone(List<Tab> ordered);
    }

    private TabSortDialog() {
    }

    public static void show(Context c, List<Tab> tabs, OnDone onDone, int xOffsetPx,
                            int surfaceAlpha) {
        // 与其它弹窗统一：浮动窗口 + 圆角底板（窗口底仍是透明的，圆角由内容卡片负责）
        final Dialog dialog = new Dialog(c, com.jin.freeze.R.style.Theme_Jin_Alert);
        final List<Tab> order = new ArrayList<>(tabs);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));

        TextView title = Ui.text(c, "标签排序", 14, Ui.TEXT_PRIMARY, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = Ui.text(c, "按住右侧把手上下拖动，点完成保存", 11, Ui.TEXT_SECONDARY, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Ui.dp(c, 2);
        hintLp.bottomMargin = Ui.dp(c, 6);
        root.addView(hint, hintLp);

        RecyclerView rv = new RecyclerView(c);
        rv.setLayoutManager(new LinearLayoutManager(c));
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final SortAdapter adapter = new SortAdapter(c, order, surfaceAlpha);
        rv.setAdapter(adapter);
        int rowPx = Ui.dp(c, 42);
        int maxPx = Ui.dp(c, 300);
        int height = Math.min(maxPx, Math.max(rowPx, rowPx * order.size()));
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        rvLp.leftMargin = Ui.dp(c, 6);
        rvLp.rightMargin = Ui.dp(c, 6);
        root.addView(rv, rvLp);

        ItemTouchHelper touch = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv1,
                                  @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition();
                int b = to.getBindingAdapterPosition();
                if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION || a == b) {
                    return false;
                }
                order.add(b, order.remove(a));
                adapter.notifyItemMoved(a, b);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                // 只排序，不删除
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // 整行按住即可拖动，不要求先点把手
                return true;
            }
        });
        touch.attachToRecyclerView(rv);

        TextView done = Ui.text(c, "完成", 13, Ui.color(c, R.color.blue), true);
        done.setGravity(Gravity.CENTER);
        done.setBackgroundResource(R.drawable.bg_menu_item);
        done.setOnClickListener(v -> {
            dialog.dismiss();
            onDone.onDone(order);
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40));
        doneLp.leftMargin = Ui.dp(c, 8);
        doneLp.rightMargin = Ui.dp(c, 8);
        doneLp.topMargin = Ui.dp(c, 4);
        root.addView(done, doneLp);

        dialog.setContentView(root);
        // 排序期间只接受拖动：点弹窗外不关闭，避免误触丢结果
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        Ui.roundDialog(dialog, root, 18);
        // 排序弹窗不要把背后的列表压暗：卡片透明时压暗会糊成一片灰
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0f);
        }
        // 跟随设置页透明度：面板边框与内部按钮一起变
        applyAlpha(root, surfaceAlpha);
        if (dialog.getWindow() != null) {
            // 与「设置」面板等宽：屏宽减 104dp、上限 304dp
            int width = Math.min(c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 104),
                    Ui.dp(c, 304));
            dialog.getWindow().setLayout(Math.max(width, Ui.dp(c, 220)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            // 跟随设置面板的拖动偏移，保证左边缘对齐
            android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.x = xOffsetPx;
            dialog.getWindow().setAttributes(lp);
        }
    }

    /** 把透明度铺到整棵视图树；标签行是 RecyclerView 动态创建的，另外在行里单独刷。 */
    private static void applyAlpha(View v, int alpha) {
        if (v == null) {
            return;
        }
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg != null) {
            bg.mutate().setAlpha(alpha);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyAlpha(g.getChildAt(i), alpha);
            }
        }
    }

    private static final class SortAdapter extends RecyclerView.Adapter<SortAdapter.VH> {
        private final Context c;
        private final List<Tab> data;
        private final int surfaceAlpha;

        SortAdapter(Context c, List<Tab> data, int surfaceAlpha) {
            this.c = c;
            this.data = data;
            this.surfaceAlpha = surfaceAlpha;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
            row.setBackgroundResource(R.drawable.bg_menu_item);
            if (row.getBackground() != null) {
                row.getBackground().mutate().setAlpha(surfaceAlpha);
            }

            TextView name = Ui.text(c, "", 13, Ui.TEXT_PRIMARY, false);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView handle = new ImageView(c);
            handle.setImageResource(R.drawable.ic_drag_handle);
            LinearLayout.LayoutParams handleLp =
                    new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 16));
            handleLp.leftMargin = Ui.dp(c, 8);
            row.addView(handle, handleLp);

            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 42)));
            return new VH(row, name);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.name.setText(data.get(position).name);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView name;

            VH(View itemView, TextView name) {
                super(itemView);
                this.name = name;
            }
        }
    }
}
