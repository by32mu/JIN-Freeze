package com.jin.freeze.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.jin.freeze.R;
import com.jin.freeze.core.PrivShell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用冻结：pm disable-user / pm enable 冻结与解冻。
 * 列表为纯图标网格（不显示应用名/包名）：点击打开应用，长按冻结/解冻；
 * 顶栏「批量」进入多选，底部按钮批量冻结或解冻。
 */
public class AppFreezeActivity extends BaseActivity {

    private static final String PREFS = "app_freeze";
    private static final String KEY_SKIP_CONFIRM = "skip_confirm";
    /** 选择模式：作为「冻结列表」的选应用页，勾选后回传包名，不执行冻结操作。 */
    public static final String EXTRA_PICK = "pick_mode";
    public static final String EXTRA_PICKED = "picked";
    /** 每行图标数。 */
    private static final int COLUMNS = 4;
    /** 显示方式：0 网格（默认）、1 一行一条；切换后记住，下次进入沿用。 */
    private static final String KEY_LIST_VIEW = "app_list_view";

    private static final class App {
        String label;
        String pkg;
        Drawable icon;
        boolean system;
        boolean frozen;
    }

    /** 一个格子的视图引用：状态变化时原地更新，过滤/搜索时重建。 */
    private static final class Cell {
        LinearLayout root;
        ImageView icon;
    }

    private final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<App> all = new ArrayList<>();
    private final Map<String, Cell> cells = new HashMap<>();
    private final Set<String> selected = new HashSet<>();

    private LinearLayout grid;
    private EditText search;
    private TextView filterUser;
    private TextView filterSystem;
    private TextView sysHint;
    private TextView rootState;
    private TextView viewModeBtn;    private TextView batchBtn;
    private LinearLayout batchBar;
    private TextView freezeBtn;
    private TextView unfreezeBtn;

    private boolean showSystem;
    private boolean root;
    private boolean loaded;
    private boolean batchMode;
    private boolean working;
    private boolean refreshing;
    private boolean pickMode;

    /** 已冻结图标的灰度滤镜：整体变灰 + 降低不透明度，一眼区分。 */
    private static final android.graphics.ColorMatrixColorFilter GRAY;

    static {
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(0f);
        GRAY = new android.graphics.ColorMatrixColorFilter(cm);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_freeze);

        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
        ((TextView) findViewById(R.id.title)).setText(pickMode ? "选择应用" : "应用冻结");
        findViewById(R.id.back).setOnClickListener(v -> {
            if (!goBack()) {
                finish();
            }
        });
        grid = findViewById(R.id.grid);
        search = findViewById(R.id.search);
        filterUser = findViewById(R.id.filter_user);
        filterSystem = findViewById(R.id.filter_system);
        sysHint = findViewById(R.id.sys_hint);
        rootState = findViewById(R.id.root_state);
        batchBtn = findViewById(R.id.batch);
        batchBar = findViewById(R.id.batch_bar);
        freezeBtn = findViewById(R.id.btn_freeze);
        unfreezeBtn = findViewById(R.id.btn_unfreeze);

        filterUser.setOnClickListener(v -> switchFilter(false));
        filterSystem.setOnClickListener(v -> switchFilter(true));
        switchFilter(false);

        viewModeBtn = findViewById(R.id.view_mode);
        viewModeBtn.setText(listView() ? "网格" : "列表");
        viewModeBtn.setOnClickListener(v -> {
            prefs().edit().putBoolean(KEY_LIST_VIEW, !listView()).apply();
            viewModeBtn.setText(listView() ? "网格" : "列表");
            render(search.getText().toString().trim());
        });

