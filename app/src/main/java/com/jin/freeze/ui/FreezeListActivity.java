package com.jin.freeze.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.jin.freeze.R;
import com.jin.freeze.core.LaunchTarget;
import com.jin.freeze.core.LegacyMigrate;
import com.jin.freeze.core.PrivShell;
import com.jin.freeze.core.ShizukuGate;
import com.google.android.material.bottomsheet.BottomSheetDialog;

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
 * 冻结列表：用户自选的名单，支持一键冻结/解冻整份名单。
 * 冻结状态一律以 pm 的系统真值为准；点击图标直接打开（冻结的先解冻再开）。
 * 长按图标拖拽调整顺序（自定义顺序持久化）；编辑模式下点减号移出名单。
 */
public class FreezeListActivity extends BaseActivity {

    private static final String PREFS = "freeze_list";
    private static final String KEY_PKGS = "pkgs";
    private static final String KEY_ORDER = "order";
    private static final String KEY_CUSTOM = "custom_order";
    private static final int COLUMNS = 5;
    /** 半屏内容区默认高度（dp，不含把手）：按当前版本实测值固化，新装/恢复默认都用它。 */
    private static final int PANEL_CONTENT_DEFAULT_DP = 544;
    /** 布局设置弹窗默认高度（dp）：同样取当前版本实测值。 */
    private static final int LAYOUT_DIALOG_DEFAULT_DP = 218;
    /** 自定义布局：列数 + 图标大小，间距由列宽自动均分。 */
    private static final String KEY_COLUMNS = "grid_columns";
    private static final String KEY_ICON_DP = "grid_icon_dp";
    private static final String KEY_ICON_MANUAL = "grid_icon_manual";
    private static final String KEY_SHOW_NAME = "grid_show_name";
    /** 移动图标开关：打开后点图标选中、再点目标位置放下；全屏与半屏共用同一份状态。 */
    private static final String KEY_MOVE_MODE = "grid_move_mode";
    /** 悬浮冻结/解冻按钮的偏移（dp）：x 以底部居中的基准左右偏，y 从贴底往上偏。 */
    private static final String KEY_FAB_DX = "fab_dx";
    private static final String KEY_FAB_DY = "fab_dy";
    private static final String KEY_FAB_SIZE = "fab_size";
    /** 悬浮冻结按钮默认直径（dp），大小滑块与恢复默认都以它为准。 */
    private static final int FAB_SIZE_DEFAULT = 40;
    private static final int FAB_SIZE_MIN = 32;
    private static final int FAB_SIZE_MAX = 64;
    /** 标签：内置「用户应用 / 系统应用」+ 自定义；顺序、显隐、名称与每个应用的归属都落盘。 */
    private static final String KEY_TABS = "tabs";
    private static final String KEY_APP_TABS = "app_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";
    /** 是否允许在列表区域左右滑动切换标签；关掉后只能点标签。 */
    private static final String KEY_TAB_SWIPE = "tab_swipe";
    /** 半屏浮层高度：拖把手松手后落盘，与布局设置那套参数互不影响。 */
    private static final String KEY_PANEL_HEIGHT = "panel_height_dp";
    /** 布局设置弹窗的拖动偏移（相对默认位置），与半屏高度分开存。 */
    private static final String KEY_LAYOUT_DX = "layout_dialog_dx";
    private static final String KEY_LAYOUT_DY = "layout_dialog_dy";
    /** 布局设置弹窗打开时的高度（dp）：「布局面板高度」滑块记忆，绝不覆盖半屏。 */
    private static final String KEY_LAYOUT_OPEN_H = "layout_dialog_open_h";
    /** 布局设置弹窗点 ▼ 展开时的高度（dp）：「展开高度」滑块记忆，允许覆盖半屏。 */
    private static final String KEY_LAYOUT_EXPAND_H = "layout_dialog_expand_h";
    /** 旧版单一弹窗高度键：只在升级后首次读取时迁移成「布局面板高度」。 */
    private static final String KEY_LAYOUT_H_LEGACY = "layout_dialog_h";
    /** 两条高度滑块的量程（dp）。 */
    private static final int LAYOUT_OPEN_MAX_DP = 520;
    private static final int LAYOUT_EXPAND_MAX_DP = 666;
    /** 半屏顶部小白条开关：关掉后隐藏把手、高度沿用记住的值。 */
    private static final String KEY_PANEL_HANDLE = "panel_handle_enabled";
    /** 冻结成功后是否自动收起列表（全屏/半屏各自记忆，回到进入前的应用）。 */
    private static final String KEY_PANEL_DISMISS = "dismiss_after_freeze";
    /** 「隐藏后台」：勾选后离开列表时把任务从最近任务移除；全屏/半屏各自记忆。 */
    private static final String KEY_PANEL_HIDE_RECENTS = "hide_recents";
    /** 冻结成功后是否弹「已冻结」提示；两种形态共用同一个开关。 */
    private static final String KEY_SHOW_FROZEN_TIP = "show_frozen_toast";
    /** 桌面图标进入的形态：true=全屏冻结列表，false=半屏面板（默认）。 */
    private static final String KEY_FULLSCREEN_OPEN = "fullscreen_open";
    /** 半屏上方非面板区域的遮罩强度：0=近乎全透明，100=最模糊；只作用于半屏。 */
    private static final String KEY_BLUR = "backdrop_blur";
    /** 半屏面板本体档位：0=完全透明（背后看得清）、50=最模糊、100=实心。 */
    private static final String KEY_SURFACE = "surface";
    /** 底部雪花按钮底板档位：与面板同一套语义，单独记忆。 */
    private static final String KEY_FAB_SURFACE = "fab_surface";
    /** 全屏背景档位：0=完全透明、50=模糊、100=实心；与半屏的 panel_surface 各自记忆。 */
    private static final String KEY_FULL_SURFACE = "full_surface";
    /** 全屏里的雪花按钮底板档位：单独记忆，不和半屏共用。 */
    private static final String KEY_FULL_FAB_SURFACE = "full_fab_surface";
    /** 搜索框颜色档位：独立记忆，与全屏/半屏背景都解耦。 */
    private static final String KEY_SEARCH_SURFACE = "search_surface";
    /** 启动时检查 root / Shizuku：入口带上这个标记，页面起来后探测一次。 */
    public static final String EXTRA_CHECK_PRIV = "check_priv";
    /** 默认实心：与装机现有观感一致，升级后不突然变样。 */
    private static final int SURFACE_SOLID = 100;
    private static final String TAB_USER = "user";
    private static final String TAB_SYSTEM = "system";
    /** 图标长按出操作单的判定时长：350–450ms 档，比系统默认 500ms 短，按住一秒内稳出。 */
    private static final int LONG_PRESS_MS = 400;
    private int gridColumns = COLUMNS;
    private int iconDp = 52;
    private boolean iconManual;
    private boolean showName = true;
    private int fabDx;
    private int fabDy;
    private int fabSizeDp = FAB_SIZE_DEFAULT;
    /** 桌面快捷方式直达：进页面后对名单自动执行整批冻结/解冻。 */
    public static final String EXTRA_ACTION = "action";
    public static final String ACTION_FREEZE = "freeze";
    public static final String ACTION_UNFREEZE = "unfreeze";
    /** 桌面图标入口每次拉起都带上：singleTask 复用时也要重播同样的过渡，避免越点越快。 */
    public static final String EXTRA_PLAY_INTRO = "play_intro";
    /** 过渡三段时长（压暗 / 停留 / 淡出）：冷启动、复用拉起、收起共用同一组值。 */
    private static final int INTRO_IN_MS = 300;
    private static final int INTRO_HOLD_MS = 260;
    private static final int INTRO_OUT_MS = 340;
    private static final class Item {
        String pkg;
        String label;
        Drawable icon;
        boolean system;
        boolean frozen;
    }

    private static final class Cell {
        LinearLayout root;
        ImageView icon;
        TextView name;
        View badge;
        Item item;
        /** 图标自己的长按定时器：400ms 主动触发，不依赖系统默认的 500ms。 */
        Runnable longPress;
        float downX;
        float downY;
    }

    private static final class Tab {
        String id;
        String name;
        boolean visible = true;
        /** 自定义标签可删除；内置的两个是应用归属的兜底，不允许删，但允许改名。 */
        boolean custom;
    }

    private final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Cell> cells = new HashMap<>();
    /** 与屏幕上的格子一一对应，拖拽换位时只重绑数据、不重建视图，保证手势连续。 */
    private final List<Cell> cellOrder = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    /** 应用 → 标签；没有映射（或映射到已删除标签）时按 FLAG_SYSTEM 落回内置标签。 */
    private final Map<String, String> appTabs = new HashMap<>();
    private String currentTabId = TAB_USER;

    private TextView count;
    private View freezeBtn;
    private View unfreezeBtn;
    private android.widget.ImageButton editBtn;
    private LinearLayout grid;
    private LinearLayout fabGroup;
    /** 长按冻结按钮浮出的一键解冻小图标。 */
    private View unfreezeFab;
    private boolean fabExpanded;
    /** 冻结执行中的雪花旋转动画，执行完停止并复位。 */
    private android.animation.ObjectAnimator freezeSpin;
    private final Handler fabHandler = new Handler(Looper.getMainLooper());
    private final Runnable fabCollapseRunnable = this::collapseUnfreezeFab;
    private android.widget.ScrollView scrollView;
    private EditText search;
    private LinearLayout tabBar;
    private TextView sysHint;
    /** 空态：名单为空时显示在面板中部（添加应用 / 从旧版导入）。 */
    private View emptyState;
    private boolean loadedOnce;
    /** 打开选应用页期间不让「隐藏半屏后台」把面板收掉，否则回传结果没人接。 */
    private boolean pickerOpen;

    private boolean working;
    private boolean editMode;
    /** 半屏浮层承载形态（从快捷方式进入）；全屏页为 false。 */
    private boolean panelMode;
    /** 快捷方式带进来的待执行动作，数据就绪后消费一次。 */
    private String pendingAction;
    /** 移动模式：点一下选中（高亮），再点目标项即插到它前面并立即落盘。 */
    private boolean moveMode;
    /** 当前被选中的包名；null 表示没有选中。 */
    private String selectedPkg;
    /** 移动模式下长按拖动：正在被拖的包名，非空时格子保持半透明观感。 */
    private String draggingPkg;
    /** 拖动过程中最近停留的目标，避免同一格重复换位。 */
    private String lastHoverPkg;
    /** 拖动是否真的换过位：换过才在松手时落盘。 */
    private boolean dragMoved;
    /** 最近一次长按触发时间：用于吞掉紧接着的松手点击。 */
    private long lastLongPressAt;
    /** 半屏面板可拖高度范围（px），随拖动实时夹紧；只约束内容区，不含把手。 */
    private int panelMinHpx;
    private int panelMaxHpx;
    /** 当前内容区高度（px）：落盘与拖拽都只认这个值，把手高度另外加。 */
    private int panelContentPx;
    /** 「半屏高度调节」开关；关掉时隐藏把手、高度沿用记住的值。 */
    private boolean panelHandleEnabled = true;
    /** 「冻结后收起半屏」开关；等状态刷新完成后再收起，避免收起早于刷新。 */
    private boolean panelDismissAfterFreeze;
    /** 「隐藏半屏后台」开关：默认不勾选，保持半屏任务照常出现在最近任务里。 */
    protected boolean panelHideRecents;
    private boolean dismissAfterReload;
    /** 冻结成功后的提示开关；失败提示不受它影响。 */
    private boolean showFrozenTip;
    /** 列表区域左右滑动切标签；布局设置可关。 */
    private boolean tabSwipeEnabled = true;
    private float swipeDownX;
    private float swipeDownY;
    private boolean swipeTracking;

    /** 启动过渡：半屏外整屏压暗的那层（布局内，位于面板之下）。 */
    private View introScrimView;
    /** 状态栏那一条黑：挂在 DecorView 顶部，高度在首次显示前一次算好，显示后不再重排。 */
    private View introStatusView;
    /** 过渡进行中：期间再次收到拉起请求不重启，跟着这一轮自然走完。 */
    private boolean introPlaying;
    /** 已在等首帧起跑：避免同一轮里挂上第二个起跑点。 */
    private boolean introStartPosted;
    /** 每次起跑递增；旧动画的收尾回调凭它作废，不会把新一轮的遮罩关掉。 */
    private int introRun;
    /** 是否已把状态栏图标切成白色（黑条过半才切，避免亮底上看不清）。 */
    private boolean introIconsLight;
    /** 未授权提示框（启动检查与布局设置共用），避免同一时刻叠出两个。 */
    private android.app.Dialog privDialog;
    private static final String TAG_INTRO = "FW_INTRO";
    private final Runnable introReveal = this::startIntroReveal;
    private final Runnable introSafety = this::guardIntroStuck;

    /** 已冻结灰度滤镜，和「应用冻结」页保持一致。 */
    private static final android.graphics.ColorMatrixColorFilter GRAY;

    static {
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(0f);
        GRAY = new android.graphics.ColorMatrixColorFilter(cm);
    }

    private final ActivityResultLauncher<Intent> picker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                pickerOpen = false;
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                ArrayList<String> picked = result.getData()
                        .getStringArrayListExtra(AppFreezeActivity.EXTRA_PICKED);
                if (picked == null || picked.isEmpty()) {
                    return;
                }
                Set<String> cur = new HashSet<>(pkgs());
                cur.addAll(picked);
                prefs().edit().putStringSet(KEY_PKGS, cur).apply();
                reload();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panelMode = isPanelMode();
        // 列表选中项不再有高亮框，清掉历史遗留的档位键
        prefs().edit().remove("accent_surface").remove("panel_accent_surface").apply();
        setContentView(panelMode
                ? R.layout.activity_freeze_list_panel
                : R.layout.activity_freeze_list);

        View back = findViewById(R.id.back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }
        View add = findViewById(R.id.add);
        if (add != null) {
            add.setOnClickListener(v -> openPicker());
        }
        count = findViewById(R.id.count);
        freezeBtn = findViewById(R.id.btn_freeze);
        unfreezeBtn = findViewById(R.id.btn_unfreeze);
        editBtn = findViewById(R.id.edit);
        grid = findViewById(R.id.grid);
        scrollView = findViewById(R.id.scroll);
        fabGroup = findViewById(R.id.fab_group);
        search = findViewById(R.id.search);
        tabBar = findViewById(R.id.tab_bar);
        sysHint = findViewById(R.id.sys_hint);

