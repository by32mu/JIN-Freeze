package com.jin.freeze.ui;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.jin.freeze.R;
import com.jin.freeze.core.AppIconCache;
import com.jin.freeze.core.DhizukuGate;
import com.jin.freeze.core.Pinyin;
import com.jin.freeze.core.PrivRequest;
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
    /** 系统应用提示是否已被「不再提醒」关掉。 */
    private static final String KEY_SYS_HINT_HIDDEN = "hide_sys_hint";
    /** 选择页「点击应用后右上角确定」提示是否已被「不再提醒」关掉。 */
    private static final String KEY_PICK_HINT_HIDDEN = "hide_pick_hint";
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
        /** 名称的拼音首字母（懒计算，供搜索用）。 */
        String py;
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
        TextView name;
        /** 网格模式：名称行与左侧冻结雪花（整行居中，雪花贴着文字）。 */
        LinearLayout nameRow;
        ImageView snow;
    }

    // 进程内共用一个后台线程：不再每开一次页面就新建一个永不回收的线程
    private final ExecutorService IO = com.jin.freeze.JinApp.IO;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<App> all = new ArrayList<>();
    private final Map<String, Cell> cells = new HashMap<>();
    private final Set<String> selected = new java.util.LinkedHashSet<>();

    private LinearLayout grid;
    private EditText search;
    private TextView filterUser;
    private TextView filterSystem;
    private TextView stateAll;
    private TextView stateFrozen;
    private TextView stateUnfrozen;
    private TextView stateInList;
    private TextView stateNotInList;
    /** 冻结状态筛选：0=全部、1=已冻结、2=未冻结、3=已添加、4=未添加；只在本次页面内有效，重进回到全部。 */
    private int stateFilter;
    /** 冻结列表里的键（主用户纯包名，分身 pkg@uid）：选择页的「已添加 / 未添加」筛选用。 */
    private Set<String> listKeys = new HashSet<>();
    private TextView sysHint;
    private View sysHintRow;
    private TextView rootState;
    private View rootStateRow;
    private TextView rootStateDismiss;
    private ImageView viewModeBtn;
    /** 状态分段那一行（列表/宫格标识与它同一行、靠右端）。 */
    private FrameLayout viewModeRow;
    private TextView batchBtn;
    private ImageButton pickDoneBtn;
    private LinearLayout batchBar;
    /** 选择应用页顶部的已选图标条：按行铺满，可折行显示全部已选应用。 */
    private LinearLayout pickStrip;
    private TextView freezeBtn;
    private TextView unfreezeBtn;

    private boolean showSystem;
    private boolean root;
    private boolean loaded;
    private boolean loading;
    /** 上次加载时系统实际可见的包数：授权后这个数会变大，用来触发刷新提示。 */
    private int visibleCount;
    /** 只有可见包数不足、确实可能在等授权时才允许轮询。 */
    private boolean permissionWatchNeeded;
    /** 授权观察轮询是否已在跑。 */
    private boolean watchScheduled;
    private int watchTicks;
    /** 本次进入页面是否已经弹过刷新提示，避免反复弹。 */
    private boolean refreshPromptShown;
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

        // 「同步全局」打开时，选择应用页也用同一套壁纸底，列表文字跟着壁纸明暗切换
        View pageContent = findViewById(android.R.id.content);
        if (pageContent instanceof ViewGroup && ((ViewGroup) pageContent).getChildCount() > 0) {
            pageContent = ((ViewGroup) pageContent).getChildAt(0);
        }
        android.graphics.drawable.Drawable wpBg = Ui.globalWallpaperBg(this);
        if (wpBg != null && pageContent != null) {
            pageContent.setBackground(wpBg);
            final View gridView = findViewById(R.id.grid);
            if (gridView != null) {
                gridView.post(() -> Ui.tintPage(gridView));
            }
        }

        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
        ((TextView) findViewById(R.id.title)).setText(pickMode ? "选择应用" : "应用冻结");
        findViewById(R.id.back).setOnClickListener(v -> {
            if (!goBack()) {
                finish();
            }
        });
        grid = findViewById(R.id.grid);
        search = findViewById(R.id.search);
        // 光标只在键盘弹出时显示
        bindCursorToIme(search);
        filterUser = findViewById(R.id.filter_user);
        filterSystem = findViewById(R.id.filter_system);
        stateAll = findViewById(R.id.state_all);
        stateFrozen = findViewById(R.id.state_frozen);
        stateUnfrozen = findViewById(R.id.state_unfrozen);
        stateInList = findViewById(R.id.state_in_list);
        stateNotInList = findViewById(R.id.state_not_in_list);
        sysHint = findViewById(R.id.sys_hint);
        sysHintRow = findViewById(R.id.sys_hint_row);
        View sysHintDismiss = findViewById(R.id.sys_hint_dismiss);
        if (sysHintDismiss != null) {
            sysHintDismiss.setOnClickListener(v -> {
                prefs().edit().putBoolean(KEY_SYS_HINT_HIDDEN, true).apply();
                applySysHintVisibility();
            });
        }
        rootState = findViewById(R.id.root_state);
        rootStateRow = findViewById(R.id.root_state_row);
        rootStateDismiss = findViewById(R.id.root_state_dismiss);
        if (rootStateDismiss != null) {
            rootStateDismiss.setOnClickListener(v -> {
                prefs().edit().putBoolean(KEY_PICK_HINT_HIDDEN, true).apply();
                applyPickHintVisibility();
            });
        }
        batchBtn = findViewById(R.id.batch);
        batchBar = findViewById(R.id.batch_bar);
        pickStrip = findViewById(R.id.pick_strip);
        freezeBtn = findViewById(R.id.btn_freeze);
        unfreezeBtn = findViewById(R.id.btn_unfreeze);

        filterUser.setOnClickListener(v -> switchFilter(false));
        filterSystem.setOnClickListener(v -> switchFilter(true));
        // 选择页跟着发起时的标签走：从「系统应用」点添加就停在系统应用
        switchFilter(pickMode && getIntent().getBooleanExtra(EXTRA_PICK_SYSTEM, false));
        stateAll.setOnClickListener(v -> switchStateFilter(0));
        stateFrozen.setOnClickListener(v -> switchStateFilter(1));
        stateUnfrozen.setOnClickListener(v -> switchStateFilter(2));
        stateInList.setOnClickListener(v -> switchStateFilter(3));
        stateNotInList.setOnClickListener(v -> switchStateFilter(4));
        // 「已添加 / 未添加」只在选择应用（加名单）时才有意义
        if (!pickMode) {
            stateInList.setVisibility(View.GONE);
            stateNotInList.setVisibility(View.GONE);
        }
        loadFreezeListKeys();
        applyStateFilterStyle();

        viewModeRow = findViewById(R.id.view_mode_row);
        viewModeBtn = findViewById(R.id.view_mode);
        // 状态分段那一行始终显示；列表/宫格标识等名单和图标读完再出现（由 render 控制），
        // 勾选应用后也不收起
        viewModeBtn.setVisibility(View.GONE);
        // 列表/宫格切换用标识代替文字：当前是列表就显示宫格标识（点了切宫格），反之亦然
        applyViewModeIcon();
        viewModeBtn.setOnClickListener(v -> {
            prefs().edit().putBoolean(KEY_LIST_VIEW, !listView()).apply();
            applyViewModeIcon();
            render(search.getText().toString().trim());
        });

        // 选择应用页：右上角默认是列表/宫格标识，勾选应用后换成打勾的「确定」
        pickDoneBtn = findViewById(R.id.pick_done);
        if (pickDoneBtn != null) {
            pickDoneBtn.setOnClickListener(v -> finishWithPicked());
        }

        batchBtn.setText("批量");
        batchBtn.setVisibility(pickMode ? View.GONE : View.VISIBLE);
        batchBar.setVisibility(View.GONE);
        batchBtn.setOnClickListener(v -> setBatchMode(!batchMode));
        applyPickSurfaces();
        if (pickMode) {
            updateBatchBar();
        }
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

    /** 列表/宫格切换按钮：显示的是「点一下会切到的那种视图」的标识。 */
    private void applyViewModeIcon() {
        if (viewModeBtn == null) {
            return;
        }
        boolean list = listView();
        viewModeBtn.setImageResource(list ? R.drawable.ic_qp_grid : R.drawable.ic_view_list);
        viewModeBtn.setContentDescription(list ? "切换到宫格" : "切换到列表");
    }

    /** 选择应用页：横幅与搜索框跟随全屏页的透明度档位（freeze_list 偏好里同一套键）。 */
    private void applyPickSurfaces() {
        if (!pickMode) {
            return;
        }
        android.content.SharedPreferences fl =
                getSharedPreferences("freeze_list", MODE_PRIVATE);
        // 横幅：0 = 全透明，与全屏列表默认档一致
        int headerPct = Math.max(0, Math.min(100, fl.getInt("header_surface", 0)));
        View header = findViewById(R.id.pick_header);
        if (header != null && header.getBackground() != null) {
            header.getBackground().mutate().setAlpha(Math.round(255f * headerPct / 100f));
        }
        // 搜索框：填充与描边一起按「搜索框颜色」档位缩放，和全屏列表同一算法
        if (search != null) {
            int pct = Math.max(0, Math.min(100, fl.getInt("search_surface", 20)));
            int radius = Ui.dp(this, 16);
            int fill = scaleAlpha(getColor(R.color.accent_surface_bg), pct);

            GradientDrawable normal = new GradientDrawable();
            normal.setCornerRadius(radius);
            normal.setColor(fill);
            normal.setStroke(Ui.dp(this, 1),
                    scaleAlpha(getColor(R.color.accent_surface_stroke_soft), pct));

            GradientDrawable focused = new GradientDrawable();
            focused.setCornerRadius(radius);
            focused.setColor(fill);
            focused.setStroke(Ui.dp(this, 2),
                    scaleAlpha(getColor(R.color.accent_surface_stroke), pct));

            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_focused}, focused);
            sld.addState(new int[0], normal);
            search.setBackground(sld);
        }
    }

    /** 按百分比缩放颜色自身的 alpha，保留色相。 */
    private static int scaleAlpha(int color, int pct) {
        int a = Math.round(android.graphics.Color.alpha(color) * pct / 100f);
        return android.graphics.Color.argb(a,
                android.graphics.Color.red(color),
                android.graphics.Color.green(color),
                android.graphics.Color.blue(color));
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
        // 选择应用：有勾选时先清空勾选，回到刚进入选择页的初始界面；再返回才退出页面
        if (pickMode && !selected.isEmpty()) {
            selected.clear();
            for (App a : all) {
                Cell cell = cells.get(instanceKey(a.pkg, a.userId));
                if (cell != null) {
                    bindCell(a, cell);
                }
            }
            updateBatchBar();
            return true;
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页可能刚调过横幅/搜索框透明度，小窗/选择页回来时重新铺一遍
        applyPickSurfaces();
        if (loaded) {
            startPermissionWatch();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && loaded) {
            startPermissionWatch();
        } else if (!hasFocus) {
            stopPermissionWatch();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面被覆盖或进入多窗口非活动状态后，不再周期性读取完整应用列表。
        stopPermissionWatch();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPermissionWatch();
        // 下次回到页面时允许再提示一次
        refreshPromptShown = false;
    }

    /**
     * 授权观察：前台聚焦时定期数一次系统可见包数，比加载时多就说明「获取应用列表」刚放行，
     * 这时弹一次刷新提示，由用户点「刷新」再重读，不自动刷新。
     * 授权弹窗不改变焦点时原地观察；改变焦点时回到页面后继续观察。
     */
    private void startPermissionWatch() {
        if (!permissionWatchNeeded || refreshPromptShown || watchScheduled
                || !hasWindowFocus()) {
            return;
        }
        watchScheduled = true;
        watchTicks = 0;
        main.postDelayed(permissionWatchTask, 400L);
    }

    private void stopPermissionWatch() {
        watchScheduled = false;
        main.removeCallbacks(permissionWatchTask);
    }

    private final Runnable permissionWatchTask = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()
                    || !permissionWatchNeeded || refreshPromptShown) {
                watchScheduled = false;
                return;
            }
            if (!loading && !refreshPromptShown) {
                checkPermissionGrowth();
            }
            watchTicks++;
            if (watchTicks < 60) {
                main.postDelayed(this, watchTicks <= 20 ? 500L : 1000L);
            } else {
                watchScheduled = false;
            }
        }
    };

    /** 后台数一下当前可见包；比加载时多就提示刷新。 */
    private void checkPermissionGrowth() {
        IO.execute(() -> {
            int now = getPackageManager().getInstalledPackages(0).size();
            main.post(() -> {
                if (isFinishing() || isDestroyed() || refreshPromptShown
                        || !hasWindowFocus()) {
                    return;
                }
                if (now > visibleCount) {
                    refreshPromptShown = true;
                    showRefreshPrompt();
                }
            });
        });
    }

    /** 授权后的刷新提示：点「刷新」才重新读取列表。 */
    private void showRefreshPrompt() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("刷新应用列表")
                .setMessage("已获得「获取应用列表」权限，点「刷新」重新读取应用列表。")
                .setPositiveButton("刷新", (d, w) -> loadApps())
                .setNegativeButton("稍后", null)
                .create();
        dialog.show();
        // 缩小并裁成圆角
        Ui.roundAlertDialog(dialog);
        // 标题 / 正文 / 按钮统一缩小一档，默认字号在这张窄卡里太挤
        int titleId = getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId != 0 ? dialog.findViewById(titleId) : null;
        if (title == null) {
            title = dialog.findViewById(android.R.id.title);
        }
        if (title != null) {
            title.setTextSize(15);
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextSize(12.5f);
        }
        android.widget.Button okBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        android.widget.Button laterBtn = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
        if (okBtn != null) {
            okBtn.setTextSize(13);
        }
        if (laterBtn != null) {
            laterBtn.setTextSize(13);
        }
        if (dialog.getWindow() != null) {
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 96),
                    Ui.dp(this, 270));
            dialog.getWindow().setLayout(Math.max(width, Ui.dp(this, 220)),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static String instanceKey(String pkg, int userId) {
        return pkg + "@" + userId;
    }

    /** 白名单与冻结列表共用 freeze_list 里的 freeze_whitelist，白名单应用不会被冻结。 */
    private boolean isWhitelisted(String pkg, int userId) {
        Set<String> raw = getSharedPreferences("freeze_list", MODE_PRIVATE)
                .getStringSet("freeze_whitelist", null);
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        return raw.contains(pkg) || raw.contains(instanceKey(pkg, userId));
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

    /** 一次取回多用户包信息的快照：把原来七八次 su 合并成两条 shell 命令。 */
    private static final class ShellSnapshot {
        boolean root;
        final List<Integer> users = new ArrayList<>();
        final Map<Integer, Set<String>> packages = new HashMap<>();
        final Map<Integer, Set<String>> disabled = new HashMap<>();
        final Map<Integer, Set<String>> system = new HashMap<>();
    }

    /**
     * 启动时只跑两条 su：
     * 1) id + pm list users；
     * 2) 对每个用户一次读出「全部包 / 停用包 / 系统包」。
     * 输出用 @@P:用户号 这类标记分节，回到 Java 再解析。
     */
    private ShellSnapshot loadShellSnapshot() {
        ShellSnapshot snap = new ShellSnapshot();
        PrivShell.Result head = PrivShell.exec("id; echo @@USERS; pm list users", 20);
        snap.root = head.out != null && head.out.contains("uid=0")
                || PrivShell.mode() != PrivShell.Mode.NONE;
        if (head.out != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("UserInfo\\{(\\d+):").matcher(head.out);
            while (m.find()) {
                try {
                    int id = Integer.parseInt(m.group(1));
                    if (!snap.users.contains(id)) {
                        snap.users.add(id);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!snap.users.contains(0)) {
            snap.users.add(0);
        }
        Collections.sort(snap.users);
        if (!snap.root) {
            snap.users.clear();
            snap.users.add(0);
            return snap;
        }
        StringBuilder cmd = new StringBuilder();
        for (int u : snap.users) {
            cmd.append("echo \"@@P:").append(u).append("\"; pm list packages --user ").append(u).append("; ");
            cmd.append("echo \"@@D:").append(u).append("\"; pm list packages -d --user ").append(u).append("; ");
            cmd.append("echo \"@@S:").append(u).append("\"; pm list packages -s --user ").append(u).append("; ");
        }
        PrivShell.Result r = PrivShell.exec(cmd.toString(), 40);
        if (r.out == null) {
            return snap;
        }
        int user = -1;
        char kind = 0;
        for (String line : r.out.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("@@")) {
                int colon = t.indexOf(':');
                if (colon > 2) {
                    kind = t.charAt(2);
                    try {
                        user = Integer.parseInt(t.substring(colon + 1).trim());
                    } catch (NumberFormatException e) {
                        user = -1;
                    }
                }
                continue;
            }
            if (user < 0 || kind == 0 || !t.startsWith("package:")) {
                continue;
            }
            String pkg = t.substring("package:".length()).trim()
                    .replaceFirst("\\s*\\(\\s*\\d+\\s*\\)$", "").trim();
            if (pkg.isEmpty()) {
                continue;
            }
            Map<Integer, Set<String>> target = kind == 'P' ? snap.packages
                    : kind == 'D' ? snap.disabled : snap.system;
            Set<String> set = target.get(user);
            if (set == null) {
                set = new HashSet<>();
                target.put(user, set);
            }
            set.add(pkg);
        }
        return snap;
    }

    private void loadApps() {
        if (loading) {
            return;
        }
        loading = true;
        // 首次进来先给「正在读取」；补拉时保留现有列表，只有空列表才回到加载态
        render(search.getText().toString().trim());
        IO.execute(() -> {
            ShellSnapshot snap = loadShellSnapshot();
            final boolean hasPriv = snap.root;
            // 无 root/Shizuku 但有 Dhizuku 时也能冻结（暂停语义），照常展示列表
            final boolean dhizuku = DhizukuGate.only(this);

            // pm list packages -d 是系统侧的冻结状态，比 PackageInfo 的 enabled 更准
            Set<String> disabled = hasPriv ? snap.disabled.get(0) : null;
            if (disabled == null) {
                disabled = new HashSet<>();
            }
            Set<String> suspended = hasPriv || dhizuku
                    ? SuspendSupport.suspendedAmong(SuspendSupport.cached(this))
                    : new HashSet<>();

            List<App> apps = new ArrayList<>();
            PackageManager pm = getPackageManager();
            // 记录系统当前真正对外的可见包数：授权前后对比它就能知道是否刚放行
            final int visibleNow = pm.getInstalledPackages(0).size();
            final List<Integer> userIds = snap.users;
            // 主用户的系统包集合只取一次：既用于列表，也用来判断「获取应用列表」是否已放行
            final Set<String> shellPkgs0 = hasPriv && snap.packages.get(0) != null
                    ? snap.packages.get(0) : new HashSet<>();
            for (int userId : userIds) {
                Set<String> packages = userId == 0
                        ? shellPkgs0
                        : snap.packages.getOrDefault(userId, new HashSet<>());
                List<PackageInfo> installed = userId == 0
                        ? pm.getInstalledPackages(0) : new ArrayList<>();
                if (userId != 0) {
                    Set<String> dis = snap.disabled.getOrDefault(userId, new HashSet<>());
                    Set<String> sys = snap.system.getOrDefault(userId, new HashSet<>());
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
                    Set<String> truthForUser = userId == 0 ? disabled : snap.disabled.get(userId);
                    a.frozen = truthForUser != null
                            ? (truthForUser.contains(a.pkg)
                            || (userId == 0 && suspended.contains(a.pkg)))
                            : !ai.enabled;
                    try {
                        // 只命中内存缓存，保证列表第一时间出来；
                        // 磁盘/解码交给 fillIconsAsync 在后台并发补，避免每次进页面都同步读几百张图标
                        a.icon = AppIconCache.peek(this, a.pkg);
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
                root = hasPriv || dhizuku;
                // 说明文字统一左对齐
                rootState.setGravity(android.view.Gravity.START);
                rootState.setText(pickMode
                        ? "点击应用可单/多选，右上角☑️完成添加至列表"
                        : hasPriv
                        ? "已通过 " + PrivShell.label() + " 执行：点击图标打开应用，长按冻结/解冻"
                        : dhizuku
                        ? "已通过 Dhizuku 执行（仅暂停）：点击图标打开应用，长按冻结/解冻"
                        : "未获得 root、Shizuku 或 Dhizuku 权限：冻结/解冻不可用，请在半屏布局设置里授权");
                applyPickHintVisibility();
                all.clear();
                all.addAll(shownApps);
                loaded = true;
                // 记下本次可见包数，后续轮询一旦发现变多就提示刷新
                visibleCount = visibleNow;
                render(search.getText().toString().trim());
                fillIconsAsync();
                // 可见包数明显少于系统真实安装数 → 「获取应用列表」没放行，才需要盯着授权；
                // 已放行的正常入口不再做轮询，避免每次进来都反复数包拖慢速度
                boolean permissionMissing = hasPriv
                        ? (!shellPkgs0.isEmpty() && visibleNow < shellPkgs0.size() - 3)
                        : visibleNow <= 1;
                permissionWatchNeeded = permissionMissing;
                if (permissionMissing) {
                    startPermissionWatch();
                } else {
                    stopPermissionWatch();
                }
            });
        });
    }

    private final Runnable refreshTask = this::refreshFrozen;

    /**
     * 列表先出、图标后补：没命中缓存的图标在后台并发解码，
     * 完成后按实例键回填到已显示的格子上，避免一次性解码上百个图标把首屏拖慢。
     */
    private void fillIconsAsync() {
        for (final App a : new ArrayList<>(all)) {
            if (a.icon != null) {
                continue;
            }
            AppIconCache.EXEC.execute(() -> {
                android.graphics.drawable.Drawable icon = null;
                try {
                    PackageInfo pi = getPackageManager().getPackageInfo(a.pkg, 0);
                    if (pi != null && pi.applicationInfo != null) {
                        icon = AppIconCache.load(AppFreezeActivity.this, a.pkg, pi.applicationInfo);
                    }
                } catch (Exception ignored) {
                }
                if (icon == null) {
                    return;
                }
                final android.graphics.drawable.Drawable loaded = icon;
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    a.icon = loaded;
                    Cell cell = cells.get(instanceKey(a.pkg, a.userId));
                    if (cell != null) {
                        cell.icon.setImageDrawable(loaded);
                    }
                });
            });
        }
    }

    private void switchFilter(boolean system) {
        showSystem = system;
        filterUser.setBackgroundResource(system ? R.drawable.bg_btn_white : R.drawable.bg_btn_blue_soft);
        filterUser.setTextColor(system ? getColor(R.color.blue) : getColor(R.color.tab_selected_text));
        filterSystem.setBackgroundResource(system ? R.drawable.bg_btn_blue_soft : R.drawable.bg_btn_white);
        filterSystem.setTextColor(system ? getColor(R.color.tab_selected_text) : getColor(R.color.blue));
        sysHintRow.setVisibility(system && !prefs().getBoolean(KEY_SYS_HINT_HIDDEN, false)
                ? View.VISIBLE : View.GONE);
        // 只切换标签 + 重绘：冻结状态不会因为换标签而变，没必要再跑一次 pm 真值查询
        //（之前这里每次都 refreshFrozen → 一次 shell 查询 + 二次重绘，切标签明显卡顿）
        render(search.getText().toString().trim());
    }

    private void applySysHintVisibility() {
        if (sysHintRow == null) {
            return;
        }
        sysHintRow.setVisibility(showSystem && !prefs().getBoolean(KEY_SYS_HINT_HIDDEN, false)
                ? View.VISIBLE : View.GONE);
    }

    /** 选择页的「点击应用后右上角确定」提示：可点「不再提醒」永久收起。 */
    private void applyPickHintVisibility() {
        if (rootStateRow == null) {
            return;
        }
        boolean hidden = prefs().getBoolean(KEY_PICK_HINT_HIDDEN, false);
        rootStateRow.setVisibility(pickMode && hidden ? View.GONE : View.VISIBLE);
        if (rootStateDismiss != null) {
            rootStateDismiss.setVisibility(pickMode ? View.VISIBLE : View.GONE);
        }
    }

    /** 冻结状态分段选择：只重排当前内存里的列表，不重新拉全量数据。 */
    private void switchStateFilter(int filter) {
        stateFilter = filter;
        applyStateFilterStyle();
        render(search.getText().toString().trim());
    }

    private void applyStateFilterStyle() {
        TextView[] views = {stateAll, stateFrozen, stateUnfrozen, stateInList, stateNotInList};
        for (int i = 0; i < views.length; i++) {
            boolean on = i == stateFilter;
            views[i].setBackgroundResource(on ? R.drawable.bg_btn_blue_soft : R.drawable.bg_btn_white);
            views[i].setTextColor(on ? getColor(R.color.tab_selected_text) : getColor(R.color.blue));
        }
    }

    /** 冻结列表里的键：主用户是纯包名，分身是 pkg@uid（与冻结列表落盘格式一致）。 */
    private void loadFreezeListKeys() {
        Set<String> raw = getSharedPreferences("freeze_list", MODE_PRIVATE)
                .getStringSet("pkgs", new HashSet<>());
        listKeys = new HashSet<>(raw);
    }


    /** 名称、包名、多用户后缀命中；中文名称再按拼音首字母命中（如 wx → 微信）。 */
    private static boolean matchesQuery(App a, String q) {
        if (a.label.toLowerCase(Locale.ROOT).contains(q)
                || a.pkg.toLowerCase(Locale.ROOT).contains(q)
                || (a.userId != 0 && String.valueOf(a.userId).contains(q))) {
            return true;
        }
        if (a.py == null) {
            a.py = Pinyin.initials(a.label);
        }
        return !a.py.isEmpty() && a.py.contains(q);
    }

    /** 搜索、标签（用户/系统）与冻结状态三者取交集；状态一律从 App.frozen 读，不另记一份。 */
    private void render(String query) {
        if (grid == null) {
            return;
        }
        // 列表卡与宫格卡都跟搜索框左右对齐：搜索框在 26dp / 27dp 处。
        // 列表行铺满容器；宫格每格自带 4dp 外边距，容器两侧各少 4dp，卡片边才落回同一条线。
        // 选择应用页把顶部间距收窄（提示语与图标的距离和设计稿一致），其余页面保持 10dp。
        boolean list = listView();
        // 列表模式补 4dp：宫格第一排卡片上沿 = 容器上内边距 + 行间距 + 格子外边距，
        // 列表卡片上沿只有容器上内边距 + 行间距，补上这 4dp 让第一个应用最上沿对齐。
        int topPad = (pickMode ? 4 : 10) + (list ? 4 : 0);
        grid.setPadding(Ui.dp(this, list ? 26 : 22), Ui.dp(this, topPad),
                Ui.dp(this, list ? 27 : 23), Ui.dp(this, 16));
        // 列表/宫格标识：列表界面沿用宫格界面那一处位置，始终按宫格最后一列图标右缘对齐。
        // 这一行本身带 14dp 父级内边距，按钮 32dp 且有 8dp 内边距，故右侧留白要减掉这 22dp。
        if (viewModeRow != null) {
            int availRow = getResources().getDisplayMetrics().widthPixels
                    - Ui.dp(this, 22) - Ui.dp(this, 23);
            int cellW = availRow / COLUMNS;
            int inset = Ui.dp(this, 23) + Ui.dp(this, 4)
                    + Math.max(0, (cellW - Ui.dp(this, 8) - Ui.dp(this, 46)) / 2);
            viewModeRow.setPadding(0, 0, Math.max(0, inset - Ui.dp(this, 22)), 0);
        }
        // 名单与图标还没读完时先不显示切换标识，加载完成后再出现
        if (viewModeBtn != null) {
            viewModeBtn.setVisibility(pickMode && loaded ? View.VISIBLE : View.GONE);
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
            if (stateFilter == 3 && !inFreezeList(a)) {
                continue;
            }
            if (stateFilter == 4 && inFreezeList(a)) {
                continue;
            }
            if (!q.isEmpty() && !matchesQuery(a, q)) {
                continue;
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
            } else if (stateFilter == 3) {
                grid.addView(emptyText("当前标签下没有已添加的应用"));
            } else if (stateFilter == 4) {
                grid.addView(emptyText("当前标签下没有未添加的应用"));
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
        // 切换标签、切换状态筛选都保留已选应用：不再按当前可见范围收缩 selected，
        // 顶部已选图标条始终展示全部勾选项。
        updateBatchBar();
        if (listView()) {
            for (int i = 0; i < shown.size(); i++) {
                View r = buildRow(shown.get(i));
                if (pickMode && i == 0 && r.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = 0;
                }
                grid.addView(r);
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
                // 选择应用页第一排不留额外间距：提示语到卡片上沿的距离按设计稿收紧
                if (!(pickMode && i == 0)) {
                    rlp.topMargin = Ui.dp(this, 6);
                }
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
        // 选择应用页点一下只留选中高亮，不再叠按压水波纹（避免看起来像一层阴影）
        if (!pickMode) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            box.setForeground(getDrawable(tv.resourceId));
        }
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

        // 图标下方一行应用名：居中、单行、超长省略；雪花与名称同一行居中，短名称时也贴着文字
        TextView name = Ui.text(this, a.label, 11, Ui.TEXT_PRIMARY, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER);
        ImageView snow = new ImageView(this);
        snow.setImageResource(R.drawable.ic_sc_fab_freeze);
        snow.setVisibility(View.GONE);
        nameRow.addView(snow, new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)));
        nameRow.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams nameRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameRowLp.topMargin = Ui.dp(this, 6);
        nameRowLp.leftMargin = Ui.dp(this, 2);
        nameRowLp.rightMargin = Ui.dp(this, 2);
        box.addView(nameRow, nameRowLp);

        wrap.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Cell cell = new Cell();
        cell.root = box;
        cell.icon = icon;
        cell.name = name;
        cell.nameRow = nameRow;
        cell.snow = snow;
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
        // 选择应用页只保留选中高亮，不叠按压水波纹
        if (!pickMode) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setForeground(getDrawable(tv.resourceId));
        }
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
        cell.name = name;
        cells.put(instanceKey(a.pkg, a.userId), cell);
        bindCell(a, cell);

        row.setContentDescription(a.label);
        bindActions(a, cell, row);
        return row;
    }

    /** 网格/列表共用的点击与长按行为。 */
    private void bindActions(App a, Cell cell, View target) {
        target.setOnClickListener(v -> {
            if (pickMode || batchMode) {
                // 选择页保持 v2.9 的交互：点一下选中，右上角「确定」再加入列表
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

            @Override
            public boolean listRemoveEnabled() {
                return false;
            }
        });
    }

    private SharedPreferences listPrefs() {
        return getSharedPreferences("freeze_list", MODE_PRIVATE);
    }

    private boolean inFreezeList(App a) {
        // 用 onCreate 时缓存的名单键，避免每次判断都拷贝一遍 prefs 里的 StringSet
        return listKeys.contains(storageKey(a.pkg, a.userId));
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
        loadFreezeListKeys();
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
        final String key = storageKey(a.pkg, a.userId);
        // 旧数据是单个标签字符串，新数据是数组；一个应用可以同时加入多个标签
        java.util.Set<String> mine = new java.util.LinkedHashSet<>();
        try {
            Object v = new JSONObject(fp.getString("app_tabs", "{}")).opt(key);
            if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i, "");
                    if (!id.isEmpty()) {
                        mine.add(id);
                    }
                }
            } else if (v instanceof String && !((String) v).isEmpty()) {
                mine.add((String) v);
            }
        } catch (Exception ignored) {
        }
        // 没有显式归属时按系统标记落回内置标签，当前项才不会标错
        if (mine.isEmpty()) {
            mine.add(a.system ? "system" : "user");
        }
        List<MoveTabDialog.Tab> entries = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            entries.add(new MoveTabDialog.Tab(ids.get(i), names.get(i), visibles.get(i),
                    mine.contains(ids.get(i))));
        }
        MoveTabDialog.show(this, a.label, a.icon, entries, target -> {
            // 用户应用不允许进入内置的「系统应用」标签
            if (!a.system && "system".equals(target.id)) {
                Toast.makeText(this, "用户应用禁止移入系统应用标签",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            try {
                // 移动语义：覆盖原有归属，只留目标标签
                JSONObject o = new JSONObject(fp.getString("app_tabs", "{}"));
                JSONArray arr = new JSONArray();
                arr.put(target.id);
                o.put(key, arr);
                fp.edit().putString("app_tabs", o.toString()).apply();
                com.jin.freeze.core.SwipeFreeze.syncAsync(this);
                Toast.makeText(this, "已移动到「" + target.name + "」",
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
            return true;
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
        // 实例键：分身和主应用各占一个快捷方式 ID
        ShortcutPin.pin(this, instanceKey(a.pkg, a.userId), a.label, a.icon);
    }

    private void bindCell(App a, Cell cell) {
        // 选择应用页固定用灰度表示已冻结，不跟随「冻结应用显示灰度图标」开关，
        // 也不显示名称左侧的雪花（那个开关只作用于冻结列表）
        if (a.frozen) {
            cell.icon.setColorFilter(GRAY);
            cell.icon.setAlpha(0.45f);
        } else {
            cell.icon.clearColorFilter();
            cell.icon.setAlpha(1f);
        }
        if (cell.snow != null) {
            cell.snow.setVisibility(View.GONE);
        }
        if (cell.name != null) {
            cell.name.setCompoundDrawables(null, null, null, null);
            // 名称过长时省略，避免撑出格子
            int maxW = Math.max(Ui.dp(this, 24),
                    getResources().getDisplayMetrics().widthPixels / COLUMNS
                            - Ui.dp(this, 28) - Ui.dp(this, 10));
            cell.name.setMaxWidth(maxW);
        }
        boolean sel = (batchMode || pickMode) && selected.contains(instanceKey(a.pkg, a.userId));
        cell.root.setBackgroundResource(sel
                ? R.drawable.bg_card_selected : R.drawable.bg_card);
    }

    /** 添加页内解冻：成功后恢复彩色并给出「已解冻」，失败展示 pm 真实输出。 */
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
                    Toast.makeText(this, "已解冻", Toast.LENGTH_SHORT).show();
                } else {
                    Ui.smallDialog(this, "解冻失败",
                            a.label + "：" + Ui.pmReason(r, false), "知道了");
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
            Set<String> disabledRaw = disabledPackages();
            // Dhizuku-only 读不到 pm 停用列表：按「没有停用项」处理，冻结态看暂停真值
            if (disabledRaw == null && DhizukuGate.only(this)) {
                disabledRaw = new HashSet<>();
            }
            final Set<String> disabled = disabledRaw;
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
            // 勾选到应用后：右上角显示打勾的「确定」；列表/宫格标识固定在状态分段行，
            // 不再随勾选隐藏
            boolean has = !selected.isEmpty();
            if (pickDoneBtn != null) {
                pickDoneBtn.setVisibility(has ? View.VISIBLE : View.GONE);
            }
            refreshPickStrip();
            return;
        }
        boolean has = !selected.isEmpty() && !working;
        freezeBtn.setEnabled(has);
        freezeBtn.setAlpha(has ? 1f : 0.4f);
        unfreezeBtn.setEnabled(has);
        unfreezeBtn.setAlpha(has ? 1f : 0.4f);
    }

    /**
     * 选择应用页顶部的已选图标条：与全屏列表多选时的图标条同一套密度（30dp 图标 + 6dp 间距），
     * 左缘对齐下方大图标/搜索框的 26dp；一行放不下自动折行，显示全部已选。
     */
    private void refreshPickStrip() {
        if (pickStrip == null) {
            return;
        }
        pickStrip.removeAllViews();
        if (!pickMode) {
            pickStrip.setVisibility(View.GONE);
            return;
        }
        List<App> picked = new ArrayList<>();
        for (App a : all) {
            if (selected.contains(instanceKey(a.pkg, a.userId))) {
                picked.add(a);
            }
        }
        if (picked.isEmpty()) {
            pickStrip.setVisibility(View.GONE);
            return;
        }
        int padStart = Ui.dp(this, 26);
        int padEnd = Ui.dp(this, 27);
        int sz = Ui.dp(this, 30);
        int gap = Ui.dp(this, 6);
        int avail = getResources().getDisplayMetrics().widthPixels - padStart - padEnd;
        int perRow = Math.max(1, (avail + gap) / (sz + gap));
        // 满行用等宽弹簧撑开：第一个贴左边缘、最后一个贴右边缘，与下方大图标两侧对齐
        int fullGap = perRow > 1 ? Math.max(gap, (avail - perRow * sz) / (perRow - 1)) : gap;
        for (int i = 0; i < picked.size(); i += perRow) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(padStart, 0, padEnd, 0);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.dp(this, 6);
            row.setLayoutParams(rlp);
            int end = Math.min(i + perRow, picked.size());
            boolean full = end - i == perRow;
            for (int k = i; k < end; k++) {
                App a = picked.get(k);
                if (k > i) {
                    if (full) {
                        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
                    }
                }
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(a.icon != null ? a.icon
                        : androidx.core.content.ContextCompat.getDrawable(this,
                        android.R.drawable.sym_def_app_icon));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setContentDescription(a.label);
                // 跟随当前页面的图标状态：已冻结灰化压暗，未冻结保持彩色
                if (a.frozen) {
                    iv.setColorFilter(GRAY);
                    iv.setAlpha(0.45f);
                } else {
                    iv.clearColorFilter();
                    iv.setAlpha(1f);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                if (!full && k < end - 1) {
                    lp.rightMargin = fullGap;
                }
                row.addView(iv, lp);
            }
            pickStrip.addView(row);
        }
        pickStrip.setVisibility(View.VISIBLE);
    }

    /** 冻结/解冻失败时按 root → Shizuku → Dhizuku 补申请一次权限；已有通道时静默跳过。 */
    private void requestPrivAfterFail() {
        PrivRequest.ensure(this, got -> {
            if (got && !isFinishing() && !isDestroyed()) {
                root = true;
                refreshFrozen();
            }
        });
    }

    private void toggle(App a) {
        if (!root) {
            Toast.makeText(this, "需要 root、Shizuku 或 Dhizuku 权限才能冻结/解冻", Toast.LENGTH_SHORT).show();
            requestPrivAfterFail();
            return;
        }
        if (working) {
            Toast.makeText(this, "正在执行其他操作，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!a.frozen && isWhitelisted(a.pkg, a.userId)) {
            Toast.makeText(this, a.label + " 在白名单里，不会被冻结", Toast.LENGTH_SHORT).show();
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
            // 系统应用被 ROM 拦住停用时，自动降级为 pm suspend（只覆盖主用户）；
            // Dhizuku-only 没有 shell，冻结直接走暂停通道
            boolean blocked = toFreeze && a.userId == 0 && !ok
                    && (SuspendSupport.romBlocked(r.out) || DhizukuGate.only(this));
            PrivShell.Result failShow = r;
            if (blocked) {
                PrivShell.Result s = SuspendSupport.suspend(this, a.pkg, a.label);
                if (suspendApplied(a.pkg, s)) {
                    ok = true;
                    SuspendSupport.remember(this, Collections.singleton(a.pkg));
                } else {
                    failShow = s;
                    android.util.Log.w("JinPm", "suspend fallback failed: code=" + s.code
                            + " timeout=" + s.timeout + "\n"
                            + (s.out == null ? "" : s.out.trim()));
                }
            }
            final boolean done = ok;
            final boolean suspendedNow = blocked && ok;
            final PrivShell.Result failR = failShow;
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!done) {
                    Ui.smallDialog(this, toFreeze ? "冻结失败" : "解冻失败",
                            a.label + "：" + (blocked && !DhizukuGate.only(this)
                                    ? Ui.NO_FREEZE_CHANNEL : Ui.pmReason(failR, toFreeze)), "知道了");
                    // 失败多半是没权限：顺手补一次可申请的授权
                    requestPrivAfterFail();
                    return;
                }
                a.frozen = toFreeze;
                a.suspended = suspendedNow;
                render(search.getText().toString().trim());
                updateBatchBar();
                refreshFrozen();
                // 长按图标菜单触发的提示走系统默认 Toast
                Toast.makeText(this, toFreeze ? "已冻结" : "已解冻", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * 已冻结（含暂停）应用单击：撤销暂停 + 启用（需要时）后直接启动，一次点击完成，
     * 不弹二次确认；失败只给一句短原因。
     */
    private void unfreezeAndOpen(App a) {
        if (!root) {
            Toast.makeText(this, "需要 root、Shizuku 或 Dhizuku 权限才能解冻并打开", Toast.LENGTH_SHORT).show();
            requestPrivAfterFail();
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
                    Ui.smallDialog(this, "解冻失败，无法打开",
                            a.label + "：" + (failReason != null
                                    ? failReason : Ui.pmReason(r, false)), "知道了");
                    requestPrivAfterFail();
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
        if (freeze) {
            // 白名单应用任何通道都不冻，批量冻结时直接跳过
            int before = targetApps.size();
            targetApps.removeIf(a -> isWhitelisted(a.pkg, a.userId));
            int skipped = before - targetApps.size();
            if (skipped > 0) {
                Toast.makeText(this, "已跳过白名单应用 " + skipped + " 个",
                        Toast.LENGTH_SHORT).show();
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
            final boolean dhizuku = DhizukuGate.only(this);
            Map<String, PrivShell.Result> results = new HashMap<>();
            Set<String> degraded = new HashSet<>();
            for (App app : targetApps) {
                String pkg = app.pkg;
                PrivShell.Result r = execPm(pkg, app.userId, freeze);
                results.put(instanceKey(pkg, app.userId), r);
                // 分身实例使用带用户 ID 的 pm 命令；暂停降级暂只保留主用户路径。
                // Dhizuku-only 没有 shell，冻结直接走暂停通道。
                if (freeze && app.userId == 0
                        && (SuspendSupport.romBlocked(r.out) || dhizuku)) {
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
                } else if (truth != null || dhizuku) {
                    boolean dis = truth != null && truth.contains(pkg);
                    ok = freeze ? (dis || suspendedTruth.contains(pkg))
                            : (!dis && !suspendedTruth.contains(pkg));
                } else {
                    ok = isPmOk(r);
                }
                if (ok) {
                    okCount++;
                } else {
                    fails.add(app.label + "：" + (degraded.contains(pkg) && !dhizuku
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
                if (!fails.isEmpty()) {
                    // 有失败项：顺手补一次可申请的授权
                    requestPrivAfterFail();
                }
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
        // Dhizuku-only 没有 shell：解冻走取消暂停通道（冻结走 runToggle/runBatch 的降级暂停）
        if (!freeze && userId == 0 && DhizukuGate.only(this)) {
            return SuspendSupport.unsuspend(pkg);
        }
        String cmd = freeze
                ? "pm disable-user --user " + userId + " " + pkg
                : "pm unsuspend --user " + userId + " " + pkg
                + "; pm enable --user " + userId + " " + pkg;
        PrivShell.Result r = PrivShell.exec(cmd, 30);
        // pm 只能撤销 pm 自己建的暂停：凚的 root 通道/锁屏/划卡建的是 suspender=com.android.shell，
        // 遇到这种暂停 pm 会打印 true 却不生效，这里按候选 suspender 用 CLI 再撤一次
        if (!freeze && userId == 0 && r.out != null
                && r.out.contains("new suspended state: true")
                && SuspendSupport.clearForeignSuspension(pkg)) {
            r = PrivShell.exec("pm enable --user 0 " + pkg, 30);
        }
        return r;
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
            // Dhizuku-only 读不到停用列表：按暂停真值判，读不到才看命令结果
            if (DhizukuGate.only(this)) {
                Boolean s = SuspendSupport.suspendedOne(pkg);
                return s == null ? isPmOk(r) : (freeze ? s : !s);
            }
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
        // 分身要在它自己的用户下解析启动组件：主应用被冻结时 PackageManager 会返回 null，
        // 直接判定「没有可启动的界面」，而桌面仍能打开双开
        if (a.userId != 0) {
            String comp = resolveCloneLauncher(a.pkg, a.userId);
            if (comp == null) {
                Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
                return;
            }
            PrivShell.Result r = PrivShell.exec(
                    "am start --user " + a.userId + " -n " + comp, 15);
            if (r == null || r.timeout || (r.out != null && r.out.contains("Error"))) {
                PrivShell.exec("am start --user " + a.userId
                        + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                        + a.pkg, 15);
            }
            return;
        }
        Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (i == null) {
            Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
            return;
        }
        // 有双开时直接用 startActivity 会弹「主应用还是双开」：在后台线程明确 --user 0 启动
        final Intent launch = i;
        IO.execute(() -> {
            String comp = launch.getComponent() == null ? null
                    : launch.getComponent().flattenToShortString();
            boolean done = false;
            if (comp != null && PrivShell.available()) {
                PrivShell.Result r = PrivShell.exec("am start --user 0 -n " + comp, 15);
                done = r != null && !r.timeout && r.out != null && !r.out.contains("Error");
            }
            if (!done) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    try {
                        startActivity(launch);
                    } catch (Exception e) {
                        Toast.makeText(this, "打开失败：" + e.getClass().getSimpleName(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** 在分身用户下解析启动组件（「包/类」）；解析不到返回 null。 */
    private String resolveCloneLauncher(String pkg, int userId) {
        // 注意必须带 -a/-c/-p：resolve-activity 的位置参数是 URI，不是包名
        PrivShell.Result r = PrivShell.exec("cmd package resolve-activity --brief --user "
                + userId + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                + pkg + " | tail -n 1", 10);
        String out = r.out == null ? "" : r.out.trim();
        if (out.contains("/")) {
            return out;
        }
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null && i.getComponent() != null) {
            return i.getComponent().flattenToShortString();
        }
        return null;
    }
}