        batchBtn.setText(pickMode ? "确定" : "批量");        batchBar.setVisibility(View.GONE);
        batchBtn.setOnClickListener(v -> {
            if (pickMode) {
                finishWithPicked();
            } else {
                setBatchMode(!batchMode);
            }
        });
        freezeBtn.setOnClickListener(v -> {
            if (selected.isEmpty() || working) {
                return;
            }
            runBatch(true);
        });
        unfreezeBtn.setOnClickListener(v -> {
            if (selected.isEmpty() || working) {
                return;
            }
            runBatch(false);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render(s.toString().trim());
                // 过滤后状态仍以系统真值重新读一次（防抖，避免每敲一个字都跑 su）
                main.removeCallbacks(refreshTask);
                main.postDelayed(refreshTask, 400);
            }
        });

        loadApps();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean listView() {
        return prefs().getBoolean(KEY_LIST_VIEW, false);
    }

    @Override
    public void onBackPressed() {
        if (!goBack()) {
            super.onBackPressed();
        }
    }

    /** 逐层返回：先退出搜索结果，再退出多选，最后才关闭页面。 */
    private boolean goBack() {
        if (search != null && search.getText().length() > 0) {
            search.setText("");
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            }
            search.clearFocus();
            return true;
        }
        if (batchMode) {
            setBatchMode(false);
            return true;
        }
        return false;
    }

    private void loadApps() {
        IO.execute(() -> {
            PrivShell.Result id = PrivShell.exec("id", 15);
            final boolean hasPriv = id.out != null && id.out.contains("uid=0")
                    || PrivShell.mode() != PrivShell.Mode.NONE;

            // pm list packages -d 是系统侧的冻结状态，比 PackageInfo 的 enabled 更准
            Set<String> disabled = new HashSet<>();
            if (hasPriv) {
                PrivShell.Result d = PrivShell.exec("pm list packages -d --user 0", 30);
                if (d.out != null) {
                    for (String line : d.out.split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("package:")) {
                            disabled.add(t.substring("package:".length()).trim());
                        }
                    }
                }
            }

            List<App> apps = new ArrayList<>();
            PackageManager pm = getPackageManager();
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null) {
                    continue;
                }
                App a = new App();
                a.label = pm.getApplicationLabel(ai).toString();
                a.pkg = pi.packageName;
                a.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                a.frozen = disabled.contains(a.pkg) || !ai.enabled;
                // 核心系统应用不进入任何选择/浏览列表
                if (com.jin.freeze.core.CoreProtect.isProtected(this, a.pkg, a.label)) {
                    continue;
                }
                try {
                    a.icon = ai.loadIcon(pm);
                } catch (Exception ignored) {
                }
                apps.add(a);
            }
            // 选择模式：展示全部应用，已在名单里的也保留，长按可与半屏一样「移除列表并解冻」
            Collections.sort(apps, (x, y) -> {
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(x.label, y.label);
                return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(x.pkg, y.pkg);
            });
            final List<App> shownApps = apps;

            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                root = hasPriv;
                rootState.setText(pickMode
                        ? "点击图标勾选加入名单；长按可运行 / 冻结 / 解冻 / 移除列表"
                        : hasPriv
                        ? "已通过 " + PrivShell.label() + " 执行：点击图标打开应用，长按冻结/解冻"
                        : "未获得 root 或 Shizuku 权限：冻结/解冻不可用，请在半屏布局设置里授权");
                all.clear();
                all.addAll(shownApps);
                loaded = true;
                render(search.getText().toString().trim());
            });
        });
    }

    private final Runnable refreshTask = this::refreshFrozen;

    private void switchFilter(boolean system) {
        showSystem = system;
        filterUser.setBackgroundResource(system ? R.drawable.bg_btn_white : R.drawable.bg_btn_blue);
        filterUser.setTextColor(system ? getColor(R.color.blue) : 0xFFFFFFFF);
        filterSystem.setBackgroundResource(system ? R.drawable.bg_btn_blue : R.drawable.bg_btn_white);
        filterSystem.setTextColor(system ? 0xFFFFFFFF : getColor(R.color.blue));
        sysHint.setVisibility(system ? View.VISIBLE : View.GONE);
        render(search.getText().toString().trim());
        refreshFrozen();
    }

    /** 搜索与「用户应用 / 系统应用」取交集；每次重建都从 App.frozen 读状态，不会因过滤丢状态。 */
    private void render(String query) {
        if (grid == null) {
            return;
        }
        grid.removeAllViews();
        cells.clear();
        if (!loaded) {
            grid.addView(emptyText("正在读取应用列表…"));
            return;
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<App> shown = new ArrayList<>();
        for (App a : all) {
            if (a.system != showSystem) {
                continue;
            }
            if (!q.isEmpty()
                    && !a.label.toLowerCase(Locale.ROOT).contains(q)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            shown.add(a);
        }
        if (shown.isEmpty()) {
            grid.addView(emptyText(pickMode && q.isEmpty() && !showSystem
                    ? "名单里的应用也会显示，长按可移除列表并解冻"
                    : "没有匹配的应用"));
            return;
        }
        // 固定按名称排序：同名时再按包名定序，保证任何情况下顺序稳定、不随冻结状态跳动
        Collections.sort(shown, (x, y) -> {
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(x.label, y.label);
            return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(x.pkg, y.pkg);
        });
        if (listView()) {
            for (App a : shown) {
                grid.addView(buildRow(a));
            }
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = Ui.dp(this, 6);
                row.setLayoutParams(rlp);
                grid.addView(row);
            }
            row.addView(buildCell(shown.get(i)));
        }
        // 最后一行补空位，保证每格宽度一致
        if (row != null) {
            int left = COLUMNS - shown.size() % COLUMNS;
            if (left < COLUMNS) {
                for (int i = 0; i < left; i++) {
                    View gap = new View(this);
                    row.addView(gap, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
            }
        }
    }

    private TextView emptyText(String s) {
        TextView t = Ui.text(this, s, 13, Ui.TEXT_SECONDARY, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
        return t;
    }

    private View buildCell(App a) {
        FrameLayout wrap = new FrameLayout(this);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wrapLp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        wrap.setLayoutParams(wrapLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 10));
        box.setBackgroundResource(R.drawable.bg_card);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        box.setForeground(getDrawable(tv.resourceId));
        box.setClickable(true);
        box.setLongClickable(true);

        ImageView icon = new ImageView(this);
        if (a.icon != null) {
            icon.setImageDrawable(a.icon);
        } else {
            icon.setImageResource(R.drawable.ic_launcher);
        }
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        // 真裁切圆角（随尺寸自适应），不加白色卡片底
        int iconPx = Ui.dp(this, 46);
        android.graphics.drawable.GradientDrawable round =
                new android.graphics.drawable.GradientDrawable();
        round.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        round.setColor(0x00000000);
        round.setCornerRadius(Math.max(Ui.dp(this, 6), (int) (iconPx * 0.18f)));
        icon.setBackground(round);
        icon.setClipToOutline(true);

        // 图标下方一行应用名：居中、单行、超长省略；状态仍只由图标灰度表达
        TextView name = Ui.text(this, a.label, 11, Ui.TEXT_PRIMARY, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Ui.dp(this, 6);
        nameLp.leftMargin = Ui.dp(this, 2);
        nameLp.rightMargin = Ui.dp(this, 2);
        box.addView(name, nameLp);

        wrap.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Cell cell = new Cell();
        cell.root = box;
        cell.icon = icon;
        cells.put(a.pkg, cell);
        bindCell(a, cell);

        box.setContentDescription(a.label);
        bindActions(a, cell, box);
        return wrap;
    }

    /** 一行一条：左图标 + 右名称，点击/长按行为与网格格子完全一致。 */
    private View buildRow(App a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 6);
        row.setLayoutParams(rlp);
        row.setBackgroundResource(R.drawable.bg_card);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(getDrawable(tv.resourceId));
        row.setClickable(true);
        row.setLongClickable(true);

        ImageView icon = new ImageView(this);
        if (a.icon != null) {
            icon.setImageDrawable(a.icon);
        } else {
            icon.setImageResource(R.drawable.ic_launcher);
        }
        int iconPx = Ui.dp(this, 36);
        android.graphics.drawable.GradientDrawable round =
                new android.graphics.drawable.GradientDrawable();
        round.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        round.setColor(0x00000000);
        round.setCornerRadius(Math.max(Ui.dp(this, 5), (int) (iconPx * 0.18f)));
        icon.setBackground(round);
        icon.setClipToOutline(true);
        row.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));

        TextView name = Ui.text(this, a.label, 14, Ui.TEXT_PRIMARY, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = Ui.dp(this, 12);
        row.addView(name, nameLp);

        Cell cell = new Cell();
        cell.root = row;
        cell.icon = icon;
        cells.put(a.pkg, cell);
        bindCell(a, cell);

        row.setContentDescription(a.label);
        bindActions(a, cell, row);
        return row;
    }

    /** 网格/列表共用的点击与长按行为。 */
    private void bindActions(App a, Cell cell, View target) {
        target.setOnClickListener(v -> {
            if (pickMode || batchMode) {
                if (selected.contains(a.pkg)) {
                    selected.remove(a.pkg);
                } else {
                    selected.add(a.pkg);
                }
                bindCell(a, cell);
                updateBatchBar();
                return;
            }
            // 单击一律打开；已冻结的这一次点击内部先解冻再启动
            if (a.frozen) {
                unfreezeAndOpen(a);
            } else {
                launch(a);
            }
        });
        target.setOnLongClickListener(v -> {
            if (pickMode) {
                // 与半屏列表共用同一套操作单，不再单独维护一份「解冻确认」
                showAppSheet(a);
                return true;
            }
            if (batchMode) {
                return false;
            }
            toggle(a);
            return true;
        });
    }

    /** 底部操作单：选项与顺序由 AppActionSheet 统一，行为映射到本页已有逻辑。 */
    private void showAppSheet(App a) {
        AppActionSheet.show(this, a.label, a.icon, a.frozen, new AppActionSheet.Actions() {
            @Override
            public void run() {
                if (a.frozen) {
                    unfreezeAndOpen(a);
                } else {
                    launch(a);
                }
            }

            @Override
            public void toggleFreeze() {
                if (a.frozen) {
                    doPickUnfreeze(a, cells.get(a.pkg));
                } else {
                    toggle(a);
                }
            }

            @Override
            public void moveTab() {
                moveToTab(a);
            }

            @Override
            public void appSettings() {
                openAppSettings(a.pkg);
            }

            @Override
            public void pinHome() {
                AppFreezeActivity.this.pinToHome(a);
            }

            @Override
            public boolean inList() {
                return inFreezeList(a.pkg);
            }

            @Override
            public void removeAndUnfreeze() {
                removeFromList(a);
            }
        });
    }

    private SharedPreferences listPrefs() {
        return getSharedPreferences("freeze_list", MODE_PRIVATE);
    }

    private boolean inFreezeList(String pkg) {
        return new HashSet<>(listPrefs().getStringSet("pkgs", new HashSet<>())).contains(pkg);
    }

    /** 与「冻结列表」里的移除入口同一语义：先摘名单与标签归属，再解冻；解冻失败单独报错。 */
    private void removeFromList(App a) {
        SharedPreferences fp = listPrefs();
        Set<String> cur = new HashSet<>(fp.getStringSet("pkgs", new HashSet<>()));
        cur.remove(a.pkg);
        SharedPreferences.Editor ed = fp.edit().putStringSet("pkgs", cur);
        String raw = fp.getString("order", "");
        if (!raw.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                String p = line.trim();
                if (p.isEmpty() || p.equals(a.pkg)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p);
            }
            ed.putString("order", sb.toString());
        }
        try {
            JSONObject o = new JSONObject(fp.getString("app_tabs", "{}"));
            if (o.has(a.pkg)) {
                o.remove(a.pkg);
                ed.putString("app_tabs", o.toString());
            }
        } catch (Exception ignored) {
        }
        ed.apply();
        if (!a.frozen) {
            Toast.makeText(this, "已移出名单", Toast.LENGTH_SHORT).show();
            return;
        }
        doPickUnfreeze(a, cells.get(a.pkg));
    }

    /** 更改标签页：读取冻结列表的标签配置，写回 app_tabs，与列表页共用同一份数据。 */
    private void moveToTab(App a) {
        SharedPreferences fp = listPrefs();
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(fp.getString("tabs", ""));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String id = o.optString("id", "");
                String name = o.optString("name", "");
                if (!id.isEmpty() && !name.isEmpty()) {
                    ids.add(id);
                    names.add(name);
                }
            }
        } catch (Exception ignored) {
        }
        if (ids.isEmpty()) {
            ids.add("user");
            names.add("用户应用");
            ids.add("system");
            names.add("系统应用");
        }
        String cur = "";
        try {
            cur = new JSONObject(fp.getString("app_tabs", "{}")).optString(a.pkg, "");
        } catch (Exception ignored) {
        }
        List<String> targetIds = new ArrayList<>();
        List<String> targetNames = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (!ids.get(i).equals(cur)) {
                targetIds.add(ids.get(i));
                targetNames.add(names.get(i));
            }
        }
        if (targetIds.isEmpty()) {
            Toast.makeText(this, "只有一个标签，先在布局设置里新建标签", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("移动「" + a.label + "」到…")
                .setItems(targetNames.toArray(new String[0]), (d, w) -> {
                    try {
                        JSONObject o = new JSONObject(fp.getString("app_tabs", "{}"));
                        o.put(a.pkg, targetIds.get(w));
                        fp.edit().putString("app_tabs", o.toString()).apply();
                        Toast.makeText(this, "已移动到「" + targetNames.get(w) + "」",
                                Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openAppSettings(String pkg) {
        Intent detail = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + pkg));
        detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(detail);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void pinToHome(App a) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (launchIntent == null || launchIntent.getComponent() == null) {
            Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
            return;
        }
        com.jin.freeze.core.LaunchTarget.Target target =
                new com.jin.freeze.core.LaunchTarget.Target(a.pkg,
                        launchIntent.getComponent().getClassName(), a.label);
        ShortcutPin.pin(this, "frozen_" + a.pkg, a.label, a.icon, target);
    }

    private void bindCell(App a, Cell cell) {
        // 已冻结：整体灰化并压暗（像按下去的状态）；未冻结保持彩色。不加角标/文字/边框。
        if (a.frozen) {
            cell.icon.setColorFilter(GRAY);
            cell.icon.setAlpha(0.45f);
        } else {
            cell.icon.clearColorFilter();
            cell.icon.setAlpha(1f);
        }
        boolean sel = (batchMode || pickMode) && selected.contains(a.pkg);
        cell.root.setBackgroundResource(sel
                ? R.drawable.bg_card_selected : R.drawable.bg_card);
    }

    /** 添加页内解冻：成功静默恢复彩色，失败展示 pm 真实输出。 */
    private void doPickUnfreeze(App a, Cell cell) {
        IO.execute(() -> {
            String out = execPm(a.pkg, false);
            boolean ok = isPmOk(out);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (ok) {
                    a.frozen = false;
                    bindCell(a, cell);
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("解冻失败")
                            .setMessage(out == null || out.trim().isEmpty()
                                    ? "pm 未返回输出" : out.trim())
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        });
    }

    /** 选择模式确认：把勾选的包名回传给「冻结列表」。 */
    private void finishWithPicked() {
        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_PICKED, new ArrayList<>(selected));
        setResult(RESULT_OK, data);
        finish();
    }

    /** 用 pm 的系统真值刷新所有应用状态（别的工具冻结的也能显示成已冻结）。 */
    private void refreshFrozen() {
        if (refreshing || !root || !loaded) {
            return;
        }
        refreshing = true;
        IO.execute(() -> {
            PrivShell.Result d = PrivShell.exec("pm list packages -d --user 0", 30);
            Set<String> disabled = new HashSet<>();
            boolean ok = d.out != null && d.out.contains("package:");
            if (ok) {
                for (String line : d.out.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("package:")) {
                        disabled.add(t.substring("package:".length()).trim());
                    }
                }
            }
            main.post(() -> {
                refreshing = false;
                if (isFinishing() || isDestroyed() || !ok) {
                    return;
                }
                for (App a : all) {
                    a.frozen = disabled.contains(a.pkg);
                }
                // 状态变化后按「已冻结在前」的规则重排，避免状态变了位置不动
                render(search.getText().toString().trim());
            });
        });
    }

    private void setBatchMode(boolean on) {
        batchMode = on;
        selected.clear();
        batchBtn.setText(on ? "完成" : "批量");
        batchBar.setVisibility(on ? View.VISIBLE : View.GONE);
        for (App a : all) {
            Cell cell = cells.get(a.pkg);
            if (cell != null) {
                bindCell(a, cell);
            }
        }
        updateBatchBar();
    }

    private void updateBatchBar() {
        if (pickMode) {
            batchBtn.setText(selected.isEmpty() ? "确定" : "确定(" + selected.size() + ")");
            return;
        }
        boolean has = !selected.isEmpty() && !working;
        freezeBtn.setEnabled(has);
        freezeBtn.setAlpha(has ? 1f : 0.4f);
        unfreezeBtn.setEnabled(has);
        unfreezeBtn.setAlpha(has ? 1f : 0.4f);
    }

    private void toggle(App a) {
        if (!root) {
            Toast.makeText(this, "需要 root 或 Shizuku 权限才能冻结/解冻", Toast.LENGTH_SHORT).show();
            return;
        }
        if (working) {
            return;
        }
        boolean toFreeze = !a.frozen;
        if (toFreeze && com.jin.freeze.core.CoreProtect.isProtected(this, a.pkg, a.label)) {
            Toast.makeText(this, "「" + a.label + "」是核心系统应用，已保护，不能冻结",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (prefs().getBoolean(KEY_SKIP_CONFIRM, false)) {
            runToggle(a);
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        CheckBox never = new CheckBox(this);
        never.setText("以后不再提示");
        never.setTextSize(12);
        never.setTextColor(Ui.secondary(this));
        box.addView(never);
        new AlertDialog.Builder(this)
                .setTitle((toFreeze ? "冻结 " : "解冻 ") + a.label)
                .setMessage(a.pkg + (toFreeze
                        ? "\n\n冻结后应用将无法运行，桌面入口也会消失。"
                        : "\n\n解冻后应用恢复正常使用。"))
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    if (never.isChecked()) {
                        prefs().edit().putBoolean(KEY_SKIP_CONFIRM, true).apply();
                    }
                    runToggle(a);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 单条切换：成功后原地更新该格状态，失败把 pm 的真实输出原样展示。 */
    private void runToggle(App a) {
        final boolean toFreeze = !a.frozen;
        working = true;
        IO.execute(() -> {
            String out = execPm(a.pkg, toFreeze);
            boolean ok = isPmOk(out);
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    new AlertDialog.Builder(this)
                            .setTitle(toFreeze ? "冻结失败" : "解冻失败")
                            .setMessage(a.pkg + "\n\n" + out)
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                a.frozen = toFreeze;
                render(search.getText().toString().trim());
                updateBatchBar();
                refreshFrozen();
                Toast.makeText(this, (toFreeze ? "已冻结 " : "已解冻 ") + a.label,
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** 已冻结应用单击：内部先解冻再启动，一次点击完成；失败给出 pm 的真实原因。 */
    private void unfreezeAndOpen(App a) {
        if (!root) {
            Toast.makeText(this, "需要 root 或 Shizuku 权限才能解冻并打开", Toast.LENGTH_SHORT).show();
            return;
        }
        if (working) {
            return;
        }
        working = true;
        IO.execute(() -> {
            String out = execPm(a.pkg, false);
            boolean ok = isPmOk(out);
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    new AlertDialog.Builder(this)
                            .setTitle("解冻失败，无法打开")
                            .setMessage(a.pkg + "\n\n" + out)
                            .setPositiveButton("知道了", null)
                            .show();
                    refreshFrozen();
                    return;
                }
                a.frozen = false;
                render(search.getText().toString().trim());
                refreshFrozen();
                launch(a);
            });
        });
    }

    /** 批量冻结/解冻选中的应用：逐条执行并汇总成功/失败，失败项带 pm 原始报错。 */
    private void runBatch(boolean freeze) {
        // 执行层兜底：核心应用即使被选中也直接跳过，不执行 pm disable-user
        final List<String> targets = new ArrayList<>();
        int protectedCount = 0;
        for (String pkg : selected) {
            if (freeze && com.jin.freeze.core.CoreProtect.isProtected(this, pkg, labelOf(pkg))) {
                protectedCount++;
            } else {
                targets.add(pkg);
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, protectedCount + " 个应用受核心保护，已跳过", Toast.LENGTH_SHORT).show();
            return;
        }
        final int protectedFinal = protectedCount;
        working = true;
        updateBatchBar();
        freezeBtn.setText(freeze ? "冻结中…" : "一键冻结");
        unfreezeBtn.setText(freeze ? "一键解冻" : "解冻中…");
        IO.execute(() -> {
            int okCount = 0;
            List<String> fails = new ArrayList<>();
            for (String pkg : targets) {
                String out = execPm(pkg, freeze);
                if (isPmOk(out)) {
                    okCount++;
                } else {
                    fails.add(labelOf(pkg) + "（" + pkg + "）\n" + out);
                }
            }
            final int okFinal = okCount;
            main.post(() -> {
                working = false;
                freezeBtn.setText("一键冻结");
                unfreezeBtn.setText("一键解冻");
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBatchMode(false);
                // 批量结束后按系统真值重新读一次状态，避免显示与实际不一致
                refreshFrozen();
                StringBuilder sb = new StringBuilder();
                sb.append("成功 ").append(okFinal).append(" 个，失败 ")
                        .append(fails.size()).append(" 个");
                if (protectedFinal > 0) {
                    sb.append("，受保护跳过 ").append(protectedFinal).append(" 个");
                }
                for (String f : fails) {
                    sb.append("\n\n").append(f);
                }
                new AlertDialog.Builder(this)
                        .setTitle(freeze ? "批量冻结完成" : "批量解冻完成")
                        .setMessage(sb.toString())
                        .setPositiveButton("知道了", null)
                        .show();
            });
        });
    }

    /** 执行 pm 命令，返回合并后的真实输出（失败时用于展示）。 */
    private String execPm(String pkg, boolean freeze) {
        String cmd = freeze ? "pm disable-user --user 0 " + pkg : "pm enable " + pkg;
        PrivShell.Result r = PrivShell.exec(cmd, 30);
        String out = r.out == null ? "" : r.out.trim();
        if (out.isEmpty()) {
            out = "pm 无输出（退出码 " + r.code + "）";
        }
        return out;
    }

    private boolean isPmOk(String out) {
        String low = out.toLowerCase(Locale.ROOT);
        return out.contains("new state:")
                || (!low.contains("error") && !low.contains("exception")
                && !low.contains("failure") && !low.contains("denied")
                && !low.contains("无输出"));
    }

    private String labelOf(String pkg) {
        for (App a : all) {
            if (a.pkg.equals(pkg)) {
                return a.label;
            }
        }
        return pkg;
    }

    private void launch(App a) {
        Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (i == null) {
            Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }
}