        if (editBtn != null) {
            editBtn.setOnClickListener(v -> setEditMode(!editMode));
        }
        View backup = findViewById(R.id.backup);
        if (backup != null) {
            backup.setOnClickListener(v -> showBackup());
        }
        View restore = findViewById(R.id.restore);
        if (restore != null) {
            restore.setOnClickListener(v -> showRestore());
        }
        View layoutBtn = findViewById(R.id.layout);
        if (layoutBtn != null) {
            layoutBtn.setOnClickListener(v -> showLayoutDialog());
        }
        // 半屏形态底栏没有输入框：左边搜索图标直接进添加应用页（自带搜索与用户/系统切换）
        View panelSearch = findViewById(R.id.panel_search);
        if (panelSearch != null) {
            panelSearch.setOnClickListener(v -> openPicker());
        }
        emptyState = findViewById(R.id.panel_empty);
        View emptyAdd = findViewById(R.id.empty_add);
        if (emptyAdd != null) {
            emptyAdd.setOnClickListener(v -> openPicker());
        }
        ensurePanelLayoutInit();
        gridColumns = prefs().getInt(lk(KEY_COLUMNS), COLUMNS);
        if (gridColumns < 4 || gridColumns > 6) {
            // 旧数据（2/3 列）按 4 列处理并修正落盘
            gridColumns = 4;
            prefs().edit().putInt(lk(KEY_COLUMNS), 4).apply();
        }
        iconDp = prefs().getInt(lk(KEY_ICON_DP), 52);
        iconManual = prefs().getBoolean(lk(KEY_ICON_MANUAL), false);
        showName = prefs().getBoolean(lk(KEY_SHOW_NAME), true);
        moveMode = prefs().getBoolean(lk(KEY_MOVE_MODE), false);
        showFrozenTip = prefs().getBoolean(lk(KEY_SHOW_FROZEN_TIP), false);
        tabSwipeEnabled = prefs().getBoolean(lk(KEY_TAB_SWIPE), true);
        fabDx = prefs().getInt(lk(KEY_FAB_DX), 0);
        fabDy = prefs().getInt(lk(KEY_FAB_DY), 0);
        fabSizeDp = prefs().getInt(lk(KEY_FAB_SIZE), FAB_SIZE_DEFAULT);
        if (fabSizeDp < FAB_SIZE_MIN || fabSizeDp > FAB_SIZE_MAX) {
            fabSizeDp = FAB_SIZE_DEFAULT;
            prefs().edit().putInt(lk(KEY_FAB_SIZE), fabSizeDp).apply();
        }
        applyFabSize();
        setupUnfreezeFab();
        panelDismissAfterFreeze = prefs().getBoolean(lk(KEY_PANEL_DISMISS), false);
        panelHideRecents = prefs().getBoolean(lk(KEY_PANEL_HIDE_RECENTS), false);
        if (panelMode) {
            panelHandleEnabled = prefs().getBoolean(KEY_PANEL_HANDLE, true);
            setupPanel();
            applyPanelSurface();
            applyFabSurface();
            applySearchSurface();
            applyPanelFabPosition();
        } else {
            applyFullscreenSurface();
            applyFullscreenFabSurface();
            applyFullscreenTextColors();
            fabGroup.post(this::applyFabPosition);
            // 监听父容器尺寸变化（而不是按钮组自身），避免设置边距时自触发死循环
            ((View) fabGroup.getParent()).addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or, ob) -> applyFabPosition());
        }
        loadTabs();
        setSysHintVisible();
        renderTabBar();

        if (search != null) {
            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    render();
                }
            });
        }

        // 一键冻结/解冻不做前置确认，直接执行
        freezeBtn.setOnClickListener(v -> runBatch(true, filtered()));
        if (unfreezeBtn != null) {
            unfreezeBtn.setOnClickListener(v -> runBatch(false, filtered()));
        }

        pendingAction = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ACTION);
        // 首启迁移提示：等第一轮名单读完再判断，避免误判为空
        main.postDelayed(this::maybeOfferMigration, 900);

        if (panelMode) {
            // 状态栏黑条在这里一次挂好（高度一次算准），之后只改 alpha，不触发窗口/inset 重算
            ensureIntroStatusView();
            if (savedInstanceState == null && consumeIntroRequest()) {
                playIntro();
            }
        }

        // 打开 app 先检查一次权限：root / Shizuku 都没有时弹统一提示框
        if (savedInstanceState == null && consumeCheckRequest()) {
            checkPrivilegeOnStart();
        }
    }

    /** 半屏浮层形态：圆角面板默认占屏幕约七成，拖把手可改高度，上方透出桌面。 */
    private void setupPanel() {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int handleH = panelHandleHeightPx();
        // 高度一律按「内容区」计算，把手那一截单独占位；上下限也只约束内容区
        panelMinHpx = Ui.dp(this, 300);
        panelMaxHpx = Math.max(panelMinHpx, Math.round(screenH * 0.94f) - handleH);
        int savedDp = prefs().getInt(KEY_PANEL_HEIGHT, 0);
        int content = savedDp > 0 ? Ui.dp(this, savedDp)
                : Ui.dp(this, PANEL_CONTENT_DEFAULT_DP);
        panelContentPx = Math.max(panelMinHpx, Math.min(panelMaxHpx, content));
        View scrim = findViewById(R.id.panel_scrim);
        if (scrim != null) {
            scrim.setOnClickListener(v -> closeWithIntro(this::finish));
        }
        View handle = findViewById(R.id.panel_handle);
        if (handle != null) {
            applyPanelHandleState();
        }
        // 高版本叠加窗口模糊，让上方桌面呈现磨砂效果；不支持时仅保留半透明遮罩
        applyBackdropStrength();
    }

    /**
     * 半屏上方非面板区域的遮罩：0% 近乎全透明（几乎不糊），100% 最模糊。
     * 只动遮罩本身，不改面板与背景窗口之外的任何东西。
     */
    private void applyBackdropStrength() {
        if (!panelMode) {
            return;
        }
        int pct = Math.max(0, Math.min(100, prefs().getInt(lk(KEY_BLUR), 25)));
        View scrim = findViewById(R.id.panel_scrim);
        if (scrim != null) {
            // 原遮罩 #33000000：按比例缩放到 0（全透明）→ 原值
            int alpha = 0x33 * pct / 100;
            scrim.setBackgroundColor(alpha << 24);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                android.view.Window w = getWindow();
                android.view.WindowManager.LayoutParams attrs = w.getAttributes();
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                attrs = w.getAttributes();
                int maxRadius = Ui.dp(this, 28);
                attrs.setBlurBehindRadius(Math.round(maxRadius * pct / 100f));
                w.setAttributes(attrs);
            } catch (Exception ignored) {
            }
        }
    }

    /** 半屏面板本体：只动面板自己的背景透明度，不碰上方遮罩的深浅与窗口模糊。 */
    private void applyPanelSurface() {
        applySurface(findViewById(R.id.panel),
                prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID));
    }

    /** 底部雪花按钮底板：与面板同一套档位，值单独记忆；点按/长按行为不变。 */
    private void applyFabSurface() {
        int pct = prefs().getInt(lk(KEY_FAB_SURFACE), SURFACE_SOLID);
        applySurface(fabGroup, pct);
        applySurface(unfreezeFab, pct);
    }

    /** 搜索框颜色当前档位：全屏默认 30%，半屏默认 100%；已存过值的用户沿用自己的值。 */
    private int searchPct() {
        int def = panelMode ? SURFACE_SOLID : 30;
        return Math.max(0, Math.min(100,
                prefs().getInt(lk(KEY_SEARCH_SURFACE), def)));
    }

    /** 按百分比缩放颜色自身的 alpha，保留色相。 */
    private static int scaleAlpha(int color, int pct) {
        int a = Math.round(android.graphics.Color.alpha(color) * pct / 100f);
        return android.graphics.Color.argb(a,
                android.graphics.Color.red(color),
                android.graphics.Color.green(color),
                android.graphics.Color.blue(color));
    }

    /** 搜索框：填充与描边一起跟「搜索框颜色」档位缩放，和背景档位、选中框都互不影响。 */
    private void applySearchSurface() {
        if (search == null) {
            return;
        }
        int pct = searchPct();
        int radius = Ui.dp(this, 16);
        int fill = scaleAlpha(getColor(R.color.accent_surface_bg), pct);

        android.graphics.drawable.GradientDrawable normal =
                new android.graphics.drawable.GradientDrawable();
        normal.setCornerRadius(radius);
        normal.setColor(fill);
        normal.setStroke(Ui.dp(this, 1),
                scaleAlpha(getColor(R.color.accent_surface_stroke_soft), pct));

        android.graphics.drawable.GradientDrawable focused =
                new android.graphics.drawable.GradientDrawable();
        focused.setCornerRadius(radius);
        focused.setColor(fill);
        focused.setStroke(Ui.dp(this, 2),
                scaleAlpha(getColor(R.color.accent_surface_stroke), pct));

        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[0], normal);
        search.setBackground(sld);
    }

    /**
     * 全屏背景：0 = 整页透明（背后页面看得清）、50 = 磨砂、100 = 实心。
     * 与半屏同一套档位语义，但键独立记忆；窗口模糊与状态栏/导航栏底色跟着同一档位走，
     * 默认 100% 时窗口、状态栏仍是原来的 page_bg，观感不变。
     */
    private void applyFullscreenSurface() {
        if (panelMode) {
            return;
        }
        int pct = Math.max(0, Math.min(100,
                prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID)));
        applyFullscreenSurfaces(pct);
        android.view.Window w = getWindow();
        if (w == null) {
            return;
        }
        int base = getColor(R.color.page_bg);
        int alpha = Math.round(255f * pct / 100f);
        int argb = android.graphics.Color.argb(alpha,
                android.graphics.Color.red(base),
                android.graphics.Color.green(base),
                android.graphics.Color.blue(base));
        // 内容区被 inset 让出状态栏/导航栏，这两条也要跟着档位淡出，否则会留一条实心底色
        w.setStatusBarColor(argb);
        w.setNavigationBarColor(argb);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                android.view.WindowManager.LayoutParams attrs = w.getAttributes();
                int maxRadius = Ui.dp(this, 28);
                attrs.setBlurBehindRadius(Math.round(maxRadius * pct / 100f));
                w.setAttributes(attrs);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 全屏「同一档位」铺到所有可见底板：页面背景、顶栏、搜索框、用户/系统标签胶囊、
     * 以及移动模式下的选中高亮框。列表每次重绘后要再调一次，避免新建的视图漏掉。
     */
    private void applyFullscreenSurfaces(int pct) {
        applySurface(findViewById(R.id.page_root), pct);
        applySurface(findViewById(R.id.page_header), pct);
        if (tabBar != null) {
            for (int i = 0; i < tabBar.getChildCount(); i++) {
                applySurface(tabBar.getChildAt(i), pct);
            }
        }
        if (grid != null) {
            for (int r = 0; r < grid.getChildCount(); r++) {
                View row = grid.getChildAt(r);
                if (!(row instanceof ViewGroup)) {
                    continue;
                }
                ViewGroup cellsOfRow = (ViewGroup) row;
                for (int c = 0; c < cellsOfRow.getChildCount(); c++) {
                    applySurface(cellsOfRow.getChildAt(c), pct);
                }
            }
        }
        // 搜索框颜色归自己的档位单独管，上面这轮铺完再盖回自己的 alpha
        applySearchSurface();
    }

    /** 全屏里原来偏灰的入口/提示文字统一压成同一个深色（深色模式用高对比浅色）。 */
    private void applyFullscreenTextColors() {
        if (panelMode) {
            return;
        }
        int color = getColor(R.color.fs_text_muted);
        int[] ids = {R.id.layout, R.id.backup, R.id.restore, R.id.sys_hint, R.id.count};
        for (int id : ids) {
            View v = findViewById(id);
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(color);
            }
        }
        if (search != null) {
            search.setHintTextColor(color);
        }
    }

    /** 列表/标签重绘后补一遍：新加的胶囊、格子、高亮框也要跟着当前档位。 */
    private void reapplyFullscreenSurfaces() {
        if (panelMode) {
            return;
        }
        applyFullscreenSurfaces(Math.max(0, Math.min(100,
                prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID))));
    }

    /** 全屏里的雪花按钮底板：同一套档位，单独记忆。 */
    private void applyFullscreenFabSurface() {
        if (panelMode) {
            return;
        }
        int pct = Math.max(0, Math.min(100,
                prefs().getInt(KEY_FULL_FAB_SURFACE, SURFACE_SOLID)));
        applySurface(fabGroup, pct);
        applySurface(unfreezeFab, pct);
    }

    /**
     * 档位：0 = 完全透明（背后看得清）、50 = 磨砂（透出背后并压暗）、100 = 完全不透明（实心）。
     * 只改视图自身背景的 alpha：窗口背景模糊与遮罩颜色都不动，所以半屏外那层不受影响。
     */
    private void applySurface(View v, int pct) {
        if (v == null) {
            return;
        }
        int p = Math.max(0, Math.min(100, pct));
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg != null) {
            bg.mutate().setAlpha(Math.round(255f * p / 100f));
        }
    }

    /** 把手占位高度（含顶部外边距）：GONE 时也能算准，不依赖当前测量结果。 */
    private int panelHandleHeightPx() {
        View handle = findViewById(R.id.panel_handle);
        if (handle == null) {
            return 0;
        }
        ViewGroup.LayoutParams lp = handle.getLayoutParams();
        int own = lp != null && lp.height > 0 ? lp.height : Ui.dp(this, 22);
        int margins = 0;
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            margins = m.topMargin + m.bottomMargin;
        }
        return own + margins;
    }

    /**
     * 开关切换后立即生效：把手显隐只影响总高，内容高始终用记住的 panelContentPx，
     * 所以关掉时总高 = 内容高，重新打开时总高 = 内容高 + 把手高，不会跳。
     */
    private void applyPanelHandleState() {
        View handle = findViewById(R.id.panel_handle);
        View panel = findViewById(R.id.panel);
        if (handle == null) {
            return;
        }
        if (panelHandleEnabled) {
            handle.setVisibility(View.VISIBLE);
            handle.setOnTouchListener(new PanelResizeListener());
        } else {
            // 先摘监听再隐藏：正在拖动时后续事件不再改高度
            handle.setOnTouchListener(null);
            handle.setVisibility(View.GONE);
        }
        if (panel != null) {
            setPanelHeight(panel, panelContentPx
                    + (panelHandleEnabled ? panelHandleHeightPx() : 0));
        }
    }

    /** 拖把手实时改内容高度；继续往下拉过下限再松手就关闭回桌面。 */
    private final class PanelResizeListener implements View.OnTouchListener {
        private float downY;
        private int startContent;
        private boolean closing;

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            if (!panelHandleEnabled) {
                return false;
            }
            View panel = findViewById(R.id.panel);
            if (panel == null) {
                return false;
            }
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downY = e.getRawY();
                    // 起手只记内容区高度，拖动过程与落盘都不含把手
                    startContent = panelContentPx;
                    closing = false;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    int raw = startContent - Math.round(e.getRawY() - downY);
                    panelContentPx = Math.max(panelMinHpx, Math.min(panelMaxHpx, raw));
                    setPanelHeight(panel, panelContentPx + panelHandleHeightPx());
                    closing = raw < panelMinHpx - Ui.dp(FreezeListActivity.this, 40);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (closing) {
                        finish();
                        overridePendingTransition(0, 0);
                    } else {
                        // 只落盘内容高度，和布局设置那套参数互不覆盖
                        int dp = Math.round(panelContentPx
                                / getResources().getDisplayMetrics().density);
                        prefs().edit().putInt(KEY_PANEL_HEIGHT, dp).apply();
                    }
                    closing = false;
                    return true;
                }
                default:
                    return false;
            }
        }
    }

    private void setPanelHeight(View panel, int h) {
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp.height == h) {
            return;
        }
        lp.height = h;
        panel.setLayoutParams(lp);
    }

    /** 勾选「半屏高度调节」后：让顶部把手呼吸两次，提示可以拖动改高度。 */
    private void flashPanelHandle() {
        View handle = findViewById(R.id.panel_handle);
        if (handle == null || handle.getVisibility() != View.VISIBLE) {
            return;
        }
        handle.animate().cancel();
        handle.setAlpha(1f);
        android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofFloat(
                handle, View.ALPHA, 1f, 0.2f, 1f, 0.2f, 1f);
        anim.setDuration(1100);
        anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        anim.start();
    }

    /** 半屏形态由子类打开；全屏入口保持 false。 */
    protected boolean isPanelMode() {
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask 复用时窗口不重建：这里补播过渡，保证第 N 次拉起和第 1 次是同一条节奏
        if (panelMode && intent != null && intent.getBooleanExtra(EXTRA_PLAY_INTRO, false)) {
            intent.removeExtra(EXTRA_PLAY_INTRO);
            android.util.Log.d(TAG_INTRO, "onNewIntent replay");
            playIntro();
        }
        // 再次点桌面图标也算一次「打开 app」：复用同一套启动检查
        if (intent != null && intent.getBooleanExtra(EXTRA_CHECK_PRIV, false)) {
            intent.removeExtra(EXTRA_CHECK_PRIV);
            checkPrivilegeOnStart();
        }
    }

    /** 入口带的一次性标记：消费掉，避免重建时重复播放。 */
    private boolean consumeIntroRequest() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA_PLAY_INTRO, false)) {
            return false;
        }
        intent.removeExtra(EXTRA_PLAY_INTRO);
        return true;
    }

    /**
     * 拉起过渡的统一入口。
     * 关键：动画时钟对齐到窗口的首帧绘制，而不是 onCreate/onNewIntent 的调用时刻。
     * 冷启动时两者能差几百毫秒，若在 onCreate 就起跑，窗口真正显示时动画可能已经跑到一半甚至结束，
     * 于是「第一次慢、之后一开就到位」的差异就出来了。对齐首帧后每次看到的都是完整一轮。
     */
    private void playIntro() {
        if (!panelMode) {
            return;
        }
        if (introScrimView == null) {
            introScrimView = findViewById(R.id.panel_intro_scrim);
        }
        if (introScrimView == null) {
            return;
        }
        if (introStatusView == null) {
            ensureIntroStatusView();
        }
        if (introStartPosted) {
            return;
        }
        introStartPosted = true;
        final View decor = getWindow().getDecorView();
        final android.view.ViewTreeObserver.OnPreDrawListener listener =
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        removePreDraw(decor, this);
                        startIntroNow("pre-draw");
                        return true;
                    }
                };
        decor.getViewTreeObserver().addOnPreDrawListener(listener);
        // 窗口没有新的绘制（例如已在最前）时兜底起跑，不会一直等
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!introStartPosted) {
                    return;
                }
                removePreDraw(decor, listener);
                startIntroNow("fallback");
            }
        }, 260);
    }

    private static void removePreDraw(View decor,
                                      android.view.ViewTreeObserver.OnPreDrawListener listener) {
        android.view.ViewTreeObserver vto = decor.getViewTreeObserver();
        if (vto.isAlive()) {
            vto.removeOnPreDrawListener(listener);
        }
    }

    /**
     * 真正起跑：整屏由透明慢慢压暗 → 停留 → 慢慢淡出。
     * 只在没有过渡在跑时起跑；已经在跑就让它自然走完，不重启、不叠加。
     */
    private void startIntroNow(String reason) {
        introStartPosted = false;
        if (!panelMode || introScrimView == null) {
            return;
        }
        if (introPlaying) {
            android.util.Log.d(TAG_INTRO, "start skipped: already playing");
            return;
        }
        final int run = ++introRun;
        introPlaying = true;
        introScrimView.animate().cancel();
        if (introStatusView != null) {
            introStatusView.animate().cancel();
        }
        main.removeCallbacks(introReveal);
        main.removeCallbacks(introSafety);
        introScrimView.setVisibility(View.VISIBLE);
        introScrimView.setAlpha(0f);
        introScrimView.animate().alpha(1f)
                .setDuration(INTRO_IN_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setUpdateListener(a -> syncIntroIcons(introScrimView.getAlpha()))
                .start();
        if (introStatusView != null) {
            introStatusView.setVisibility(View.VISIBLE);
            introStatusView.setAlpha(0f);
            introStatusView.animate().alpha(1f)
                    .setDuration(INTRO_IN_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        android.util.Log.d(TAG_INTRO, "start run=" + run + " " + reason);
        main.postDelayed(introReveal, INTRO_IN_MS + INTRO_HOLD_MS);
        main.postDelayed(introSafety, INTRO_IN_MS + INTRO_HOLD_MS + INTRO_OUT_MS + 400);
    }

    /** 兜底：动画回调万一没落地（窗口/监听时序），也把状态清干净，避免之后每轮都被跳过。 */
    private void guardIntroStuck() {
        if (!introPlaying) {
            return;
        }
        android.util.Log.d(TAG_INTRO, "safety run=" + introRun);
        if (introScrimView != null && introScrimView.getVisibility() == View.VISIBLE
                && introScrimView.getAlpha() > 0.01f) {
            startIntroReveal();
            return;
        }
        finishIntro(introRun);
    }

    /** 停留结束：遮罩按同一条慢节奏淡出，露出背后模糊桌面，不再有第二个闪白/闪黑的中间态。 */
    private void startIntroReveal() {
        if (!introPlaying || introScrimView == null) {
            return;
        }
        final int run = introRun;
        android.util.Log.d(TAG_INTRO, "reveal run=" + run);
        introScrimView.animate().alpha(0f)
                .setDuration(INTRO_OUT_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setUpdateListener(a -> syncIntroIcons(introScrimView.getAlpha()))
                .withEndAction(() -> finishIntro(run))
                .start();
        if (introStatusView != null) {
            introStatusView.animate().alpha(0f)
                    .setDuration(INTRO_OUT_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void finishIntro(int run) {
        if (run != introRun) {
            return;
        }
        introPlaying = false;
        if (introScrimView != null) {
            introScrimView.setVisibility(View.GONE);
        }
        // 状态栏那条黑留在原位（半屏显示期间一直压黑，图标才始终看得清）
        applyPanelStatusIcons();
        android.util.Log.d(TAG_INTRO, "finish run=" + run);
    }

    /**
     * 收起也走同一条节奏：先把半屏外慢慢压暗，压满再结束，避免「一关就闪没」。
     * 遮罩已全黑时按最短 120ms 收尾，不会额外拖慢。
     */
    private void closeWithIntro(Runnable finishAction) {
        if (!panelMode) {
            finishAction.run();
            return;
        }
        if (introScrimView == null) {
            introScrimView = findViewById(R.id.panel_intro_scrim);
        }
        if (introScrimView == null) {
            finishAction.run();
            return;
        }
        if (introStatusView == null) {
            ensureIntroStatusView();
        }
        final int run = ++introRun;
        introPlaying = true;
        main.removeCallbacks(introReveal);
        float from = introScrimView.getVisibility() == View.VISIBLE
                ? Math.min(1f, Math.max(0f, introScrimView.getAlpha())) : 0f;
        introScrimView.animate().cancel();
        if (introStatusView != null) {
            introStatusView.animate().cancel();
        }
        int ms = Math.max(120, Math.round(INTRO_IN_MS * (1f - from)));
        introScrimView.setVisibility(View.VISIBLE);
        introScrimView.setAlpha(from);
        introScrimView.animate().alpha(1f)
                .setDuration(ms)
                .setInterpolator(new DecelerateInterpolator())
                .setUpdateListener(a -> syncIntroIcons(introScrimView.getAlpha()))
                .withEndAction(() -> {
                    if (run == introRun) {
                        finishAction.run();
                    }
                })
                .start();
        if (introStatusView != null) {
            introStatusView.setVisibility(View.VISIBLE);
            introStatusView.setAlpha(from);
            introStatusView.animate().alpha(1f)
                    .setDuration(ms)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /**
     * 状态栏那一条黑：一次算好高度挂到 DecorView 顶部。
     * 不用 setStatusBarColor —— 那会触发窗口 inset 重算，半屏会「跳一下」。
     */
    private void ensureIntroStatusView() {
        if (introStatusView != null) {
            return;
        }
        int top = introStatusInsetPx();
        if (top <= 0) {
            return;
        }
        View v = new View(this);
        v.setBackgroundColor(getResources().getColor(R.color.launch_scrim));
        v.setAlpha(0f);
        v.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, top, Gravity.TOP);
        ((ViewGroup) getWindow().getDecorView()).addView(v, lp);
        introStatusView = v;
    }

    /** 状态栏（含挖孔）顶边高度：显示前同步取窗口 metrics，取不到再退回系统资源与兜底值。 */
    private int introStatusInsetPx() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                android.view.WindowInsets ins = getWindowManager()
                        .getCurrentWindowMetrics().getWindowInsets();
                int top = ins.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.displayCutout()).top;
                if (top > 0) {
                    return top;
                }
            } catch (Throwable ignored) {
                // 个别 ROM 在建窗阶段取不到，退回资源值
            }
        }
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return getResources().getDimensionPixelSize(id);
        }
        return Ui.dp(this, 24);
    }

    /** 黑条过半才把状态栏图标切成白色，淡出过一半再切回跟随背景，保证任何时刻都看得清。 */
    private void syncIntroIcons(float blackAlpha) {
        boolean forceWhite = blackAlpha > 0.45f;
        if (forceWhite == introIconsLight) {
            return;
        }
        introIconsLight = forceWhite;
        if (forceWhite) {
            statusIconsController().setAppearanceLightStatusBars(false);
            return;
        }
        applyPanelStatusIcons();
    }

    /**
     * 半屏显示期间状态栏压在桌面/壁纸上，按壁纸主色的亮度决定图标深浅：
     * 深色背景用白图标，浅色背景用深色图标。
     */
    private void applyPanelStatusIcons() {
        introIconsLight = false;
        statusIconsController().setAppearanceLightStatusBars(!darkBackdrop());
    }

    /** 结束过渡 / 离开页面都把状态栏恢复成跟随深浅色，不残留强制白色。 */
    private void restoreStatusBarIcons() {
        introIconsLight = false;
        statusIconsController().setAppearanceLightStatusBars(!nightMode());
    }

    private WindowInsetsControllerCompat statusIconsController() {
        return WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    }

    private boolean nightMode() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 半屏背后是否为深色背景：优先用系统给出的壁纸主色亮度，取不到再退回深色模式。 */
    private boolean darkBackdrop() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            try {
                android.app.WallpaperColors colors = android.app.WallpaperManager
                        .getInstance(this)
                        .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                if (colors != null && colors.getPrimaryColor() != null) {
                    int c = colors.getPrimaryColor().toArgb();
                    double lum = (0.299 * android.graphics.Color.red(c)
                            + 0.587 * android.graphics.Color.green(c)
                            + 0.114 * android.graphics.Color.blue(c)) / 255.0;
                    return lum < 0.55;
                }
            } catch (Throwable ignored) {
                // 部分系统不给壁纸颜色，退回深浅色判断
            }
        }
        return nightMode();
    }

    @Override
    protected boolean isBarIconAppearanceForced() {
        return introPlaying;
    }

    private void openPicker() {
        // 勾选「隐藏半屏后台」时，面板一暂停就会被移除；选应用期间先豁免
        pickerOpen = true;
        Intent i = new Intent(this, AppFreezeActivity.class);
        i.putExtra(AppFreezeActivity.EXTRA_PICK, true);
        picker.launch(i);
    }

    /** 半屏浮层底栏的设置图标直接进布局设置；备份/恢复收进布局弹窗底部，避免功能丢失。 */

    private void setSysHintVisible() {
        if (sysHint != null) {
            sysHint.setVisibility(TAB_SYSTEM.equals(currentTabId) ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 列表区域左右滑动切标签。
     * 移动模式优先级最高：此时横向手势一律不接管，全部让给选中 / 拖动换位。
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (!tabSwipeEnabled || moveMode || editMode || working) {
            return super.dispatchTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                swipeTracking = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (!swipeTracking) {
                    float dx = ev.getX() - swipeDownX;
                    float dy = ev.getY() - swipeDownY;
                    // 明确横向意图（18dp 起判、横向占优）才接管，纵向滚动照常
                    if (Math.abs(dx) > Ui.dp(this, 18)
                            && Math.abs(dx) > Math.abs(dy) * 1.5f
                            && inListViewArea(swipeDownX, swipeDownY)) {
                        swipeTracking = true;
                    }
                }
                if (swipeTracking) {
                    return true;
                }
                break;
            case android.view.MotionEvent.ACTION_UP:
                if (swipeTracking) {
                    float dx = ev.getX() - swipeDownX;
                    if (Math.abs(dx) > Ui.dp(this, 56)) {
                        switchTabBySwipe(dx < 0 ? 1 : -1);
                    }
                    swipeTracking = false;
                    return true;
                }
                swipeTracking = false;
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                swipeTracking = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 触点是否落在列表滚动区域内，且不在悬浮按钮上。 */
    private boolean inListViewArea(float rawX, float rawY) {
        if (scrollView == null || scrollView.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        scrollView.getLocationOnScreen(loc);
        if (rawX < loc[0] || rawX > loc[0] + scrollView.getWidth()
                || rawY < loc[1] || rawY > loc[1] + scrollView.getHeight()) {
            return false;
        }
        return !inView(fabGroup, rawX, rawY) && !inView(unfreezeFab, rawX, rawY);
    }

    private boolean inView(View v, float rawX, float rawY) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
    }

    /** 按当前标签排列顺序切到上一条 / 下一条可见标签（dir=-1 上一条，1 下一条）。 */
    private void switchTabBySwipe(int dir) {
        List<Tab> visible = new ArrayList<>();
        for (Tab t : tabs) {
            if (t.visible) {
                visible.add(t);
            }
        }
        int index = -1;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).id.equals(currentTabId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        int next = index + dir;
        if (next < 0 || next >= visible.size()) {
            return;
        }
        selectTab(visible.get(next).id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到页面都重新读一次系统真值
        reload();
        // 半屏显示期间状态栏压在桌面上，按壁纸明暗决定图标深浅（过渡中不动）
        if (panelMode && !introPlaying) {
            applyPanelStatusIcons();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面就把状态栏图标恢复成跟随深浅色，不残留过渡时的强制白色
        if (panelMode) {
            restoreStatusBarIcons();
        }
        // 勾选「隐藏后台」时，离开列表就把自身任务从最近任务摘掉；全屏与半屏都生效。
        // 用 finishAndRemoveTask 而不是 finish()，避免只关页面留下空任务卡片。
        if (panelHideRecents && !pickerOpen) {
            finishAndRemoveTask();
        }
    }

    /**
     * 半屏的侧边返回：直接结束并把自身任务一起移除，不再留在后台/最近任务里。
     * 全屏冻结列表仍走默认返回。
     */
    @Override
    public void onBackPressed() {
        if (panelMode) {
            android.util.Log.d("FW_GESTURE", "onBackPressed -> remove task");
            closeWithIntro(() -> {
                finishAndRemoveTask();
                overridePendingTransition(0, 0);
            });
            return;
        }
        super.onBackPressed();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /**
     * 布局参数按承载形态各存一份：半屏浮层加 panel_ 前缀。
     * 名单、标签归属、排序、冻结状态仍然共用同一套 key。
     */
    private String lk(String base) {
        return panelMode ? "panel_" + base : base;
    }

    /** 第一次进半屏浮层时把当前全屏布局抄一份作为初始值，之后两边各自独立。 */
    private void ensurePanelLayoutInit() {
        if (!panelMode || prefs().getBoolean("panel_layout_init", false)) {
            return;
        }
        prefs().edit()
                .putInt("panel_" + KEY_COLUMNS, prefs().getInt(KEY_COLUMNS, COLUMNS))
                .putInt("panel_" + KEY_ICON_DP, prefs().getInt(KEY_ICON_DP, 52))
                .putBoolean("panel_" + KEY_ICON_MANUAL, prefs().getBoolean(KEY_ICON_MANUAL, false))
                .putBoolean("panel_" + KEY_SHOW_NAME, prefs().getBoolean(KEY_SHOW_NAME, true))
                .putBoolean("panel_" + KEY_MOVE_MODE, prefs().getBoolean(KEY_MOVE_MODE, false))
                .putBoolean("panel_" + KEY_SHOW_FROZEN_TIP,
                        prefs().getBoolean(KEY_SHOW_FROZEN_TIP, false))
                .putBoolean("panel_" + KEY_PANEL_HIDE_RECENTS,
                        prefs().getBoolean(KEY_PANEL_HIDE_RECENTS, false))
                .putBoolean("panel_" + KEY_PANEL_DISMISS,
                        prefs().getBoolean(KEY_PANEL_DISMISS, false))
                .putInt("panel_" + KEY_FAB_DX, prefs().getInt(KEY_FAB_DX, 0))
                .putInt("panel_" + KEY_FAB_DY, prefs().getInt(KEY_FAB_DY, 0))
                .putInt("panel_" + KEY_FAB_SIZE, prefs().getInt(KEY_FAB_SIZE, FAB_SIZE_DEFAULT))
                .putBoolean("panel_layout_init", true)
                .apply();
    }

    private Set<String> pkgs() {
        return new HashSet<>(prefs().getStringSet(KEY_PKGS, new HashSet<>()));
    }

    private boolean customOrder() {
        return prefs().getBoolean(KEY_CUSTOM, false);
    }

    private List<String> savedOrder() {
        String raw = prefs().getString(KEY_ORDER, "");
        List<String> list = new ArrayList<>();
        if (raw.isEmpty()) {
            return list;
        }
        for (String s : raw.split("\n")) {
            if (!s.trim().isEmpty()) {
                list.add(s.trim());
            }
        }
        return list;
    }

    // ---------------- 标签 ----------------

    private Tab findTab(String id) {
        if (id == null) {
            return null;
        }
        for (Tab t : tabs) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    private int visibleTabCount() {
        int n = 0;
        for (Tab t : tabs) {
            if (t.visible) {
                n++;
            }
        }
        return n;
    }

    private String firstVisibleTabId() {
        for (Tab t : tabs) {
            if (t.visible) {
                return t.id;
            }
        }
        return TAB_USER;
    }

    private void loadTabs() {
        tabs.clear();
        String raw = prefs().getString(KEY_TABS, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Tab t = new Tab();
                    t.id = o.optString("id", "");
                    t.name = o.optString("name", "");
                    t.visible = o.optBoolean("visible", true);
                    if (t.id.isEmpty() || t.name.isEmpty() || findTab(t.id) != null) {
                        continue;
                    }
                    // 内置标签只认固定 id，其余一律按自定义处理
                    t.custom = !TAB_USER.equals(t.id) && !TAB_SYSTEM.equals(t.id);
                    tabs.add(t);
                }
            } catch (Exception ignored) {
            }
        }
        // 内置标签必须在且排在最前；旧版本没有标签数据时也靠这里补齐
        if (findTab(TAB_USER) == null) {
            Tab t = new Tab();
            t.id = TAB_USER;
            t.name = "用户应用";
            tabs.add(0, t);
        }
        if (findTab(TAB_SYSTEM) == null) {
            Tab t = new Tab();
            t.id = TAB_SYSTEM;
            t.name = "系统应用";
            tabs.add(Math.min(1, tabs.size()), t);
        }
        loadAppTabs();
        currentTabId = prefs().getString(KEY_CURRENT_TAB, TAB_USER);
        Tab cur = findTab(currentTabId);
        if (cur == null || !cur.visible) {
            currentTabId = firstVisibleTabId();
        }
    }

    private void saveTabs() {
        JSONArray arr = new JSONArray();
        for (Tab t : tabs) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", t.id);
                o.put("name", t.name);
                o.put("visible", t.visible);
                o.put("custom", t.custom);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        prefs().edit().putString(KEY_TABS, arr.toString()).apply();
    }

    private void loadAppTabs() {
        appTabs.clear();
        String raw = prefs().getString(KEY_APP_TABS, "");
        if (raw.isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray names = o.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                String pkg = names.optString(i);
                String tab = o.optString(pkg, "");
                // 标签已被删掉时不做映射，tabIdOf 会按 FLAG_SYSTEM 落回内置标签
                if (!pkg.isEmpty() && findTab(tab) != null) {
                    appTabs.put(pkg, tab);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveAppTabs() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : appTabs.entrySet()) {
            try {
                o.put(e.getKey(), e.getValue());
            } catch (Exception ignored) {
            }
        }
        prefs().edit().putString(KEY_APP_TABS, o.toString()).apply();
    }

    /** 应用归属：没有显式映射（或映射到已删标签）时按 FLAG_SYSTEM 落回内置标签。 */
    private String tabIdOf(Item it) {
        String id = appTabs.get(it.pkg);
        if (id != null && findTab(id) != null) {
            return id;
        }
        return it.system ? TAB_SYSTEM : TAB_USER;
    }

    private void renderTabBar() {
        if (tabBar == null) {
            return;
        }
        tabBar.removeAllViews();
        boolean firstPanelTab = true;
        for (Tab t : tabs) {
            if (!t.visible) {
                continue;
            }
            boolean on = t.id.equals(currentTabId);
            if (panelMode) {
                // 半屏形态：文字 + 下划线指示当前标签
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView label = new TextView(this);
                label.setText(t.name);
                label.setTextSize(14);
                label.setSingleLine(true);
                label.setGravity(Gravity.CENTER);
                label.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8));
                label.setTextColor(on ? getColor(R.color.text_primary)
                        : getColor(R.color.text_secondary));
                label.setTypeface(null, on ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
                item.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                View underline = new View(this);
                underline.setBackgroundColor(getColor(R.color.blue));
                underline.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
                LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2));
                ulp.leftMargin = Ui.dp(this, 4);
                ulp.rightMargin = Ui.dp(this, 4);
                item.addView(underline, ulp);
                item.setOnClickListener(v -> selectTab(t.id));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                // 只在非首个标签上加左间距，避免末尾多出一截导致整体看起来偏左
                if (!firstPanelTab) {
                    lp.leftMargin = Ui.dp(this, 18);
                }
                firstPanelTab = false;
                tabBar.addView(item, lp);
                continue;
            }
            TextView chip = new TextView(this);
            chip.setText(t.name);
            chip.setTextSize(13);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 16), Ui.dp(this, 7), Ui.dp(this, 16), Ui.dp(this, 7));
            chip.setBackgroundResource(on ? R.drawable.bg_btn_blue : R.drawable.bg_btn_white);
            chip.setTextColor(on ? 0xFFFFFFFF : getColor(R.color.blue));
            chip.setTypeface(null, on ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            chip.setOnClickListener(v -> selectTab(t.id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            tabBar.addView(chip, lp);
        }
        reapplyFullscreenSurfaces();
    }

    private void selectTab(String id) {
        Tab t = findTab(id);
        if (t == null || !t.visible) {
            return;
        }
        currentTabId = id;
        prefs().edit().putString(KEY_CURRENT_TAB, id).apply();
        setSysHintVisible();
        renderTabBar();
        render();
        if (scrollView != null) {
            scrollView.scrollTo(0, 0);
        }
    }

    /** 新建 / 重命名标签；名称不能为空也不能与其它标签重名。 */
    private void showTabNameDialog(Tab tab, Runnable done) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setHint("标签名称");
        if (tab != null) {
            input.setText(tab.name);
            input.setSelection(input.getText().length());
        }
        int pad = Ui.dp(this, 18);
        input.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(tab == null ? "新建标签" : "重命名标签")
                .setView(input)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "标签名称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            for (Tab t : tabs) {
                if (t != tab && t.name.equals(name)) {
                    Toast.makeText(this, "已有同名标签", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (tab == null) {
                Tab t = new Tab();
                t.id = "tab_" + System.currentTimeMillis();
                t.name = name;
                t.custom = true;
                tabs.add(t);
            } else {
                tab.name = name;
            }
            saveTabs();
            renderTabBar();
            if (done != null) {
                done.run();
            }
            d.dismiss();
        });
    }

    /** 删除自定义标签：其应用清掉归属映射，按 FLAG_SYSTEM 回到内置标签，不丢数据。 */
    private void confirmDeleteTab(Tab tab, Runnable done) {
        int count = 0;
        for (Item it : items) {
            if (tab.id.equals(tabIdOf(it))) {
                count++;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("删除标签「" + tab.name + "」")
                .setMessage("其中的 " + count + " 个应用会回到原来的「用户应用 / 系统应用」，不会丢失。")
                .setPositiveButton("删除", (d, w) -> {
                    List<String> back = new ArrayList<>();
                    for (Map.Entry<String, String> e : appTabs.entrySet()) {
                        if (tab.id.equals(e.getValue())) {
                            back.add(e.getKey());
                        }
                    }
                    for (String pkg : back) {
                        appTabs.remove(pkg);
                    }
                    saveAppTabs();
                    tabs.remove(tab);
                    // 只剩被删标签可见时，兜底把「用户应用」显示出来
                    if (visibleTabCount() == 0) {
                        Tab user = findTab(TAB_USER);
                        if (user != null) {
                            user.visible = true;
                        }
                    }
                    if (tab.id.equals(currentTabId)) {
                        currentTabId = firstVisibleTabId();
                        prefs().edit().putString(KEY_CURRENT_TAB, currentTabId).apply();
                    }
                    saveTabs();
                    setSysHintVisible();
                    renderTabBar();
                    render();
                    if (done != null) {
                        done.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setEditMode(boolean on) {
        editMode = on;
        // 顶栏减号作为移除模式开关，激活时高亮
        editBtn.setBackgroundResource(on ? R.drawable.bg_minus_active : 0);
        editBtn.setImageTintList(android.content.res.ColorStateList.valueOf(
                on ? 0xFFFFFFFF : getColor(R.color.text_primary)));
        render();
    }

    private static int byDefault(Item x, Item y) {
        if (x.frozen != y.frozen) {
            return x.frozen ? -1 : 1;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(x.label, y.label);
    }

    protected void reload() {
        Set<String> wanted = pkgs();
        List<String> order = savedOrder();
        boolean custom = customOrder();
        IO.execute(() -> {
            Set<String> disabled = disabledPackages();
            boolean truthOk = disabled != null;
            if (disabled == null) {
                disabled = new HashSet<>();
            }
            PackageManager pm = getPackageManager();
            Map<String, Item> map = new HashMap<>();
            Set<String> kept = new HashSet<>();
            Set<String> removed = new HashSet<>();
            for (String pkg : wanted) {
                Item it = new Item();
                it.pkg = pkg;
                ApplicationInfo ai = null;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    // 已卸载：从名单（及标签归属）里移除
                    removed.add(pkg);
                    continue;
                } catch (Exception ignored) {
                }
                if (ai != null) {
                    it.label = pm.getApplicationLabel(ai).toString();
                    it.icon = ai.loadIcon(pm);
                    it.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                } else {
                    it.label = pkg;
                }
                it.frozen = disabled.contains(pkg);
                // 核心系统应用不允许留在名单里，启动时静默清理
                if (com.jin.freeze.core.CoreProtect.isProtected(this, pkg, it.label)) {
                    continue;
                }
                kept.add(pkg);
                map.put(pkg, it);
            }
            if (!removed.isEmpty()) {
                for (String pkg : removed) {
                    appTabs.remove(pkg);
                }
                saveAppTabs();
            }
            if (!kept.equals(wanted)) {
                prefs().edit().putStringSet(KEY_PKGS, kept).apply();
            }
            List<Item> list = new ArrayList<>();
            if (custom && !order.isEmpty()) {
                // 自定义顺序：按保存的顺序排，后加入的新项按默认规则接在末尾
                for (String pkg : order) {
                    Item it = map.remove(pkg);
                    if (it != null) {
                        list.add(it);
                    }
                }
                List<Item> rest = new ArrayList<>(map.values());
                Collections.sort(rest, FreezeListActivity::byDefault);
                list.addAll(rest);
            } else {
                list.addAll(map.values());
                Collections.sort(list, FreezeListActivity::byDefault);
            }
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                items.clear();
                items.addAll(list);
                loadedOnce = true;
                render();
                // 冻结设置成「后收起」时，等这一轮状态刷新落地再关面板，回到进入前的应用
                if (dismissAfterReload) {
                    dismissAfterReload = false;
                    finishAndRemoveTask();
                    overridePendingTransition(0, 0);
                    return;
                }
                if (pendingAction != null) {
                    String act = pendingAction;
                    pendingAction = null;
                    runBatch(ACTION_FREEZE.equals(act), filtered());
                }
            });
        });
    }

    /** 系统真值：pm list packages -d；读不到返回 null。 */
    private Set<String> disabledPackages() {
        PrivShell.Result r = PrivShell.exec("pm list packages -d --user 0", 30);
        if (r.out == null || !r.out.contains("package:")) {
            return null;
        }
        Set<String> set = new HashSet<>();
        for (String line : r.out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) {
                set.add(t.substring("package:".length()).trim());
            }
        }
        return set;
    }

    /** 当前标签 + 搜索取交集；一键冻结/解冻只作用于这里返回的名单项。 */
    private List<Item> filtered() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Item> shown = new ArrayList<>();
        for (Item it : items) {
            if (!currentTabId.equals(tabIdOf(it))) {
                continue;
            }
            if (!q.isEmpty()
                    && !it.label.toLowerCase(Locale.ROOT).contains(q)
                    && !it.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            shown.add(it);
        }
        // 没有手动拖拽过才按「已冻结在前、组内名称升序」自动排；自定义顺序不受状态变化影响
        if (!customOrder()) {
            Collections.sort(shown, FreezeListActivity::byDefault);
        }
        return shown;
    }

    private void render() {
        if (grid == null) {
            return;
        }
        grid.removeAllViews();
        cells.clear();
        cellOrder.clear();
        List<Item> shown = filtered();
        updateCount(shown);
        if (emptyState != null) {
            emptyState.setVisibility(loadedOnce && shown.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (shown.isEmpty()) {
            reapplyFullscreenSurfaces();
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % gridColumns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.START);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = Ui.dp(this, 12);
                row.setLayoutParams(rlp);
                grid.addView(row);
            }
            row.addView(buildCell(shown.get(i)));
        }
        reapplyFullscreenSurfaces();
    }

    private void updateCount(List<Item> shown) {
        boolean empty = shown.isEmpty();
        if (count != null) {
            if (items.isEmpty()) {
                count.setText("名单为空，点右上角「+」添加应用");
            } else if (empty) {
                count.setText("当前筛选下没有应用");
            } else if (editMode) {
                count.setText("编辑模式：点图标右上角减号移出名单");
            } else {
                count.setText("当前筛选 " + shown.size() + " 个"
                        + (customOrder() ? " · 自定义顺序" : "")
                        + (moveMode ? "，点击选中或长按拖动可换位"
                                    : "，点击打开，长按打开菜单"));
            }
        }
        freezeBtn.setAlpha(empty || working ? 0.4f : 1f);
        freezeBtn.setEnabled(!empty && !working);
        if (unfreezeBtn != null) {
            // 一键解冻图标始终保持纯黑（深色模式走高对比浅色）；名单为空只置灰交互，不再把图标淡成 40%
            unfreezeBtn.setAlpha(1f);
            unfreezeBtn.setEnabled(!empty && !working);
        }
    }

    private View buildCell(Item it) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        // 不再铺白色卡片底：图标直接落在页面背景上，只保留点击水波纹
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 6));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        box.setForeground(getDrawable(tv.resourceId));
        box.setClickable(true);
        box.setLongClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                computedCellPx(), ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);

        FrameLayout iconBox = new FrameLayout(this);
        int iconPx = computedIconPx();
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconBox.setLayoutParams(ibLp);
        ImageView icon = new ImageView(this);
        iconBox.addView(icon, new FrameLayout.LayoutParams(iconPx, iconPx));
        int radius = Math.max(Ui.dp(this, 6), (int) (iconPx * 0.18f));
        android.graphics.drawable.GradientDrawable round =
                new android.graphics.drawable.GradientDrawable();
        round.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        round.setColor(0x00000000);
        round.setCornerRadius(radius);
        icon.setBackground(round);
        icon.setClipToOutline(true);

        TextView badge = new TextView(this);
        badge.setText("−");
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(13);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundResource(R.drawable.bg_remove_badge);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18));
        bLp.gravity = Gravity.TOP | Gravity.END;
        badge.setLayoutParams(bLp);
        iconBox.addView(badge);
        box.addView(iconBox);

        TextView name = Ui.text(this, "", 11, Ui.TEXT_PRIMARY, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Ui.dp(this, 6);
        box.addView(name, nameLp);
        name.setVisibility(showName ? View.VISIBLE : View.GONE);

        final Cell cell = new Cell();
        cell.root = box;
        cell.icon = icon;
        cell.name = name;
        cell.badge = badge;
        // 监听器不绑定具体应用：拖拽换位后只重绑数据，点击/长按跟着当前数据走
        badge.setOnClickListener(v -> {
            if (cell.item != null) {
                removeNow(cell.item);
            }
        });
        box.setOnClickListener(v -> {
            Item cur = cell.item;
            if (cur == null || editMode) {
                return;
            }
            // 长按刚触发过（已弹出操作单）：吞掉这次松手带来的点击
            if (android.os.SystemClock.uptimeMillis() - lastLongPressAt < 800) {
                return;
            }
            if (moveMode) {
                onMoveClick(cur);
                return;
            }
            if (cur.frozen) {
                unfreezeAndOpen(cur);
            } else {
                launch(cur);
            }
        });
        box.setOnLongClickListener(null);
        // 长按按开关分流：
        // 移动模式 → 进入长按拖动换位（不弹操作单）；普通模式 → 弹原来的操作单。
        cell.longPress = () -> {
            if (editMode || cell.item == null) {
                return;
            }
            lastLongPressAt = android.os.SystemClock.uptimeMillis();
            if (moveMode) {
                startDrag(box, cell.item);
                return;
            }
            box.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            showAppSheet(cell.item);
        };
        box.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    cell.downX = e.getRawX();
                    cell.downY = e.getRawY();
                    // 按下就先从父容器（ScrollView / 半屏容器）手里把事件要过来，
                    // 否则轻微滑动就被当成列表滚动，长按计时被 CANCEL 掉
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    v.postDelayed(cell.longPress, LONG_PRESS_MS);
                    break;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - cell.downX;
                    float dy = e.getRawY() - cell.downY;
                    // 手指明显移动（≥18dp）就取消长按计时，避免滑动列表时误弹菜单
                    if (dx * dx + dy * dy > (float) dragSlop() * dragSlop()) {
                        v.removeCallbacks(cell.longPress);
                    }
                    break;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(cell.longPress);
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                default:
                    break;
            }
            // 不消费事件：点击、列表滚动仍按原逻辑走
            return false;
        });
        // 移动模式下长按拖动：接住拖拽事件做换位；普通模式直接忽略
        box.setOnDragListener((v, event) -> onDrag(event, cell));
        cellOrder.add(cell);
        bindCellTo(cell, it);
        return box;
    }

    /** 把一个格子的显示内容换成指定应用（含冻结灰度、移除角标、移动模式选中态）。 */
    private void bindCellTo(Cell cell, Item it) {
        cell.item = it;
        cells.put(it.pkg, cell);
        if (it.icon != null) {
            cell.icon.setImageDrawable(it.icon);
        } else {
            cell.icon.setImageResource(R.drawable.ic_launcher);
        }
        cell.name.setText(it.label);
        cell.root.setContentDescription(it.label);
        cell.badge.setVisibility(editMode ? View.VISIBLE : View.GONE);
        if (it.frozen) {
            cell.icon.setColorFilter(GRAY);
            cell.icon.setAlpha(0.45f);
        } else {
            cell.icon.clearColorFilter();
            cell.icon.setAlpha(1f);
        }
        cell.root.setBackground(moveMode && it.pkg.equals(selectedPkg) ? selectedBackground() : null);
        cell.root.setAlpha(moveMode && it.pkg.equals(draggingPkg) ? 0.35f : 1f);
    }

    private void rebindCells() {
        List<Item> shown = filtered();
        for (int i = 0; i < cellOrder.size() && i < shown.size(); i++) {
            bindCellTo(cellOrder.get(i), shown.get(i));
        }
        updateCount(shown);
        reapplyFullscreenSurfaces();
    }

    // ---------------- 点击移动图标（移动模式） ----------------

    /** 全屏列表选中项不再画高亮框：直接跟页面背景同色（保持点击行为不变）。 */
    private android.graphics.drawable.Drawable selectedBackground() {
        return null;
    }

    /** 移动模式：第一次点击选中并高亮，第二次点目标项即插到目标前面并实时落盘。 */
    private void onMoveClick(Item cur) {
        if (selectedPkg == null) {
            selectedPkg = cur.pkg;
            Toast.makeText(this, "已选中「" + cur.label + "」，点目标位置放下", Toast.LENGTH_SHORT).show();
            rebindCells();
            return;
        }
        if (selectedPkg.equals(cur.pkg)) {
            selectedPkg = null;
            rebindCells();
            return;
        }
        Item moved = itemOf(selectedPkg);
        selectedPkg = null;
        if (moved == null) {
            rebindCells();
            return;
        }
        if (moveItemBefore(moved, cur)) {
            persistOrder();
            Toast.makeText(this, "已移动到「" + cur.label + "」前面", Toast.LENGTH_SHORT).show();
        }
        render();
    }

    /** 把 moved 插到 target 之前；只重排当前可见项，隐藏项保持原槽位。返回是否真的换位。 */
    private boolean moveItemBefore(Item moved, Item target) {
        List<Item> shown = filtered();
        int from = -1;
        int to = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).pkg.equals(moved.pkg)) {
                from = i;
            }
            if (shown.get(i).pkg.equals(target.pkg)) {
                to = i;
            }
        }
        if (from < 0 || to < 0 || from == to) {
            return false;
        }
        List<Item> reordered = new ArrayList<>(shown);
        Item moving = reordered.remove(from);
        // 落点语义：插入到目标应用之前，其余顺次后移（不是互换）
        int insert = from < to ? to - 1 : to;
        reordered.add(insert, moving);
        int k = 0;
        for (int i = 0; i < items.size(); i++) {
            if (shown.contains(items.get(i))) {
                items.set(i, reordered.get(k++));
            }
        }
        return true;
    }

    /** 触摸移动阈值：取系统 slop 的两倍且不小于 18dp，手抖不算移动。 */
    private int dragSlop() {
        return Math.max(ViewConfiguration.get(this).getScaledTouchSlop() * 2, Ui.dp(this, 18));
    }

    // ---------------- 长按拖动换位（移动模式） ----------------

    private void startDrag(View cell, Item it) {
        draggingPkg = it.pkg;
        lastHoverPkg = it.pkg;
        dragMoved = false;
        selectedPkg = null;
        cell.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        ClipData data = ClipData.newPlainText("pkg", it.pkg);
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(cell);
        // 本地拖拽：不申请全局权限
        cell.startDragAndDrop(data, shadow, null, 0);
        rebindCells();
    }

    private boolean onDrag(DragEvent event, Cell cell) {
        if (!moveMode || draggingPkg == null) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                // 不接这个事件，后续 ENTERED/LOCATION 不会派发到格子上
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION: {
                Item hovered = cell.item;
                if (hovered == null) {
                    return true;
                }
                if (!hovered.pkg.equals(draggingPkg) && !hovered.pkg.equals(lastHoverPkg)) {
                    Item moved = itemOf(draggingPkg);
                    if (moved != null && moveItemBefore(moved, hovered)) {
                        lastHoverPkg = hovered.pkg;
                        dragMoved = true;
                        // 只重绑数据，不重建视图，保证拖拽手势连续
                        rebindCells();
                    }
                }
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                finishDrag();
                return true;
            default:
                return false;
        }
    }

    private void finishDrag() {
        if (draggingPkg == null) {
            return;
        }
        boolean moved = dragMoved;
        draggingPkg = null;
        lastHoverPkg = null;
        dragMoved = false;
        if (moved) {
            persistOrder();
        }
        render();
    }


    private Item itemOf(String pkg) {
        for (Item it : items) {
            if (it.pkg.equals(pkg)) {
                return it;
            }
        }
        return null;
    }

    /** 长按不动松手：把应用移入另一个标签；一个应用只属于一个标签。 */
    private void showMoveDialog(Item it) {
        final List<Tab> targets = new ArrayList<>();
        String cur = tabIdOf(it);
        for (Tab t : tabs) {
            if (!t.id.equals(cur)) {
                targets.add(t);
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "只有一个标签，先在布局设置里新建标签", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            Tab t = targets.get(i);
            names[i] = t.visible ? t.name : t.name + "（已隐藏）";
        }
        new AlertDialog.Builder(this)
                .setTitle("移动「" + it.label + "」到…")
                .setItems(names, (d, w) -> moveToTab(it, targets.get(w)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void moveToTab(Item it, Tab target) {
        appTabs.put(it.pkg, target.id);
        saveAppTabs();
        Toast.makeText(this, "已移动到「" + target.name + "」", Toast.LENGTH_SHORT).show();
        render();
    }

    // ---------------- 长按底部操作单 ----------------

    /** 长按不拖动松手：底部半屏操作单（冰箱式），拖动排序不进这里。 */
    private void showAppSheet(Item it) {
        AppActionSheet.show(this, it.label, it.icon, it.frozen, new AppActionSheet.Actions() {
            @Override
            public void run() {
                runApp(it);
            }

            @Override
            public void toggleFreeze() {
                FreezeListActivity.this.toggleFreeze(it);
            }

            @Override
            public void moveTab() {
                showMoveDialog(it);
            }

            @Override
            public void appSettings() {
                openAppSettings(it);
            }

            @Override
            public void pinHome() {
                pinToHome(it);
            }

            @Override
            public boolean inList() {
                return true;
            }

            @Override
            public void removeAndUnfreeze() {
                FreezeListActivity.this.removeAndUnfreeze(it);
            }
        });
    }

    /** 运行：已冻结的先解冻再打开。 */
    private void runApp(Item it) {
        if (it.frozen) {
            unfreezeAndOpen(it);
        } else {
            launch(it);
        }
    }

    private void toggleFreeze(Item it) {
        if (it.frozen) {
            unfreezeSingle(it);
        } else {
            freezeSingle(it);
        }
    }

    /** 只冻结当前应用；已冻结时直接提示，不重复执行。成功静默，失败给 pm 真实输出。 */
    private void freezeSingle(Item it) {
        if (working) {
            return;
        }
        if (it.frozen) {
            Toast.makeText(this, "「" + it.label + "」已经是冻结状态", Toast.LENGTH_SHORT).show();
            return;
        }
        if (com.jin.freeze.core.CoreProtect.isProtected(this, it.pkg, it.label)) {
            Toast.makeText(this, "该应用受核心保护，不能冻结", Toast.LENGTH_SHORT).show();
            return;
        }
        working = true;
        startFreezeSpin();
        IO.execute(() -> {
            String out = execPm(it.pkg, true);
            boolean ok = isPmOk(out);
            main.post(() -> {
                stopFreezeSpin();
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    showPmError("冻结失败", it, out);
                    return;
                }
                it.frozen = true;
                rebindCells();
                if (showFrozenTip) {
                    Toast.makeText(this, "已冻结", Toast.LENGTH_SHORT).show();
                }
                if (panelDismissAfterFreeze) {
                    dismissAfterReload = true;
                }
                reload();
            });
        });
    }

    private void unfreezeSingle(Item it) {
        if (working) {
            return;
        }
        working = true;
        IO.execute(() -> {
            String out = execPm(it.pkg, false);
            boolean ok = isPmOk(out);
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    showPmError("解冻失败", it, out);
                    return;
                }
                it.frozen = false;
                rebindCells();
                reload();
            });
        });
    }

    private void showPmError(String title, Item it, String out) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("「" + it.label + "」\n" + out)
                .setPositiveButton("知道了", null)
                .show();
        reload();
    }

    /** 在其他应用中配置：走系统应用详情页，失败落系统设置首页并提示。 */
    private void openAppSettings(Item it) {
        Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + it.pkg));
        detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(detail);
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "没有找到该应用的详情入口，已打开系统设置", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    /** 添加到桌面：系统固定快捷方式，指向该应用的启动页。 */
    private void pinToHome(Item it) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(it.pkg);
        if (launchIntent == null || launchIntent.getComponent() == null) {
            Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
            return;
        }
        LaunchTarget.Target target = new LaunchTarget.Target(it.pkg,
                launchIntent.getComponent().getClassName(), it.label);
        ShortcutPin.pin(this, "frozen_" + it.pkg, it.label, it.icon, target);
    }

    /**
     * 移除列表并解冻：先把应用移出名单（含标签归属与顺序），再真正解冻。
     * 解冻失败要单独报 pm 的真实原因，不能把「已移出」当成整体成功。
     */
    private void removeAndUnfreeze(Item it) {
        if (working) {
            return;
        }
        Set<String> cur = pkgs();
        cur.remove(it.pkg);
        prefs().edit().putStringSet(KEY_PKGS, cur).apply();
        appTabs.remove(it.pkg);
        saveAppTabs();
        // 只摘掉这一项，不动是否自定义顺序的标记
        List<String> order = savedOrder();
        if (order.remove(it.pkg)) {
            StringBuilder sb = new StringBuilder();
            for (String p : order) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p);
            }
            prefs().edit().putString(KEY_ORDER, sb.toString()).apply();
        }
        items.remove(it);
        cells.remove(it.pkg);
        if (!it.frozen) {
            Toast.makeText(this, "已移出名单", Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        working = true;
        render();
        IO.execute(() -> {
            String out = execPm(it.pkg, false);
            boolean ok = isPmOk(out);
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    new AlertDialog.Builder(this)
                            .setTitle("已移出名单，但解冻失败")
                            .setMessage("「" + it.label + "」\n" + out)
                            .setPositiveButton("知道了", null)
                            .show();
                }
                reload();
            });
        });
    }

    private void persistOrder() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(it.pkg);
        }
        prefs().edit()
                .putString(KEY_ORDER, sb.toString())
                .putBoolean(KEY_CUSTOM, true)
                .apply();
    }

    /** 每格宽度：按屏幕可用宽度均分，保证整行等宽、左右对称。 */
    private int computedCellPx() {
        int width = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 28);
        return width / Math.max(2, gridColumns);
    }

    /** 图标太小看不清，下限固定 28dp。 */
    private int iconSizeMinDp() {
        return 28;
    }

    /** 图标上限：格子内再留 10dp，确保一格放得下且不溢出；最多 88dp。 */
    private int iconSizeMaxDp() {
        int cellDp = Math.round(computedCellPx() / getResources().getDisplayMetrics().density);
        return Math.max(iconSizeMinDp() + 4, Math.min(88, cellDp - 10));
    }

    /** 图标大小：未手动指定时以默认 4 列 52dp 为基准随列数缩放；手动指定时夹在上限内，保证不溢出。 */
    private int computedIconPx() {
        int cell = computedCellPx();
        int icon = iconManual ? Ui.dp(this, iconDp)
                : Ui.dp(this, 52) * COLUMNS / Math.max(2, gridColumns);
        int min = Ui.dp(this, 28);
        int max = Math.max(min, cell - Ui.dp(this, 10));
        return Math.max(min, Math.min(max, icon));
    }

    private int currentIconDp() {
        return Math.round(computedIconPx() / getResources().getDisplayMetrics().density);
    }

    /** 按钮直径与内嵌图标同比缩放，热区就是按钮本身。 */
    private void applyFabSize() {
        if (fabGroup == null) {
            return;
        }
        View btn = findViewById(R.id.btn_freeze);
        int px = Ui.dp(this, fabSizeDp);
        btn.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        int pad = Math.round(px * 0.2f);
        btn.setPadding(pad, pad, pad, pad);
    }

    private int fabInsetPx() {
        return Ui.dp(this, 8);
    }

    /** 底部居中的基准横坐标：两个滑块都在这个基准上做偏移。 */
    private int fabCenterX() {
        View parent = (View) fabGroup.getParent();
        return Math.max(fabInsetPx(), (parent.getWidth() - fabGroup.getWidth()) / 2);
    }

    private int fabMaxX() {
        View parent = (View) fabGroup.getParent();
        return Math.max(fabInsetPx(), parent.getWidth() - fabGroup.getWidth() - fabInsetPx());
    }

    /** 从贴底位置最多能往上抬多少：上边界到列表区域顶部，避免遮住搜索/筛选行。 */
    private int fabMaxLiftPx() {
        View parent = (View) fabGroup.getParent();
        int top = (scrollView == null ? 0 : scrollView.getTop()) + fabInsetPx();
        int bottom = parent.getHeight() - fabInsetPx() - fabGroup.getHeight();
        return Math.max(0, bottom - top);
    }

    /** 把落盘的偏移换算成实际坐标；越界时贴边停住。 */
    private void applyFabPosition() {
        // 半屏形态用另一套布局参数，基准仍是底部居中
        if (panelMode) {
            applyPanelFabPosition();
            return;
        }
        if (fabGroup == null || fabGroup.getWidth() == 0
                || !(fabGroup.getParent() instanceof View)) {
            return;
        }
        View parent = (View) fabGroup.getParent();
        int inset = fabInsetPx();
        int x = fabCenterX() + Ui.dp(this, fabDx);
        x = Math.max(inset, Math.min(fabMaxX(), x));
        int topLimit = (scrollView == null ? 0 : scrollView.getTop()) + inset;
        // 偏移 0 就是真正贴底，安全区由根布局的 inset padding 让出，这里不再重复加
        int bottomLimit = parent.getHeight() - fabGroup.getHeight();
        int y = bottomLimit - Ui.dp(this, fabDy);
        y = Math.max(topLimit, Math.min(bottomLimit, y));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fabGroup.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = x;
        lp.topMargin = y;
        fabGroup.setLayoutParams(lp);
        // 等这一轮 layout 落地后再贴解冻图标，避免用到旧的 left/top
        fabGroup.post(this::positionUnfreezeFab);
    }

    /** 半屏形态的雪花按钮：底部居中为基准，再按各自落盘的偏移平移。 */
    private void applyPanelFabPosition() {
        if (fabGroup == null || !(fabGroup.getParent() instanceof View)) {
            return;
        }
        View parent = (View) fabGroup.getParent();
        fabGroup.post(() -> {
            int w = parent.getWidth();
            if (w <= 0 || fabGroup.getWidth() <= 0) {
                return;
            }
            int maxX = Math.max(0, (w - fabGroup.getWidth()) / 2 - Ui.dp(this, 8));
            int x = Math.max(-maxX, Math.min(maxX, Ui.dp(this, fabDx)));
            // 只允许往上抬，最多抬到标签条下方，避免挡住内容
            int maxUp = Ui.dp(this, 120);
            int y = Math.max(-maxUp, Math.min(0, -Ui.dp(this, fabDy)));
            fabGroup.setTranslationX(x);
            fabGroup.setTranslationY(y);
            positionUnfreezeFab();
        });
    }

    /** 开始执行冻结：雪花持续旋转，同时禁用按钮避免连点并发。 */
    private void startFreezeSpin() {
        if (freezeBtn == null) {
            return;
        }
        if (freezeBtn instanceof android.widget.ImageButton) {
            ((android.widget.ImageButton) freezeBtn).setEnabled(false);
        } else {
            freezeBtn.setEnabled(false);
        }
        if (freezeSpin != null) {
            return;
        }
        freezeSpin = android.animation.ObjectAnimator.ofFloat(freezeBtn, View.ROTATION, 0f, 360f);
        freezeSpin.setDuration(900);
        freezeSpin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        freezeSpin.setInterpolator(new android.view.animation.LinearInterpolator());
        freezeSpin.start();
    }

    /** 停止旋转并复位；失败时要先调这个再弹错误。 */
    private void stopFreezeSpin() {
        if (freezeSpin != null) {
            freezeSpin.cancel();
            freezeSpin = null;
        }
        if (freezeBtn != null) {
            freezeBtn.setRotation(0f);
            freezeBtn.setEnabled(true);
        }
    }

    /** 长按冻结按钮展开一键解冻；3 秒无操作或点空白处收回。 */
    private void setupUnfreezeFab() {
        unfreezeFab = findViewById(R.id.btn_unfreeze_fab);
        if (unfreezeFab == null) {
            return;
        }
        unfreezeFab.setVisibility(View.GONE);
        unfreezeFab.setOnClickListener(v -> {
            collapseUnfreezeFab();
            if (!working) {
                runBatch(false, filtered());
            }
        });
        // 点击/按住解冻图标都算操作，重新计时
        unfreezeFab.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                scheduleFabCollapse();
            }
            return false;
        });
        if (freezeBtn != null) {
            freezeBtn.setOnLongClickListener(v -> {
                expandUnfreezeFab();
                return true;
            });
            freezeBtn.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                        && unfreezeFab.getVisibility() == View.VISIBLE) {
                    scheduleFabCollapse();
                }
                return false;
            });
        }
        // 列表区域按下即视为点空白处，立即收回
        if (scrollView != null) {
            scrollView.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    collapseUnfreezeFab();
                }
                return false;
            });
        }
    }

    private void expandUnfreezeFab() {
        if (unfreezeFab == null || isFinishing()) {
            return;
        }
        // 先显示再定位：GONE 时拿不到宽高，直接量会偏
        fabExpanded = true;
        unfreezeFab.setAlpha(0f);
        unfreezeFab.setScaleX(0.7f);
        unfreezeFab.setScaleY(0.7f);
        unfreezeFab.setVisibility(View.VISIBLE);
        unfreezeFab.post(() -> {
            positionUnfreezeFab();
            unfreezeFab.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(140).start();
        });
        scheduleFabCollapse();
    }

    private void scheduleFabCollapse() {
        fabHandler.removeCallbacks(fabCollapseRunnable);
        fabHandler.postDelayed(fabCollapseRunnable, 3000);
    }

    private void collapseUnfreezeFab() {
        fabHandler.removeCallbacks(fabCollapseRunnable);
        if (!fabExpanded || unfreezeFab == null
                || unfreezeFab.getVisibility() != View.VISIBLE) {
            return;
        }
        fabExpanded = false;
        unfreezeFab.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(120)
                .withEndAction(() -> {
                    // 期间若又展开，就不要收回
                    if (!fabExpanded) {
                        unfreezeFab.setVisibility(View.GONE);
                    }
                }).start();
    }

    /** 把解冻小图标贴到冻结按钮正上方（含偏移与面板位移），避免压住列表和底栏。 */
    private void positionUnfreezeFab() {
        if (unfreezeFab == null || fabGroup == null
                || !(fabGroup.getParent() instanceof View)) {
            return;
        }
        int uw = unfreezeFab.getWidth() > 0 ? unfreezeFab.getWidth() : Ui.dp(this, 40);
        int uh = unfreezeFab.getHeight() > 0 ? unfreezeFab.getHeight() : Ui.dp(this, 40);
        int x = fabGroup.getLeft() + Math.round(fabGroup.getTranslationX())
                + (fabGroup.getWidth() - uw) / 2;
        int y = fabGroup.getTop() + Math.round(fabGroup.getTranslationY())
                - uh - Ui.dp(this, 8);
        unfreezeFab.setTranslationX(x);
        unfreezeFab.setTranslationY(y);
    }

    @Override
    protected void onDestroy() {
        fabHandler.removeCallbacks(fabCollapseRunnable);
        main.removeCallbacks(introReveal);
        main.removeCallbacks(introSafety);
        if (introScrimView != null) {
            introScrimView.animate().cancel();
        }
        if (introStatusView != null) {
            introStatusView.animate().cancel();
        }
        stopFreezeSpin();
        super.onDestroy();
    }

    private void showLayoutDialog() {
        int pad = Ui.dp(this, 16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 2), pad, Ui.dp(this, 4));

        // 顶部操作提示：紧贴标题下方、与标题成一组，不随内容滚动
        TextView gestureHint = new TextView(this);
        android.text.SpannableStringBuilder hintText = new android.text.SpannableStringBuilder(
                "长按布局移动位置，点击 ");
        Drawable chevron = getDrawable(R.drawable.ic_chevron_down);
        if (chevron != null) {
            // 直接复用右上角那个倒三角 drawable，保证和收起按钮是同一个图形
            chevron = chevron.mutate();
            int iconSize = Ui.dp(this, 12);
            chevron.setBounds(0, 0, iconSize, iconSize);
            hintText.append(" ");
            int start = hintText.length() - 1;
            hintText.setSpan(new android.text.style.ImageSpan(chevron,
                            android.text.style.ImageSpan.ALIGN_CENTER),
                    start, hintText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        hintText.append(" 调节高度");
        gestureHint.setText(hintText);
        gestureHint.setTextSize(11);
        gestureHint.setTextColor(getColor(R.color.text_secondary));
        gestureHint.setPadding(pad, 0, pad, Ui.dp(this, 4));

        // 权限标识挪到标题行，这里不再单独占一行

        // 图标大小：与列数同一套滑块样式，放在列数下面；范围随列宽自适应（一格放得下且不溢出）
        TextView iconTitle = new TextView(this);
        iconTitle.setText("图标大小");
        iconTitle.setTextSize(12);
        iconTitle.setTextColor(getColor(R.color.text_primary));
        iconTitle.setPadding(0, Ui.dp(this, 12), 0, 0);

        final TextView sizeLabel = new TextView(this);
        sizeLabel.setTextSize(11);
        sizeLabel.setTextColor(getColor(R.color.text_secondary));

        final SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(Math.max(1, iconSizeMaxDp() - iconSizeMinDp()));
        sizeBar.setProgress(Math.max(0, Math.min(sizeBar.getMax(),
                currentIconDp() - iconSizeMinDp())));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                // 拖动过程只做实时预览，松手再落盘
                iconDp = p + iconSizeMinDp();
                iconManual = true;
                sizeLabel.setText("图标大小：" + iconDp + "dp");
                render();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs().edit().putInt(lk(KEY_ICON_DP), iconDp)
                        .putBoolean(lk(KEY_ICON_MANUAL), true).apply();
            }
        });
        sizeLabel.setText("图标大小：" + (sizeBar.getProgress() + iconSizeMinDp()) + "dp");

        CheckBox nameBox = new CheckBox(this);
        nameBox.setText("显示名称");
        nameBox.setTextSize(12);
        nameBox.setMinimumHeight(Ui.dp(this, 32));
        nameBox.setChecked(showName);
        nameBox.setOnCheckedChangeListener((btn, on) -> {
            showName = on;
            prefs().edit().putBoolean(lk(KEY_SHOW_NAME), on).apply();
            render();
        });

        // 「移动图标」「半屏高度调节」勾选瞬间都要收起布局弹窗，先把实例留个引用
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];

        // 移动图标开关（手势最高优先级）：打开后点击选中、再点目标位置放下，长按拖动换位；
        // 标签左右滑动、长按操作单、点击打开应用全部让路。关闭后恢复原来分工。
        CheckBox moveBox = new CheckBox(this);
        moveBox.setText("移动图标");
        moveBox.setTextSize(12);
        moveBox.setMinimumHeight(Ui.dp(this, 32));
        moveBox.setChecked(moveMode);
        moveBox.setOnCheckedChangeListener((btn, on) -> {
            moveMode = on;
            selectedPkg = null;
            prefs().edit().putBoolean(lk(KEY_MOVE_MODE), on).apply();
            render();
            if (on) {
                // 和「半屏高度调节」一致：开启瞬间自动收起布局设置，让用户直接去移动图标
                if (dialogRef[0] != null) {
                    dialogRef[0].dismiss();
                }
                Toast.makeText(this, "移动模式：点击选中/换位、长按拖动；标签滑动与长按操作单暂停",
                        Toast.LENGTH_SHORT).show();
            }
        });

        // 冻结成功提示开关：全屏与半屏共用
        CheckBox tipBox = new CheckBox(this);
        tipBox.setText("显示已冻结提示");
        tipBox.setTextSize(12);
        tipBox.setMinimumHeight(Ui.dp(this, 32));
        tipBox.setChecked(showFrozenTip);
        tipBox.setOnCheckedChangeListener((btn, on) -> {
            showFrozenTip = on;
            prefs().edit().putBoolean(lk(KEY_SHOW_FROZEN_TIP), on).apply();
        });

        // 半屏专属：顶部小白条是否允许拖动改高度（开关与高度值分开存）
        CheckBox handleBox = null;
        CheckBox dismissBox = new CheckBox(this);
        dismissBox.setText("冻结后收起列表");
        dismissBox.setTextSize(12);
        dismissBox.setMinimumHeight(Ui.dp(this, 32));
        dismissBox.setChecked(panelDismissAfterFreeze);
        dismissBox.setOnCheckedChangeListener((btn, on) -> {
            panelDismissAfterFreeze = on;
            prefs().edit().putBoolean(lk(KEY_PANEL_DISMISS), on).apply();
        });

        CheckBox hideRecentsBox = new CheckBox(this);
        hideRecentsBox.setText("隐藏后台");
        hideRecentsBox.setTextSize(12);
        hideRecentsBox.setMinimumHeight(Ui.dp(this, 32));
        hideRecentsBox.setChecked(panelHideRecents);
        hideRecentsBox.setOnCheckedChangeListener((btn, on) -> {
            panelHideRecents = on;
            prefs().edit().putBoolean(lk(KEY_PANEL_HIDE_RECENTS), on).apply();
        });

        if (panelMode) {
            handleBox = new CheckBox(this);
            handleBox.setText("半屏高度小白条调节");
            handleBox.setTextSize(12);
            handleBox.setMinimumHeight(Ui.dp(this, 32));
            handleBox.setChecked(panelHandleEnabled);
            handleBox.setOnCheckedChangeListener((btn, on) -> {
                // 只在「关 → 开」时触发：收起弹窗并让把手呼吸一次，提示现在可以拖
                boolean turningOn = on && !panelHandleEnabled;
                panelHandleEnabled = on;
                prefs().edit().putBoolean(KEY_PANEL_HANDLE, on).apply();
                applyPanelHandleState();
                if (turningOn) {
                    if (dialogRef[0] != null) {
                        dialogRef[0].dismiss();
                    }
                    main.postDelayed(FreezeListActivity.this::flashPanelHandle, 160);
                }
            });

        }

        // 悬浮按钮位置：横向以「底部居中」为 0 左右偏，纵向以贴底为 0 往上抬，拖动即时生效
        final float density = getResources().getDisplayMetrics().density;
        final int xMinDp = Math.round((fabInsetPx() - fabCenterX()) / density);
        final int xMaxDp = Math.round((fabMaxX() - fabCenterX()) / density);
        final int maxLiftDp = Math.round(fabMaxLiftPx() / density);

        TextView fabTitle = new TextView(this);
        fabTitle.setText("一键冻结按钮位置");
        fabTitle.setTextSize(12);
        fabTitle.setTextColor(getColor(R.color.text_primary));
        fabTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

        final TextView xValue = new TextView(this);
        xValue.setTextSize(11);
        xValue.setTextColor(getColor(R.color.text_secondary));

        final TextView sizeValue = new TextView(this);
        sizeValue.setTextSize(11);
        sizeValue.setTextColor(getColor(R.color.text_secondary));
        sizeValue.setText("按钮大小：" + fabSizeDp + "dp");

        SeekBar fabSizeBar = new SeekBar(this);
        fabSizeBar.setMax(FAB_SIZE_MAX - FAB_SIZE_MIN);
        fabSizeBar.setProgress(fabSizeDp - FAB_SIZE_MIN);
        fabSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                fabSizeDp = FAB_SIZE_MIN + p;
                prefs().edit().putInt(lk(KEY_FAB_SIZE), fabSizeDp).apply();
                sizeValue.setText("按钮大小：" + fabSizeDp + "dp");
                applyFabSize();
                fabGroup.post(FreezeListActivity.this::applyFabPosition);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        final TextView yValue = new TextView(this);
        yValue.setTextSize(11);
        yValue.setTextColor(getColor(R.color.text_secondary));

        final Runnable refreshFabLabels = () -> {
            xValue.setText("左右偏移：" + (fabDx > 0 ? "+" : "") + fabDx + "dp（0 为居中）");
            yValue.setText("距底部：" + fabDy + "dp（0 为贴底）");
        };

        SeekBar xBar = new SeekBar(this);
        xBar.setMax(Math.max(0, xMaxDp - xMinDp));
        xBar.setProgress(Math.max(0, Math.min(xMaxDp, fabDx) - xMinDp));
        xBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                fabDx = xMinDp + p;
                prefs().edit().putInt(lk(KEY_FAB_DX), fabDx).apply();
                refreshFabLabels.run();
                applyFabPosition();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        SeekBar yBar = new SeekBar(this);
        yBar.setMax(Math.max(0, maxLiftDp));
        yBar.setProgress(Math.max(0, Math.min(maxLiftDp, fabDy)));
        yBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                fabDy = p;
                prefs().edit().putInt(lk(KEY_FAB_DY), fabDy).apply();
                refreshFabLabels.run();
                applyFabPosition();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        TextView fabReset = new TextView(this);
        fabReset.setText("恢复默认（底部居中）");
        fabReset.setTextSize(11);
        fabReset.setTextColor(getColor(R.color.blue));
        fabReset.setPadding(0, Ui.dp(this, 8), 0, 0);
        fabReset.setOnClickListener(v -> {
            fabDx = 0;
            fabDy = 0;
            fabSizeDp = FAB_SIZE_DEFAULT;
            prefs().edit().putInt(lk(KEY_FAB_DX), 0).putInt(lk(KEY_FAB_DY), 0)
                    .putInt(lk(KEY_FAB_SIZE), fabSizeDp).apply();
            xBar.setProgress(-xMinDp);
            yBar.setProgress(0);
            fabSizeBar.setProgress(fabSizeDp - FAB_SIZE_MIN);
            sizeValue.setText("按钮大小：" + fabSizeDp + "dp");
            refreshFabLabels.run();
            applyFabSize();
            fabGroup.post(FreezeListActivity.this::applyFabPosition);
        });
        refreshFabLabels.run();

        // 标签：隐藏任一标签（数据保留）、新建/改名/删除自定义标签
        TextView tabTitle = new TextView(this);
        tabTitle.setText("标签");
        tabTitle.setTextSize(12);
        tabTitle.setTextColor(getColor(R.color.text_primary));
        tabTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

        TextView tabHint = new TextView(this);
        tabHint.setText("取消勾选即隐藏该标签（其中的应用数据保留）；所有标签都可改名，删除仅限自定义标签，删除后应用回到原归属。");
        tabHint.setTextSize(10);
        tabHint.setTextColor(getColor(R.color.text_secondary));
        tabHint.setPadding(0, 0, 0, Ui.dp(this, 4));

        // 左右滑动切换标签：关掉后只认点击，避免与长按移动 / 列表滚动打架
        CheckBox swipeBox = new CheckBox(this);
        swipeBox.setText("列表左右滑动切换标签");
        swipeBox.setTextSize(12);
        swipeBox.setMinimumHeight(Ui.dp(this, 32));
        swipeBox.setChecked(tabSwipeEnabled);
        swipeBox.setOnCheckedChangeListener((b, on) -> {
            tabSwipeEnabled = on;
            prefs().edit().putBoolean(lk(KEY_TAB_SWIPE), on).apply();
        });

        LinearLayout tabList = new LinearLayout(this);
        tabList.setOrientation(LinearLayout.VERTICAL);

        TextView newTab = new TextView(this);
        newTab.setText("＋ 新建标签");
        newTab.setTextSize(11);
        newTab.setTextColor(getColor(R.color.blue));
        newTab.setPadding(0, Ui.dp(this, 8), 0, 0);

        final Runnable[] refreshTabSettings = new Runnable[1];
        refreshTabSettings[0] = () -> {
            tabList.removeAllViews();
            for (Tab t : tabs) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                CheckBox cb = new CheckBox(this);
                cb.setText(t.name);
                cb.setTextSize(12);
                cb.setMinimumHeight(Ui.dp(FreezeListActivity.this, 32));
                cb.setChecked(t.visible);
                cb.setOnCheckedChangeListener((b, on) -> {
                    if (on == t.visible) {
                        return;
                    }
                    if (!on && visibleTabCount() <= 1) {
                        Toast.makeText(this, "至少保留一个标签", Toast.LENGTH_SHORT).show();
                        b.setChecked(true);
                        return;
                    }
                    t.visible = on;
                    if (!on && t.id.equals(currentTabId)) {
                        // 当前标签被隐藏 → 切到第一个仍可见的标签
                        currentTabId = firstVisibleTabId();
                        prefs().edit().putString(KEY_CURRENT_TAB, currentTabId).apply();
                    }
                    saveTabs();
                    setSysHintVisible();
                    renderTabBar();
                    render();
                });
                row.addView(cb, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                // 内置标签也能改名（只改显示名，不影响它承载用户/系统应用的性质）；删除仍只限自定义标签
                TextView rename = new TextView(this);
                rename.setText("改名");
                rename.setTextSize(11);
                rename.setTextColor(getColor(R.color.blue));
                rename.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
                rename.setOnClickListener(v -> showTabNameDialog(t, refreshTabSettings[0]));
                row.addView(rename);
                if (t.custom) {
                    TextView del = new TextView(this);
                    del.setText("删除");
                    del.setTextSize(11);
                    del.setTextColor(0xFFE5484D);
                    del.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), 0, Ui.dp(this, 6));
                    del.setOnClickListener(v -> confirmDeleteTab(t, refreshTabSettings[0]));
                    row.addView(del);
                }
                tabList.addView(row);
            }
        };
        refreshTabSettings[0].run();
        newTab.setOnClickListener(v -> showTabNameDialog(null, refreshTabSettings[0]));

        box.addView(nameBox);
        box.addView(moveBox);
        box.addView(tipBox);

        // 全屏打开模式：只决定桌面图标进来时的形态，两种形态各用各的布局参数
        CheckBox fullBox = new CheckBox(this);
        fullBox.setText("全屏打开模式");
        fullBox.setTextSize(12);
        fullBox.setMinimumHeight(Ui.dp(this, 32));
        fullBox.setChecked(prefs().getBoolean(KEY_FULLSCREEN_OPEN, false));
        fullBox.setOnCheckedChangeListener((b, on) -> {
            prefs().edit().putBoolean(KEY_FULLSCREEN_OPEN, on).apply();
            boolean switching = (on == panelMode);
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
            if (switching) {
                // 点击即切换：直接换到另一种形态的 Activity，不用退出重进
                Class<?> next = on ? FreezeListActivity.class : FreezePanelActivity.class;
                Intent intent = new Intent(this, next);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(intent);
                finish();
            } else {
                Toast.makeText(this, on
                                ? "下次从桌面图标进入：全屏列表"
                                : "下次从桌面图标进入：半屏面板",
                        Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(fullBox);

        if (hideRecentsBox != null) {
            box.addView(hideRecentsBox);
        }
        if (handleBox != null) {
            box.addView(handleBox);
        }
        if (dismissBox != null) {
            box.addView(dismissBox);
        }
        box.addView(swipeBox);
        box.addView(tabTitle);
        box.addView(tabHint);
        box.addView(tabList);
        box.addView(newTab);
        box.addView(fabTitle);
        box.addView(sizeValue);
        box.addView(fabSizeBar);
        box.addView(xValue);
        box.addView(xBar);
        box.addView(yValue);
        box.addView(yBar);
        box.addView(fabReset);

        // 列数滑块放在面板最下面：4/5/6 三档，和上面几个滑块同一套样式
        TextView colsTitle = new TextView(this);
        colsTitle.setText("列数");
        colsTitle.setTextSize(12);
        colsTitle.setTextColor(getColor(R.color.text_primary));
        colsTitle.setPadding(0, Ui.dp(this, 12), 0, 0);

        final TextView colsValue = new TextView(this);
        colsValue.setTextSize(11);
        colsValue.setTextColor(getColor(R.color.text_secondary));
        colsValue.setText("列数：" + gridColumns + " 列");

        SeekBar colsBar = new SeekBar(this);
        colsBar.setMax(2);
        colsBar.setProgress(Math.max(0, Math.min(2, gridColumns - 4)));
        colsBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                gridColumns = p + 4;
                // 列数变化 → 图标按新列数自适应重算
                iconManual = false;
                prefs().edit().putInt(lk(KEY_COLUMNS), gridColumns)
                        .putBoolean(lk(KEY_ICON_MANUAL), false).apply();
                colsValue.setText("列数：" + gridColumns + " 列");
                int min = iconSizeMinDp();
                sizeBar.setMax(Math.max(1, iconSizeMaxDp() - min));
                sizeBar.setProgress(Math.max(0, Math.min(sizeBar.getMax(),
                        currentIconDp() - min)));
                sizeLabel.setText("图标大小：" + (sizeBar.getProgress() + min) + "dp");
                render();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        box.addView(colsTitle);
        box.addView(colsValue);
        box.addView(colsBar);
        box.addView(iconTitle);
        box.addView(sizeLabel);
        box.addView(sizeBar);

        // 半屏上方「非面板区域」的遮罩强度：越小越透明（看得清背后页面），越大越模糊
        if (panelMode) {
            final TextView blurLabel = new TextView(this);
            blurLabel.setTextSize(11);
            blurLabel.setTextColor(getColor(R.color.text_secondary));
            blurLabel.setPadding(0, Ui.dp(this, 12), 0, 0);

            final SeekBar blurBar = new SeekBar(this);
            blurBar.setMax(100);
            int blurPct = Math.max(0, Math.min(100, prefs().getInt(lk(KEY_BLUR), 25)));
            blurBar.setProgress(blurPct);
            blurLabel.setText("上方遮罩：" + blurPct + "%（越小越透明，越大越模糊）");
            blurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(lk(KEY_BLUR), p).apply();
                    blurLabel.setText("上方遮罩：" + p + "%（越小越透明，越大越模糊）");
                    applyBackdropStrength();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            box.addView(blurLabel);
            box.addView(blurBar);

            // 半屏面板本体：0=完全透明、50=最模糊、100=实心；只改面板自己，外面那层遮罩不动
            final TextView panelLabel = new TextView(this);
            panelLabel.setTextSize(11);
            panelLabel.setTextColor(getColor(R.color.text_secondary));
            panelLabel.setPadding(0, Ui.dp(this, 10), 0, 0);

            final SeekBar panelBar = new SeekBar(this);
            panelBar.setMax(100);
            int panelPct = Math.max(0, Math.min(100,
                    prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID)));
            panelBar.setProgress(panelPct);
            panelLabel.setText("半屏面板：" + panelPct + "%（0 全透明 · 50 模糊 · 100 实心）");
            panelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(lk(KEY_SURFACE), p).apply();
                    panelLabel.setText("半屏面板：" + p + "%（0 全透明 · 50 模糊 · 100 实心）");
                    applyPanelSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            // 底部雪花按钮底板：与面板同一套档位语义，值单独记忆
            final TextView fabAlphaLabel = new TextView(this);
            fabAlphaLabel.setTextSize(11);
            fabAlphaLabel.setTextColor(getColor(R.color.text_secondary));
            fabAlphaLabel.setPadding(0, Ui.dp(this, 10), 0, 0);

            final SeekBar fabAlphaBar = new SeekBar(this);
            fabAlphaBar.setMax(100);
            int fabAlphaPct = Math.max(0, Math.min(100,
                    prefs().getInt(lk(KEY_FAB_SURFACE), SURFACE_SOLID)));
            fabAlphaBar.setProgress(fabAlphaPct);
            fabAlphaLabel.setText("雪花按钮：" + fabAlphaPct + "%（0 全透明 · 50 模糊 · 100 实心）");
            fabAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(lk(KEY_FAB_SURFACE), p).apply();
                    fabAlphaLabel.setText("雪花按钮：" + p + "%（0 全透明 · 50 模糊 · 100 实心）");
                    applyFabSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            box.addView(panelLabel);
            box.addView(panelBar);
            box.addView(fabAlphaLabel);
            box.addView(fabAlphaBar);
        } else {
            // 全屏形态也补两条：全屏背景档位 + 雪花按钮底板档位。
            // 与半屏同一套语义（0 全透明 / 50 模糊 / 100 实心），键独立记忆，默认实心不变观感。
            final TextView fullBgLabel = new TextView(this);
            fullBgLabel.setTextSize(11);
            fullBgLabel.setTextColor(getColor(R.color.text_secondary));
            fullBgLabel.setPadding(0, Ui.dp(this, 12), 0, 0);

            final SeekBar fullBgBar = new SeekBar(this);
            fullBgBar.setMax(100);
            int fullBgPct = Math.max(0, Math.min(100,
                    prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID)));
            fullBgBar.setProgress(fullBgPct);
            fullBgLabel.setText("全屏背景：" + fullBgPct + "%（0 全透明 · 50 模糊 · 100 实心）");
            fullBgBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(KEY_FULL_SURFACE, p).apply();
                    fullBgLabel.setText("全屏背景：" + p + "%（0 全透明 · 50 模糊 · 100 实心）");
                    applyFullscreenSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            final TextView fullFabLabel = new TextView(this);
            fullFabLabel.setTextSize(11);
            fullFabLabel.setTextColor(getColor(R.color.text_secondary));
            fullFabLabel.setPadding(0, Ui.dp(this, 10), 0, 0);

            final SeekBar fullFabBar = new SeekBar(this);
            fullFabBar.setMax(100);
            int fullFabPct = Math.max(0, Math.min(100,
                    prefs().getInt(KEY_FULL_FAB_SURFACE, SURFACE_SOLID)));
            fullFabBar.setProgress(fullFabPct);
            fullFabLabel.setText("雪花按钮：" + fullFabPct + "%（0 全透明 · 50 模糊 · 100 实心）");
            fullFabBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(KEY_FULL_FAB_SURFACE, p).apply();
                    fullFabLabel.setText("雪花按钮：" + p + "%（0 全透明 · 50 模糊 · 100 实心）");
                    applyFullscreenFabSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            box.addView(fullBgLabel);
            box.addView(fullBgBar);
            box.addView(fullFabLabel);
            box.addView(fullFabBar);
        }

        // 搜索框颜色：独立档位，与背景档位解耦（列表选中项已不再画高亮框）
        final TextView searchLabel = new TextView(this);
        searchLabel.setTextSize(11);
        searchLabel.setTextColor(getColor(R.color.text_secondary));
        searchLabel.setPadding(0, Ui.dp(this, 10), 0, 0);

        final SeekBar searchBar = new SeekBar(this);
        searchBar.setMax(100);
        int searchStart = searchPct();
        searchBar.setProgress(searchStart);
        searchLabel.setText("搜索框颜色：" + searchStart + "%（0 全透明 · 100 实心）");
        searchBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                prefs().edit().putInt(lk(KEY_SEARCH_SURFACE), p).apply();
                searchLabel.setText("搜索框颜色：" + p + "%（0 全透明 · 100 实心）");
                applySearchSurface();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        box.addView(searchLabel);
        box.addView(searchBar);

        // 备份 / 恢复 / 旧版导入：放在所有开关与滑块之后，作为布局设置的最后入口
        TextView backupTitle = new TextView(this);
        backupTitle.setText("备份与恢复");
        backupTitle.setTextSize(12);
        backupTitle.setTextColor(getColor(R.color.text_primary));
        backupTitle.setPadding(0, Ui.dp(this, 12), 0, 0);

        TextView backupEntry = new TextView(this);
        backupEntry.setText("备份名单（复制到剪贴板）");
        backupEntry.setTextSize(12);
        backupEntry.setTextColor(getColor(R.color.blue));
        backupEntry.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        TextView restoreEntry = new TextView(this);
        restoreEntry.setText("恢复名单（粘贴导入）");
        restoreEntry.setTextSize(12);
        restoreEntry.setTextColor(getColor(R.color.blue));
        restoreEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));

        box.addView(backupTitle);
        box.addView(backupEntry);
        box.addView(restoreEntry);

        // 最底部：捐赠开发者（点开显示收款码，可保存到相册）
        TextView donateEntry = new TextView(this);
        donateEntry.setText("捐赠开发者");
        donateEntry.setTextSize(12);
        donateEntry.setTextColor(getColor(R.color.blue));
        donateEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        box.addView(donateEntry);

        // 自定义底板：宽度收窄、固定高度内滚动、真圆角裁切（全屏与半屏共用同一套尺寸）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 弹窗外层留出阴影绘制空间：圆角 outline + elevation，阴影跟着圆角走
        final int shadowPad = Ui.dp(this, 12);
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        root.setElevation(Ui.dp(this, night ? 18 : 10));

        // 标题行：右侧加一个高度调节入口，不挤占「完成」与关闭位置
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 2));
        TextView title = new TextView(this);
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        title.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 权限标识直接拼进标题文本：同一 TextView 天然同基线，读起来是一句话
        bindPrivilegeTitle(title);

        // 中间留白，把高度调节按钮推到最右
        header.addView(new View(this), new LinearLayout.LayoutParams(
                0, 1, 1f));
        final android.widget.ImageButton sizeToggle = new android.widget.ImageButton(this);
        sizeToggle.setImageResource(R.drawable.ic_chevron_down);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        sizeToggle.setBackgroundResource(ripple.resourceId);
        sizeToggle.setContentDescription("调节面板高度");
        sizeToggle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sizeToggle.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        header.addView(sizeToggle, new LinearLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34)));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(gestureHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 点右侧箭头才展开的高度滑块：只改弹窗自身高度，和半屏高度、坐标分开存
        LinearLayout heightRow = new LinearLayout(this);
        heightRow.setOrientation(LinearLayout.VERTICAL);
        heightRow.setPadding(pad, 0, pad, Ui.dp(this, 2));
        heightRow.setVisibility(View.GONE);
        final TextView openValue = Ui.text(this, "布局面板高度", 11, Ui.TEXT_SECONDARY, false);
        final SeekBar openBar = new SeekBar(this);
        final TextView expandValue = Ui.text(this, "展开高度", 11, Ui.TEXT_SECONDARY, false);
        final SeekBar expandBar = new SeekBar(this);
        expandValue.setPadding(0, Ui.dp(this, 6), 0, 0);
        heightRow.addView(openValue);
        heightRow.addView(openBar);
        heightRow.addView(expandValue);
        heightRow.addView(expandBar);
        root.addView(heightRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.ScrollView dialogScroll = new android.widget.ScrollView(this);
        dialogScroll.setClipToPadding(false);
        dialogScroll.setFillViewport(true);
        dialogScroll.addView(box, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(dialogScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(13);
        done.setTypeface(null, android.graphics.Typeface.BOLD);
        done.setTextColor(getColor(R.color.blue));
        done.setGravity(Gravity.CENTER);
        done.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        root.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 外层容器留出阴影空间：内容仍按圆角裁切，elevation 阴影沿圆角扩散
        FrameLayout shell = new FrameLayout(this);
        shell.setClipChildren(false);
        shell.setClipToPadding(false);
        shell.setPadding(shadowPad, shadowPad, shadowPad, shadowPad);
        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(shell);
        dialogRef[0] = dialog;
        done.setOnClickListener(v -> dialog.dismiss());
        backupEntry.setOnClickListener(v -> {
            dialog.dismiss();
            showBackup();
        });
        restoreEntry.setOnClickListener(v -> {
            dialog.dismiss();
            showRestore();
        });
        donateEntry.setOnClickListener(v -> {
            dialog.dismiss();
            showDonateDialog();
        });
        sizeToggle.setOnClickListener(v -> {
            boolean show = heightRow.getVisibility() != View.VISIBLE;
            heightRow.setVisibility(show ? View.VISIBLE : View.GONE);
            sizeToggle.setImageResource(show
                    ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
            // 展开按「展开高度」记忆值，收起回「布局面板高度」记忆值；不再回默认自适应
            applyDialogHeight(dialog, show ? expandDialogHeightPx() : openDialogHeightPx(), shadowPad);
        });
        dialog.setOnShowListener(d -> {
            android.view.Window w = dialog.getWindow();
            if (w == null) {
                return;
            }
            // 首帧兜底：万一参数在 show 前没生效，这里再压一次同样的值，避免露默认位
            applyDialogGeometry(dialog, w, root, title, heightRow, openBar, openValue, expandBar,
                    expandValue, defaultDialogWidth(), shadowPad);
        });
        // 在 show 之前就把坐标与高度写进窗口参数：第一帧直接落在记忆位置，不再先默认后跳
        android.view.Window pre = dialog.getWindow();
        if (pre != null) {
            applyDialogGeometry(dialog, pre, root, title, heightRow, openBar, openValue, expandBar,
                    expandValue, defaultDialogWidth(), shadowPad);
        }
        dialog.show();
        // 弹窗内容比可视区高，防止某个控件抢焦点把滚动区带到底部
        dialogScroll.post(() -> dialogScroll.scrollTo(0, 0));
    }

    /** 捐赠开发者：整图按屏幕宽度 65% 展示，圆角卡片承载，可保存到相册。 */
    private void showDonateDialog() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        final int imgW = Math.round(screenW * 0.65f);
        int cardPad = Ui.dp(this, 14);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPad, cardPad, cardPad, Ui.dp(this, 4));
        card.setBackgroundResource(R.drawable.bg_dialog);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        Bitmap code = decodeDonate();
        if (code != null) {
            int imgH = Math.round(imgW * code.getHeight() / (float) code.getWidth());
            image.setImageBitmap(Bitmap.createScaledBitmap(code, imgW, imgH, true));
        }
        android.graphics.drawable.GradientDrawable round =
                new android.graphics.drawable.GradientDrawable();
        round.setColor(getColor(R.color.card_bg));
        round.setCornerRadius(Ui.dp(this, 10));
        image.setBackground(round);
        image.setClipToOutline(true);
        card.addView(image, new LinearLayout.LayoutParams(
                imgW, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dp(this, 4), 0, 0);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(getColor(R.color.text_primary));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        TextView save = new TextView(this);
        save.setText("保存");
        save.setTextSize(14);
        save.setTypeface(null, android.graphics.Typeface.BOLD);
        save.setTextColor(getColor(R.color.blue));
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        actions.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(save, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(card);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
        }
        cancel.setOnClickListener(v -> d.dismiss());
        save.setOnClickListener(v -> saveDonateImage());
        d.show();
        if (w != null) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = imgW + cardPad * 2;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
    }

    /** 原图按 nodpi 解析，避免被系统按屏幕密度重采样糊掉。 */
    private Bitmap decodeDonate() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        return BitmapFactory.decodeResource(getResources(), R.drawable.donate_wx, o);
    }

    private void saveDonateImage() {
        final Bitmap bmp = decodeDonate();
        if (bmp == null) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        IO.execute(() -> {
            String msg;
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME,
                            "jin_donate_" + System.currentTimeMillis() + ".png");
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    v.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/凚");
                    Uri uri = getContentResolver()
                            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    java.io.OutputStream os = uri == null ? null
                            : getContentResolver().openOutputStream(uri);
                    if (os == null) {
                        throw new java.io.IOException("无法写入相册");
                    }
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    os.close();
                    msg = "已保存到相册 Pictures/凚";
                } else {
                    java.io.File dir = new java.io.File(Environment
                            .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "凚");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new java.io.IOException("无法创建目录");
                    }
                    java.io.File f = new java.io.File(dir,
                            "jin_donate_" + System.currentTimeMillis() + ".png");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    msg = "已保存到 " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                msg = "保存失败：" + e.getMessage();
            }
            final String out = msg;
            runOnUiThread(() -> Toast.makeText(this, out, Toast.LENGTH_SHORT).show());
        });
    }

    private int defaultDialogWidth() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        return Math.min(screenW - Ui.dp(this, 104), Ui.dp(this, 304));
    }

    /** 把记忆坐标、记忆高度一次性写进窗口参数；show 前调用即首帧到位，show 后再调一次做兜底。 */
    private void applyDialogGeometry(android.app.Dialog dialog, android.view.Window w,
                                     final View root, final View title, final View heightRow,
                                     final SeekBar openBar, final TextView openValue,
                                     final SeekBar expandBar, final TextView expandValue,
                                     int width, int shadowPad) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        int insetDp = Math.round((screenW - width) / density);
        final int defaultX = (screenW - width) / 2 - shadowPad;
        final int defaultY = Ui.dp(this, 8) - shadowPad;
        // 点开时按「布局面板高度」记忆值，且绝不覆盖半屏面板
        int height = openDialogHeightPx();

        Ui.roundDialog(dialog, root, insetDp, height);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = width + shadowPad * 2;
        lp.height = height + shadowPad * 2;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = clampInt(defaultX + prefs().getInt(lk(KEY_LAYOUT_DX), 0),
                -shadowPad, Math.max(-shadowPad, screenW - width - shadowPad));
        lp.y = clampInt(defaultY + prefs().getInt(lk(KEY_LAYOUT_DY), 0),
                -shadowPad, Math.max(-shadowPad, screenH - height - shadowPad));
        w.setAttributes(lp);

        // 滑块 1：布局面板高度 0–520dp，记忆下次点开布局设置时的高度，超出可用空间自动收窄
        openBar.setMax(LAYOUT_OPEN_MAX_DP);
        openBar.setProgress(openDialogDp());
        openValue.setText(heightLabel("布局面板高度", openDialogDp(), clampedOpenHeightPx(openDialogDp())));
        openBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int dp = clampInt(p, 0, LAYOUT_OPEN_MAX_DP);
                prefs().edit().putInt(lk(KEY_LAYOUT_OPEN_H), dp).apply();
                openValue.setText(heightLabel("布局面板高度", dp, clampedOpenHeightPx(dp)));
                // 正在展开状态时不要被收起档位打断，等收起再落到这个高度
                if (heightRow.getVisibility() != View.VISIBLE) {
                    applyDialogHeight(dialog, clampedOpenHeightPx(dp), shadowPad);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        // 滑块 2：展开高度 0–666dp，记忆点右上角倒三角时的高度，允许覆盖半屏
        expandBar.setMax(LAYOUT_EXPAND_MAX_DP);
        expandBar.setProgress(expandDialogDp());
        expandValue.setText(heightLabel("展开高度", expandDialogDp(), clampedExpandHeightPx(expandDialogDp())));
        expandBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int dp = clampInt(p, 0, LAYOUT_EXPAND_MAX_DP);
                prefs().edit().putInt(lk(KEY_LAYOUT_EXPAND_H), dp).apply();
                expandValue.setText(heightLabel("展开高度", dp, clampedExpandHeightPx(dp)));
                applyDialogHeight(dialog, clampedExpandHeightPx(dp), shadowPad);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        attachDialogDrag(w, title, defaultX, defaultY, width, height, shadowPad);
    }

    /** 「布局面板高度」记忆值（dp）：升级后首次读取时从旧版单一高度键迁移。 */
    private int openDialogDp() {
        String key = lk(KEY_LAYOUT_OPEN_H);
        if (!prefs().contains(key)) {
            int legacy = prefs().getInt(lk(KEY_LAYOUT_H_LEGACY), -1);
            return clampInt(legacy >= 0 ? legacy : LAYOUT_DIALOG_DEFAULT_DP, 0, LAYOUT_OPEN_MAX_DP);
        }
        return clampInt(prefs().getInt(key, LAYOUT_DIALOG_DEFAULT_DP), 0, LAYOUT_OPEN_MAX_DP);
    }

    /** 「展开高度」记忆值（dp）。 */
    private int expandDialogDp() {
        return clampInt(prefs().getInt(lk(KEY_LAYOUT_EXPAND_H), LAYOUT_DIALOG_DEFAULT_DP),
                0, LAYOUT_EXPAND_MAX_DP);
    }

    /** 点开布局设置时的高度（px）：取「布局面板高度」记忆值，且绝不覆盖半屏界面。 */
    private int openDialogHeightPx() {
        return clampedOpenHeightPx(openDialogDp());
    }

    /** 点 ▼ 展开时的高度（px）：取「展开高度」记忆值，允许覆盖半屏区域。 */
    private int expandDialogHeightPx() {
        return clampedExpandHeightPx(expandDialogDp());
    }

    /** 「布局面板高度」实际可用高度：上下限同时夹紧，确保不越界、不压半屏。 */
    private int clampedOpenHeightPx(int dp) {
        int max = openHeightMaxPx();
        return clampInt(Ui.dp(this, dp), Math.min(Ui.dp(this, 170), max), max);
    }

    /** 「展开高度」实际可用高度：允许覆盖半屏，只受屏幕与 666dp 限制。 */
    private int clampedExpandHeightPx(int dp) {
        int max = expandHeightMaxPx();
        return clampInt(Ui.dp(this, dp), Math.min(Ui.dp(this, 170), max), max);
    }

    /** 滑块标签：实际高度被可用空间收窄时，把实际值一并标出来。 */
    private String heightLabel(String name, int dp, int appliedPx) {
        int appliedDp = Math.round(appliedPx / getResources().getDisplayMetrics().density);
        return name + "：" + dp + "dp" + (appliedDp != dp ? "（实际 " + appliedDp + "dp）" : "");
    }

    /** 布局面板高度上限：半屏形态停半屏面板上沿之上，绝不允许覆盖半屏。 */
    private int openHeightMaxPx() {
        int pad = Ui.dp(this, 12);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int max = screenH - statusBarHeightPx() - pad * 2 - Ui.dp(this, 8);
        if (panelMode) {
            View panel = findViewById(R.id.panel);
            if (panel != null) {
                int[] loc = new int[2];
                panel.getLocationOnScreen(loc);
                max = Math.min(max, loc[1] - statusBarHeightPx() - Ui.dp(this, 16));
            }
        }
        max = Math.min(max, Ui.dp(this, LAYOUT_OPEN_MAX_DP));
        return Math.max(0, max);
    }

    /** 展开高度上限：允许覆盖半屏区域，只受屏幕可用高度与 666dp 限制。 */
    private int expandHeightMaxPx() {
        int pad = Ui.dp(this, 12);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int max = screenH - statusBarHeightPx() - pad * 2 - Ui.dp(this, 8);
        max = Math.min(max, Ui.dp(this, LAYOUT_EXPAND_MAX_DP));
        return Math.max(0, max);
    }

    /** 只改布局弹窗高度，坐标不动；变高后别顶出屏幕下沿。 */
    private void applyDialogHeight(android.app.Dialog dialog, int heightPx, int shadowPad) {
        android.view.Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.height = heightPx + shadowPad * 2;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (lp.y + lp.height > screenH) {
            lp.y = Math.max(-shadowPad, screenH - lp.height);
        }
        w.setAttributes(lp);
    }

    /** 长按标题 300ms 后可拖动弹窗；松手把相对默认位置的偏移单独落盘，不动半屏高度。 */
    private void attachDialogDrag(final android.view.Window w, final View handle,
                                  final int baseX, final int baseY,
                                  final int width, final int height, final int pad) {
        final Handler h = new Handler(Looper.getMainLooper());
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;
            private boolean dragging;
            private final Runnable activate = () -> {
                dragging = true;
                // 与长按图标出操作单同一档震动，只在真正进入移动状态时震一次
                handle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                // 进入移动状态的教程提示：停留久一点，够看清；落下后不再提示
                Toast.makeText(FreezeListActivity.this,
                        "长按布局移动位置，点击 ▼ 调节高度", Toast.LENGTH_LONG).show();
                handle.setAlpha(0.55f);
            };

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        dragging = false;
                        h.postDelayed(activate, 300);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!dragging) {
                            if (Math.abs(e.getRawX() - downRawX) > Ui.dp(FreezeListActivity.this, 16)
                                    || Math.abs(e.getRawY() - downRawY)
                                    > Ui.dp(FreezeListActivity.this, 16)) {
                                h.removeCallbacks(activate);
                            }
                            return true;
                        }
                        int screenW = getResources().getDisplayMetrics().widthPixels;
                        int screenH = getResources().getDisplayMetrics().heightPixels;
                        int curH = w.getAttributes().height;
                        lp.x = clampInt(startX + Math.round(e.getRawX() - downRawX),
                                -pad, Math.max(-pad, screenW - width - pad));
                        lp.y = clampInt(startY + Math.round(e.getRawY() - downRawY),
                                -pad, Math.max(-pad, screenH - curH - pad));
                        w.setAttributes(lp);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        h.removeCallbacks(activate);
                        if (dragging) {
                            handle.setAlpha(1f);
                            prefs().edit()
                                    .putInt(lk(KEY_LAYOUT_DX), lp.x - baseX)
                                    .putInt(lk(KEY_LAYOUT_DY), lp.y - baseY)
                                    .apply();
                        } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            // 未进入拖动就是一次点击：手动补 performClick，权限那段的点击仍可用
                            handle.performClick();
                        }
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private int statusBarHeightPx() {
        // 部分 ROM 取不到 status_bar_height 资源，优先用窗口 insets，保证几何计算不落到 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.view.WindowInsets ins = getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    return ins.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                }
                return ins.getSystemWindowInsetTop();
            }
        }
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : Ui.dp(this, 24);
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 备份：按「用户应用 / 系统应用」分组导出，组内一行一个包名，顺序与列表一致。 */
    private void showBackup() {
        PackageManager pm = getPackageManager();
        StringBuilder user = new StringBuilder();
        StringBuilder sys = new StringBuilder();
        for (Item it : items) {
            // 归属以 PackageManager 的 FLAG_SYSTEM 为准，不靠名字猜
            boolean system = it.system;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(it.pkg, 0);
                system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (Exception ignored) {
            }
            StringBuilder sb = system ? sys : user;
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(it.pkg);
        }
        StringBuilder text = new StringBuilder("[用户应用]");
        if (user.length() > 0) {
            text.append('\n').append(user);
        }
        text.append('\n').append("[系统应用]");
        if (sys.length() > 0) {
            text.append('\n').append(sys);
        }
        // 设置区：标签名、布局、滑动开关、全屏模式都跟着备份走
        StringBuilder settings = new StringBuilder();
        Map<String, ?> all = prefs().getAll();
        for (String key : BACKUP_SETTING_KEYS) {
            Object v = all.get(key);
            if (v == null || v instanceof Set) {
                continue;
            }
            String s = String.valueOf(v);
            if (s.contains("\n")) {
                continue;
            }
            settings.append('\n').append(key).append('=').append(s);
        }
        if (settings.length() > 0) {
            text.append('\n').append("[设置]").append(settings);
        }
        final String body = text.toString();
        TextView view = new TextView(this);
        view.setText(body);
        view.setTextIsSelectable(true);
        view.setTextSize(13);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(view);
        new AlertDialog.Builder(this)
                .setTitle("备份名单（" + items.size() + " 个）")
                .setView(sv)
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("冻结名单", body));
                    Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 恢复：兼容分组格式与旧版纯包名列表，逐行追加到名单末尾。 */
    private void showRestore() {
        EditText input = new EditText(this);
        input.setHint("粘贴备份内容，例如：\n[用户应用]\ncom.example.app\n[系统应用]\ncom.android.xxx");
        input.setTextSize(13);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(6);
        input.setMaxLines(12);
        input.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("恢复名单")
                .setView(sv)
                .setPositiveButton("导入", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        // 手动接管「导入」：校验失败/正在操作时不关闭弹窗
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                doImport(input.getText().toString(), dialog));
    }

    // ---------------- 旧版迁移 / 权限 ----------------

    /** 首启迁移提示：只在检测到旧版、且当前名单为空时弹一次。 */
    private void maybeOfferMigration() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (prefs().getBoolean("legacy_offer_done", false)) {
            return;
        }
        if (!LegacyMigrate.installed(this) || !items.isEmpty()) {
            return;
        }
        prefs().edit().putBoolean("legacy_offer_done", true).apply();
        offerMigration(false);
    }

    private void offerMigration(boolean manual) {
        if (!LegacyMigrate.installed(this)) {
            if (manual) {
                Toast.makeText(this, "没有检测到旧版「搞机助手」", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("从旧版导入")
                .setMessage("检测到旧版「搞机助手」的冻结名单，可一次导入名单、标签与布局设置。\n\n"
                        + "旧数据在对方私有目录，读取需要 root 或已授权的 Shizuku（root 模式）。")
                .setPositiveButton("立即导入", (d, w) -> runMigration())
                .setNegativeButton("稍后", null)
                .show();
    }

    private void runMigration() {
        Toast.makeText(this, "正在读取旧版数据…", Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            LegacyMigrate.Outcome outcome = LegacyMigrate.readAndImport(this);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (outcome.error != null) {
                    new AlertDialog.Builder(this)
                            .setTitle("导入失败")
                            .setMessage(outcome.error
                                    + "\n\n也可以让旧版「搞机助手」导出备份，再用「恢复名单」粘贴导入。")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                reload();
                if (outcome.count > 0) {
                    Toast.makeText(this, "已导入 " + outcome.count + " 个应用",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** 布局设置顶部的权限行：显示 root / Shizuku 状态，未授权时可点开授权。 */
    /** 标题整句：「布局（冻结权限：Root）」，括号内小一号、次要色、非加粗。 */
    private void bindPrivilegeTitle(TextView view) {
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            String head = "布局";
            String tail = "（冻结权限：" + privilegeValue() + "）";
            android.text.SpannableStringBuilder sb =
                    new android.text.SpannableStringBuilder(head + tail);
            int start = head.length();
            int end = sb.length();
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.73f), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(
                            getColor(R.color.text_secondary)), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.NORMAL), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(sb);
        };
        refresh[0].run();
        IO.execute(() -> {
            PrivShell.mode();
            main.post(refresh[0]);
        });
        view.setOnClickListener(v -> handlePrivilegeClick(refresh[0]));
    }

    /** 权限值：Root / Shizuku / 未授权，检测中单独一档。 */
    private String privilegeValue() {
        PrivShell.Mode mode = PrivShell.peek();
        if (mode == null) {
            return "检测中";
        }
        if (mode == PrivShell.Mode.ROOT) {
            return "Root";
        }
        if (mode == PrivShell.Mode.SHIZUKU) {
            return "Shizuku";
        }
        return "未授权";
    }

    private void handlePrivilegeClick(Runnable refresh) {
        PrivShell.Mode mode = PrivShell.peek();
        if (mode == PrivShell.Mode.ROOT) {
            Toast.makeText(this, "已通过 root 执行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mode == PrivShell.Mode.SHIZUKU) {
            Toast.makeText(this, "已通过 Shizuku 执行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ShizukuGate.installed(this)) {
            showPrivilegeMissingDialog();
            return;
        }
        ShizukuGate.request(this, () -> {
            PrivShell.reset();
            IO.execute(() -> {
                PrivShell.mode();
                main.post(refresh);
            });
        });
    }

    /**
     * 未授权提示：布局设置的权限行与启动检查共用同一个弹窗。
     * 圆角（16dp 裁切）+ 收窄宽度 + 紧凑内边距，标题「您未授权于凚」，下面保留原来的说明小字。
     */
    private void showPrivilegeMissingDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (privDialog != null && privDialog.isShowing()) {
            return;
        }
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = Ui.dp(this, 18);
        root.setPadding(padH, Ui.dp(this, 16), padH, Ui.dp(this, 4));

        TextView title = new TextView(this);
        title.setText("您未授权于凚");
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText("未获得 root，也未安装 Shizuku。\n\n"
                + "安装并启动 Shizuku 后回到这里点击授权即可；"
                + "若设备已有 root，请确认已允许本应用使用超级用户权限。");
        message.setTextSize(12);
        message.setLineSpacing(Ui.dp(this, 2), 1f);
        message.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 8);
        root.addView(message, mlp);

        TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextSize(13);
        ok.setTypeface(null, android.graphics.Typeface.BOLD);
        ok.setTextColor(getColor(R.color.blue));
        ok.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ok.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), 0, Ui.dp(this, 8));
        ok.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Ui.dp(this, 2);
        root.addView(ok, olp);

        dialog.setContentView(root);
        // 首帧就按圆角收窄落位，onShow 再兜一次，避免先出大块默认底再跳
        int insetDp = 92;
        Ui.roundDialog(dialog, root, insetDp);
        dialog.setOnShowListener(d -> Ui.roundDialog(dialog, root, insetDp));
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setDimAmount(0.4f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.CENTER);
        }
        privDialog = dialog;
        dialog.show();
    }

    /** 入口带的一次性启动检查标记：消费掉，避免重建时重复探测。 */
    private boolean consumeCheckRequest() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA_CHECK_PRIV, false)) {
            return false;
        }
        intent.removeExtra(EXTRA_CHECK_PRIV);
        return true;
    }

    /** 启动检查：root 与 Shizuku 都不可用时弹统一提示；有任一通道则静默通过。 */
    private void checkPrivilegeOnStart() {
        IO.execute(() -> {
            PrivShell.Mode mode = PrivShell.mode();
            if (mode == PrivShell.Mode.NONE) {
                main.post(this::showPrivilegeMissingDialog);
            }
        });
    }

    /** 备份文本里随名单一起带走的设置键（不含包名、顺序这类多行数据）。 */
    private static final String[] BACKUP_SETTING_KEYS = {
            "tabs", "app_tabs", "current_tab", "custom_order",
            "grid_columns", "grid_icon_dp", "grid_icon_manual", "grid_show_name",
            "grid_move_mode", "fab_dx", "fab_dy", "fab_size", "show_frozen_toast",
            "panel_height_dp", "panel_handle_enabled", "panel_hide_recents",
            "panel_dismiss_after_freeze", "panel_grid_columns", "panel_grid_icon_dp",
            "panel_grid_icon_manual", "panel_grid_show_name", "panel_grid_move_mode",
            "panel_fab_dx", "panel_fab_dy", "panel_fab_size", "panel_show_frozen_toast",
            "tab_swipe", "panel_tab_swipe", "fullscreen_open", "panel_backdrop_blur",
            "hide_recents", "dismiss_after_freeze",
            KEY_LAYOUT_DX, KEY_LAYOUT_DY, KEY_LAYOUT_OPEN_H, KEY_LAYOUT_EXPAND_H,
            KEY_LAYOUT_H_LEGACY,
            "panel_" + KEY_LAYOUT_DX, "panel_" + KEY_LAYOUT_DY,
            "panel_" + KEY_LAYOUT_OPEN_H, "panel_" + KEY_LAYOUT_EXPAND_H,
            "panel_" + KEY_LAYOUT_H_LEGACY,
            "panel_surface", "panel_fab_surface",
            KEY_FULL_SURFACE, KEY_FULL_FAB_SURFACE,
            KEY_SEARCH_SURFACE, "panel_" + KEY_SEARCH_SURFACE
    };

    private void doImport(String raw, AlertDialog dialog) {
        Set<String> cur = pkgs();
        PackageManager pm = getPackageManager();
        List<String> added = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int dup = 0;
        int missing = 0;
        int prot = 0;
        boolean inSettings = false;
        final boolean[] settingsApplied = {false};
        for (String line : raw.split("\n")) {
            String pkg = line.trim();
            if (pkg.isEmpty()) {
                continue;
            }
            if ("[设置]".equals(pkg)) {
                inSettings = true;
                continue;
            }
            if (pkg.startsWith("[") && pkg.endsWith("]")) {
                // 分组标题：归属不依赖标题，导入后按 FLAG_SYSTEM 现场判定
                inSettings = false;
                continue;
            }
            if (inSettings) {
                int eq = pkg.indexOf('=');
                if (eq > 0) {
                    String key = pkg.substring(0, eq).trim();
                    String value = pkg.substring(eq + 1).trim();
                    for (String known : BACKUP_SETTING_KEYS) {
                        if (known.equals(key)) {
                            applyBackupSetting(key, value);
                            settingsApplied[0] = true;
                            break;
                        }
                    }
                }
                continue;
            }
            if (cur.contains(pkg) || !seen.add(pkg)) {
                dup++;
                continue;
            }
            String label;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai).toString();
            } catch (Exception e) {
                missing++;
                continue;
            }
            if (com.jin.freeze.core.CoreProtect.isProtected(this, pkg, label)) {
                prot++;
                continue;
            }
            added.add(pkg);
        }
        if (!added.isEmpty()) {
            Set<String> next = new HashSet<>(cur);
            next.addAll(added);
            // 原有顺序不变，导入项按文件里的先后追加到末尾，保证每个分类里的顺序都能还原
            List<String> merged = new ArrayList<>();
            for (Item it : items) {
                if (next.contains(it.pkg) && !merged.contains(it.pkg)) {
                    merged.add(it.pkg);
                }
            }
            for (String p : added) {
                if (!merged.contains(p)) {
                    merged.add(p);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String p : merged) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p);
            }
            prefs().edit().putStringSet(KEY_PKGS, next)
                    .putString(KEY_ORDER, sb.toString())
                    .putBoolean(KEY_CUSTOM, true)
                    .apply();
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        StringBuilder msg = new StringBuilder();
        msg.append("成功导入 ").append(added.size()).append(" 个");
        int skipped = dup + missing + prot;
        if (skipped > 0) {
            msg.append("，跳过 ").append(skipped).append(" 个：");
            if (dup > 0) {
                msg.append("\n· 已在名单 ").append(dup).append(" 个");
            }
            if (missing > 0) {
                msg.append("\n· 未安装 ").append(missing).append(" 个");
            }
            if (prot > 0) {
                msg.append("\n· 受核心保护 ").append(prot).append(" 个");
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("恢复完成")
                .setMessage(msg.toString())
                .setPositiveButton("知道了", (d, w) -> {
                    if (settingsApplied[0]) {
                        // 布局参数在 onCreate 读取，重建一次让导入的设置立即生效
                        recreate();
                    } else {
                        reload();
                    }
                })
                .show();
    }

    /** 按原类型写回备份里的设置值：布尔 / 整数 / 字符串。 */
    private void applyBackupSetting(String key, String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            prefs().edit().putBoolean(key, Boolean.parseBoolean(value)).apply();
        } else if (value.matches("-?\\d+")) {
            try {
                prefs().edit().putInt(key, Integer.parseInt(value)).apply();
            } catch (NumberFormatException e) {
                prefs().edit().putString(key, value).apply();
            }
        } else {
            prefs().edit().putString(key, value).apply();
        }
    }

    private void removeNow(Item it) {
        Set<String> cur = pkgs();
        cur.remove(it.pkg);
        prefs().edit().putStringSet(KEY_PKGS, cur).apply();
        items.remove(it);
        if (customOrder()) {
            persistOrder();
        }
        render();
        // 移出名单后顺手解冻；成功静默，失败给出 pm 真实原因（不把「已移出」当成功）
        final String pkg = it.pkg;
        final String label = it.label;
        IO.execute(() -> {
            PrivShell.Result r = PrivShell.exec("pm enable " + pkg, 20);
            String out = r.out == null ? "" : r.out.trim();
            boolean ok = out.contains("new state:");
            if (!ok) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("已移出名单，但解冻失败")
                            .setMessage("「" + label + "」\n" + (out.isEmpty() ? "pm 未返回输出" : out))
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        });
    }

    private void runBatch(boolean freeze, List<Item> shown) {
        if (working) {
            return;
        }
        final List<String> targets = new ArrayList<>();
        int protectedCount = 0;
        int skippedCount = 0;
        for (Item it : shown) {
            // 已经到位的直接跳过，不发起 pm 调用，也不算失败
            if (freeze == it.frozen) {
                skippedCount++;
                continue;
            }
            if (freeze && com.jin.freeze.core.CoreProtect.isProtected(this, it.pkg, it.label)) {
                protectedCount++;
            } else {
                targets.add(it.pkg);
            }
        }
        final int protectedFinal = protectedCount;
        final int skippedFinal = skippedCount;
        if (targets.isEmpty()) {
            // 全部已到位：静默无事可做；开了「冻结后收起列表」就直接收起回原应用，不弹提示
            if (skippedCount > 0 && protectedCount == 0) {
                if (panelDismissAfterFreeze) {
                    finishAndRemoveTask();
                    overridePendingTransition(0, 0);
                }
                return;
            }
            String msg;
            if (protectedCount > 0 && skippedCount == 0) {
                msg = protectedCount + " 个应用受核心保护，已跳过";
            } else if (skippedCount > 0) {
                msg = "没有需要处理的应用（" + skippedCount + " 个已到位，"
                        + protectedCount + " 个受保护）";
            } else {
                msg = "当前筛选下没有应用";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return;
        }
        working = true;
        if (freeze) {
            startFreezeSpin();
        }
        render();
        if (count != null) {
            count.setText((freeze ? "正在冻结 " : "正在解冻 ") + targets.size() + " 个应用…");
        }
        IO.execute(() -> {
            // 只开一次 su、一条脚本跑完全部包；处理完统一回读一次状态
            long startedAt = System.currentTimeMillis();
            Map<String, String> outputs = execPmBatch(targets, freeze);
            android.util.Log.i("FreezeBatch", (freeze ? "freeze " : "unfreeze ")
                    + targets.size() + " pkg took " + (System.currentTimeMillis() - startedAt) + " ms");
            int okCount = 0;
            List<String> fails = new ArrayList<>();
            for (String pkg : targets) {
                String out = outputs.get(pkg);
                if (out == null) {
                    out = "pm 未返回该包的结果";
                }
                if (isPmOk(out)) {
                    okCount++;
                } else {
                    fails.add(labelOf(pkg) + "（" + pkg + "）\n" + out);
                }
            }
            final int okFinal = okCount;
            main.post(() -> {
                stopFreezeSpin();
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // 全部成功时完全静默，只靠图标灰度变化体现结果
                if (!fails.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("失败 ").append(fails.size()).append(" 个");
                    if (skippedFinal > 0) {
                        sb.append("（另有 ").append(skippedFinal).append(" 个无需处理）");
                    }
                    if (protectedFinal > 0) {
                        sb.append("（另有 ").append(protectedFinal).append(" 个受核心保护已跳过）");
                    }
                    for (String f : fails) {
                        sb.append("\n\n").append(f);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(freeze ? "冻结失败" : "解冻失败")
                            .setMessage(sb.toString())
                            .setPositiveButton("知道了", null)
                            .show();
                } else if (protectedFinal > 0) {
                    Toast.makeText(this, protectedFinal + " 个应用受核心保护，已跳过",
                            Toast.LENGTH_SHORT).show();
                }
                // 冻结且没有失败项时，按设置在本轮刷新完成后收起列表
                if (freeze && fails.isEmpty()) {
                    if (showFrozenTip) {
                        Toast.makeText(this, "已冻结", Toast.LENGTH_SHORT).show();
                    }
                    if (panelDismissAfterFreeze) {
                        dismissAfterReload = true;
                    }
                }
                // 执行完按系统真值重新读；自定义顺序保持不变，只更新灰度
                reload();
            });
        });
    }

    /** 一条 su 脚本批量执行 pm：每包打一行标记，输出里按标记切分每个包的真实结果。 */
    private Map<String, String> execPmBatch(List<String> pkgs, boolean freeze) {
        final String mark = "@@PKG@@";
        StringBuilder script = new StringBuilder();
        for (String pkg : pkgs) {
            script.append("echo '").append(mark).append(pkg).append("'; ")
                    .append(freeze ? "pm disable-user --user 0 " : "pm enable ")
                    .append(pkg).append(" 2>&1; ");
        }
        PrivShell.Result r = PrivShell.exec(script.toString(), Math.max(30, 10 + pkgs.size() * 5));
        Map<String, String> result = new HashMap<>();
        String current = null;
        StringBuilder buf = null;
        for (String line : (r.out == null ? "" : r.out).split("\n")) {
            if (line.startsWith(mark)) {
                if (current != null && buf != null) {
                    result.put(current, buf.toString().trim());
                }
                current = line.substring(mark.length()).trim();
                buf = new StringBuilder();
            } else if (buf != null) {
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(line);
            }
        }
        if (current != null && buf != null) {
            result.put(current, buf.toString().trim());
        }
        if (r.timeout) {
            for (String pkg : pkgs) {
                String v = result.get(pkg);
                result.put(pkg, v == null || v.isEmpty()
                        ? "执行超时（" + r.code + "）" : v);
            }
        }
        return result;
    }

    private String labelOf(String pkg) {
        for (Item it : items) {
            if (it.pkg.equals(pkg)) {
                return it.label;
            }
        }
        return pkg;
    }

    /** 冻结的应用点击后先解冻再打开；失败给出 pm 的真实报错。成功路径完全静默。 */
    private void unfreezeAndOpen(Item it) {
        if (working) {
            return;
        }
        working = true;
        IO.execute(() -> {
            String out = execPm(it.pkg, false);
            boolean ok = isPmOk(out);
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    new AlertDialog.Builder(this)
                            .setTitle("解冻失败，无法打开")
                            .setMessage(it.pkg + "\n\n" + out)
                            .setPositiveButton("知道了", null)
                            .show();
                    reload();
                    return;
                }
                it.frozen = false;
                rebindCells();
                launch(it);
                reload();
            });
        });
    }

    private void launch(Item it) {
        Intent i = getPackageManager().getLaunchIntentForPackage(it.pkg);
        if (i == null) {
            Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
            return;
        }
        // 面板里主动打开应用：正常离场，面板行为交给系统默认
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

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
}
