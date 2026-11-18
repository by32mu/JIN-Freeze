package com.jin.freeze.ui;

import android.app.AlertDialog;
import android.content.ComponentName;
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
import com.jin.freeze.core.AppIconCache;
import com.jin.freeze.core.PrivShell;
import com.jin.freeze.core.SuspendSupport;
import com.jin.freeze.core.SwipeFreeze;

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
    /** 选择页默认停在哪个标签：false=用户应用、true=系统应用，跟发起页当前标签一致。 */
    public static final String EXTRA_PICK_SYSTEM = "pick_system";
    /** 每行图标数。 */
    private static final int COLUMNS = 4;
    /** 显示方式：0 网格（默认）、1 一行一条；切换后记住，下次进入沿用。 */
    private static final String KEY_LIST_VIEW = "app_list_view";

    private static final class App {
        String label;
        String pkg;
        int userId;
        Drawable icon;
        boolean system;
        boolean frozen;
        /** 走降级通道（pm suspend）冻结。 */
        boolean suspended;
    }

    /** 一个格子的视图引用：状态变化时原地更新，过滤/搜索时重建。 */
    private static final class Cell {
        LinearLayout root;
        ImageView icon;
    }

    // 进程内共用一个后台线程：不再每开一次页面就新建一个永不回收的线程
    private final ExecutorService IO = com.jin.freeze.JinApp.IO;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<App> all = new ArrayList<>();
    private final Map<String, Cell> cells = new HashMap<>();
    private final Set<String> selected = new HashSet<>();

    private LinearLayout grid;
    private EditText search;
    private TextView filterUser;
    private TextView filterSystem;
    private TextView stateAll;
    private TextView stateFrozen;
    private TextView stateUnfrozen;
    /** 冻结状态筛选：0=全部、1=已冻结、2=未冻结；只在本次页面内有效，重进回到全部。 */
    private int stateFilter;
    private TextView sysHint;
    private TextView rootState;
    private TextView viewModeBtn;    private TextView batchBtn;
    private LinearLayout batchBar;
    private TextView freezeBtn;
    private TextView unfreezeBtn;

    private boolean showSystem;
    private boolean root;
    private boolean loaded;
    private boolean loading;
    /** 上一次没读到完整应用列表（多半是「获取应用列表」还没放行）：回前台时补拉。 */
    private boolean permissionRetry;
    /** 首屏加载完成后只自动补拉一次，避免权限被拒时反复空转。 */
    private boolean autoRetried;
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
        stateAll = findViewById(R.id.state_all);
        stateFrozen = findViewById(R.id.state_frozen);
        stateUnfrozen = findViewById(R.id.state_unfrozen);
        sysHint = findViewById(R.id.sys_hint);
        rootState = findViewById(R.id.root_state);
        batchBtn = findViewById(R.id.batch);
        batchBar = findViewById(R.id.batch_bar);
        freezeBtn = findViewById(R.id.btn_freeze);
        unfreezeBtn = findViewById(R.id.btn_unfreeze);

        filterUser.setOnClickListener(v -> switchFilter(false));
        filterSystem.setOnClickListener(v -> switchFilter(true));
        // 选择页跟着发起时的标签走：从「系统应用」点添加就停在系统应用
        switchFilter(pickMode && getIntent().getBooleanExtra(EXTRA_PICK_SYSTEM, false));
        stateAll.setOnClickListener(v -> switchStateFilter(0));
        stateFrozen.setOnClickListener(v -> switchStateFilter(1));
        stateUnfrozen.setOnClickListener(v -> switchStateFilter(2));
        applyStateFilterStyle();

        viewModeBtn = findViewById(R.id.view_mode);
        viewModeBtn.setText(listView() ? "网格" : "列表");
        viewModeBtn.setOnClickListener(v -> {
            prefs().edit().putBoolean(KEY_LIST_VIEW, !listView()).apply();
            viewModeBtn.setText(listView() ? "网格" : "列表");
            render(search.getText().toString().trim());
        });

        batchBtn.setText(pickMode ? "确定" : "批量");
        // 选择页改成点图标直接加入，不再需要「确定」
        batchBtn.setVisibility(pickMode ? View.GONE : View.VISIBLE);
        batchBar.setVisibility(View.GONE);
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

    @Override
    protected void onResume() {
        super.onResume();
        // 首次授权同意、被拒后重新授权、从系统设置授权返回都会回到这里：
        // 只要上次没读全，就主动补拉一次，不必退出页面再进来
        if (loaded && !loading && permissionRetry) {
            loadApps();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 部分 ROM 的「获取应用列表」弹窗是悬浮窗，不会触发 onResume，用焦点恢复兜底
        if (hasFocus && loaded && !loading && permissionRetry) {
            main.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed() && loaded && !loading && permissionRetry) {
                    loadApps();
                }
            }, 300);
        }
    }

    private static String instanceKey(String pkg, int userId) {
        return pkg + "@" + userId;
    }

    /** 名单存储键：主用户用纯包名（兼容旧数据），分身才带「@用户」。 */
    private static String storageKey(String pkg, int userId) {
        return userId == 0 ? pkg : instanceKey(pkg, userId);
    }

    /** 多用户实例的名称后缀：999 是双开，其余（如 10）是分身。 */
    private static String cloneTag(int userId) {
        return userId == 999 ? "（双开）" : "（分身）";
    }

    /** 判断名单/顺序里的一条记录是否就是当前这个应用实例。 */
    private static boolean sameInstance(String stored, String pkg, int userId) {
        return packagePart(stored).equals(pkg) && parseUserId(stored) == userId;
    }

    /** 取用户号：同时兼容「pkg@10」与「pkg (10)」两种分身写法。 */
    private static int parseUserId(String key) {
        int at = key.lastIndexOf('@');
        if (at >= 0) {
            try {
                return Integer.parseInt(key.substring(at + 1).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(\\s*(\\d+)\\s*\\)\\s*$").matcher(key);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 取纯包名：兼容本应用的「pkg@10」写法，以及系统/其他工具常见的
     * 「com.android.settings (10)」写法（用户号放在包名后的括号里）。
     */
    private static String packagePart(String key) {
        int at = key.lastIndexOf('@');
        if (at > 0) {
            return key.substring(0, at);
        }
        return key.replaceFirst("\\s*\\(\\s*\\d+\\s*\\)$", "").trim();
    }

    private List<Integer> discoverUsers() {
        Set<Integer> ids = new HashSet<>();
        ids.add(0);
        PrivShell.Result r = PrivShell.exec("pm list users", 15);
        if (r.out != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("UserInfo\\{(\\d+):").matcher(r.out);
            while (m.find()) {
                try {
                    ids.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        List<Integer> result = new ArrayList<>(ids);
        Collections.sort(result);
        return result;
    }

    private Set<String> listPackagesForUser(int userId) {
        Set<String> packages = new HashSet<>();
        PrivShell.Result r = PrivShell.exec("pm list packages --user " + userId, 30);
        if (r.out == null) {
            return packages;
        }
        for (String line : r.out.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) {
                String pkg = t.substring("package:".length()).trim();
                // 系统/部分工作空间会把用户号拼在包名后（如 com.android.settings (10)），还原成真实包名
                pkg = pkg.replaceFirst("\\s*\\(\\s*\\d+\\s*\\)$", "").trim();
                if (!pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }
        return packages;
    }

    /** 指定用户下的系统应用包名：分身里只装着的系统组件也能归到「系统应用」标签。 */
    private Set<String> listSystemPackagesForUser(int userId) {
        Set<String> packages = new HashSet<>();
        PrivShell.Result r = PrivShell.exec("pm list packages -s --user " + userId, 30);
        if (r.out == null) {
            return packages;
        }
        for (String line : r.out.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) {
                String pkg = t.substring("package:".length()).trim();
                pkg = pkg.replaceFirst("\\s*\\(\\s*\\d+\\s*\\)$", "").trim();
                if (!pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }
        return packages;
    }

    private void loadApps() {
        if (loading) {
            return;
        }
        loading = true;
        // 首次进来先给「正在读取」；补拉时保留现有列表，只有空列表才回到加载态
        render(search.getText().toString().trim());
        IO.execute(() -> {
            PrivShell.Result id = PrivShell.exec("id", 15);
            final boolean hasPriv = id.out != null && id.out.contains("uid=0")
                    || PrivShell.mode() != PrivShell.Mode.NONE;

            // pm list packages -d 是系统侧的冻结状态，比 PackageInfo 的 enabled 更准
            Set<String> disabled = hasPriv ? disabledPackages() : null;
            if (disabled == null) {
                disabled = new HashSet<>();
            }
            Set<String> suspended = hasPriv
                    ? SuspendSupport.suspendedAmong(SuspendSupport.cached(this))
                    : new HashSet<>();

            List<App> apps = new ArrayList<>();
            PackageManager pm = getPackageManager();
            List<Integer> userIds = hasPriv ? discoverUsers() : Collections.singletonList(0);
            // 每个分身用户单独读一次停用真值与系统包集合，避免把主用户的状态套到分身上
            Map<Integer, Set<String>> userDisabled = new HashMap<>();
            Map<Integer, Set<String>> userSystem = new HashMap<>();
            for (int userId : userIds) {
                if (userId != 0) {
                    userDisabled.put(userId, disabledPackages(userId));
                    userSystem.put(userId, listSystemPackagesForUser(userId));
                }
            }
            for (int userId : userIds) {
                Set<String> packages = hasPriv ? listPackagesForUser(userId) : new HashSet<>();
                List<PackageInfo> installed = userId == 0
                        ? pm.getInstalledPackages(0) : new ArrayList<>();
                if (userId != 0) {
                    Set<String> dis = userDisabled.get(userId);
                    Set<String> sys = userSystem.get(userId);
                    for (String pkg : packages) {
                        PackageInfo pi = null;
                        try {
                            pi = pm.getPackageInfo(pkg, 0);
                        } catch (Exception ignored) {
                        }
                        if (pi != null) {
                            installed.add(pi);
                            continue;
                        }
                        // 只装在分身/工作空间里的应用：主用户的 PackageManager 查不到详情，
                        // 仍按包名录出来（名称暂用包名），保证能冻结/解冻。
                        // 系统应用按该用户自己的系统包集合判定，仍归到「系统应用」标签。
                        App a = new App();
                        a.label = pkg + cloneTag(userId);
                        a.pkg = pkg;
                        a.userId = userId;
                        a.system = sys != null && sys.contains(pkg);
                        a.frozen = dis != null && dis.contains(pkg);
                        apps.add(a);
                    }
                }
                for (PackageInfo pi : installed) {
                    ApplicationInfo ai = pi.applicationInfo;
                    if (ai == null) {
                        continue;
                    }
                    App a = new App();
                    a.label = pm.getApplicationLabel(ai).toString();
                    a.pkg = pi.packageName;
                    a.userId = userId;
                    if (userId != 0) {
                        // 多用户实例标识加在应用名称后；包名行另带 (用户号)
                        a.label += cloneTag(userId);
                    }
                    a.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    String key = instanceKey(a.pkg, userId);
                    Set<String> truthForUser = userId == 0 ? disabled : userDisabled.get(userId);
                    a.frozen = truthForUser != null
                            ? (truthForUser.contains(a.pkg)
                            || (userId == 0 && suspended.contains(a.pkg)))
                            : !ai.enabled;
                    try {
                        // 走公共缓存：再次进入添加页时图标直接命中，不再重复解码
                        a.icon = AppIconCache.load(this, a.pkg, ai);
                    } catch (Exception ignored) {
                    }
                    apps.add(a);
                }
            }
            // 选择模式：展示全部应用，已在名单里的也保留，长按可与半屏一样「移除列表并解冻」
            Collections.sort(apps, (x, y) -> {
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(x.label, y.label);
                return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(x.pkg, y.pkg);
            });
            final List<App> shownApps = apps;

            main.post(() -> {
                loading = false;
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
                // 只剩自己或完全为空，基本可判定系统还没允许读取应用列表，留待回前台再补
                permissionRetry = shownApps.size() <= 1;
                render(search.getText().toString().trim());
                // 用户可能在这次读取还没结束时就点了同意：此时 onResume 已经错过，
                // 只要窗口已重新拿到焦点，就再补一次（只补一次，防权限被拒时空转）
                if (permissionRetry && !autoRetried && hasWindowFocus()) {
                    autoRetried = true;
                    main.postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed() && loaded && !loading && permissionRetry) {
                            loadApps();
                        }
                    }, 300);
                }
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

    /** 冻结状态分段选择：只重排当前内存里的列表，不重新拉全量数据。 */
    private void switchStateFilter(int filter) {
        stateFilter = filter;
        applyStateFilterStyle();
        render(search.getText().toString().trim());
    }

    private void applyStateFilterStyle() {
        TextView[] views = {stateAll, stateFrozen, stateUnfrozen};
        for (int i = 0; i < views.length; i++) {
            boolean on = i == stateFilter;
            views[i].setBackgroundResource(on ? R.drawable.bg_btn_blue : R.drawable.bg_btn_white);
            views[i].setTextColor(on ? 0xFFFFFFFF : getColor(R.color.blue));
        }
    }


    /** 搜索、标签（用户/系统）与冻结状态三者取交集；状态一律从 App.frozen 读，不另记一份。 */
    private void render(String query) {
        if (grid == null) {
            return;
        }
        grid.removeAllViews();
        cells.clear();
        if (!loaded || (loading && all.isEmpty())) {
            grid.addView(emptyText("正在读取应用列表…"));
            return;
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<App> shown = new ArrayList<>();
        for (App a : all) {
            if (a.system != showSystem) {
                continue;
            }
            // 冻结状态筛选与当前标签叠加：全部=当前标签下的全部，不跨标签合并
            if (stateFilter == 1 && !a.frozen) {
                continue;
            }
            if (stateFilter == 2 && a.frozen) {
                continue;
            }
            if (!q.isEmpty()) {
                // 支持按多用户后缀搜：输入 999 / 10 也能找到对应分身/双开实例
                boolean hit = a.label.toLowerCase(Locale.ROOT).contains(q)
                        || a.pkg.toLowerCase(Locale.ROOT).contains(q)
                        || (a.userId != 0 && String.valueOf(a.userId).contains(q));
                if (!hit) {
                    continue;
                }
            }
            shown.add(a);
        }
        if (shown.isEmpty()) {
            if (all.isEmpty()) {
                grid.addView(emptyText("没有读取到应用：请允许「获取应用列表」权限，返回后会自动刷新"));
            } else if (!q.isEmpty()) {
                grid.addView(emptyText("没有匹配的应用"));
            } else if (stateFilter == 1) {
                grid.addView(emptyText("当前标签下没有已冻结的应用"));
            } else if (stateFilter == 2) {
                grid.addView(emptyText("当前标签下没有未冻结的应用"));
            } else {
                grid.addView(emptyText(pickMode && !showSystem
                        ? "名单里的应用也会显示，长按可移除列表并解冻"
                        : "没有匹配的应用"));
            }
            return;
        }
        // 固定按名称排序：同名时再按包名定序，保证任何情况下顺序稳定、不随冻结状态跳动
        Collections.sort(shown, (x, y) -> {
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(x.label, y.label);
            return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(x.pkg, y.pkg);
        });
        // 勾选只保留当前可见的项：切标签/筛选/搜索后，批量与「确定」不会带上看不见的应用
        Set<String> shownPkgs = new HashSet<>();
        for (App a : shown) {
            shownPkgs.add(instanceKey(a.pkg, a.userId));
        }
        selected.retainAll(shownPkgs);
        updateBatchBar();
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
            // 取不到图标的应用（如只在分身里存在的系统组件）用系统默认应用图标，不冒充凚自己的图标
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
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
        cells.put(instanceKey(a.pkg, a.userId), cell);
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
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
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

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.leftMargin = Ui.dp(this, 12);

        TextView name = Ui.text(this, a.label, 14, Ui.TEXT_PRIMARY, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 添加应用（选择模式）的列表形态：名称下方补一行包名，便于区分同名或名称被截断的应用；
        // 分身把用户号拼在包名后面（与系统应用列表一致），应用名称本身不加后缀
        if (pickMode) {
            String pkgText = a.userId == 0 ? a.pkg : a.pkg + " (" + a.userId + ")";
            TextView pkg = Ui.text(this, pkgText, 11, Ui.TEXT_SECONDARY, false);
            pkg.setSingleLine(true);
            pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pkgLp.topMargin = Ui.dp(this, 2);
            texts.addView(pkg, pkgLp);
        }
        row.addView(texts, textsLp);

        Cell cell = new Cell();
        cell.root = row;
        cell.icon = icon;
        cells.put(instanceKey(a.pkg, a.userId), cell);
        bindCell(a, cell);

        row.setContentDescription(a.label);
        bindActions(a, cell, row);
        return row;
    }

    /** 网格/列表共用的点击与长按行为。 */
    private void bindActions(App a, Cell cell, View target) {
        target.setOnClickListener(v -> {
            if (pickMode) {
                // 点击图标即加入名单并返回列表，不再走勾选 + 确定
                Intent data = new Intent();
                ArrayList<String> picked = new ArrayList<>();
                picked.add(instanceKey(a.pkg, a.userId));
                data.putStringArrayListExtra(EXTRA_PICKED, picked);
                setResult(RESULT_OK, data);
                finish();
                return;
            }
            if (batchMode) {
                String key = instanceKey(a.pkg, a.userId);
                if (selected.contains(key)) {
                    selected.remove(key);
                } else {
                    selected.add(key);
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
                // 与列表长按图标同一套震动反馈
                target.performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
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
                    doPickUnfreeze(a, cells.get(instanceKey(a.pkg, a.userId)));
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
                return inFreezeList(a);
            }

            @Override
            public void removeFromList() {
                removeOnlyFromList(a);
            }

            @Override
            public void removeAndUnfreeze() {
                AppFreezeActivity.this.removeFromList(a);
            }

            @Override
            public boolean tagMoveEnabled() {
                return false;
            }

            @Override
            public boolean pinHomeEnabled() {
                return false;
            }
        });
    }

    private SharedPreferences listPrefs() {
        return getSharedPreferences("freeze_list", MODE_PRIVATE);
    }

    private boolean inFreezeList(App a) {
        return new HashSet<>(listPrefs().getStringSet("pkgs", new HashSet<>()))
                .contains(storageKey(a.pkg, a.userId));
    }

    /** 与「冻结列表」里的移除入口同一语义：先摘名单与标签归属，再解冻；解冻失败单独报错。 */
    /** 仅从名单移除：不解冻、不卸载；标签归属与自定义顺序里的这一项一并清掉。 */
    private void removeOnlyFromList(App a) {
        SharedPreferences fp = listPrefs();
        Set<String> cur = new HashSet<>(fp.getStringSet("pkgs", new HashSet<>()));
        if (!cur.remove(storageKey(a.pkg, a.userId))) {
            return;
        }
        SharedPreferences.Editor ed = fp.edit().putStringSet("pkgs", cur);
        String raw = fp.getString("order", "");
        if (!raw.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                String p = line.trim();
                if (p.isEmpty() || sameInstance(p, a.pkg, a.userId)) {
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
            String listKey = storageKey(a.pkg, a.userId);
            if (o.has(listKey)) {
                o.remove(listKey);
                ed.putString("app_tabs", o.toString());
            }
        } catch (Exception ignored) {
        }
        ed.apply();
        SwipeFreeze.syncAsync(this);
        Toast.makeText(this, "已从列表移除", Toast.LENGTH_SHORT).show();
    }

    private void removeFromList(App a) {
        SharedPreferences fp = listPrefs();
        Set<String> cur = new HashSet<>(fp.getStringSet("pkgs", new HashSet<>()));
        cur.remove(storageKey(a.pkg, a.userId));
        SharedPreferences.Editor ed = fp.edit().putStringSet("pkgs", cur);
        String raw = fp.getString("order", "");
        if (!raw.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                String p = line.trim();
                if (p.isEmpty() || sameInstance(p, a.pkg, a.userId)) {
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
            String listKey = storageKey(a.pkg, a.userId);
            if (o.has(listKey)) {
                o.remove(listKey);
                ed.putString("app_tabs", o.toString());
            }
        } catch (Exception ignored) {
        }
        ed.apply();
        if (!a.frozen) {
            Toast.makeText(this, "已移出名单", Toast.LENGTH_SHORT).show();
            return;
        }
        doPickUnfreeze(a, cells.get(instanceKey(a.pkg, a.userId)));
    }

    /** 更改标签页：读取冻结列表的标签配置，写回 app_tabs，与列表页共用同一份数据。 */
    private void moveToTab(App a) {
        SharedPreferences fp = listPrefs();
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Boolean> visibles = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(fp.getString("tabs", ""));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String id = o.optString("id", "");
                String name = o.optString("name", "");
                if (!id.isEmpty() && !name.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                    names.add(name);
                    visibles.add(o.optBoolean("visible", true));
                }
            }
        } catch (Exception ignored) {
        }
        if (ids.isEmpty()) {
            ids.add("user");
            names.add("用户应用");
            visibles.add(true);
            ids.add("system");
            names.add("系统应用");
            visibles.add(true);
        } else {
            // 内置标签必须始终可选：旧数据里只存了自定义标签时，补上「用户应用 / 系统应用」，
            // 否则非系统应用会被一直挡在系统应用标签之外。
            if (!ids.contains("user")) {
                ids.add(0, "user");
                names.add(0, "用户应用");
                visibles.add(0, true);
            }
            if (!ids.contains("system")) {
                int at = Math.min(1, ids.size());
                ids.add(at, "system");
                names.add(at, "系统应用");
                visibles.add(at, true);
            }
        }
        String cur = "";
        try {
            cur = new JSONObject(fp.getString("app_tabs", "{}"))
                    .optString(storageKey(a.pkg, a.userId), "");
        } catch (Exception ignored) {
        }
        // 没有显式归属时按系统标记落回内置标签，当前项才不会标错
        if (cur.isEmpty()) {
            cur = a.system ? "system" : "user";
        }
        List<MoveTabDialog.Tab> entries = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            entries.add(new MoveTabDialog.Tab(ids.get(i), names.get(i), visibles.get(i),
                    ids.get(i).equals(cur)));
        }
        final String key = storageKey(a.pkg, a.userId);
        MoveTabDialog.show(this, a.label, a.icon, entries, target -> {
            if (target.current) {
                return;
            }
            // 用户应用不允许进入内置的「系统应用」标签
            if (!a.system && "system".equals(target.id)) {
                Toast.makeText(this, "用户应用禁止移入系统应用标签",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                JSONObject o = new JSONObject(fp.getString("app_tabs", "{}"));
                o.put(key, target.id);
                fp.edit().putString("app_tabs", o.toString()).apply();
                Toast.makeText(this, "已移动到「" + target.name + "」",
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
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
        ShortcutPin.pin(this, a.pkg, a.label, a.icon);
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
        boolean sel = batchMode && selected.contains(instanceKey(a.pkg, a.userId));
        cell.root.setBackgroundResource(sel
                ? R.drawable.bg_card_selected : R.drawable.bg_card);
    }

    /** 添加页内解冻：成功静默恢复彩色，失败展示 pm 真实输出。 */
    private void doPickUnfreeze(App a, Cell cell) {
        IO.execute(() -> {
            PrivShell.Result r = execPm(a.pkg, a.userId, false);
            boolean ok = applied(a, false, r);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (ok) {
                    a.frozen = false;
                    a.suspended = false;
                    bindCell(a, cell);
                } else {
                    AlertDialog failDlg = new AlertDialog.Builder(this)
                            .setTitle("解冻失败")
                            .setMessage(a.label + "：" + Ui.pmReason(r, false))
                            .setPositiveButton("知道了", null)
                            .create();
                    failDlg.show();
                    Ui.roundAlertDialog(failDlg);
                }
            });
        });
    }

    /** 选择模式确认：把勾选的包名回传给「冻结列表」。 */
    private void finishWithPicked() {
        Intent data = new Intent();
        ArrayList<String> picked = new ArrayList<>();
        for (String key : selected) {
            picked.add(key);
        }
        data.putStringArrayListExtra(EXTRA_PICKED, picked);
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
            Set<String> disabled = disabledPackages();
            boolean ok = disabled != null;
            // 暂停态同样算冻结：只对缓存里本机暂停过的包批量查一次
            Set<String> suspended = SuspendSupport.suspendedAmong(SuspendSupport.cached(this));
            if (!suspended.isEmpty()) {
                // 只在实际读到暂停项时回写缓存：一次读失败不把已暂停的清空
                SuspendSupport.replace(this, suspended);
            }
            Map<Integer, Set<String>> userDisabled = new HashMap<>();
            for (App a : all) {
                if (a.userId != 0 && !userDisabled.containsKey(a.userId)) {
                    userDisabled.put(a.userId, disabledPackages(a.userId));
                }
            }
            main.post(() -> {
                refreshing = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                for (App a : all) {
                    if (a.userId == 0) {
                        if (!ok) continue;
                        a.suspended = suspended.contains(a.pkg);
                        a.frozen = disabled.contains(a.pkg) || a.suspended;
                    } else {
                        Set<String> truth = userDisabled.get(a.userId);
                        if (truth != null) {
                            a.frozen = truth.contains(a.pkg);
                        }
                    }
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
            Cell cell = cells.get(instanceKey(a.pkg, a.userId));
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
        AlertDialog confirmDlg = new AlertDialog.Builder(this)
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
                .create();
        confirmDlg.show();
        Ui.roundAlertDialog(confirmDlg);
    }

    /** 单条切换：成功后原地更新该格状态，失败把 pm 的真实输出原样展示。 */
    private void runToggle(App a) {
        final boolean toFreeze = !a.frozen;
        working = true;
        IO.execute(() -> {
            PrivShell.Result r = execPm(a.pkg, a.userId, toFreeze);
            boolean ok = applied(a, toFreeze, r);
            // 系统应用被 ROM 拦住停用时，自动降级为 pm suspend（只覆盖主用户）
            boolean blocked = toFreeze && a.userId == 0 && !ok && SuspendSupport.romBlocked(r.out);
            if (blocked) {
                PrivShell.Result s = SuspendSupport.suspend(this, a.pkg, a.label);
                if (suspendApplied(a.pkg, s)) {
                    ok = true;
                    SuspendSupport.remember(this, Collections.singleton(a.pkg));
                } else {
                    android.util.Log.w("JinPm", "suspend fallback failed: code=" + s.code
                            + " timeout=" + s.timeout + "\n"
                            + (s.out == null ? "" : s.out.trim()));
                }
            }
            final boolean done = ok;
            final boolean suspendedNow = blocked && ok;
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!done) {
                    AlertDialog failDlg = new AlertDialog.Builder(this)
                            .setTitle(toFreeze ? "冻结失败" : "解冻失败")
                            .setMessage(a.label + "：" + (blocked
                                    ? Ui.NO_FREEZE_CHANNEL : Ui.pmReason(r, toFreeze)))
                            .setPositiveButton("知道了", null)
                            .create();
                    failDlg.show();
                    Ui.roundAlertDialog(failDlg);
                    return;
                }
                a.frozen = toFreeze;
                a.suspended = suspendedNow;
                render(search.getText().toString().trim());
                updateBatchBar();
                refreshFrozen();
                Toast.makeText(this, (toFreeze ? "已冻结 " : "已解冻 ") + a.label,
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * 已冻结（含暂停）应用单击：撤销暂停 + 启用（需要时）后直接启动，一次点击完成，
     * 不弹二次确认；失败只给一句短原因。
     */
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
            PrivShell.Result r = execPm(a.pkg, a.userId, false);
            boolean ok = applied(a, false, r);
            // 撤销暂停失败时单独给一句，不落到通用的「启用未生效」
            final String failReason = !ok && a.userId == 0
                    && SuspendSupport.cached(this).contains(a.pkg)
                    && Boolean.TRUE.equals(SuspendSupport.suspendedOne(a.pkg))
                    ? "本机不允许取消暂停，无法打开" : null;
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    AlertDialog failDlg = new AlertDialog.Builder(this)
                            .setTitle("解冻失败，无法打开")
                            .setMessage(a.label + "：" + (failReason != null
                                    ? failReason : Ui.pmReason(r, false)))
                            .setPositiveButton("知道了", null)
                            .create();
                    failDlg.show();
                    Ui.roundAlertDialog(failDlg);
                    refreshFrozen();
                    return;
                }
                a.frozen = false;
                a.suspended = false;
                render(search.getText().toString().trim());
                refreshFrozen();
                launch(a);
            });
        });
    }

    /** 批量冻结/解冻选中的应用：逐条执行并汇总成功/失败，失败项带 pm 原始报错。 */
    private void runBatch(boolean freeze) {
        final List<App> targetApps = new ArrayList<>();
        for (App a : all) {
            if (selected.contains(instanceKey(a.pkg, a.userId))) {
                targetApps.add(a);
            }
        }
        if (targetApps.isEmpty()) {
            return;
        }
        working = true;
        updateBatchBar();
        freezeBtn.setText(freeze ? "冻结中…" : "一键冻结");
        unfreezeBtn.setText(freeze ? "一键解冻" : "解冻中…");
        IO.execute(() -> {
            Map<String, PrivShell.Result> results = new HashMap<>();
            Set<String> degraded = new HashSet<>();
            for (App app : targetApps) {
                String pkg = app.pkg;
                PrivShell.Result r = execPm(pkg, app.userId, freeze);
                results.put(instanceKey(pkg, app.userId), r);
                // 分身实例使用带用户 ID 的 pm 命令；暂停降级暂只保留主用户路径。
                if (freeze && app.userId == 0 && SuspendSupport.romBlocked(r.out)) {
                    degraded.add(pkg);
                    results.put(instanceKey(pkg, app.userId),
                            SuspendSupport.suspend(this, pkg, app.label));
                }
            }
            // 批量只回读一次真值，逐包按「是否已在目标状态」判成败
            Set<String> truth = disabledPackages();
            // 降级通道真值同样只批量读一次：冻结查「停用没成功的」，解冻查本机暂停过的
            Set<String> suspendCandidates = new HashSet<>();
            for (App app : targetApps) {
                if (app.userId == 0) {
                    suspendCandidates.add(app.pkg);
                }
            }
            if (freeze) {
                if (truth != null) {
                    suspendCandidates.removeIf(pkg -> truth.contains(pkg));
                }
            } else {
                suspendCandidates.retainAll(SuspendSupport.cached(this));
            }
            Set<String> suspendedTruth = SuspendSupport.suspendedAmong(suspendCandidates);
            if (freeze) {
                SuspendSupport.remember(this, suspendedTruth);
            } else {
                Set<String> next = SuspendSupport.cached(this);
                next.removeAll(suspendCandidates);
                next.addAll(suspendedTruth);
                SuspendSupport.replace(this, next);
            }
            int okCount = 0;
            List<String> fails = new ArrayList<>();
            for (App app : targetApps) {
                String pkg = app.pkg;
                String key = instanceKey(pkg, app.userId);
                PrivShell.Result r = results.get(key);
                boolean ok;
                if (app.userId != 0) {
                    ok = isPmOk(r);
                } else if (truth != null) {
                    boolean dis = truth.contains(pkg);
                    ok = freeze ? (dis || suspendedTruth.contains(pkg))
                            : (!dis && !suspendedTruth.contains(pkg));
                } else {
                    ok = isPmOk(r);
                }
                if (ok) {
                    okCount++;
                } else {
                    fails.add(app.label + "：" + (degraded.contains(pkg)
                            ? Ui.NO_FREEZE_CHANNEL : Ui.pmReason(r, freeze)));
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
                for (String f : fails) {
                    sb.append("\n\n").append(f);
                }
                AlertDialog doneDlg = new AlertDialog.Builder(this)
                        .setTitle(freeze ? "批量冻结完成" : "批量解冻完成")
                        .setMessage(sb.toString())
                        .setPositiveButton("知道了", null)
                        .create();
                doneDlg.show();
                Ui.roundAlertDialog(doneDlg);
            });
        });
    }

    /** 执行 pm，返回完整结果（含退出码与是否超时），成败不在这里判。 */
    private PrivShell.Result execPm(String pkg, boolean freeze) {
        return execPm(pkg, 0, freeze);
    }

    private PrivShell.Result execPm(String pkg, int userId, boolean freeze) {
        String cmd = freeze
                ? "pm disable-user --user " + userId + " " + pkg
                : "pm unsuspend --user " + userId + " " + pkg
                + "; pm enable --user " + userId + " " + pkg;
        return PrivShell.exec(cmd, 30);
    }

    /**
     * 系统真值：pm list packages -d。
     * null = 没读到（超时 / 命令失败）；空集合 = 读到且确实没有冻结项，
     * 两者必须分开，否则「一个都没冻结」会被当成读取失败。
     */
    private Set<String> disabledPackages() {
        return disabledPackages(0);
    }

    /** 读指定用户下的停用包：分身必须按自己的用户查。 */
    private Set<String> disabledPackages(int userId) {
        PrivShell.Result r = PrivShell.exec("pm list packages -d --user " + userId, 30);
        if (r.timeout) {
            return null;
        }
        Set<String> set = new HashSet<>();
        boolean any = false;
        if (r.out != null) {
            for (String line : r.out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("package:")) {
                    set.add(t.substring("package:".length()).trim());
                    any = true;
                }
            }
        }
        if (!any && r.code != 0) {
            return null;
        }
        return set;
    }

    /**
     * 以系统真值判定这一步是否已生效：
     * 冻结 = 在禁用列表里，或（降级通道）处于暂停态；
     * 解冻 = 既不在禁用列表，也确实退出了暂停。
     */
    /** 按应用实例判定：分身查不到主用户禁用列表，只能按命令输出判断。 */
    private boolean applied(App a, boolean freeze, PrivShell.Result r) {
        if (a.userId != 0) {
            return isPmOk(r);
        }
        return applied(a.pkg, freeze, r);
    }

    private boolean applied(String pkg, boolean freeze, PrivShell.Result r) {
        Set<String> truth = disabledPackages();
        if (truth == null) {
            return isPmOk(r);
        }
        if (freeze) {
            if (truth.contains(pkg)) {
                return true;
            }
            return Boolean.TRUE.equals(SuspendSupport.suspendedOne(pkg));
        }
        if (truth.contains(pkg)) {
            return false;
        }
        // 只对本机暂停过的包额外确认一次「确实退出暂停」
        if (SuspendSupport.cached(this).contains(pkg)) {
            Boolean s = SuspendSupport.suspendedOne(pkg);
            if (Boolean.TRUE.equals(s)) {
                return false;
            }
            if (s == null && !isPmOk(r)) {
                return false;
            }
            SuspendSupport.forget(this, pkg);
        }
        return true;
    }

    /** 降级执行 pm suspend 后确认暂停确实生效：优先 dumpsys 真值，读不到才看命令结果。 */
    private boolean suspendApplied(String pkg, PrivShell.Result r) {
        if (Boolean.TRUE.equals(SuspendSupport.suspendedOne(pkg))) {
            return true;
        }
        return isPmOk(r) && !SuspendSupport.romBlocked(r == null ? null : r.out);
    }

    /** 真值读不到时的兜底：退出码为 0 且输出里没有报错关键字才算成功。 */
    private boolean isPmOk(PrivShell.Result r) {
        if (r == null || r.timeout || r.code != 0) {
            return false;
        }
        String low = r.out == null ? "" : r.out.toLowerCase(Locale.ROOT);
        return !low.contains("error") && !low.contains("exception")
                && !low.contains("failure") && !low.contains("denied");
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
        // 分身要在它自己的用户下启动，否则打开的是主应用
        if (a.userId != 0 && i.getComponent() != null) {
            PrivShell.exec("am start --user " + a.userId + " -n "
                    + i.getComponent().flattenToShortString(), 15);
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }
}
