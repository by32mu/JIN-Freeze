package com.jin.freeze.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.jin.freeze.R;
import com.jin.freeze.JinApp;
import com.jin.freeze.core.AppIconCache;
import com.jin.freeze.core.LaunchTarget;
import com.jin.freeze.core.PrivShell;
import com.jin.freeze.core.ShizukuGate;
import com.jin.freeze.core.SuspendDialog;
import com.jin.freeze.core.SuspendSupport;
import com.jin.freeze.core.SwipeFreeze;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 冻结列表：用户自选的名单，支持一键冻结/解冻整份名单。
 * 冻结状态一律以 pm 的系统真值为准；点击图标直接打开（冻结的先解冻再开）。
 * 长按图标拖拽调整顺序（自定义顺序持久化）；编辑模式下点减号移出名单。
 */
public class FreezeListActivity extends BaseActivity {

    private static final String PREFS = "freeze_list";
    private static final String KEY_PKGS = "pkgs";

    private static String instanceKey(String pkg, int userId) {
        return pkg + "@" + userId;
    }

    /** 落盘用的键：主用户沿用纯包名（兼容旧名单），分身才带「@用户」后缀。 */
    private static String storageKey(String pkg, int userId) {
        return userId == 0 ? pkg : instanceKey(pkg, userId);
    }

    private static String packagePart(String key) {
        int at = key.lastIndexOf('@');
        return at > 0 ? key.substring(0, at) : key;
    }

    private static int userIdPart(String key) {
        int at = key.lastIndexOf('@');
        if (at < 0) return 0;
        try { return Integer.parseInt(key.substring(at + 1)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private static final String KEY_ORDER = "order";
    private static final String KEY_CUSTOM = "custom_order";
    private static final int COLUMNS = 5;
    /** 半屏内容区默认高度（dp，不含把手）：按当前版本实测值固化，新装/恢复默认都用它。 */
    private static final int PANEL_CONTENT_DEFAULT_DP = 544;
    /** 布局设置弹窗默认高度（dp）：新装/重置取 520dp，手动调过的沿用记忆值。 */
    private static final int LAYOUT_DIALOG_DEFAULT_DP = 520;
    /** 自定义布局：列数 + 图标大小，间距由列宽自动均分。 */
    private static final String KEY_COLUMNS = "grid_columns";
    private static final String KEY_ICON_DP = "grid_icon_dp";
    private static final String KEY_ICON_MANUAL = "grid_icon_manual";
    private static final String KEY_SHOW_NAME = "grid_show_name";
    /** 半屏专属：勾选后每个图标右上角常驻红色减号，点它即把该应用移出名单。 */
    private static final String KEY_PANEL_QUICK_REMOVE = "panel_quick_remove";
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
    /** 半屏形态的一键冻结按钮出厂值：直径 38dp、离底 5dp、底板不透明度 60%。 */
    private static final int PANEL_FAB_SIZE_DEFAULT = 38;
    private static final int PANEL_FAB_DY_DEFAULT = 5;
    private static final int PANEL_FAB_SURFACE_DEFAULT = 60;
    /** 全屏形态「距底部」出厂值：首次安装或恢复默认时取 18dp。 */
    private static final int FAB_DY_DEFAULT = 18;
    /** 标签：内置「用户应用 / 系统应用」+ 自定义；顺序、显隐、名称与每个应用的归属都落盘。 */
    private static final String KEY_TABS = "tabs";
    private static final String KEY_APP_TABS = "app_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";
    /** 默认标签：进入冻结列表时优先停在这里；空表示没设置，按顺序落到第一个可见标签。 */
    private static final String KEY_DEFAULT_TAB = "default_tab";
    /** 是否允许在列表区域左右滑动切换标签；关掉后只能点标签。 */
    private static final String KEY_TAB_SWIPE = "tab_swipe";
    /** 隐藏标签文字：只收起标签名那一排，列表仍显示当前勾选的标签；仅勾选数为 1 时可用。 */
    private static final String KEY_HIDE_TAB_ROW = "hide_tab_row";
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
    private static final int LAYOUT_EXPAND_MAX_DP = 888;
    /** 半屏顶部小白条开关：关掉后隐藏把手、高度沿用记住的值。 */
    private static final String KEY_PANEL_HANDLE = "panel_handle_enabled";
    /** 冻结成功后是否自动收起列表（全屏/半屏各自记忆，回到进入前的应用）。 */
    private static final String KEY_PANEL_DISMISS = "dismiss_after_freeze";
    /** 「隐藏后台」：勾选后本任务不进最近任务；全屏/半屏各自记忆。 */
    private static final String KEY_PANEL_HIDE_RECENTS = "hide_recents";
    /** 「冻结后保留桌面图标」：勾选后冻结改走 pm suspend，桌面图标不消失；两种形态共用。 */
    private static final String KEY_KEEP_LAUNCHER_ICON = "keep_launcher_icon";
    /** 「长按冻结按钮触发解冻图标」：取消后长按无反应也不震动；两种形态共用，默认勾选。 */
    private static final String KEY_FAB_LONG_PRESS_UNFREEZE = "fab_long_press_unfreeze";
    /** 「仅冻结/解冻当前标签」：勾选后底部一键冻结与长按解冻只处理当前标签页的应用。 */
    private static final String KEY_BATCH_CURRENT_TAB_ONLY = "batch_current_tab_only";

    /** 「半屏打开时隐藏状态栏」：只作用于半屏面板，不影响全屏页。 */
    private static final String KEY_PANEL_HIDE_STATUS_BAR = "panel_hide_status_bar";

    /** 自定义壁纸：原图 URI + 显示范围（缩放/横向/纵向），半屏与全屏各存一份。 */
    private static final String KEY_WALLPAPER_URI = "wallpaper_uri";
    private static final String KEY_WALLPAPER_ZOOM = "wallpaper_zoom";
    private static final String KEY_WALLPAPER_DX = "wallpaper_dx";
    private static final String KEY_WALLPAPER_DY = "wallpaper_dy";
    /** 壁纸自己的模糊度：0 完全清晰，独立于背景透明度/模糊档位。 */
    private static final String KEY_WALLPAPER_BLUR = "wallpaper_blur";
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
    /** 全屏顶栏（横幅）背景档位：单独记忆；没设过时跟随「全屏背景」，升级后观感不变。 */
    private static final String KEY_HEADER_SURFACE = "header_surface";
    /** 全屏标签页胶囊整体档位：单独记忆，默认实心。 */
    private static final String KEY_TAB_SURFACE = "tab_surface";
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
    /** 半屏「快捷移除图标」：图标右上角常驻红色减号，点标记即移出名单。 */
    private boolean quickRemove;
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
        int userId;
        String label;
        Drawable icon;
        boolean system;
        boolean frozen;
        /** 走降级通道（pm suspend）冻结：显示成冻结态，另在图标右下角加区分标识。 */
        boolean suspended;
    }

    private static final class Cell {
        LinearLayout root;
        FrameLayout iconBox;
        ImageView icon;
        TextView name;
        View badge;
        /** 暂停标识：只在 pm suspend 降级冻结时显示，正常 disable-user 冻结不显示。 */
        SuspendBadge suspendBadge;
        Item item;
        /** 图标自己的长按定时器：400ms 主动触发，不依赖系统默认的 500ms。 */
        Runnable longPress;
        float downX;
        float downY;
        /** 最新触点：长按触发前再核一次是否仍在抖动范围内，滑动过就不算长按。 */
        float lastX;
        float lastY;
        /** 多选态选中后加在 iconBox 上的圆角描边圈，removeSelectRing 用它 removeView。 */
        View selectRing;
    }

    /**
     * 暂停标识：图标右下角的小圆标，里面画两条白色竖杠。
     * 自己画而不是用文字，避免部分 ROM 字体缺 U+23F8 之类的暂停符号；
     * 主色实心 + 细白描边，深浅色主题下都看得清。
     */
    private static final class SuspendBadge extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bar = new android.graphics.RectF();

        SuspendBadge(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            float stroke = Math.max(1f, r * 0.16f);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xFF4E7CF6);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, r - stroke / 2f, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            float barW = Math.max(1.2f, r * 0.26f);
            float barH = r * 0.86f;
            float gap = barW * 0.45f;
            bar.set(cx - gap - barW, cy - barH / 2f, cx - gap, cy + barH / 2f);
            canvas.drawRoundRect(bar, barW / 2f, barW / 2f, paint);
            bar.set(cx + gap, cy - barH / 2f, cx + gap + barW, cy + barH / 2f);
            canvas.drawRoundRect(bar, barW / 2f, barW / 2f, paint);
        }
    }

    private static final class Tab {
        String id;
        String name;
        boolean visible = true;
        /** 自定义标签可删除；内置的两个是应用归属的兜底，不允许删，但允许改名。 */
        boolean custom;
    }

    // 进程内共用一个后台线程：不再每开一次面板就新建一个永不回收的线程
    private final ExecutorService IO = com.jin.freeze.JinApp.IO;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** LSPosed 模块在线状态：null=探测中，true=在线，false=离线 */
    private Boolean lsposedAlive;
    /** 暂停真值后台复核是否在跑，避免连续 reload 重复排队 dumpsys。 */
    private final java.util.concurrent.atomic.AtomicBoolean suspendVerifying =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 真值不一致触发的那次重刷不再复核，避免来回重复。 */
    private volatile int skipSuspendVerify;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!SwipeFreeze.ACTION_STATUS_PONG.equals(intent.getAction())) {
                return;
            }
            lsposedAlive = true;
            Runnable refresh = privTitleRefresh;
            if (refresh != null) {
                refresh.run();
            }
        }
    };
    private final List<Item> items = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    /** 应用 → 标签；没有映射（或映射到已删除标签）时按 FLAG_SYSTEM 落回内置标签。 */
    private final Map<String, String> appTabs = new HashMap<>();
    private String currentTabId = TAB_USER;

    private TextView count;
    private View freezeBtn;
    private View settingsBtn;
    private android.widget.ImageButton editBtn;
    private androidx.recyclerview.widget.RecyclerView grid;
    private GridAdapter gridAdapter;
    private androidx.recyclerview.widget.ItemTouchHelper itemTouchHelper;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::render;
    private LinearLayout fabGroup;
    /** 长按冻结按钮浮出的一键解冻小图标。 */
    private View unfreezeFab;
    private boolean fabExpanded;
    /** 冻结执行中的雪花旋转动画，执行完停止并复位。 */
    private android.animation.ObjectAnimator freezeSpin;
    private final Handler fabHandler = new Handler(Looper.getMainLooper());
    private final Runnable fabCollapseRunnable = this::collapseUnfreezeFab;
    private View scrollView;
    private EditText search;
    private LinearLayout tabBar;
    private TextView sysHint;
    /** 空态：名单为空时显示在面板中部（添加应用）。 */
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
    /** 选应用回传后的第一个新包名：刷新完成后切到它所属的标签并滚过去。 */
    private String pendingRevealPkg;
    /** 移动模式：点一下选中（高亮），再点目标项即插到它前面并立即落盘。 */
    private boolean moveMode;
    /** 当前被选中的包名；null 表示没有选中。 */
    private String selectedPkg;
    /** 移动模式下长按拖动：正在被拖的包名，非空时格子保持半透明观感。 */
    private String draggingPkg;
    /** 拖动是否真的换过位：换过才在松手时落盘。 */
    private boolean dragMoved;
    /** 最近一次长按触发时间：用于吞掉紧接着的松手点击。 */
    private long lastLongPressAt;
    /** 多选模式：第一次长按进入，第二次长按弹批量操作菜单。 */
    private boolean selectMode;
    /** 多选模式下已选中的包名集合；退出多选时清空。 */
    private final Set<String> selectedPkgs = new HashSet<>();
    /** 批量操作的目标快照：弹窗打开那一刻定格，退出多选清空选中后仍能执行。 */
    private final List<Item> batchTargets = new ArrayList<>();
    /** 多选态顶部已选图标横排（懒加载），选中增减时刷新。 */
    private HorizontalScrollView selectStrip;
    private LinearLayout selectStripRow;
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
    /** 「冻结后保留桌面图标」开关：勾选后冻结走 suspend 通道，图标保留并置灰。 */
    private boolean keepLauncherIcon;
    /** 「仅冻结/解冻当前标签」开关：勾选后一键冻结/解冻只作用于当前标签页。 */
    private boolean batchCurrentTabOnly;
    /** 半屏打开时是否隐藏状态栏（布局设置开关，默认不隐藏）。 */
    private boolean panelHideStatusBar;
    /** 当前是否已把状态栏隐藏掉；隐藏期间不再做图标反色。 */
    private boolean statusBarHidden;
    /** 隐藏状态栏内容时保留的顶部空间（px），保证面板位置和背景都不变。 */
    private int statusBarReservePx;

    /** 自定义壁纸：原图与当前背景绘制器；为空表示沿用纯色/圆角底板背景。 */
    private String wallpaperUri;
    private Bitmap wallpaperSourceBitmap;
    private Bitmap wallpaperBitmap;
    /** 壁纸自己的模糊度（0 = 完全清晰），与透明度滑块分开记忆。 */
    private int wallpaperBlur;
    /** 布局设置里壁纸状态那一行，选图/清除后就地刷新。 */
    private TextView layoutWpState;
    private WallpaperDrawable wallpaperDrawable;
    private boolean dismissAfterReload;
    /** 冻结成功后的提示开关；失败提示不受它影响。 */
    private boolean showFrozenTip;
    /** 列表区域左右滑动切标签；布局设置可关。 */
    private boolean tabSwipeEnabled = true;
    /** 隐藏标签文字：只收起标签名那一排，列表与滑动手势照常工作。 */
    private boolean hideTabRow;
    /** 布局设置里高度块正在做显隐过渡：期间忽略滚动回调的重复切换。 */
    private boolean heightRowAnimating;
    private float swipeDownX;
    private float swipeDownY;
    private boolean swipeTracking;
    /** 标签滑动切页动画进行中：不接管新的横向手势，避免叠加。 */
    private boolean tabAnimating;
    /** 曾经离开前台：下次 onStart 视为「重新进入列表」，落回默认标签。 */
    private boolean wasStopped;

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
    /** 布局设置标题里「冻结权限：X」的刷新入口，授权状态变化时直接重绘。 */
    private Runnable privTitleRefresh;
    private final Runnable introReveal = this::startIntroReveal;
    private final Runnable introSafety = this::guardIntroStuck;

    /**
     * 状态栏图标补压的时间点：桌面长按菜单收起、系统重算系统栏外观都发生在入场后一两秒内，
     * 单次设置会被后来的值覆盖，所以要在几个时间点重复压，保证最终生效的是本窗口的值。
     */
    private static final int[] TONE_DELAYS_MS = {120, 320, 700, 1300};
    private final Runnable[] toneRetries = new Runnable[TONE_DELAYS_MS.length];
    private final Runnable toneAfterTouch = this::applyPanelBarState;

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
                List<String> normalized = new ArrayList<>();
                for (String key : picked) {
                    normalized.add(storageKey(packagePart(key), userIdPart(key)));
                }
                Set<String> cur = new HashSet<>(pkgs());
                for (String key : normalized) {
                    if (!cur.contains(key)) {
                        pendingRevealPkg = key;
                        break;
                    }
                }
                cur.addAll(normalized);
                prefs().edit().putStringSet(KEY_PKGS, cur).apply();
                SwipeFreeze.syncAsync(this);
                reload();
            });

    /** 自定义壁纸：系统文件选择器，拿到可长期读取的 URI 后立刻进入裁剪/显示范围调整。 */
    private final ActivityResultLauncher<String[]> wallpaperPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                    // 个别文件提供方不给长期权限，本次会话内仍可读
                }
                Bitmap bmp = decodeWallpaper(uri);
                if (bmp == null) {
                    Toast.makeText(this, "这张图片读不出来，换一张试试", Toast.LENGTH_SHORT).show();
                    return;
                }
                releaseWallpaper();
                wallpaperSourceBitmap = bmp;
                wallpaperUri = uri.toString();
                wallpaperBlur = 0;
                prefs().edit()
                        .putString(lk(KEY_WALLPAPER_URI), uri.toString())
                        .putInt(lk(KEY_WALLPAPER_BLUR), 0)
                        .apply();
                rebuildWallpaperDrawable();
                applyWallpaperBackground();
                if (layoutWpState != null) {
                    layoutWpState.setText("当前：已设置");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panelMode = isPanelMode();
        // 列表选中项不再有高亮框，清掉历史遗留的档位键
        prefs().edit().remove("accent_surface").remove("panel_accent_surface").apply();
        // 先在进内容之前把壁纸准备好：解码有进程内缓存，首帧就可以直接挂上，不会先露一帧白底
        loadWallpaperFromPrefs();
        // 整页底板在窗口第一次显示之前就挂到窗口背景上：首帧就带着「全屏背景」设定的透明度，
        // 不会先由系统/窗口兜一层实心（浅色模式是白）底、下一帧才被透明度盖回来
        applyWindowBackdrop();
        setContentView(panelMode
                ? R.layout.activity_freeze_list_panel
                : R.layout.activity_freeze_list);
        // 权限行要区分「只激活 LSPosed」：模块在 system_server 回 pong 证明在线
        androidx.core.content.ContextCompat.registerReceiver(this, statusReceiver,
                new IntentFilter(SwipeFreeze.ACTION_STATUS_PONG),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        applyWallpaperBackground();
        // 长按选项框勾了「同步」时，提前在后台备好半屏壁纸，长按弹出就不用等解码/模糊
        if (prefs().getBoolean(WallpaperSource.KEY_SHEET_WALLPAPER, false)) {
            IO.execute(() -> WallpaperSource.sheetBitmap(this, srcMode()));
        }
        // 半屏「隐藏状态栏」只藏图标内容：隐藏后 inset 会归零，这里按记住的高度补回顶部让位，
        // 面板位置、上方背景都与正常显示时一致，不会变成沉浸式全屏。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content), (v, insets) -> {
                    androidx.core.graphics.Insets bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                                    | WindowInsetsCompat.Type.displayCutout());
                    androidx.core.graphics.Insets ime =
                            insets.getInsets(WindowInsetsCompat.Type.ime());
                    // 留白要加在 statusBarInsetTarget() 上：全屏页给它内层内容，page_root 的背景
                    // 才能从 y=0 一直铺到状态栏/导航栏底下；半屏仍是 content（面板本身不铺满）。
                    // 之前这里写死 v（content），会把 page_root 整体挤到状态栏下方，表现为
                    // 「全屏没覆盖状态栏」。
                    View insetTarget = statusBarInsetTarget();
                    if (insetTarget == null) {
                        insetTarget = v;
                    }
                    int top = Math.max(bars.top, statusBarReservePx);
                    insetTarget.setPadding(bars.left, top, bars.right,
                            Math.max(bars.bottom, ime.bottom));
                    return insets;
                });

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
        // 全屏左上角是 btn_settings，半屏右下角仍是 layout，两个形态共用同一套设置弹窗
        settingsBtn = findViewById(R.id.btn_settings);
        if (settingsBtn == null) {
            settingsBtn = findViewById(R.id.layout);
        }
        editBtn = findViewById(R.id.edit);
        grid = findViewById(R.id.grid);
        scrollView = grid;
        fabGroup = findViewById(R.id.fab_group);
        search = findViewById(R.id.search);
        tabBar = findViewById(R.id.tab_bar);
        sysHint = findViewById(R.id.sys_hint);

        if (editBtn != null) {
            editBtn.setOnClickListener(v -> setEditMode(!editMode));
        }
        if (settingsBtn != null) {
            settingsBtn.setOnClickListener(v -> showLayoutDialog());
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
        migrateUpdatedDefaults();
        gridColumns = prefs().getInt(lk(KEY_COLUMNS), COLUMNS);
        if (gridColumns < 4 || gridColumns > 8) {
            // 旧数据（2/3 列）按 4 列处理并修正落盘
            gridColumns = 4;
            prefs().edit().putInt(lk(KEY_COLUMNS), 4).apply();
        }
        iconDp = prefs().getInt(lk(KEY_ICON_DP), 52);
        iconManual = prefs().getBoolean(lk(KEY_ICON_MANUAL), false);
        showName = prefs().getBoolean(lk(KEY_SHOW_NAME), true);
        quickRemove = panelMode && prefs().getBoolean(KEY_PANEL_QUICK_REMOVE, false);
        moveMode = prefs().getBoolean(lk(KEY_MOVE_MODE), false);
        showFrozenTip = prefs().getBoolean(lk(KEY_SHOW_FROZEN_TIP), true);
        tabSwipeEnabled = prefs().getBoolean(lk(KEY_TAB_SWIPE), true);
        hideTabRow = prefs().getBoolean(KEY_HIDE_TAB_ROW, false);
        fabDx = prefs().getInt(lk(KEY_FAB_DX), 0);
        fabDy = prefs().getInt(lk(KEY_FAB_DY), panelMode ? PANEL_FAB_DY_DEFAULT : FAB_DY_DEFAULT);
        fabSizeDp = prefs().getInt(lk(KEY_FAB_SIZE),
                panelMode ? PANEL_FAB_SIZE_DEFAULT : FAB_SIZE_DEFAULT);
        if (fabSizeDp < FAB_SIZE_MIN || fabSizeDp > FAB_SIZE_MAX) {
            fabSizeDp = panelMode ? PANEL_FAB_SIZE_DEFAULT : FAB_SIZE_DEFAULT;
            prefs().edit().putInt(lk(KEY_FAB_SIZE), fabSizeDp).apply();
        }
        // 列数等布局参数读完之后再挂适配器，首帧就是正确的 spanCount
        setupGrid();
        applyFabSize();
        setupUnfreezeFab();
        panelDismissAfterFreeze = prefs().getBoolean(lk(KEY_PANEL_DISMISS), true);
        panelHideRecents = prefs().getBoolean(lk(KEY_PANEL_HIDE_RECENTS), false);
        keepLauncherIcon = prefs().getBoolean(KEY_KEEP_LAUNCHER_ICON, false);
        batchCurrentTabOnly = prefs().getBoolean(KEY_BATCH_CURRENT_TAB_ONLY, false);
        if (panelMode) {
            panelHandleEnabled = prefs().getBoolean(KEY_PANEL_HANDLE, true);
            panelHideStatusBar = prefs().getBoolean(KEY_PANEL_HIDE_STATUS_BAR, false);
            setupPanel();
            applyPanelSurface();
            applyFabSurface();
            applySearchSurface();
            applyPanelFabPosition();
            applyPanelBarState();
            applyWallpaperBackground();
        } else {
            applyFullscreenSurface();
            applyFullscreenFabSurface();
            applyFullscreenTextColors();
            applyWallpaperBackground();
            fabGroup.post(this::applyFabPosition);
            // 监听父容器尺寸变化（而不是按钮组自身），避免设置边距时自触发死循环
            ((View) fabGroup.getParent()).addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or, ob) -> applyFabPosition());
        }
        loadTabs();
        setSysHintVisible();
        renderTabBar();
        // 进页面就把名单文件刷新一次：新装/卸载、标签变化都靠这次对齐
        SwipeFreeze.syncAsync(this);

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
                    // 输入防抖：逐字输入只在停下来 150ms 后刷新一次，避免每敲一下就重绑整屏
                    searchHandler.removeCallbacks(searchRunnable);
                    searchHandler.postDelayed(searchRunnable, 150);
                }
            });
        }

        // 一键冻结不做前置确认，直接执行
        freezeBtn.setOnClickListener(v -> runBatch(true, batchTargets()));

        pendingAction = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ACTION);
        // 「从旧版导入」已移除，清掉历史遗留的迁移标记
        if (prefs().contains("legacy_offer_done")) {
            prefs().edit().remove("legacy_offer_done").apply();
        }

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
        int pct = prefs().getInt(lk(KEY_FAB_SURFACE),
                panelMode ? PANEL_FAB_SURFACE_DEFAULT : SURFACE_SOLID);
        applySurface(fabGroup, pct);
        applySurface(unfreezeFab, pct);
    }

    // ---------------- 自定义壁纸 ----------------

    /** 读回本形态（半屏 / 全屏各一份）的壁纸配置与原图。 */
    private void loadWallpaperFromPrefs() {
        wallpaperUri = prefs().getString(lk(KEY_WALLPAPER_URI), null);
        // 旧版的裁剪参数已废弃，顺手清掉，避免残留键
        if (prefs().contains(lk(KEY_WALLPAPER_ZOOM))) {
            prefs().edit()
                    .remove(lk(KEY_WALLPAPER_ZOOM))
                    .remove(lk(KEY_WALLPAPER_DX))
                    .remove(lk(KEY_WALLPAPER_DY))
                    .apply();
        }
        wallpaperBlur = Math.max(0, Math.min(100, prefs().getInt(lk(KEY_WALLPAPER_BLUR), 0)));
        if (wallpaperUri == null || wallpaperUri.isEmpty()) {
            return;
        }
        wallpaperSourceBitmap = decodeWallpaper(Uri.parse(wallpaperUri));
        if (wallpaperSourceBitmap == null) {
            // 图片被删或权限失效：清掉配置，回到原来的纯色/圆角底板
            wallpaperUri = null;
            prefs().edit().remove(lk(KEY_WALLPAPER_URI)).apply();
            return;
        }
        rebuildWallpaperDrawable();
    }

    /** 壁纸模糊只按用户自己这条滑块算：0 直接用原解码图（完全清晰），越大越柔和。 */
    /**
     * 模糊在挂背景之前同步算好（结果有缓存，同一档位只算一次）：
     * 打开和拖动滑块都是直接落到目标档位，不会先露一帧清晰图，也不会先露白。
     *
     * 设了非 0 模糊时，首帧先挂「降采样 + 快速模糊」的结果（几十毫秒），保证打开瞬间就是模糊态；
     * 全分辨率模糊随后在后台算好替换，画面只会更细腻，不会变清晰。
     */
    private void rebuildWallpaperDrawable() {
        if (wallpaperSourceBitmap == null) {
            wallpaperDrawable = null;
            return;
        }
        final Bitmap base = wallpaperSourceBitmap;
        final int pct = wallpaperBlur;
        final String uri = wallpaperUri;
        final int mode = srcMode();
        if (pct <= 0 || uri == null) {
            swapWallpaperBitmap(base);
            return;
        }
        swapWallpaperBitmap(WallpaperSource.quickBlur(this, uri, base, pct, mode));
        IO.execute(() -> {
            final Bitmap full = WallpaperSource.blurred(this, uri, base, pct, mode);
            main.post(() -> {
                if (isFinishing() || isDestroyed() || full == null) {
                    return;
                }
                // 期间又换了图/档位，就丢弃这次结果，避免把旧档位的模糊挂上去
                if (wallpaperBlur != pct || !uri.equals(wallpaperUri)
                        || wallpaperSourceBitmap != base) {
                    return;
                }
                swapWallpaperBitmap(full);
                applyWallpaperBackground();
            });
        });
    }

    /** 本形态对应的壁纸/模糊缓存槽位：半屏与全屏各一套，互不串用。 */
    private int srcMode() {
        return panelMode ? WallpaperSource.MODE_PANEL : WallpaperSource.MODE_FULL;
    }

    private void swapWallpaperBitmap(Bitmap bmp) {
        wallpaperBitmap = bmp;
        wallpaperDrawable = new WallpaperDrawable(bmp, panelMode ? Ui.dp(this, 20) : 0);
    }

    /** 按目标视图尺寸解码（屏幕宽高）：由 WallpaperSource 统一缓存，反复打开不再重复解码。 */
    private Bitmap decodeWallpaper(Uri uri) {
        return WallpaperSource.decode(this, uri == null ? null : uri.toString(), srcMode());
    }

    /** 位图归 WallpaperSource 缓存所有，这里只断引用，不回收，避免把缓存里的图回收掉。 */
    private void releaseWallpaper() {
        wallpaperSourceBitmap = null;
        wallpaperBitmap = null;
        wallpaperDrawable = null;
    }

    /**
     * 把壁纸挂到背景层：半屏是在面板底板上、全屏是交给窗口背景（applyWindowBackdrop）。
     * 透明度仍由原来的背景滑块控制（全屏改窗口底板的 alpha，半屏改面板 drawable 的 alpha）。
     */
    private void applyWallpaperBackground() {
        if (panelMode) {
            View panel = findViewById(R.id.panel);
            if (panel == null) {
                return;
            }
            if (wallpaperDrawable != null) {
                panel.setBackground(wallpaperDrawable);
            } else {
                panel.setBackgroundResource(R.drawable.bg_sheet);
            }
            applyPanelSurface();
            applyPanelBottomIconTone();
        } else {
            View root = findViewById(R.id.page_root);
            if (root == null) {
                return;
            }
            // 底色由 page_root 这一层统一承担（见 applyWindowBackdrop），窗口背景保持全透明
            root.setBackground(null);
            applyFullscreenSurface();
        }
    }

    /** 当前「全屏背景」档位：0 = 整页透明，100 = 实心。半屏不参与。 */
    private int fullSurfacePct() {
        return Math.max(0, Math.min(100, prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID)));
    }

    /**
     * 全屏顶栏（横幅）档位：没单独设过时跟随「全屏背景」，
     * 这样已有用户升级后横幅观感不变，动过新滑块才改为独立记忆。
     */
    private int headerSurfacePct() {
        int v = prefs().getInt(KEY_HEADER_SURFACE, -1);
        return v < 0 ? fullSurfacePct() : Math.max(0, Math.min(100, v));
    }

    /** 全屏标签页胶囊整体档位：默认实心，与页面背景互不影响。 */
    private int tabSurfacePct() {
        return Math.max(0, Math.min(100, prefs().getInt(KEY_TAB_SURFACE, SURFACE_SOLID)));
    }

    /**
     * 全屏页：系统栏留白加到内层内容上，page_root 的背景一直铺到状态栏/导航栏底下；
     * 半屏保持默认（留白加在 content 上）。
     */
    @Override
    protected View statusBarInsetTarget() {
        if (isPanelMode()) {
            return super.statusBarInsetTarget();
        }
        View root = findViewById(R.id.page_root);
        if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0) {
            return ((ViewGroup) root).getChildAt(0);
        }
        return super.statusBarInsetTarget();
    }

    /**
     * 全屏底板：全工程唯一允许写「全屏背景」透明度的位置。
     * 直接画在 page_root 这一层（不再交给 Window.setBackgroundDrawable：窗口背景在部分 ROM 上
     * 换实例也不重绘，表现就是拖滑块整页背景不动）。每次新建 drawable 实例，保证立刻重绘。
     * 规则（不可回归）：图标、文字、标签、搜索框一律不参与这一档 alpha；
     * 它们都绘制在 page_root 之上，背景层变化不会改变它们的颜色与不透明度。
     * 半屏的底板是带圆角、只占下半屏的面板，窗口背景保持主题里的完全透明。
     */
    private void applyWindowBackdrop() {
        android.view.Window w = getWindow();
        if (w == null || panelMode) {
            return;
        }
        int pct = fullSurfacePct();
        int alpha = Math.round(255f * pct / 100f);
        // 窗口背景保持全透明，整页底色只由 page_root 一层承担，避免两层各带一次 alpha
        w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        // 窗口背景已经覆盖整块窗口（含状态栏/导航栏那两条），系统栏再刷一层同色会比页面更实；
        // 首帧前也不该让系统栏先按主题里的 page_bg 实心铺一条白，所以这里统一保持完全透明
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        // 新系统默认会给透明导航栏/状态栏加一层对比度保护色（那条灰底），
        // 会让状态栏区域和页面背景不是同一个颜色；全屏页统一关掉
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                w.setNavigationBarContrastEnforced(false);
                w.setStatusBarContrastEnforced(false);
            } catch (Throwable ignored) {
            }
        }
        View root = findViewById(R.id.page_root);
        if (root != null) {
            if (wallpaperDrawable != null) {
                // 新实例：同一个 drawable 重复 setBackground 不会触发重绘
                WallpaperDrawable next = new WallpaperDrawable(wallpaperBitmap, 0);
                next.setAlpha(alpha);
                root.setBackground(next);
            } else {
                int base = getColor(R.color.page_bg);
                root.setBackground(new ColorDrawable(android.graphics.Color.argb(alpha,
                        android.graphics.Color.red(base),
                        android.graphics.Color.green(base),
                        android.graphics.Color.blue(base))));
            }
        }
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
        // 状态栏/导航栏压在页面背景上：背景偏实（浅色）时用深色图标，
        // 偏透明时页面可能压着深色壁纸，切成浅色图标保证可读
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
        controller.setAppearanceLightStatusBars(pct >= 50);
        controller.setAppearanceLightNavigationBars(pct >= 50);
    }

    private void clearCustomWallpaper() {
        prefs().edit()
                .remove(lk(KEY_WALLPAPER_URI))
                .remove(lk(KEY_WALLPAPER_ZOOM))
                .remove(lk(KEY_WALLPAPER_DX))
                .remove(lk(KEY_WALLPAPER_DY))
                .remove(lk(KEY_WALLPAPER_BLUR))
                .apply();
        wallpaperUri = null;
        wallpaperBlur = 0;
        releaseWallpaper();
        WallpaperSource.drop(srcMode());
        applyWallpaperBackground();
    }

    /** 设了自定义壁纸时，底栏的搜索/设置按钮改成深色（黑）图标，换任何图都不会糊在背景上看不清。 */
    private void applyPanelBottomIconTone() {
        if (!panelMode) {
            return;
        }
        int tint = getColor(wallpaperDrawable != null
                ? R.color.header_icon_black : R.color.text_secondary);
        tintHeaderIcon(R.id.panel_search, tint);
        tintHeaderIcon(R.id.layout, tint);
    }

    private void tintHeaderIcon(int id, int color) {
        View v = findViewById(id);
        if (v instanceof android.widget.ImageButton) {
            ((android.widget.ImageButton) v).setImageTintList(ColorStateList.valueOf(color));
        }
    }

    /** 搜索框透明度当前档位：只用于全屏，默认 30%；已存过值的用户沿用自己的值。 */
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
     * 与半屏同一套档位语义，但键独立记忆；整页底色（含状态栏/导航栏那两条）由窗口背景统一承担，
     * 窗口模糊跟同一档位走，默认 100% 时观感与原来的 page_bg 实心页面一致。
     */
    private void applyFullscreenSurface() {
        if (panelMode) {
            return;
        }
        int pct = fullSurfacePct();
        // 拖动滑块时窗口底板也跟着走：整页背景的 alpha 始终只有一个来源
        applyWindowBackdrop();
        applyFullscreenSurfaces(pct);
        android.view.Window w = getWindow();
        if (w == null) {
            return;
        }
        // 窗口背景已经覆盖整块窗口（含状态栏/导航栏那两条），系统栏再刷一层同色会比页面更实；
        // 首帧前也不该让系统栏先按主题里的 page_bg 实心铺一条白，所以这里统一保持完全透明
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
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
     * 全屏底板按各自的档位铺：顶栏走「横幅透明度」，标签页胶囊整条走「标签页透明度」，
     * 移动模式下的选中高亮框仍跟「全屏背景」（整页底色在窗口背景上）。
     * 列表每次重绘后要再调一次，避免新建的视图漏掉。
     */
    private void applyFullscreenSurfaces(int pct) {
        // page_root 的底板已经交给窗口背景（applyWindowBackdrop），这里不再给它上第二层 alpha
        applySurface(findViewById(R.id.page_header), headerSurfacePct());
        // 标签页胶囊整条一组：选中/未选中一起缩放，与页面背景互不影响
        if (tabBar != null) {
            int tabPct = tabSurfacePct();
            for (int i = 0; i < tabBar.getChildCount(); i++) {
                applySurface(tabBar.getChildAt(i), tabPct);
            }
        }
        // 格子自身没有底板（图标直接落在页面背景上），不需要遍历列表子 View
        // 搜索框颜色归自己的档位单独管，上面这轮铺完再盖回自己的 alpha
        applySearchSurface();
    }

    /** 全屏里原来偏灰的入口/提示文字统一压成同一个深色（深色模式用高对比浅色）。 */
    private void applyFullscreenTextColors() {
        if (panelMode) {
            return;
        }
        int color = getColor(R.color.fs_text_muted);
        int[] ids = {R.id.sys_hint, R.id.count};
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
            playIntro();
        }
        // 再次点桌面图标也算一次「打开 app」：复用同一套启动检查
        if (intent != null && intent.getBooleanExtra(EXTRA_CHECK_PRIV, false)) {
            intent.removeExtra(EXTRA_CHECK_PRIV);
            checkPrivilegeOnStart();
        }
        // singleTask 复用：窗口不重建、不会走 onCreate，外观要在这里重新压
        if (panelMode) {
            schedulePanelStatusTone();
        }
        // 桌面图标再次拉起也算新一次进入：落回默认标签
        resetToDefaultTab();
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
            return;
        }
        ++introRun;
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
        main.postDelayed(introReveal, INTRO_IN_MS + INTRO_HOLD_MS);
        main.postDelayed(introSafety, INTRO_IN_MS + INTRO_HOLD_MS + INTRO_OUT_MS + 400);
    }

    /** 兜底：动画回调万一没落地（窗口/监听时序），也把状态清干净，避免之后每轮都被跳过。 */
    private void guardIntroStuck() {
        if (!introPlaying) {
            return;
        }
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
        applyPanelBarState();
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
        // 半屏隐藏状态栏时没有那一条位置，不铺黑条
        if (panelHideStatusBar) {
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
        // 隐藏状态栏时没有图标要调，避免留下强制白色
        if (statusBarHidden) {
            return;
        }
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
     * 深色背景用白图标，浅色背景用深色图标。隐藏状态栏时无需反色。
     */
    private void applyPanelStatusIcons() {
        if (!panelMode || statusBarHidden) {
            return;
        }
        // 拉起过渡期间由 syncIntroIcons 统一控制（黑条过半时强制白色）
        if (introPlaying) {
            return;
        }
        introIconsLight = false;
        statusIconsController().setAppearanceLightStatusBars(!darkBackdrop());
    }

    /**
     * 半屏系统栏状态：隐藏状态栏内容，或按壁纸亮度反色图标。两者互斥——
     * 隐藏时不再做反色，取消勾选后立刻按当前壁纸亮度补一次反色。
     * 隐藏的是状态栏里的图标/时间那些内容，状态栏那条空间仍然留着：
     * 先把高度记下来，给内容顶部补同样的 padding，面板不会因此上移、背景也不变。
     */
    private void applyPanelBarState() {
        if (!panelMode) {
            return;
        }
        WindowInsetsControllerCompat controller = statusIconsController();
        if (panelHideStatusBar) {
            if (!statusBarHidden) {
                statusBarReservePx = introStatusInsetPx();
            }
            statusBarHidden = true;
            controller.hide(WindowInsetsCompat.Type.statusBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            // 隐藏后要允许窗口画进刘海/状态栏那条区域，否则系统会拿黑色补出来
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
            if (introStatusView != null) {
                introStatusView.setVisibility(View.GONE);
            }
            applyContentInsets();
            return;
        }
        if (statusBarHidden) {
            statusBarHidden = false;
            statusBarReservePx = 0;
            controller.show(WindowInsetsCompat.Type.statusBars());
            applyContentInsets();
        }
        applyPanelStatusIcons();
    }

    /** 内容区边距：隐藏状态栏时把那条空间补回来，面板位置与背景都和显示时一致。 */
    private void applyContentInsets() {
        View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        androidx.core.view.ViewCompat.requestApplyInsets(content);
    }

    /**
     * 立刻压一次外观，并在入场后的几个时间点重复压。
     * 长按桌面图标进来时，桌面会先收起快捷方式菜单，它消失时系统可能把自己那套状态栏外观
     * 重新刷回来——这次覆盖晚于 onResume / onWindowFocusChanged，单次设置会被冲掉，
     * 所以补压到 1.3s（搞机助手同款处理）。
     */
    private void schedulePanelStatusTone() {
        if (!panelMode) {
            return;
        }
        applyPanelBarState();
        View decor = getWindow().getDecorView();
        for (int i = 0; i < TONE_DELAYS_MS.length; i++) {
            if (toneRetries[i] == null) {
                toneRetries[i] = this::applyPanelBarState;
            }
            decor.removeCallbacks(toneRetries[i]);
            decor.postDelayed(toneRetries[i], TONE_DELAYS_MS[i]);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 窗口属性（模糊标志等）重新下发后，系统栏外观与可见性可能被重置，这里再压一次
        if (hasFocus && panelMode) {
            schedulePanelStatusTone();
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        // 快捷方式入口经入口壳中转，回来时系统可能重置过外观，这条路径也补齐
        if (panelMode) {
            schedulePanelStatusTone();
        }
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
        return introPlaying || (!isPanelMode() && fullSurfacePct() < 50);
    }

    private void openPicker() {
        // 勾选「隐藏半屏后台」时，面板一暂停就会被移除；选应用期间先豁免
        pickerOpen = true;
        Intent i = new Intent(this, AppFreezeActivity.class);
        i.putExtra(AppFreezeActivity.EXTRA_PICK, true);
        // 选择页默认停在发起时所在的标签（用户应用/系统应用），选完回到这里
        i.putExtra(AppFreezeActivity.EXTRA_PICK_SYSTEM, TAB_SYSTEM.equals(currentTabId));
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
        // 长按桌面图标进来的这条路径：桌面收起快捷方式菜单时可能顺带把状态栏刷回它那套外观，
        // 且这次覆盖发生在本页回调之后。面板拿到第一下触摸时（菜单一定已消失）再压一次，
        // 不依赖系统回调的先后顺序（搞机助手同款处理）。
        if (panelMode && ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            applyPanelBarState();
            View decor = getWindow().getDecorView();
            decor.removeCallbacks(toneAfterTouch);
            decor.postDelayed(toneAfterTouch, 80);
        }
        if (!tabSwipeEnabled || moveMode || editMode || working || tabAnimating) {
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
                    followTabSwipe(ev.getX() - swipeDownX);
                    return true;
                }
                break;
            case android.view.MotionEvent.ACTION_UP:
                if (swipeTracking) {
                    settleTabSwipe(ev.getX() - swipeDownX);
                    swipeTracking = false;
                    return true;
                }
                swipeTracking = false;
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                if (swipeTracking) {
                    // 手势被打断也回到原位，不留半截位移
                    settleTabSwipe(0f);
                }
                swipeTracking = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 跟手拖动：有相邻标签时列表按手指位移移动，边缘只剩 1/4 位移做阻尼。 */
    private void followTabSwipe(float dx) {
        View v = tabSlideView();
        if (v == null) {
            return;
        }
        int dir = dx < 0 ? 1 : -1;
        float move = hasTabNeighbor(dir) ? dx : dx * 0.25f;
        float cap = v.getWidth() * 0.6f;
        v.setTranslationX(Math.max(-cap, Math.min(cap, move)));
    }

    /**
     * 松手收尾：过阈值且有相邻标签就滑出 → 切换 → 从另一侧滑入；
     * 否则回弹。全程位移动画，不做瞬间跳变。
     */
    private void settleTabSwipe(float dx) {
        final View v = tabSlideView();
        if (v == null) {
            return;
        }
        final int dir = dx < 0 ? 1 : -1;
        boolean canSwitch = Math.abs(dx) > Ui.dp(this, 56) && hasTabNeighbor(dir);
        if (!canSwitch) {
            v.animate().translationX(0f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            return;
        }
        final float w = v.getWidth();
        tabAnimating = true;
        v.animate().translationX(dir > 0 ? -w : w)
                .setDuration(150)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (isFinishing() || isDestroyed()) {
                        tabAnimating = false;
                        return;
                    }
                    switchTabBySwipe(dir);
                    v.setTranslationX(dir > 0 ? w : -w);
                    v.animate().translationX(0f)
                            .setDuration(190)
                            .setInterpolator(new DecelerateInterpolator())
                            .withEndAction(() -> tabAnimating = false)
                            .start();
                })
                .start();
    }

    /** 横向滑动动画作用的视图（列表区域）。 */
    private View tabSlideView() {
        return scrollView;
    }

    /** 当前标签在 dir 方向（-1 上一条 / 1 下一条）是否还有可见标签。 */
    private boolean hasTabNeighbor(int dir) {
        List<Tab> visible = new ArrayList<>();
        for (Tab t : tabs) {
            if (t.visible) {
                visible.add(t);
            }
        }
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).id.equals(currentTabId)) {
                int next = i + dir;
                return next >= 0 && next < visible.size();
            }
        }
        return false;
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
        // 授权管理器里点完「允许」回到凚时自动重测：失败缓存有 TTL，这里强制重来一次
        if (privDialog != null && privDialog.isShowing()) {
            recheckPrivilegeAsync(true, true, null);
        } else if (PrivShell.peek() == null || PrivShell.peek() == PrivShell.Mode.NONE) {
            recheckPrivilegeAsync(false, false, null);
        }
        // 半屏显示期间状态栏压在桌面上，按壁纸明暗决定图标深浅（过渡中不压，交给过渡自己控）
        if (panelMode && !introPlaying) {
            schedulePanelStatusTone();
        }
        syncRecentsExclusion();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (wasStopped) {
            wasStopped = false;
            // 从后台回前台算新一次进入，落回默认标签；进选应用页这类内部跳转不算
            if (!pickerOpen && !isFinishing()) {
                resetToDefaultTab();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        wasStopped = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面就把状态栏图标恢复成跟随深浅色，不残留过渡时的强制白色
        if (panelMode) {
            restoreStatusBarIcons();
        }
        // 与冰箱一致：这里不再 finishAndRemoveTask / finish。
        // 任务在 onResume 时就已经被排除出最近任务，系统不会给它画快照；
        // 先让卡片进过最近任务再移除，正是 HyperOS 桌面留下可点旧帧的原因。
    }

    /**
     * 开关打开时让本任务从一开始就不进最近任务；关掉时恢复，保证还能正常留在后台。
     * 与冰箱 onResume 里遍历 AppTask 置/清 excludeFromRecents 的做法一致，
     * 覆盖「开关拨动前就已经存在的旧任务」；新任务由隐藏入口的清单属性兜住。
     */
    private void syncRecentsExclusion() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(!panelHideRecents);
            }
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                if (t.getTaskInfo().id == getTaskId()) {
                    t.setExcludeFromRecents(panelHideRecents);
                }
            }
        } catch (Throwable ignored) {
            // 个别 ROM 改不了任务属性；新任务仍由隐藏入口的 excludeFromRecents 兜住
        }
    }

    /**
     * 半屏的侧边返回：直接结束并把自身任务一起移除，不再留在后台/最近任务里。
     * 全屏冻结列表仍走默认返回。
     */
    @Override
    public void onBackPressed() {
        // 多选态优先退出多选，不直接关面板
        if (selectMode) {
            exitSelectMode();
            return;
        }
        if (panelMode) {
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
                        prefs().getBoolean(KEY_SHOW_FROZEN_TIP, true))
                .putBoolean("panel_" + KEY_PANEL_HIDE_RECENTS,
                        prefs().getBoolean(KEY_PANEL_HIDE_RECENTS, false))
                .putBoolean("panel_" + KEY_PANEL_DISMISS,
                        prefs().getBoolean(KEY_PANEL_DISMISS, true))
                .putInt("panel_" + KEY_FAB_DX, prefs().getInt(KEY_FAB_DX, 0))
                .putInt("panel_" + KEY_FAB_DY,
                        prefs().getInt(KEY_FAB_DY, PANEL_FAB_DY_DEFAULT))
                .putInt("panel_" + KEY_FAB_SIZE,
                        prefs().getInt(KEY_FAB_SIZE, PANEL_FAB_SIZE_DEFAULT))
                .putBoolean("panel_layout_init", true)
                .apply();
    }

    /**
     * 一次性迁移到本版新默认：只把仍等于老出厂值的项改掉，用户改过的值原样保留。
     * 新装设备没有这些键，直接按新默认读取，不经过这里。
     */
    private void migrateUpdatedDefaults() {
        if (prefs().getBoolean("defaults_migrated_115", false)) {
            return;
        }
        SharedPreferences.Editor e = prefs().edit();
        // 老默认 40dp / 贴底 0dp，由「半屏初始化」从全屏设置抄过来的也算没动过
        if (prefs().getInt("panel_fab_size", -1) == FAB_SIZE_DEFAULT) {
            e.putInt("panel_fab_size", PANEL_FAB_SIZE_DEFAULT);
        }
        if (prefs().getInt("panel_fab_dy", -1) == 0) {
            e.putInt("panel_fab_dy", PANEL_FAB_DY_DEFAULT);
        }
        // 「显示已冻结提示」「冻结后收起列表」老默认是关，改为开
        for (String k : new String[]{"show_frozen_toast", "panel_show_frozen_toast"}) {
            if (!prefs().getBoolean(k, true)) {
                e.putBoolean(k, true);
            }
        }
        for (String k : new String[]{"dismiss_after_freeze", "panel_dismiss_after_freeze"}) {
            if (!prefs().getBoolean(k, true)) {
                e.putBoolean(k, true);
            }
        }
        e.putBoolean("defaults_migrated_115", true).apply();
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
        // 旧版本允许把标签全部取消勾选，落盘后标签栏会空掉；这里兜底恢复两个内置标签
        if (visibleTabCount() == 0) {
            for (Tab t : tabs) {
                if (TAB_USER.equals(t.id) || TAB_SYSTEM.equals(t.id)) {
                    t.visible = true;
                }
            }
            saveTabs();
        }
        // 每次进入列表都落回默认标签（没设置过就按原来的第一个可见标签）
        currentTabId = defaultTabId();
        prefs().edit().putString(KEY_CURRENT_TAB, currentTabId).apply();
    }

    /** 默认标签：设置过且仍然可见才生效；否则落到第一个可见标签。 */
    private String defaultTabId() {
        String id = prefs().getString(KEY_DEFAULT_TAB, null);
        if (id != null) {
            Tab t = findTab(id);
            if (t != null && t.visible) {
                return id;
            }
        }
        return firstVisibleTabId();
    }

    /** 标签被删除或改名后清掉默认键，出厂状态是「没有任何标签被设为默认」。 */
    private void clearDefaultTabIf(String tabId) {
        if (tabId != null && tabId.equals(prefs().getString(KEY_DEFAULT_TAB, ""))) {
            prefs().edit().remove(KEY_DEFAULT_TAB).apply();
        }
    }

    /** 回到默认标签（切换列表内容与标签条）；已在默认标签上则不动。 */
    private void resetToDefaultTab() {
        String id = defaultTabId();
        if (id.equals(currentTabId)) {
            return;
        }
        currentTabId = id;
        prefs().edit().putString(KEY_CURRENT_TAB, id).apply();
        setSysHintVisible();
        renderTabBar();
        render();
        scrollToTop();
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
        SwipeFreeze.syncAsync(this);
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
        SwipeFreeze.syncAsync(this);
    }

    /** 应用归属：没有显式映射（或映射到已删标签）时按 FLAG_SYSTEM 落回内置标签。 */
    private String tabIdOf(Item it) {
        String id = appTabs.get(it.pkg);
        if (id != null && findTab(id) != null) {
            return id;
        }
        return it.system ? TAB_SYSTEM : TAB_USER;
    }

    /** 标签排显示/收起：收起时把这一行的高度完全让给列表，并立刻重新测量，不等下次刷新。 */
    private void applyTabRowVisibility(boolean show) {
        if (tabBar == null) {
            return;
        }
        View tabRow = tabBar.getParent() instanceof View ? (View) tabBar.getParent() : null;
        if (tabRow == null) {
            return;
        }
        int want = show ? View.VISIBLE : View.GONE;
        if (tabRow.getVisibility() == want) {
            return;
        }
        tabRow.setVisibility(want);
        // 全屏形态里这一行还放着「布局/备份/恢复」：收起标签条后补一个占位，
        // 让这三个入口仍贴右边，不因标签条消失而挤到最左
        if (tabRow.getParent() instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) tabRow.getParent();
            if (row.getOrientation() == LinearLayout.HORIZONTAL) {
                View spacer = row.findViewWithTag("tab_row_spacer");
                if (show) {
                    if (spacer != null) {
                        row.removeView(spacer);
                    }
                } else if (spacer == null) {
                    spacer = new View(this);
                    spacer.setTag("tab_row_spacer");
                    row.addView(spacer, row.indexOfChild(tabRow) + 1,
                            new LinearLayout.LayoutParams(0, 0, 1f));
                }
            }
        }
        // 收起后列表高度变大：主动请求一次布局，滚动范围与图标位置当帧就跟上
        if (grid != null) {
            grid.requestLayout();
        }
        View panel = findViewById(R.id.panel);
        if (panel != null) {
            panel.requestLayout();
        }
    }

    private void renderTabBar() {
        if (tabBar == null) {
            return;
        }
        tabBar.removeAllViews();
        // 隐藏标签文字只作用于半屏面板：全屏的标签栏始终正常显示
        applyTabRowVisibility(!(panelMode && hideTabRow));
        if (visibleTabCount() == 0) {
            return;
        }
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
        scrollToTop();
    }

    /** 新建 / 重命名标签；名称不能为空也不能与其它标签重名。 */
    private void showTabNameDialog(Tab tab, Runnable done) {
        // 紧凑卡片：自绘标题/输入/按钮，窗口宽度不撑满，内边距与间距统一压紧
        int padH = Ui.dp(this, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(padH, Ui.dp(this, 16), padH, Ui.dp(this, 6));

        TextView title = new TextView(this);
        title.setText(tab == null ? "新建标签" : "重命名标签");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(13);
        input.setHint("标签名称");
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));
        input.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        if (tab != null) {
            input.setText(tab.name);
            input.setSelection(input.getText().length());
        }
        card.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        final TextView[] buttons = new TextView[2];
        String[] labels = {"取消", "确定"};
        for (int i = 0; i < labels.length; i++) {
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(13);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(Ui.dp(this, 44));
            b.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
            b.setTextColor(getColor(R.color.blue));
            if (i == 1) {
                b.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            b.setBackgroundResource(tv.resourceId);
            buttons[i] = b;
            btnRow.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog d = new AlertDialog.Builder(this)
                .setView(card)
                .create();
        d.show();
        Ui.roundAlertDialog(d);
        // 卡片收窄：上限 300dp，两侧至少留 40dp；圆角仍按 16dp 裁切
        android.view.Window win = d.getWindow();
        if (win != null) {
            int width = Math.min(Ui.dp(this, 300),
                    getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 80));
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        // 弹窗出现后等布局完成再聚焦并拉起输入法，键盘跟着弹窗一起上来
        input.post(() -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        buttons[0].setOnClickListener(v -> {
            hideIme(input);
            d.dismiss();
        });
        buttons[1].setOnClickListener(v -> {
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
                // 改名后默认设置失效，回到「没有默认标签」的出厂状态
                clearDefaultTabIf(tab.id);
            }
            saveTabs();
            renderTabBar();
            if (done != null) {
                done.run();
            }
            hideIme(input);
            d.dismiss();
        });
    }

    private void hideIme(View v) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    /** 标签标题点开的说明卡片：尺寸与新建/改名同一套，只有「知道了」一个按钮。 */
    private void showTabHintDialog() {
        int padH = Ui.dp(this, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(padH, Ui.dp(this, 16), padH, Ui.dp(this, 6));

        TextView body = new TextView(this);
        body.setText("勾选标签，则显示勾选的冻结列表；\n取消勾选，则不显示该标签冻结列表；\n点击设为默认，每次启动显示默认标签。");
        body.setTextSize(13);
        body.setTextColor(getColor(R.color.text_primary));
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextSize(13);
        ok.setGravity(Gravity.CENTER);
        ok.setMinHeight(Ui.dp(this, 44));
        ok.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        ok.setTextColor(getColor(R.color.blue));
        ok.setTypeface(null, android.graphics.Typeface.BOLD);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        ok.setBackgroundResource(tv.resourceId);
        btnRow.addView(ok, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog d = new AlertDialog.Builder(this)
                .setView(card)
                .create();
        d.show();
        Ui.roundAlertDialog(d);
        android.view.Window win = d.getWindow();
        if (win != null) {
            int width = Math.min(Ui.dp(this, 300),
                    getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 80));
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        ok.setOnClickListener(v -> d.dismiss());
    }

    /** 删除自定义标签：其应用清掉归属映射，按 FLAG_SYSTEM 回到内置标签，不丢数据。 */
    private void confirmDeleteTab(Tab tab, Runnable done) {
        int count = 0;
        for (Item it : items) {
            if (tab.id.equals(tabIdOf(it))) {
                count++;
            }
        }
        // 与新建/改名共用同一套紧凑卡片尺寸：300dp 上限、18/16/6 内边距、13sp 正文与按钮
        int padH = Ui.dp(this, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(padH, Ui.dp(this, 16), padH, Ui.dp(this, 6));

        TextView title = new TextView(this);
        title.setText("删除标签「" + tab.name + "」");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText("其中的 " + count + " 个应用会回到原来的「用户应用 / 系统应用」，不会丢失。");
        body.setTextSize(13);
        body.setTextColor(getColor(R.color.text_secondary));
        body.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        final TextView[] buttons = new TextView[2];
        String[] labels = {"取消", "删除"};
        for (int i = 0; i < labels.length; i++) {
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(13);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(Ui.dp(this, 44));
            b.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
            b.setTextColor(getColor(R.color.blue));
            if (i == 1) {
                b.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            b.setBackgroundResource(tv.resourceId);
            buttons[i] = b;
            btnRow.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog del = new AlertDialog.Builder(this)
                .setView(card)
                .create();
        del.show();
        Ui.roundAlertDialog(del);
        android.view.Window win = del.getWindow();
        if (win != null) {
            int width = Math.min(Ui.dp(this, 300),
                    getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 80));
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        buttons[0].setOnClickListener(v -> del.dismiss());
        buttons[1].setOnClickListener(v -> {
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
            // 删掉的标签若是默认，键一并清掉，不能留下指向不存在标签的默认值
            clearDefaultTabIf(tab.id);
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
            del.dismiss();
        });
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

    /**
     * 首帧用的快速列表：只用本机 PackageInfo 和暂停缓存，不跑任何 shell 命令。
     * 冻结状态与真值可能有两三处偏差，后台真值回来后由 reload 的正式渲染覆盖。
     */
    private List<Item> quickItems(Set<String> wanted, List<String> order, boolean custom) {
        PackageManager pm = getPackageManager();
        Set<String> suspended = SuspendSupport.cached(this);
        Map<String, Item> map = new HashMap<>();
        for (String key : wanted) {
            String pkg = packagePart(key);
            int userId = userIdPart(key);
            Item it = new Item();
            it.pkg = key;
            it.userId = userId;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                it.label = pm.getApplicationLabel(ai).toString();
                // 内存/磁盘缓存里的图标先顶上，缺的等正式刷新再补
                it.icon = AppIconCache.peekAny(this, pkg);
                it.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                it.suspended = userId == 0 && suspended.contains(pkg);
                it.frozen = it.suspended || !ai.enabled;
            } catch (Exception e) {
                it.label = pkg;
            }
            if (userId != 0) {
                it.label += "（双开）";
            }
            map.put(key, it);
        }
        List<Item> list = new ArrayList<>();
        if (custom && !order.isEmpty()) {
            for (String pkg : order) {
                Item it = map.remove(pkg);
                if (it != null) {
                    list.add(it);
                }
            }
        }
        List<Item> rest = new ArrayList<>(map.values());
        Collections.sort(rest, FreezeListActivity::byDefault);
        list.addAll(rest);
        return list;
    }

    protected void reload() {
        Set<String> wanted = pkgs();
        List<String> order = savedOrder();
        boolean custom = customOrder();
        // 冷启动首帧：不查 shell（su/pm 命令要几百毫秒），先用 PackageInfo 自带状态画出来，
        // 后台真值回来后再刷一次，避免白屏等命令。
        if (!loadedOnce && items.isEmpty() && !wanted.isEmpty()) {
            items.addAll(quickItems(wanted, order, custom));
            render();
        }
        IO.execute(() -> {
            Set<String> disabled = disabledPackages();
            boolean truthOk = disabled != null;
            if (disabled == null) {
                // 真值没读到：下面按 PackageInfo.enabled 兜底，不能一律当成未冻结
                disabled = new HashSet<>();
            }
            // 降级通道（pm suspend）的包不会出现在 pm list packages -d 里。
            // 首屏只用本机暂停缓存渲染；逐个 dumpsys 复核耗时（18 个包约 1.2s），
            // 放到渲染完成后的后台做，见 verifySuspendedAsync()
            Set<String> suspended = new HashSet<>(SuspendSupport.cached(this));
            // 分身按各自用户读一次停用真值，主用户与分身的状态互不影响
            Map<Integer, Set<String>> cloneDisabled = new HashMap<>();
            for (String key : wanted) {
                int uid = userIdPart(key);
                if (uid != 0 && !cloneDisabled.containsKey(uid)) {
                    cloneDisabled.put(uid, disabledPackagesFor(uid));
                }
            }
            PackageManager pm = getPackageManager();
            Map<String, Item> map = new HashMap<>();
            Set<String> kept = new HashSet<>();
            Set<String> removed = new HashSet<>();
            for (String key : wanted) {
                String pkg = packagePart(key);
                int userId = userIdPart(key);
                Item it = new Item();
                it.pkg = key;
                it.userId = userId;
                ApplicationInfo ai = null;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    // 已卸载：从名单（及标签归属）里移除
                    removed.add(key);
                    continue;
                } catch (Exception ignored) {
                }
                if (ai != null) {
                    it.label = pm.getApplicationLabel(ai).toString();
                    // 首屏只取内存缓存，未命中的图标在下面并行解码，避免串行 loadIcon 拖慢
                    it.icon = AppIconCache.peek(this, pkg);
                    it.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                } else {
                    it.label = pkg;
                }
                if (userId == 0) {
                    it.suspended = suspended.contains(pkg);
                    it.frozen = truthOk ? (disabled.contains(pkg) || it.suspended)
                            : (ai != null && !ai.enabled);
                } else {
                    Set<String> cloneTruth = cloneDisabled.get(userId);
                    it.frozen = cloneTruth != null ? cloneTruth.contains(pkg)
                            : (ai != null && !ai.enabled);
                }
                if (userId != 0) {
                    it.label += "（双开）";
                }
                kept.add(key);
                map.put(key, it);
            }
            if (!removed.isEmpty()) {
                for (String key : removed) {
                    appTabs.remove(key);
                }
                saveAppTabs();
            }
            if (!kept.equals(wanted)) {
                prefs().edit().putStringSet(KEY_PKGS, kept).apply();
                SwipeFreeze.syncAsync(this);
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
            // 缓存未命中的图标并行解码：串行 loadIcon 每个 5~20ms，几十个就要几百毫秒。
            // 解码线程统一由 AppIconCache 的线程池承担，最多等 800ms，超时先用占位图标渲染。
            List<Item> needIcon = new ArrayList<>();
            for (Item it : list) {
                if (it.icon == null) {
                    needIcon.add(it);
                }
            }
            if (!needIcon.isEmpty()) {
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(needIcon.size());
                for (Item it : needIcon) {
                    final Item target = it;
                    AppIconCache.EXEC.execute(() -> {
                        try {
                            String name = packagePart(target.pkg);
                            ApplicationInfo info = pm.getApplicationInfo(name, 0);
                            Drawable icon = AppIconCache.load(this, name, info);
                            if (icon != null) {
                                target.icon = icon;
                            }
                        } catch (Exception ignored) {
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                try {
                    latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                items.clear();
                items.addAll(list);
                loadedOnce = true;
                render();
                // 上面等图标最多 800ms，超时的那几个继续在后台补，补到再重绘一次
                loadPendingIconsAsync();
                verifySuspendedAsync();
                if (pendingRevealPkg != null) {
                    String pkg = pendingRevealPkg;
                    pendingRevealPkg = null;
                    revealAdded(pkg);
                }
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
                    runBatch(ACTION_FREEZE.equals(act), batchTargets());
                }
            });
        });
    }

    /** 超时仍未拿到图标的项，渲染后继续在后台补；全部补完再重绘一次。 */
    private void loadPendingIconsAsync() {
        final List<Item> pending = new ArrayList<>();
        for (Item it : items) {
            if (it.icon == null) {
                pending.add(it);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger left =
                new java.util.concurrent.atomic.AtomicInteger(pending.size());
        final PackageManager pm = getPackageManager();
        for (final Item it : pending) {
            AppIconCache.EXEC.execute(() -> {
                Drawable icon = null;
                try {
                    String name = packagePart(it.pkg);
                    ApplicationInfo info = pm.getApplicationInfo(name, 0);
                    icon = AppIconCache.load(this, name, info);
                } catch (Exception ignored) {
                }
                final Drawable loaded = icon;
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    it.icon = loaded;
                    if (left.decrementAndGet() == 0) {
                        render();
                    }
                });
            });
        }
    }

    /**
     * 渲染后复核暂停真值：列表首屏不阻塞，后台逐个 dumpsys 查缓存里的包，
     * 只在真值与缓存不一致（且真值非空，避免一次读失败把缓存清空）时回写并重刷一次。
     */
    private void verifySuspendedAsync() {
        if (skipSuspendVerify > 0) {
            skipSuspendVerify--;
            return;
        }
        final Set<String> cached = SuspendSupport.cached(this);
        if (cached.isEmpty() || !suspendVerifying.compareAndSet(false, true)) {
            return;
        }
        IO.execute(() -> {
            Set<String> truth = SuspendSupport.suspendedAmong(cached);
            suspendVerifying.set(false);
            if (truth.isEmpty() || truth.equals(cached)) {
                return;
            }
            SuspendSupport.replace(this, truth);
            skipSuspendVerify = 1;
            main.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    reload();
                }
            });
        });
    }

    /**
     * 系统真值：pm list packages -d。
     * 返回 null 表示这次没读到（超时 / 命令失败）；正常执行但一个冻结项都没有时返回空集合，
     * 不能把「空」当成读失败，否则会把全部条目渲染成未冻结。
     */
    private Set<String> disabledPackages() {
        return disabledPackagesFor(0);
    }

    /** 读指定用户下的停用包；分身必须按自己的用户查，不能共用 user 0 的结果。 */
    private Set<String> disabledPackagesFor(int userId) {
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
        // 一行 package: 都没有：退出码 0 表示确实没有冻结应用，非 0 才算读失败
        if (!any && r.code != 0) {
            return null;
        }
        return set;
    }

    /** 当前标签 + 搜索取交集；标签内渲染与「仅当前标签」的批量操作都走这里。 */
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

    /**
     * 一键冻结 / 长按解冻的处理范围：
     * 勾选「仅冻结/解冻当前标签」时等于当前标签（含搜索过滤），否则是整份名单（只保留搜索过滤）。
     */
    private List<Item> batchTargets() {
        if (batchCurrentTabOnly) {
            return filtered();
        }
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Item> shown = new ArrayList<>();
        for (Item it : items) {
            if (!q.isEmpty()
                    && !it.label.toLowerCase(Locale.ROOT).contains(q)
                    && !it.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            shown.add(it);
        }
        return shown;
    }

    /** 网格初始化：列数跟着 gridColumns 走，视图由 RecyclerView 复用。 */
    private void setupGrid() {
        if (grid == null) {
            return;
        }
        grid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(
                this, Math.max(2, gridColumns)));
        gridAdapter = new GridAdapter();
        grid.setAdapter(gridAdapter);
        itemTouchHelper = new androidx.recyclerview.widget.ItemTouchHelper(new DragCallback());
        itemTouchHelper.attachToRecyclerView(grid);
    }

    /** 列数变化只改 spanCount，不重建行。 */
    private void syncSpanCount() {
        if (grid == null || gridAdapter == null) {
            return;
        }
        if (!(grid.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager)) {
            return;
        }
        androidx.recyclerview.widget.GridLayoutManager lm =
                (androidx.recyclerview.widget.GridLayoutManager) grid.getLayoutManager();
        int span = Math.max(2, gridColumns);
        if (lm.getSpanCount() != span) {
            lm.setSpanCount(span);
            gridAdapter.notifyDataSetChanged();
        }
    }

    /** 切标签/回默认标签后回到顶部：RecyclerView 不会自然回顶，必须显式滚动。 */
    private void scrollToTop() {
        if (grid != null) {
            grid.scrollToPosition(0);
        }
    }

    /**
     * 添加应用回来后定位新项：按应用实际类型落到对应标签，
     * 当前标签或搜索词挡住它时切到能看见的状态并滚过去，避免误以为没加上。
     */
    private void revealAdded(String pkg) {
        Item added = null;
        for (Item it : items) {
            if (it.pkg.equals(pkg)) {
                added = it;
                break;
            }
        }
        if (added == null) {
            return;
        }
        String tabId = tabIdOf(added);
        Tab tab = findTab(tabId);
        if (tab == null || !tab.visible) {
            Toast.makeText(this, "已加入名单，但所在标签已隐藏", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!tabId.equals(currentTabId)) {
            // 实际类型与发起标签不一致：切到新项真正所属的标签，并说明去了哪
            currentTabId = tabId;
            prefs().edit().putString(KEY_CURRENT_TAB, tabId).apply();
            renderTabBar();
            setSysHintVisible();
            render();
            Toast.makeText(this, "已加入「" + tab.name + "」", Toast.LENGTH_SHORT).show();
        }
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()
                && !added.label.toLowerCase(Locale.ROOT).contains(q)
                && !added.pkg.toLowerCase(Locale.ROOT).contains(q)) {
            search.setText("");
            render();
        }
        int idx = filtered().indexOf(added);
        if (idx >= 0 && grid != null) {
            grid.scrollToPosition(idx);
        }
    }

    /** 列表刷新只换数据，视图由 RecyclerView 就近复用。 */
    private void render() {
        if (grid == null) {
            return;
        }
        List<Item> shown = filtered();
        // 选中项被切标签 / 搜索 / 刷新筛掉后立刻取消选中，避免下次点击被当成「第二次点击」
        if (selectedPkg != null) {
            boolean stillVisible = false;
            for (Item it : shown) {
                if (it.pkg.equals(selectedPkg)) {
                    stillVisible = true;
                    break;
                }
            }
            if (!stillVisible) {
                selectedPkg = null;
            }
        }
        updateCount(shown);
        if (emptyState != null) {
            emptyState.setVisibility(loadedOnce && shown.isEmpty() ? View.VISIBLE : View.GONE);
        }
        syncSpanCount();
        if (gridAdapter != null) {
            gridAdapter.submit(shown);
        }
        reapplyFullscreenSurfaces();
        // render 之后 cell 都绑定好了；如果还在多选态，补上抖动和选中圈
        refreshAllCellSelection();
        refreshSelectStrip();
    }

    private void updateCount(List<Item> shown) {
        boolean empty = shown.isEmpty();
        if (count != null) {
            String hint;
            if (items.isEmpty()) {
                hint = "名单为空，点右上角「+」添加应用";
            } else if (empty) {
                hint = "当前筛选下没有应用";
            } else if (editMode) {
                hint = "编辑模式：点图标右上角减号移出名单";
            } else if (selectMode) {
                hint = "多选模式：点图标选择，长按已选图标操作，返回退出";
            } else if (!panelMode) {
                // 全屏模式不再显示「当前筛选 N 个，点击打开，长按打开菜单」这行提示
                hint = null;
            } else {
                hint = "当前筛选 " + shown.size() + " 个"
                        + (customOrder() ? " · 自定义顺序" : "")
                        + (moveMode ? "，点击选中或长按拖动可换位"
                                    : "，点击打开，长按打开菜单");
            }
            count.setText(hint == null ? "" : hint);
            count.setVisibility(hint == null ? View.GONE : View.VISIBLE);
        }
        freezeBtn.setAlpha(empty || working ? 0.4f : 1f);
        freezeBtn.setEnabled(!empty && !working);
    }

    /** 搭一个格子并接好交互；显示内容由 bindCellTo 在复用时刷新。 */
    private Cell buildCellView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        // 纯图标格子：按下/长按都不要水波纹、阴影或任何背景变化，外观全程不变
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 6));
        box.setClickable(true);
        box.setLongClickable(true);
        androidx.recyclerview.widget.RecyclerView.LayoutParams lp =
                new androidx.recyclerview.widget.RecyclerView.LayoutParams(
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

        // 暂停标识固定在右下角，和右上角的移除减号各占一角，移动/选中态都不会互相压住
        SuspendBadge suspendBadge = new SuspendBadge(this);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 14), Ui.dp(this, 14));
        sLp.gravity = Gravity.BOTTOM | Gravity.END;
        sLp.bottomMargin = Ui.dp(this, 1);
        sLp.rightMargin = Ui.dp(this, 1);
        suspendBadge.setLayoutParams(sLp);
        suspendBadge.setVisibility(View.GONE);
        iconBox.addView(suspendBadge);
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
        cell.iconBox = iconBox;
        cell.icon = icon;
        cell.name = name;
        cell.badge = badge;
        cell.suspendBadge = suspendBadge;
        // 把 Cell 挂到 root 上：多选态要靠它反查可见格子的抖动/选中圈
        box.setTag(cell);
        // 监听器不绑定具体应用：拖拽换位后只重绑数据，点击/长按跟着当前数据走
        badge.setOnClickListener(v -> {
            if (cell.item == null) {
                return;
            }
            if (editMode) {
                // 全屏减号：移出名单并解冻
                removeNow(cell.item);
            } else {
                // 半屏快捷移除：等同长按菜单里的「从列表移除」，并同步重建划卡名单
                removeFromList(cell.item);
            }
        });
        box.setOnClickListener(v -> {
            Item cur = cell.item;
            if (cur == null || editMode) {
                return;
            }
            // 长按刚触发过（已弹出操作单/进入多选）：吞掉这次松手带来的点击
            if (android.os.SystemClock.uptimeMillis() - lastLongPressAt < 800) {
                return;
            }
            // 多选模式下点击 → toggle 选中
            if (selectMode) {
                toggleSelect(cur);
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
            // 按下后只要发生可见滑动就不再算长按，宁可不出菜单也不误触
            float dx = cell.lastX - cell.downX;
            float dy = cell.lastY - cell.downY;
            int slop = ViewConfiguration.get(this).getScaledTouchSlop();
            if (dx * dx + dy * dy > (float) slop * slop) {
                return;
            }
            lastLongPressAt = android.os.SystemClock.uptimeMillis();
            if (moveMode) {
                startDrag(box, cell.item);
                return;
            }
            box.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            if (selectMode) {
                // 多选态：长按已圈选的图标 → 弹批量操作单；长按未选的 → 先圈上
                if (selectedPkgs.contains(cell.item.pkg)) {
                    showBatchSheet();
                } else {
                    toggleSelect(cell.item);
                }
                return;
            }
            showAppSheet(cell.item);
        };
        box.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    cell.downX = e.getRawX();
                    cell.downY = e.getRawY();
                    cell.lastX = cell.downX;
                    cell.lastY = cell.downY;
                    // 不抢父容器的事件：RecyclerView 需要正常收到滑动才能滚动列表；
                    // 手指移动超过系统触摸抖动阈值时下面会取消长按计时，避免误弹菜单
                    v.postDelayed(cell.longPress, LONG_PRESS_MS);
                    break;
                case android.view.MotionEvent.ACTION_MOVE: {
                    cell.lastX = e.getRawX();
                    cell.lastY = e.getRawY();
                    float dx = cell.lastX - cell.downX;
                    float dy = cell.lastY - cell.downY;
                    // 阈值取系统 slop（约 8dp）：一旦发生可见滑动就取消长按，只走列表滚动
                    int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                    if (dx * dx + dy * dy > (float) slop * slop) {
                        v.removeCallbacks(cell.longPress);
                    }
                    break;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(cell.longPress);
                    break;
                default:
                    break;
            }
            // 不消费事件：点击、列表滚动仍按原逻辑走
            return false;
        });
        // 移动模式下长按拖动：由自定义 400ms 长按交给 ItemTouchHelper；普通模式长按弹操作单
        return cell;
    }

    /**
     * 网格适配器：屏幕外的格子不再创建，滚出屏幕的格子回收复用。
     * 数据源始终是 filtered() 的结果，位置即「可见顺序」。
     */
    private final class GridAdapter extends
            androidx.recyclerview.widget.RecyclerView.Adapter<GridAdapter.VH> {

        private final List<Item> shown = new ArrayList<>();

        final class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final Cell cell;

            VH(LinearLayout root, Cell cell) {
                super(root);
                this.cell = cell;
            }
        }

        void submit(List<Item> data) {
            shown.clear();
            shown.addAll(data);
            notifyDataSetChanged();
        }

        /** 当前可见顺序的快照：计数文案与按项刷新都以它为准。 */
        List<Item> shown() {
            return new ArrayList<>(shown);
        }

        /**
         * 按项刷新：只重绑当前挂在屏幕上的格子，不 notifyDataSetChanged，
         * 因此不会重建视图、不会跳滚动位置（冻结/解冻后的状态更新走这里）。
         */
        void refreshBound() {
            if (grid == null) {
                return;
            }
            for (int i = 0; i < grid.getChildCount(); i++) {
                androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                        grid.getChildViewHolder(grid.getChildAt(i));
                if (!(vh instanceof VH)) {
                    continue;
                }
                int pos = vh.getBindingAdapterPosition();
                if (pos >= 0 && pos < shown.size()) {
                    bindCellTo(((VH) vh).cell, shown.get(pos));
                }
            }
        }

        int positionOf(String pkg) {
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).pkg.equals(pkg)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 拖拽换位：先换可见顺序，再把新顺序回写到 items 里这些可见项占的槽位，
         * 隐藏项保持原槽位不动，落盘顺序与屏幕上看到的一致。
         */
        void moveRow(int from, int to) {
            if (from < 0 || to < 0 || from >= shown.size() || to >= shown.size() || from == to) {
                return;
            }
            Item moving = shown.remove(from);
            shown.add(to, moving);
            Set<Item> visible = new HashSet<>(shown);
            int k = 0;
            for (int i = 0; i < items.size(); i++) {
                if (visible.contains(items.get(i))) {
                    items.set(i, shown.get(k++));
                }
            }
            notifyItemMoved(from, to);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            Cell cell = buildCellView();
            return new VH(cell.root, cell);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            bindCellTo(holder.cell, shown.get(position));
        }
    }

    /** 移动模式的长按拖动：由格子自己的 400ms 长按触发，松手时落盘自定义顺序。 */
    private final class DragCallback extends androidx.recyclerview.widget.ItemTouchHelper.Callback {

        @Override
        public int getMovementFlags(androidx.recyclerview.widget.RecyclerView rv,
                                    androidx.recyclerview.widget.RecyclerView.ViewHolder vh) {
            if (!moveMode || editMode) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(
                    androidx.recyclerview.widget.ItemTouchHelper.UP
                            | androidx.recyclerview.widget.ItemTouchHelper.DOWN
                            | androidx.recyclerview.widget.ItemTouchHelper.LEFT
                            | androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            // 系统长按是 500ms；统一走格子自己的 400ms 计时，避免两套长按打架
            return false;
        }

        @Override
        public boolean canDropOver(androidx.recyclerview.widget.RecyclerView rv,
                                   androidx.recyclerview.widget.RecyclerView.ViewHolder current,
                                   androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
            return target instanceof GridAdapter.VH;
        }

        @Override
        public boolean onMove(androidx.recyclerview.widget.RecyclerView rv,
                              androidx.recyclerview.widget.RecyclerView.ViewHolder vh,
                              androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
            int from = vh.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION
                    || to == androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                return false;
            }
            if (gridAdapter != null) {
                gridAdapter.moveRow(from, to);
            }
            dragMoved = true;
            return true;
        }

        @Override
        public void onSwiped(androidx.recyclerview.widget.RecyclerView.ViewHolder vh, int direction) {
            // 只做拖拽换位，不做滑动删除
        }

        @Override
        public void onSelectedChanged(androidx.recyclerview.widget.RecyclerView.ViewHolder vh,
                                      int actionState) {
            super.onSelectedChanged(vh, actionState);
            if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG
                    && vh instanceof GridAdapter.VH) {
                Item it = ((GridAdapter.VH) vh).cell.item;
                draggingPkg = it == null ? null : it.pkg;
                // 开始拖拽就丢掉选中态：先把原来那格的选中框就地擦掉（拖拽中不能整表 notify）
                if (selectedPkg != null && grid != null) {
                    for (int i = 0; i < grid.getChildCount(); i++) {
                        androidx.recyclerview.widget.RecyclerView.ViewHolder h =
                                grid.getChildViewHolder(grid.getChildAt(i));
                        if (h instanceof GridAdapter.VH) {
                            Cell c = ((GridAdapter.VH) h).cell;
                            if (c.item != null && selectedPkg.equals(c.item.pkg)) {
                                h.itemView.setBackground(null);
                            }
                        }
                    }
                }
                selectedPkg = null;
                vh.itemView.setAlpha(0.35f);
            }
        }

        @Override
        public void clearView(androidx.recyclerview.widget.RecyclerView rv,
                              androidx.recyclerview.widget.RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            vh.itemView.setAlpha(1f);
            draggingPkg = null;
            if (dragMoved) {
                persistOrder();
                // 顺序落盘后补一次计数文案（「自定义顺序」标记要立刻出现）
                updateCount(gridAdapter == null ? filtered() : gridAdapter.shown());
            }
            dragMoved = false;
        }
    }

    /** 把一个格子的显示内容换成指定应用（含冻结灰度、移除角标、移动模式选中态）。 */
    private void bindCellTo(Cell cell, Item it) {
        // 复用前先撤掉上一轮的长按计时，避免滑出屏幕的格子把定时器带进新数据
        cell.root.removeCallbacks(cell.longPress);
        cell.item = it;
        if (it.icon != null) {
            cell.icon.setImageDrawable(it.icon);
        } else {
            cell.icon.setImageResource(R.drawable.ic_launcher);
        }
        cell.name.setText(it.label);
        cell.root.setContentDescription(it.label);
        // 编辑模式（全屏减号）或半屏勾选「快捷移除图标」时常驻红标；移动模式下让位给选中/拖动
        boolean showBadge = editMode || (panelMode && quickRemove && !moveMode);
        cell.badge.setVisibility(showBadge ? View.VISIBLE : View.GONE);
        // 暂停标识只跟随状态：正常 disable-user 冻结的图标不加任何标识
        cell.suspendBadge.setVisibility(it.suspended ? View.VISIBLE : View.GONE);
        if (it.frozen) {
            cell.icon.setColorFilter(GRAY);
            cell.icon.setAlpha(0.45f);
        } else {
            cell.icon.clearColorFilter();
            cell.icon.setAlpha(1f);
        }
        cell.root.setBackground(moveMode && it.pkg.equals(selectedPkg) ? selectedBackground() : null);
        cell.root.setAlpha(moveMode && it.pkg.equals(draggingPkg) ? 0.35f : 1f);
        // 布局参数在绑定里同步：列数 / 图标大小 / 名称显隐改了以后，复用中的格子也能立刻跟上
        ViewGroup.LayoutParams rootLp = cell.root.getLayoutParams();
        if (rootLp != null && rootLp.width != computedCellPx()) {
            rootLp.width = computedCellPx();
            cell.root.setLayoutParams(rootLp);
        }
        int iconPx = computedIconPx();
        ViewGroup.LayoutParams boxLp = cell.iconBox.getLayoutParams();
        if (boxLp != null && (boxLp.width != iconPx || boxLp.height != iconPx)) {
            boxLp.width = iconPx;
            boxLp.height = iconPx;
            cell.iconBox.setLayoutParams(boxLp);
            ViewGroup.LayoutParams iconLp = cell.icon.getLayoutParams();
            if (iconLp != null) {
                iconLp.width = iconPx;
                iconLp.height = iconPx;
                cell.icon.setLayoutParams(iconLp);
            }
            android.graphics.drawable.Drawable round = cell.icon.getBackground();
            if (round instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) round)
                        .setCornerRadius(Math.max(Ui.dp(this, 6), (int) (iconPx * 0.18f)));
            }
        }
        cell.name.setVisibility(showName ? View.VISIBLE : View.GONE);
        // 滚动复用/重绑时同步多选视觉：圈跟随当前选中集合
        if (selectMode && selectedPkgs.contains(it.pkg)) addSelectRing(cell); else removeSelectRing(cell);
    }

    /** 按项刷新：只让可见格子重绑数据，不重建视图、不动滚动位置。 */
    private void rebindCells() {
        if (gridAdapter == null) {
            return;
        }
        updateCount(gridAdapter.shown());
        gridAdapter.refreshBound();
        reapplyFullscreenSurfaces();
        // rebind 之后 cell 被 adapter 复用/重绑，多选态要重新补抖动和选中圈
        refreshAllCellSelection();
    }

    // ---------------- 点击移动图标（移动模式） ----------------

    /** 移动模式选中框：沿用主色蓝做浅底 + 细边，和页面现有的蓝色按钮同一套观感。 */
    private android.graphics.drawable.Drawable selectedBackground() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(0x1F4E7CF6);
        g.setCornerRadius(Ui.dp(this, 14));
        g.setStroke(Math.max(1, Ui.dp(this, 2)), getColor(R.color.blue));
        return g;
    }

    /** 移动模式：第一次点击选中并高亮，第二次点目标项即插到目标前面并实时落盘。 */
    private void onMoveClick(Item cur) {
        if (selectedPkg == null) {
            selectedPkg = cur.pkg;
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

    /** 400ms 长按触发：把当前格子交给 ItemTouchHelper 接管拖拽，松手时由 clearView 落盘。 */
    private void startDrag(View cell, Item it) {
        if (grid == null || gridAdapter == null || it == null) {
            return;
        }
        int pos = gridAdapter.positionOf(it.pkg);
        androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                pos >= 0 ? grid.findViewHolderForAdapterPosition(pos) : null;
        if (vh == null) {
            return;
        }
        draggingPkg = it.pkg;
        dragMoved = false;
        selectedPkg = null;
        cell.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        itemTouchHelper.startDrag(vh);
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
        AlertDialog pick = new AlertDialog.Builder(this)
                .setTitle("移动「" + it.label + "」到…")
                .setItems(names, (d, w) -> moveToTab(it, targets.get(w)))
                .setNegativeButton("取消", null)
                .create();
        pick.show();
        Ui.roundAlertDialog(pick);
    }

    private void moveToTab(Item it, Tab target) {
        appTabs.put(it.pkg, target.id);
        saveAppTabs();
        Toast.makeText(this, "已移动到「" + target.name + "」", Toast.LENGTH_SHORT).show();
        render();
    }

    // ---------------- 长按底部操作单 ----------------

    /** 多选入口：长按菜单里点了「多选」→ sheet dismiss 后调用；进入多选态。 */
    private void enterSelectMode() {
        selectMode = true;
        selectedPkgs.clear();
        Toast.makeText(this, "已进入多选模式：点图标选择，长按已选图标操作", Toast.LENGTH_SHORT).show();
        updateSelectHint();
        refreshSelectStrip();
    }

    /** 退出多选：清选中、移除圈、收起顶部图标条。 */
    private void exitSelectMode() {
        selectMode = false;
        selectedPkgs.clear();
        for (Cell c : allCells()) {
            removeSelectRing(c);
        }
        if (count != null && gridAdapter != null) updateCount(gridAdapter.shown());
        refreshSelectStrip();
    }

    /** 多选态下更新顶部提示文案（不显示数量，选中项用图标横排展示）。 */
    private void updateSelectHint() {
        if (count != null && selectMode) {
            count.setText("多选模式：点图标选择，长按已选图标操作，返回退出");
        }
    }

    /** 面板顶部的已选图标横排：懒加载插入在列表上方，选中增减时实时重建。 */
    private void ensureSelectStrip() {
        if (selectStrip != null && selectStrip.getParent() != null) return;
        if (grid == null || !(grid.getParent() instanceof ViewGroup)) return;
        ViewGroup holder = (ViewGroup) grid.getParent();
        // 全屏：grid 直接放在竖向 LinearLayout 里，插在 grid 上方
        // 半屏：grid 在带 weight 的 FrameLayout 里，需要插到面板 LinearLayout 中该容器上方
        ViewGroup host;
        int idx;
        if (holder instanceof LinearLayout) {
            host = holder;
            idx = holder.indexOfChild(grid);
        } else if (holder.getParent() instanceof LinearLayout) {
            host = (ViewGroup) holder.getParent();
            idx = host.indexOfChild(holder);
        } else {
            host = holder;
            idx = holder.indexOfChild(grid);
        }

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipToPadding(false);
        hsv.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 6));
        hsv.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int idx2 = idx < 0 ? host.getChildCount() : idx;
        if (host instanceof LinearLayout) {
            host.addView(hsv, idx2, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            host.addView(hsv, idx2);
        }
        selectStrip = hsv;
        selectStripRow = row;
    }

    /** 按当前选中集合重建顶部图标条；退出多选时整条隐藏。 */
    private void refreshSelectStrip() {
        if (selectMode) ensureSelectStrip();
        if (selectStrip == null || selectStripRow == null) return;
        selectStripRow.removeAllViews();
        if (!selectMode) {
            selectStrip.setVisibility(View.GONE);
            return;
        }
        int sz = Ui.dp(this, 30);
        int gap = Ui.dp(this, 6);
        for (Item it : items) {
            if (!selectedPkgs.contains(it.pkg)) continue;
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(it.icon != null ? it.icon
                    : androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_launcher));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setContentDescription(it.label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.rightMargin = gap;
            selectStripRow.addView(iv, lp);
        }
        selectStrip.setVisibility(selectStripRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** 多选态下点击 cell 切换选中圈。 */
    private void toggleSelect(Item it) {
        if (it == null) return;
        Cell c = findCell(it.pkg);
        boolean already = selectedPkgs.contains(it.pkg);
        if (already) {
            selectedPkgs.remove(it.pkg);
            if (c != null) removeSelectRing(c);
        } else {
            selectedPkgs.add(it.pkg);
            if (c != null) addSelectRing(c);
        }
        // 同一屏幕上可能有别的 cell 状态变了（一般不会，但以防复用错位）
        for (Cell x : allCells()) {
            if (x.item != null && selectedPkgs.contains(x.item.pkg)) addSelectRing(x);
        }
        updateSelectHint();
        refreshSelectStrip();
    }

    /** 遍历可见 cell：按当前选中集合补圈/去圈。 */
    private void refreshAllCellSelection() {
        for (Cell c : allCells()) {
            if (c.item == null || c.iconBox == null) continue;
            if (selectedPkgs.contains(c.item.pkg)) addSelectRing(c); else removeSelectRing(c);
        }
    }

    /** 给 iconBox 加选中圈：画在图标上层，半透明主色填充 + 3dp 描边，避免被应用图标盖住。 */
    private void addSelectRing(Cell c) {
        if (c.iconBox == null) return;
        if (c.selectRing != null) return;
        View ring = new View(this);
        int strokePx = Math.max(2, (int) (3f * getResources().getDisplayMetrics().density));
        int radius = Math.max(Ui.dp(this, 6), (int) (computedIconPx() * 0.18f));
        int primary = Ui.primary(this);
        android.graphics.drawable.GradientDrawable draw = new android.graphics.drawable.GradientDrawable();
        draw.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        draw.setColor((primary & 0x00FFFFFF) | 0x33000000);
        draw.setCornerRadius(radius);
        draw.setStroke(strokePx, primary);
        ring.setBackground(draw);
        ring.setClickable(false);
        ring.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // 加在最后一层：图标、角标都在它下面，圈完整可见
        c.iconBox.addView(ring, lp);
        c.selectRing = ring;
    }

    private void removeSelectRing(Cell c) {
        if (c.iconBox == null || c.selectRing == null) return;
        c.iconBox.removeView(c.selectRing);
        c.selectRing = null;
    }

    /** 按包名找可见 cell（多选时包名和 cell.item 一一对应，不会冲突）。 */
    private Cell findCell(String pkg) {
        for (Cell c : allCells()) {
            if (c.item != null && pkg.equals(c.item.pkg)) return c;
        }
        return null;
    }

    /** 取当前屏幕上所有可见 cell（grid 里的 viewHolder 集合）。 */
    private List<Cell> allCells() {
        List<Cell> out = new ArrayList<>();
        if (grid == null) return out;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof Cell) out.add((Cell) tag);
        }
        return out;
    }

    /** 长按不拖动松手：底部半屏操作单（冰箱式），拖动排序不进这里。 */
    private void showAppSheet(Item it) {
        // 选项框背景与宿主页同一档透明度：半屏取 panel_surface，全屏取 full_surface
        int sheetAlpha = panelMode
                ? prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID)
                : prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID);
        AppActionSheet.show(this, it.label, it.icon, it.frozen, srcMode(), sheetAlpha, new AppActionSheet.Actions() {
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
            public void removeFromList() {
                FreezeListActivity.this.removeFromList(it);
            }

            @Override
            public void removeAndUnfreeze() {
                FreezeListActivity.this.removeAndUnfreeze(it);
            }

            @Override
            public boolean multiSelectEnabled() {
                return true;
            }

            @Override
            public void startMultiSelect() {
                enterSelectMode();
                toggleSelect(it);
            }
        });
    }

    // ---------------- 多选批量操作单 ----------------

    /** 多选态下第二次长按触发：底部批量操作单，复用 AppActionSheet 视觉风格。 */
    private void showBatchSheet() {
        if (selectedPkgs.isEmpty()) return;
        batchTargets.clear();
        for (Item it : items) {
            if (selectedPkgs.contains(it.pkg)) batchTargets.add(it);
        }
        final int n = batchTargets.size();

        final android.graphics.Bitmap wallpaper =
                getSharedPreferences(WallpaperSource.PREF, MODE_PRIVATE)
                        .getBoolean(WallpaperSource.KEY_SHEET_WALLPAPER, false)
                        ? WallpaperSource.sheetBitmap(this, srcMode()) : null;
        final boolean onWallpaper = wallpaper != null;
        final int fg = onWallpaper ? android.graphics.Color.WHITE : Ui.TEXT_PRIMARY;
        final int iconFg = onWallpaper ? android.graphics.Color.WHITE : Ui.primary(this);
        final int lineColor = onWallpaper ? 0x33FFFFFF : Ui.color(this, R.color.divider);

        final com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_Jin_Sheet);

        android.widget.FrameLayout outer = new android.widget.FrameLayout(this);
        outer.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 56));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Drawable cardBg;
        if (onWallpaper) {
            cardBg = new android.graphics.drawable.LayerDrawable(new Drawable[]{
                    new WallpaperDrawable(wallpaper, 0),
                    new android.graphics.drawable.ColorDrawable(0x66000000)});
        } else {
            cardBg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_dialog).mutate();
        }
        content.setBackground(cardBg);
        content.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        final int radius = Ui.promptRadius(this);
        content.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        content.setClipToOutline(true);
        outer.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部已选图标横排：替代「已选 N 项」文字，横向可滚动
        HorizontalScrollView headScroll = new HorizontalScrollView(this);
        headScroll.setHorizontalScrollBarEnabled(false);
        headScroll.setClipToPadding(false);
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headRow.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 10));
        int headIcon = Ui.dp(this, 34);
        int headGap = Ui.dp(this, 6);
        for (Item it : batchTargets) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(it.icon != null ? it.icon
                    : androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_launcher));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setContentDescription(it.label);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(headIcon, headIcon);
            ilp.rightMargin = headGap;
            headRow.addView(iv, ilp);
        }
        headScroll.addView(headRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(headScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        batchDivider(content, lineColor);

        // 先扫一遍选中项，看 frozen / unfrozen 各有多少（按钮上标数量，但两个都常驻）
        int frozenCnt = 0;
        int unfrozenCnt = 0;
        for (Item it : batchTargets) {
            if (it.frozen) frozenCnt++; else unfrozenCnt++;
        }
        batchItem(R.drawable.ic_sc_freeze, "冻结（" + unfrozenCnt + "）",
                () -> batchFreeze(), sheet, fg, iconFg, content, true);
        batchItem(R.drawable.ic_sc_unfreeze, "解冻（" + frozenCnt + "）",
                () -> batchUnfreeze(), sheet, fg, iconFg, content, true);
        batchDivider(content, lineColor);
        batchItem(R.drawable.ic_action_tag, "更改标签页", () -> batchMoveTab(), sheet, fg, iconFg, content, true);
        batchItem(R.drawable.ic_action_home, "添加到桌面", () -> batchPinHome(), sheet, fg, iconFg, content, true);
        batchItem(R.drawable.ic_minus, "从列表移除", () -> batchRemove(false), sheet, fg, iconFg, content, true);
        batchDivider(content, lineColor);
        batchItem(R.drawable.ic_action_circle_unlock, "移除列表并解冻", () -> batchRemove(true), sheet, fg, iconFg, content, true);
        batchDivider(content, lineColor);
        batchItem(R.drawable.ic_back, "返回继续操作",
                () -> sheet.dismiss(), sheet, fg, iconFg, content, false);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(outer);
        sheet.setContentView(sv);
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        sheet.setOnShowListener(d -> AppActionSheet.makeSheetTransparent(sheet));
        // 默认：弹窗收起即退出多选（点空白/返回键）
        sheet.setOnDismissListener(d -> exitSelectMode());
        sheet.show();
        AppActionSheet.makeSheetTransparent(sheet);
    }

    /**
     * @param exitOnDismiss true: 执行该操作后，dismiss 时退出多选；false: 仅关闭弹窗，保持多选态。
     */
    private void batchItem(int iconRes, String text, Runnable action,
                           com.google.android.material.bottomsheet.BottomSheetDialog sheet,
                           int fg, int iconFg, LinearLayout parent, boolean exitOnDismiss) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        row.setBackgroundResource(R.drawable.bg_menu_item);
        row.setClickable(true);
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(iconFg));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        TextView label = Ui.text(this, text, 13, fg, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 12);
        row.addView(label, lp);
        row.setOnClickListener(v -> {
            if (exitOnDismiss) {
                sheet.dismiss();
                action.run();
            } else {
                // 保持多选：先取消 dismiss 监听，再关闭弹窗
                sheet.setOnDismissListener(null);
                sheet.dismiss();
                action.run();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        rlp.leftMargin = Ui.dp(this, 5); rlp.rightMargin = Ui.dp(this, 5);
        parent.addView(row, rlp);
    }

    private void batchDivider(LinearLayout parent, int color) {
        View line = new View(this);
        line.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f)));
        lp.leftMargin = Ui.dp(this, 18);
        parent.addView(line, lp);
    }

    /** 批量冻结：IO 线程拼 pm disable-user（或 keepLauncherIcon 时 suspend）脚本执行，跑完 reload。 */
    private void batchFreeze() {
        if (working) {
            Toast.makeText(this, "正在执行其他操作，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Item> todo = new ArrayList<>();
        for (Item it : batchTargets) {
            if (!it.frozen) todo.add(it);
        }
        if (todo.isEmpty()) {
            Toast.makeText(this, "选中项都已经是冻结状态", Toast.LENGTH_SHORT).show();
            exitSelectMode();
            return;
        }
        final int targetCount = todo.size();
        working = true;
        exitSelectMode();
        Toast.makeText(this, "正在冻结 " + targetCount + " 个应用…", Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            int done = 0;
            for (Item it : todo) {
                boolean ok = false;
                if (keepLauncherIcon && it.userId == 0) {
                    // 与单个冻结一致：保留桌面图标走暂停通道（分身没有这个通道）
                    ok = suspendApplied(it.pkg,
                            SuspendSupport.suspend(this, packagePart(it.pkg), it.label));
                    if (ok) {
                        SuspendSupport.remember(this, Collections.singleton(packagePart(it.pkg)));
                        PrivShell.exec("am force-stop --user " + it.userId + " "
                                + packagePart(it.pkg), 10);
                    }
                } else {
                    PrivShell.Result r = execPm(it.pkg, it.userId, true);
                    ok = applied(it.pkg, true, r);
                    // 停用被 ROM 拦住时，和单个冻结一样自动降级为暂停
                    if (!ok && SuspendSupport.romBlocked(r.out)) {
                        ok = it.userId == 0
                            && suspendApplied(it.pkg, SuspendSupport.suspend(this, packagePart(it.pkg), it.label));
                        if (ok) SuspendSupport.remember(this, Collections.singleton(packagePart(it.pkg)));
                    }
                }
                if (ok) done++;
            }
            final int doneN = done;
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) return;
                reload();
                Toast.makeText(this, "已冻结 " + doneN + " 个应用"
                        + (doneN < targetCount ? "，失败 " + (targetCount - doneN) + " 个" : ""),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** 批量解冻：IO 线程拼 pm 脚本一次性执行，跑完回读暂停缓存再 reload。 */
    private void batchUnfreeze() {
        if (working) {
            Toast.makeText(this, "正在执行其他操作，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Item> frozen = new ArrayList<>();
        for (Item it : batchTargets) {
            if (it.frozen) {
                frozen.add(it);
            }
        }
        if (frozen.isEmpty()) {
            Toast.makeText(this, "选中项都不是冻结状态", Toast.LENGTH_SHORT).show();
            exitSelectMode();
            return;
        }
        final int targetCount = frozen.size();
        working = true;
        exitSelectMode();
        Toast.makeText(this, "正在解冻 " + targetCount + " 个应用…", Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            StringBuilder script = new StringBuilder();
            Set<String> suspended = new HashSet<>();
            for (Item it : frozen) {
                String name = packagePart(it.pkg);
                if (it.suspended && it.userId == 0) {
                    suspended.add(name);
                }
                script.append("pm unsuspend --user ").append(it.userId).append(' ')
                        .append(name).append(" >/dev/null 2>&1; ");
                script.append("pm enable --user ").append(it.userId).append(' ')
                        .append(name).append(" >/dev/null 2>&1; ");
            }
            PrivShell.exec(script.toString(), Math.max(30, 10 + frozen.size() * 5));
            Set<String> truth = SuspendSupport.suspendedAmong(suspended);
            SuspendSupport.remember(this, truth);
            main.post(() -> {
                working = false;
                reload();
                Toast.makeText(this, "已解冻 " + targetCount + " 个应用", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** 批量改标签页：弹一个标签选择框，选中后全部移过去。 */
    private void batchMoveTab() {
        List<Tab> targets = new ArrayList<>();
        Set<String> curLabels = new HashSet<>();
        for (Item it : batchTargets) {
            curLabels.add(tabIdOf(it));
        }
        for (Tab t : tabs) {
            if (!curLabels.contains(t.id)) targets.add(t);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "选中项已全部在同一标签，或只有一个标签", Toast.LENGTH_SHORT).show();
            exitSelectMode();
            return;
        }
        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            Tab t = targets.get(i);
            names[i] = t.visible ? t.name : t.name + "（已隐藏）";
        }
        int n = batchTargets.size();
        AlertDialog pick = new AlertDialog.Builder(this)
                .setTitle("把 " + n + " 个应用移动到…")
                .setItems(names, (d, w) -> {
                    Tab target = targets.get(w);
                    int moved = 0;
                    for (Item it : batchTargets) {
                        appTabs.put(it.pkg, target.id);
                        moved++;
                    }
                    saveAppTabs();
                    exitSelectMode();
                    render();
                    Toast.makeText(this, "已移动 " + moved + " 个到「" + target.name + "」", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", (d, w) -> exitSelectMode())
                .setOnCancelListener(d -> exitSelectMode())
                .create();
        pick.show();
        Ui.roundAlertDialog(pick);
    }

    /** 批量添加到桌面：逐个 pinToHome，每个会触发系统确认框。 */
    private void batchPinHome() {
        int n = batchTargets.size();
        int done = 0;
        for (Item it : batchTargets) {
            pinToHome(it);
            done++;
        }
        exitSelectMode();
        if (n > 1) {
            Toast.makeText(this, "已添加 " + done + " 个到桌面", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 批量从列表移除 / 移除并解冻。
     * @param alsoUnfreeze true = 移除后顺便解冻
     */
    private void batchRemove(boolean alsoUnfreeze) {
        if (working) {
            Toast.makeText(this, "正在执行其他操作，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Item> targets = new ArrayList<>(batchTargets);
        int n = targets.size();
        if (n == 0) {
            exitSelectMode();
            return;
        }
        Set<String> cur = pkgs();
        List<String> order = savedOrder();
        List<Item> toUnfreeze = new ArrayList<>();
        Set<String> suspended = new HashSet<>();
        for (Item it : targets) {
            cur.remove(it.pkg);
            appTabs.remove(it.pkg);
            order.remove(it.pkg);
            items.remove(it);
            if (alsoUnfreeze && it.frozen) {
                toUnfreeze.add(it);
                if (it.suspended && it.userId == 0) suspended.add(it.pkg);
            }
        }
        prefs().edit().putStringSet(KEY_PKGS, cur).apply();
        StringBuilder sb = new StringBuilder();
        for (String p : order) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        prefs().edit().putString(KEY_ORDER, sb.toString()).apply();

        if (!toUnfreeze.isEmpty()) {
            IO.execute(() -> {
                StringBuilder script = new StringBuilder();
                for (Item item : toUnfreeze) {
                    String name = packagePart(item.pkg);
                    script.append("pm unsuspend --user ").append(item.userId).append(' ')
                            .append(name).append(" >/dev/null 2>&1; ");
                    script.append("pm enable --user ").append(item.userId).append(' ')
                            .append(name).append(" >/dev/null 2>&1; ");
                }
                PrivShell.exec(script.toString(), Math.max(30, 10 + toUnfreeze.size() * 5));
                Set<String> truth = SuspendSupport.suspendedAmong(suspended);
                SuspendSupport.remember(this, truth);
            });
        }

        if (selectedPkg != null && !pkgs().contains(selectedPkg)) selectedPkg = null;
        if (draggingPkg != null && !pkgs().contains(draggingPkg)) draggingPkg = null;
        exitSelectMode();
        saveAppTabs();
        SwipeFreeze.syncAsync(this);
        reload();
        Toast.makeText(this, (alsoUnfreeze ? "已移除并解冻 " : "已从列表移除 ") + n + " 个应用", Toast.LENGTH_SHORT).show();
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
        working = true;
        startFreezeSpin();
        IO.execute(() -> {
            PrivShell.Result r;
            boolean ok;
            // 勾选「保留桌面图标」时直接走暂停通道：停用会让桌面图标消失，暂停不会
            // 暂停通道只覆盖主用户，分身只能走停用
            boolean keep = keepLauncherIcon && it.userId == 0;
            boolean blocked = false;
            boolean suspendedNow = false;
            if (keep) {
                r = SuspendSupport.suspend(this, packagePart(it.pkg), it.label);
                ok = suspendApplied(it.pkg, r);
                if (ok) {
                    suspendedNow = true;
                    SuspendSupport.remember(this, Collections.singleton(packagePart(it.pkg)));
                    // 暂停不结束已有进程，补一次 force-stop 让冻结立即生效
                    PrivShell.exec("am force-stop " + packagePart(it.pkg), 10);
                } else {
                    android.util.Log.w("JinPm", "suspend failed: code=" + r.code
                            + " timeout=" + r.timeout + "\n"
                            + (r.out == null ? "" : r.out.trim()));
                }
            } else {
                r = execPm(it.pkg, it.userId, true);
                ok = applied(it.pkg, true, r);
                // 系统应用被 ROM 拦住停用（两类固定文案）时，自动降级为 pm suspend 暂停（仅主用户）
                blocked = it.userId == 0 && !ok && SuspendSupport.romBlocked(r.out);
                if (blocked) {
                    PrivShell.Result s = SuspendSupport.suspend(this, packagePart(it.pkg), it.label);
                    if (suspendApplied(it.pkg, s)) {
                        ok = true;
                        suspendedNow = true;
                        SuspendSupport.remember(this, Collections.singleton(packagePart(it.pkg)));
                    } else {
                        android.util.Log.w("JinPm", "suspend fallback failed: code=" + s.code
                                + " timeout=" + s.timeout + "\n"
                                + (s.out == null ? "" : s.out.trim()));
                    }
                }
            }
            final boolean done = ok;
            final boolean suspendedFinal = suspendedNow;
            final boolean blockedFinal = blocked;
            main.post(() -> {
                stopFreezeSpin();
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!done) {
                    if (blockedFinal) {
                        // 停用、暂停两条通道都被拒：只给一句话，原始输出已进日志
                        showPmError("冻结失败", it, Ui.NO_FREEZE_CHANNEL);
                    } else {
                        showPmError("冻结失败", it, r, true);
                    }
                    return;
                }
                it.frozen = true;
                it.suspended = suspendedFinal;
                rebindCells();
                if (showFrozenTip) {
                    FrozenTip.show(this);
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
            PrivShell.Result r = execPm(it.pkg, it.userId, false);
            boolean ok = applied(it.pkg, false, r);
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    showPmError("解冻失败", it, r, false);
                    return;
                }
                it.frozen = false;
                it.suspended = false;
                rebindCells();
                reload();
            });
        });
    }

    /**
     * 勾选「留桌面图标」时，把名单里已停用（桌面图标已消失）的应用逐个迁到暂停态：
     * 先暂停，确认 suspended= 生效后再 pm enable 把图标放回桌面；暂停失败时保持原停用态，
     * 不会把应用误放出来。
     */
    private void migrateFrozenToSuspend() {
        if (working) {
            return;
        }
        final List<String> candidates = new ArrayList<>();
        for (Item it : items) {
            // 暂停通道只覆盖主用户，分身不参与切换
            if (it.userId == 0 && it.frozen && !it.suspended) {
                candidates.add(packagePart(it.pkg));
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        working = true;
        Toast.makeText(this, "正在把 " + candidates.size() + " 个已冻结应用切到暂停…",
                Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            execSuspendBatch(candidates);
            Set<String> suspended = SuspendSupport.suspendedAmong(candidates);
            SuspendSupport.remember(this, suspended);
            if (!suspended.isEmpty()) {
                StringBuilder script = new StringBuilder();
                for (String pkg : suspended) {
                    script.append("pm enable --user 0 ").append(pkg).append(" 2>&1; ");
                }
                PrivShell.exec(script.toString(), Math.max(30, 10 + suspended.size() * 5));
                // enable 只恢复图标，不改暂停态；再回读一次，只有仍是暂停的才算切换成功
                suspended.retainAll(SuspendSupport.suspendedAmong(suspended));
                SuspendSupport.remember(this, suspended);
            }
            final int done = suspended.size();
            final int failed = candidates.size() - done;
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, failed == 0
                                ? "已切换 " + done + " 个应用：图标保留并变灰"
                                : "已切换 " + done + " 个，" + failed + " 个失败（仍保持停用）",
                        Toast.LENGTH_LONG).show();
                reload();
            });
        });
    }

    /**
     * 取消「留桌面图标」：把当前处于暂停态的已冻结应用立刻转回默认冻结（disable-user）。
     * 先 pm unsuspend 清掉暂停标志再 disable-user，避免 suspended= 残留；
     * 用 pm list packages -d 批量回读判成败，转失败的保持暂停态，不会把应用放出来。
     */
    private void migrateSuspendedToDisabled() {
        if (working) {
            Toast.makeText(this, "正在执行其他操作，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        final Set<String> pool = new HashSet<>();
        for (Item it : items) {
            if (it.frozen && it.userId == 0) {
                pool.add(packagePart(it.pkg));
            }
        }
        pool.addAll(SuspendSupport.cached(this));
        if (pool.isEmpty()) {
            return;
        }
        working = true;
        IO.execute(() -> {
            Set<String> suspended = SuspendSupport.suspendedAmong(pool);
            if (suspended.isEmpty()) {
                main.post(() -> {
                    working = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    reload();
                });
                return;
            }
            StringBuilder script = new StringBuilder();
            for (String pkg : suspended) {
                script.append("pm unsuspend --user 0 ").append(pkg)
                        .append(" >/dev/null 2>&1; pm disable-user --user 0 ").append(pkg)
                        .append(" >/dev/null 2>&1; ");
            }
            PrivShell.exec(script.toString(), Math.max(30, 15 + suspended.size() * 6));
            // 回读真值：仍在暂停集合里的算失败；disable 列表里有的才算转换成功
            Set<String> stillSuspended = SuspendSupport.suspendedAmong(suspended);
            Set<String> disabled = new HashSet<>();
            PrivShell.Result d = PrivShell.exec("pm list packages -d", 30);
            if (d.out != null) {
                for (String line : d.out.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("package:")) {
                        disabled.add(t.substring(8).trim());
                    }
                }
            }
            int done = 0;
            for (String pkg : suspended) {
                if (disabled.contains(pkg) && !stillSuspended.contains(pkg)) {
                    done++;
                    SuspendSupport.forget(this, pkg);
                }
            }
            final int ok = done;
            final int failed = suspended.size() - done;
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, failed == 0
                                ? "已切回默认冻结 " + ok + " 个应用：图标已隐藏"
                                : "已切回 " + ok + " 个，" + failed + " 个失败（仍保持暂停）",
                        Toast.LENGTH_LONG).show();
                reload();
            });
        });
    }

    /** 失败弹框只显示应用名 + 归类后的一句短原因，原始 pm 输出只进日志。 */
    private void showPmError(String title, Item it, PrivShell.Result r, boolean freeze) {
        showPmError(title, it, Ui.pmReason(r, freeze));
    }

    private void showPmError(String title, Item it, String reason) {
        AlertDialog err = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(it.label + "：" + reason)
                .setPositiveButton("知道了", null)
                .create();
        err.show();
        Ui.roundAlertDialog(err);
        reload();
    }

    /** 在其他应用中配置：走系统应用详情页，失败落系统设置首页并提示。 */
    private void openAppSettings(Item it) {
        Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packagePart(it.pkg)));
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

    /** 添加到桌面：照搬 Hail，只带包名建固定快捷方式，不预先解析启动页。 */
    private void pinToHome(Item it) {
        ShortcutPin.pin(this, packagePart(it.pkg), it.label, it.icon);
    }

    /**
     * 仅从名单移除：不解冻、不卸载，应用本身的冻结状态保持原样。
     * 连带清掉标签归属与自定义顺序里的这一项，并同步给划卡冻结的名单文件。
     */
    private void removeFromList(Item it) {
        if (working) {
            return;
        }
        Set<String> cur = pkgs();
        cur.remove(it.pkg);
        prefs().edit().putStringSet(KEY_PKGS, cur).apply();
        appTabs.remove(it.pkg);
        saveAppTabs();
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
        if (it.pkg.equals(selectedPkg)) {
            selectedPkg = null;
        }
        if (it.pkg.equals(draggingPkg)) {
            draggingPkg = null;
        }
        SwipeFreeze.syncAsync(this);
        render();
        Toast.makeText(this, "已从列表移除", Toast.LENGTH_SHORT).show();
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
        if (!it.frozen) {
            Toast.makeText(this, "已移出名单", Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        working = true;
        render();
        IO.execute(() -> {
            PrivShell.Result r = execPm(it.pkg, it.userId, false);
            boolean ok = applied(it.pkg, false, r);
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    AlertDialog failOne = new AlertDialog.Builder(this)
                            .setTitle("已移出名单，但解冻失败")
                            .setMessage(it.label + "：" + Ui.pmReason(r, false))
                            .setPositiveButton("知道了", null)
                            .create();
                    failOne.show();
                    Ui.roundAlertDialog(failOne);
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

    /** 移动模式的唯一开关口：所有入口都走这里，保证模式和持久化不会脱节。 */
    private void setMoveMode(boolean on) {
        moveMode = on;
        selectedPkg = null;
        prefs().edit().putBoolean(lk(KEY_MOVE_MODE), on).apply();
        render();
    }

    /**
     * 长按震动走 Vibrator 直出，并用 ALARM 用途提交通道：
     * 不指定用途时系统按 TOUCH 计，本机「触感」强度为 OFF 会直接忽略（IGNORED_FOR_SETTINGS）；
     * ALARM 用途不受触感开关/强度影响，保证长按一定震。
     */
    private void forceVibrate() {
        android.os.Vibrator vib;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.os.VibratorManager vm =
                    (android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            vib = vm == null ? null : vm.getDefaultVibrator();
        } else {
            vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (vib == null || !vib.hasVibrator()) {
            android.util.Log.w("JinPm", "long-press vibrate skipped: no vibrator");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.os.VibrationEffect effect = android.os.VibrationEffect.createOneShot(
                    30, android.os.VibrationEffect.DEFAULT_AMPLITUDE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(effect, android.os.VibrationAttributes.createForUsage(
                        android.os.VibrationAttributes.USAGE_ALARM));
            } else {
                vib.vibrate(effect, new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
            }
        } else {
            vib.vibrate(30);
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
                runBatch(false, batchTargets());
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
            // 震动只由开关决定：关掉系统的长按默认震动，避免取消勾选后仍震一下
            freezeBtn.setHapticFeedbackEnabled(false);
            freezeBtn.setOnLongClickListener(v -> {
                if (!prefs().getBoolean(KEY_FAB_LONG_PRESS_UNFREEZE, true)) {
                    // 取消勾选：长按吞掉，不弹解冻图标、不震动
                    return true;
                }
                forceVibrate();
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
        searchHandler.removeCallbacks(searchRunnable);
        main.removeCallbacks(introReveal);
        main.removeCallbacks(introSafety);
        if (introScrimView != null) {
            introScrimView.animate().cancel();
        }
        if (introStatusView != null) {
            introStatusView.animate().cancel();
        }
        stopFreezeSpin();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /**
     * 布局设置里的「划卡冻结（LSPosed）」开关，下面挂生效标签。
     *
     * 开关只在 root 通道可用：名单文件要由 root 写成 system:system / 644，
     * Shizuku 是 uid 2000 写不进去，所以没有 root 时开关置灰。
     */
    private void addSwipeFreezeSection(LinearLayout box) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final CheckBox enableBox = new CheckBox(this);
        enableBox.setText("划卡冻结（lsposed）");
        enableBox.setTextSize(12);
        enableBox.setMinimumHeight(Ui.dp(this, 32));
        enableBox.setChecked(SwipeFreeze.enabled(this));
        // 先置灰，root 检测完成后再决定是否可用
        enableBox.setEnabled(false);

        row.addView(enableBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 子项：只有开关勾选后才出现；缩进 + 小圆点，表明挂在「划卡冻结」节点下
        final LinearLayout child = new LinearLayout(this);
        child.setOrientation(LinearLayout.HORIZONTAL);
        child.setGravity(Gravity.CENTER_VERTICAL);
        child.setPadding(Ui.dp(this, 32), 0, 0, 0);

        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(getColor(R.color.accent_surface_stroke));
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 6), Ui.dp(this, 6));
        dotLp.rightMargin = Ui.dp(this, 10);
        child.addView(dot, dotLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setMinimumHeight(Ui.dp(this, 40));

        TextView label = new TextView(this);
        label.setText("按标签冻结");
        label.setTextSize(12);
        label.setTextColor(getColor(R.color.text_primary));
        content.addView(label);

        final TextView summary = new TextView(this);
        summary.setTextSize(11);
        summary.setTextColor(getColor(R.color.text_secondary));
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        summaryLp.leftMargin = Ui.dp(this, 10);
        content.addView(summary, summaryLp);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(14);
        chevron.setTextColor(getColor(R.color.text_secondary));
        chevron.setPadding(Ui.dp(this, 8), 0, 0, 0);
        content.addView(chevron);

        child.addView(content, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        summary.setText(tagSummaryText());
        child.setVisibility(enableBox.isChecked() ? View.VISIBLE : View.GONE);
        child.setOnClickListener(v -> showTagPickerDialog(summary));

        enableBox.setOnCheckedChangeListener((btn, on) -> {
            SwipeFreeze.setEnabled(this, on);
            SwipeFreeze.syncAsync(this);
            child.setVisibility(on ? View.VISIBLE : View.GONE);
            if (!on) {
                return;
            }
            final boolean wasOn = on;
            JinApp.IO.execute(() -> {
                boolean active = SwipeFreeze.moduleActive();
                if (active || !wasOn) {
                    return;
                }
                runOnUiThread(this::showSwipeFreezePrompt);
            });
        });

        box.addView(row);
        box.addView(child);

        // root 探测会碰 su，放后台线程；没有 root 时开关置灰（Shizuku 写不进 /data/system）
        JinApp.IO.execute(() -> {
            boolean root = SwipeFreeze.hasRoot();
            runOnUiThread(() -> {
                enableBox.setEnabled(root);
                if (!root) {
                    enableBox.setChecked(false);
                }
            });
        });
    }

    /**
     * 布局设置里的「锁屏冻结（lsposed）」开关，勾选后显示 0-300 秒延时滑块。
     *
     * 名单与标签口径和划卡冻结共用；开关、延时都写进 /data/system/jin_freeze_list.json，
     * hook 侧锁屏时按 mtime 重读，改完即生效、不用重启 system_server。
     * 同样只在 root 通道可用（Shizuku 的 uid 2000 写不进 /data/system）。
     */
    private void addLockFreezeSection(LinearLayout box) {
        final CheckBox enableBox = new CheckBox(this);
        enableBox.setText("锁屏冻结（lsposed）");
        enableBox.setTextSize(12);
        enableBox.setMinimumHeight(Ui.dp(this, 32));
        enableBox.setChecked(SwipeFreeze.lockEnabled(this));
        // 先置灰，root 检测完成后再决定是否可用
        enableBox.setEnabled(false);

        final LinearLayout child = new LinearLayout(this);
        child.setOrientation(LinearLayout.VERTICAL);
        child.setPadding(Ui.dp(this, 32), 0, 0, Ui.dp(this, 4));

        // 标签过滤：样式与划卡冻结的「按标签冻结」行一致，放在延时滑块上面
        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);

        View tagDot = new View(this);
        android.graphics.drawable.GradientDrawable tagDotBg =
                new android.graphics.drawable.GradientDrawable();
        tagDotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        tagDotBg.setColor(getColor(R.color.accent_surface_stroke));
        tagDot.setBackground(tagDotBg);
        LinearLayout.LayoutParams tagDotLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 6), Ui.dp(this, 6));
        tagDotLp.rightMargin = Ui.dp(this, 10);
        tagRow.addView(tagDot, tagDotLp);

        LinearLayout tagContent = new LinearLayout(this);
        tagContent.setOrientation(LinearLayout.HORIZONTAL);
        tagContent.setGravity(Gravity.CENTER_VERTICAL);
        tagContent.setMinimumHeight(Ui.dp(this, 40));

        TextView tagLabel = new TextView(this);
        tagLabel.setText("按标签冻结");
        tagLabel.setTextSize(12);
        tagLabel.setTextColor(getColor(R.color.text_primary));
        tagContent.addView(tagLabel);

        final TextView tagSummary = new TextView(this);
        tagSummary.setTextSize(11);
        tagSummary.setTextColor(getColor(R.color.text_secondary));
        tagSummary.setSingleLine(true);
        tagSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tagSummaryLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tagSummaryLp.leftMargin = Ui.dp(this, 10);
        tagContent.addView(tagSummary, tagSummaryLp);

        TextView tagChevron = new TextView(this);
        tagChevron.setText("›");
        tagChevron.setTextSize(14);
        tagChevron.setTextColor(getColor(R.color.text_secondary));
        tagChevron.setPadding(Ui.dp(this, 8), 0, 0, 0);
        tagContent.addView(tagChevron);

        tagRow.addView(tagContent, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tagSummary.setText(lockTagSummaryText());
        tagRow.setOnClickListener(v -> showTagPickerDialog(tagSummary, true));
        child.addView(tagRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("锁屏后延时");
        label.setTextSize(12);
        label.setTextColor(getColor(R.color.text_primary));
        titleRow.addView(label);

        final TextView value = new TextView(this);
        value.setTextSize(11);
        value.setTextColor(getColor(R.color.text_secondary));
        value.setGravity(Gravity.END);
        titleRow.addView(value, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final SeekBar delayBar = new SeekBar(this);
        delayBar.setMax(SwipeFreeze.LOCK_DELAY_MAX);
        delayBar.setProgress(SwipeFreeze.lockDelaySeconds(this));
        value.setText(lockDelayLabel(delayBar.getProgress()));
        delayBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                value.setText(lockDelayLabel(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                // 松手再落盘，拖动过程只更新文案
                SwipeFreeze.setLockDelaySeconds(FreezeListActivity.this, sb.getProgress());
                SwipeFreeze.syncAsync(FreezeListActivity.this);
            }
        });

        child.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        child.addView(delayBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        child.setVisibility(enableBox.isChecked() ? View.VISIBLE : View.GONE);

        enableBox.setOnCheckedChangeListener((btn, on) -> {
            SwipeFreeze.setLockEnabled(this, on);
            SwipeFreeze.syncAsync(this);
            child.setVisibility(on ? View.VISIBLE : View.GONE);
        });

        box.addView(enableBox);
        box.addView(child);

        // root 探测会碰 su，放后台线程；没有 root 时开关置灰（Shizuku 写不进 /data/system）
        JinApp.IO.execute(() -> {
            boolean root = SwipeFreeze.hasRoot();
            runOnUiThread(() -> {
                enableBox.setEnabled(root);
                if (!root) {
                    enableBox.setChecked(false);
                }
            });
        });
    }

    /** 锁屏延时文案：0 秒=立即冻结，60 秒=1分钟，70 秒=1分10秒，其余按秒。 */
    private static String lockDelayLabel(int seconds) {
        if (seconds <= 0) {
            return "立即冻结";
        }
        if (seconds < 60) {
            return seconds + "秒";
        }
        int minutes = seconds / 60;
        int rest = seconds % 60;
        return rest == 0 ? minutes + "分钟" : minutes + "分" + rest + "秒";
    }

    /** 「按标签冻结」这一行右侧的启用数/总数，例如 5/5；never-set 的勾选按全选处理。 */
    private String tagSummaryText() {
        return tagSummaryText(SwipeFreeze.selectedTags(this));
    }

    /** 锁屏冻结的标签勾选摘要，口径与划卡冻结一致。 */
    private String lockTagSummaryText() {
        return tagSummaryText(SwipeFreeze.lockSelectedTags(this));
    }

    private String tagSummaryText(Set<String> selected) {
        List<SwipeFreeze.Tag> tags = SwipeFreeze.allTags(this);
        int total = tags.size();
        if (total == 0) {
            return "0/0";
        }
        int on = 0;
        for (SwipeFreeze.Tag tag : tags) {
            if (selected == null || selected.contains(tag.id)) {
                on++;
            }
        }
        return on + "/" + total;
    }

    /** 「按标签冻结」勾选界面：列出凚 的全部标签，默认全选，勾选即时持久化。 */
    private void showTagPickerDialog(TextView summary) {
        showTagPickerDialog(summary, false);
    }

    /** forLock=true 时操作锁屏冻结自己那套标签勾选，界面与划卡冻结完全一致。 */
    private void showTagPickerDialog(TextView summary, final boolean forLock) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = Ui.dp(this, 15);
        root.setPadding(padH, Ui.dp(this, 13), padH, Ui.dp(this, 6));

        TextView title = new TextView(this);
        title.setText(forLock ? "锁屏冻结标签" : "按标签冻结");
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(forLock
                ? "锁屏冻结时，应用的标签与勾选项有交集才冻结；一个都不勾则不冻结。"
                : "应用的标签与勾选项有交集才冻结；一个都不勾则不冻结。");
        hint.setTextSize(10);
        hint.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, 5);
        root.addView(hint, hlp);

        // 标签按稳定 id 存取，改名不会让已勾选的过滤失效
        final List<SwipeFreeze.Tag> tags = SwipeFreeze.allTags(this);
        Set<String> selected = forLock
                ? SwipeFreeze.lockSelectedTags(this)
                : SwipeFreeze.selectedTags(this);
        final Set<String> checkedIds = new HashSet<>();

        LinearLayout tagList = new LinearLayout(this);
        tagList.setOrientation(LinearLayout.VERTICAL);
        for (SwipeFreeze.Tag tag : tags) {
            boolean on = selected == null || selected.contains(tag.id);
            if (on) {
                checkedIds.add(tag.id);
            }
            CheckBox tagBox = new CheckBox(this);
            tagBox.setText(tag.name);
            tagBox.setTextSize(11);
            tagBox.setMinimumHeight(Ui.dp(this, 34));
            tagBox.setTag(tag.id);
            tagBox.setChecked(on);
            tagBox.setOnCheckedChangeListener((btn, isChecked) -> {
                String id = (String) btn.getTag();
                if (isChecked) {
                    checkedIds.add(id);
                } else {
                    checkedIds.remove(id);
                }
                if (forLock) {
                    SwipeFreeze.setLockSelectedTags(this, checkedIds);
                } else {
                    SwipeFreeze.setSelectedTags(this, checkedIds);
                }
                SwipeFreeze.syncAsync(this);
            });
            tagList.addView(tagBox);
        }
        if (tags.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("冻结列表里还没有应用");
            empty.setTextSize(12);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            tagList.addView(empty);
        }

        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(tagList);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                tags.size() > 8 ? Ui.dp(this, 300) : ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 5);
        root.addView(scroller, slp);

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(12);
        done.setTypeface(null, android.graphics.Typeface.BOLD);
        done.setTextColor(getColor(R.color.blue));
        done.setGravity(Gravity.CENTER);
        done.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        done.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 3);
        root.addView(done, dlp);

        dialog.setContentView(root);
        Ui.roundDialog(dialog, root, 96);
        dialog.setOnShowListener(d -> Ui.roundDialog(dialog, root, 96));
        dialog.setOnDismissListener(d -> summary.setText(forLock
                ? lockTagSummaryText() : tagSummaryText()));
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setDimAmount(0.4f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    /** 开关提示：沿用凚 现有圆角对话框（Ui.roundDialog + bg_dialog），不用系统方形 AlertDialog。 */
    private void showSwipeFreezePrompt() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = Ui.dp(this, 15);
        root.setPadding(padH, Ui.dp(this, 13), padH, Ui.dp(this, 6));

        TextView title = new TextView(this);
        title.setText("划卡冻结需激活 LSPosed 并重启");
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        root.addView(title);

        TextView message = new TextView(this);
        message.setText("开关已记录，但还没在 system_server 里跑起来。\n\n"
                + "1. 在 LSPosed 里启用凚（作用域已预置，不用手动勾选）；\n"
                + "2. 重启手机；\n"
                + "3. 重启后划卡才会真正冻结。");
        message.setTextSize(11);
        message.setLineSpacing(Ui.dp(this, 2), 1f);
        message.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 7);
        root.addView(message, mlp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        TextView close = new TextView(this);
        close.setText("知道了");
        close.setTextSize(12);
        close.setTextColor(getColor(R.color.text_primary));
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        close.setOnClickListener(v -> dialog.dismiss());

        TextView go = new TextView(this);
        go.setText("前往 LSPosed");
        go.setTextSize(12);
        go.setTypeface(null, android.graphics.Typeface.BOLD);
        go.setTextColor(getColor(R.color.blue));
        go.setGravity(Gravity.CENTER);
        go.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        go.setOnClickListener(v -> {
            dialog.dismiss();
            SwipeFreeze.openLsposed(this);
        });

        actions.addView(close, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(go, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 3);
        root.addView(actions, alp);

        dialog.setContentView(root);
        Ui.roundDialog(dialog, root, 96);
        dialog.setOnShowListener(d -> Ui.roundDialog(dialog, root, 96));
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setDimAmount(0.4f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    private void showLayoutDialog() {
        int pad = Ui.dp(this, 16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 2), pad, Ui.dp(this, 4));

        // 顶部操作提示：紧贴标题下方、与标题成一组，不随内容滚动
        TextView gestureHint = new TextView(this);
        android.text.SpannableStringBuilder hintText = new android.text.SpannableStringBuilder(
                "长按设置移动位置，点击 ");
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
        hintText.append(" 调节高度与展开");
        gestureHint.setText(hintText);
        gestureHint.setTextSize(11);
        gestureHint.setTextColor(getColor(R.color.text_secondary));
        gestureHint.setPadding(pad, 0, pad, Ui.dp(this, 4));

        // 权限标识挪到标题行，这里不再单独占一行

        // 图标大小：与列数同一套滑块样式，放在列数下面；范围随列宽自适应（一格放得下且不溢出）
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
        nameBox.setText("显示应用名称");
        nameBox.setTextSize(12);
        nameBox.setMinimumHeight(Ui.dp(this, 32));
        nameBox.setChecked(showName);
        nameBox.setOnCheckedChangeListener((btn, on) -> {
            showName = on;
            prefs().edit().putBoolean(lk(KEY_SHOW_NAME), on).apply();
            render();
        });

        // 「半屏高度调节」勾选瞬间要收起布局弹窗，先把实例留个引用
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        // 打开移动模式时收起底部倒三角展开的高度块（列表本身不动），引用在弹窗建好后填
        final Runnable[] collapseHeightRow = new Runnable[1];

        // 移动图标开关（手势最高优先级）：打开后点击选中、再点目标位置放下，长按拖动换位；
        // 标签左右滑动、长按操作单、点击打开应用全部让路。关闭后恢复原来分工。
        CheckBox moveBox = new CheckBox(this);
        moveBox.setText("移动图标");
        moveBox.setTextSize(12);
        moveBox.setMinimumHeight(Ui.dp(this, 32));
        moveBox.setChecked(moveMode);
        moveBox.setOnCheckedChangeListener((btn, on) -> {
            setMoveMode(on);
            if (on) {
                // 进入移动模式：收起点开的高度块，半屏列表本身保持展开可操作
                if (collapseHeightRow[0] != null) {
                    collapseHeightRow[0].run();
                }
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

        // 冻结后保留桌面图标：勾选后冻结改走 suspend 通道，图标保留、置灰并带暂停标识
        CheckBox keepIconBox = new CheckBox(this);
        keepIconBox.setText("留桌面图标（转暂停模式）");
        keepIconBox.setTextSize(12);
        keepIconBox.setMinimumHeight(Ui.dp(this, 32));
        keepIconBox.setChecked(keepLauncherIcon);
        keepIconBox.setOnCheckedChangeListener((btn, on) -> {
            keepLauncherIcon = on;
            prefs().edit().putBoolean(KEY_KEEP_LAUNCHER_ICON, on).apply();
            if (on) {
                // 已有停用态的应用逐个迁到暂停态，否则图标仍缺失
                migrateFrozenToSuspend();
            } else {
                // 取消勾选：暂停态应用立刻转回默认冻结，图标随之消失
                migrateSuspendedToDisabled();
            }
        });

        // 长按冻结按钮：默认勾选，保持现有「弹出解冻图标」并震动；取消后长按无反应
        CheckBox longPressBox = new CheckBox(this);
        longPressBox.setText("长按冻结按钮触发解冻图标");
        longPressBox.setTextSize(12);
        longPressBox.setMinimumHeight(Ui.dp(this, 32));
        longPressBox.setChecked(prefs().getBoolean(KEY_FAB_LONG_PRESS_UNFREEZE, true));
        longPressBox.setOnCheckedChangeListener((btn, on) ->
                prefs().edit().putBoolean(KEY_FAB_LONG_PRESS_UNFREEZE, on).apply());

        // 仅冻结/解冻当前标签：勾选后底部一键冻结与长按解冻只处理当前标签页的应用
        CheckBox tabOnlyBox = new CheckBox(this);
        tabOnlyBox.setText("仅冻结/解冻当前标签");
        tabOnlyBox.setTextSize(12);
        tabOnlyBox.setMinimumHeight(Ui.dp(this, 32));
        tabOnlyBox.setChecked(batchCurrentTabOnly);
        tabOnlyBox.setOnCheckedChangeListener((btn, on) -> {
            batchCurrentTabOnly = on;
            prefs().edit().putBoolean(KEY_BATCH_CURRENT_TAB_ONLY, on).apply();
        });

        // 半屏专属：顶部小白条是否允许拖动改高度（开关与高度值分开存）
        CheckBox handleBox = null;
        CheckBox hideStatusBarBox = null;
        CheckBox quickRemoveBox = null;
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
            // 与冰箱拨开关时一样立刻同步任务属性：开着时当前任务马上退出最近任务，
            // 关掉时恢复；下次从桌面进入会由 LaunchActivity 改走对应的隐藏/普通入口
            syncRecentsExclusion();
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

            hideStatusBarBox = new CheckBox(this);
            hideStatusBarBox.setText("半屏打开时隐藏状态栏");
            hideStatusBarBox.setTextSize(12);
            hideStatusBarBox.setMinimumHeight(Ui.dp(this, 32));
            hideStatusBarBox.setChecked(panelHideStatusBar);
            hideStatusBarBox.setOnCheckedChangeListener((btn, on) -> {
                panelHideStatusBar = on;
                prefs().edit().putBoolean(KEY_PANEL_HIDE_STATUS_BAR, on).apply();
                // 隐藏时不再反色；取消勾选立刻按壁纸亮度补一次
                applyPanelBarState();
            });

            // 快捷移除：图标右上角常驻红色减号，点它等同长按菜单里的「从列表移除」
            quickRemoveBox = new CheckBox(this);
            quickRemoveBox.setText("快捷移除图标");
            quickRemoveBox.setTextSize(12);
            quickRemoveBox.setMinimumHeight(Ui.dp(this, 32));
            quickRemoveBox.setChecked(quickRemove);
            quickRemoveBox.setOnCheckedChangeListener((btn, on) -> {
                quickRemove = on;
                prefs().edit().putBoolean(KEY_PANEL_QUICK_REMOVE, on).apply();
                // 与「移动图标」一致：勾选时收起展开的高度块，设置入口与列表都保持可见
                if (on && collapseHeightRow[0] != null) {
                    collapseHeightRow[0].run();
                }
                render();
            });

        }

        // 悬浮按钮位置：横向以「底部居中」为 0 左右偏，纵向以贴底为 0 往上抬，拖动即时生效
        final float density = getResources().getDisplayMetrics().density;
        final int xMinDp = Math.round((fabInsetPx() - fabCenterX()) / density);
        final int xMaxDp = Math.round((fabMaxX() - fabCenterX()) / density);
        final int maxLiftDp = Math.round(fabMaxLiftPx() / density);

        TextView fabTitle = new TextView(this);
        fabTitle.setText("底部冻结按钮调节");
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
            xValue.setText("左右偏移：" + (fabDx > 0 ? "+" : "") + fabDx + "dp");
            yValue.setText("距底部：" + fabDy + "dp");
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

        refreshFabLabels.run();

        // 标签：隐藏任一标签（数据保留）、新建/改名/删除自定义标签
        TextView tabTitle = new TextView(this);
        tabTitle.setText("标签（点击看说明）");
        tabTitle.setTextSize(12);
        tabTitle.setTextColor(getColor(R.color.text_primary));
        tabTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        tabTitle.setOnClickListener(v -> showTabHintDialog());

        // 隐藏标签文字：只收起标签名那一排视图，勾选状态、滑动手势与当前标签都不受影响；
        // 全屏模式没有这排标签，不显示这个开关
        CheckBox hideRowBox = null;
        if (panelMode) {
            hideRowBox = new CheckBox(this);
            hideRowBox.setText("隐藏半屏标签文字");
            hideRowBox.setTextSize(12);
            hideRowBox.setMinimumHeight(Ui.dp(this, 32));
            hideRowBox.setChecked(hideTabRow);
            hideRowBox.setOnCheckedChangeListener((b, on) -> {
                if (on == hideTabRow) {
                    return;
                }
                hideTabRow = on;
                prefs().edit().putBoolean(KEY_HIDE_TAB_ROW, on).apply();
                renderTabBar();
                if (grid != null) {
                    grid.requestLayout();
                }
            });
        }

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
                    if (!on && t.visible && visibleTabCount() == 1) {
                        // 至少留一个可见标签，否则标签栏会整条空掉
                        b.setChecked(true);
                        Toast.makeText(this, "至少保留一个标签", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    t.visible = on;
                    Tab curTab = findTab(currentTabId);
                    if (curTab == null || !curTab.visible) {
                        // 当前标签不可见了：刚打开的就切过去，否则落到第一个可见标签
                        currentTabId = on ? t.id : firstVisibleTabId();
                        prefs().edit().putString(KEY_CURRENT_TAB, currentTabId).apply();
                    }
                    saveTabs();
                    setSysHintVisible();
                    renderTabBar();
                    render();
                });
                row.addView(cb, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                // 默认标签只在入口按钮上体现状态，标签条本身不加任何标记
                final boolean isDefault = t.id.equals(defaultTabId());
                TextView def = new TextView(this);
                def.setText(isDefault ? "默认" : "设为默认");
                def.setTextSize(11);
                def.setTextColor(getColor(isDefault ? R.color.blue : R.color.text_secondary));
                def.setTypeface(null, isDefault ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
                def.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
                if (!t.visible) {
                    // 隐藏的标签不能当默认，入口保持可读但不可点
                    def.setAlpha(0.4f);
                    row.addView(def);
                } else {
                    def.setOnClickListener(v -> {
                        if (t.id.equals(defaultTabId())) {
                            prefs().edit().remove(KEY_DEFAULT_TAB).apply();
                            Toast.makeText(this, "已取消默认，进入时回到第一个标签",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            prefs().edit().putString(KEY_DEFAULT_TAB, t.id).apply();
                            Toast.makeText(this, "「" + t.name + "」已设为默认标签",
                                    Toast.LENGTH_SHORT).show();
                        }
                        refreshTabSettings[0].run();
                    });
                    row.addView(def);
                }
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

        // 个性设置：与「列表图标调整」「底部冻结按钮调节」同一档小标题，放在面板最顶部
        TextView personalTitle = new TextView(this);
        personalTitle.setText("个性设置");
        personalTitle.setTextSize(12);
        personalTitle.setTextColor(getColor(R.color.text_primary));
        personalTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

        box.addView(personalTitle);
        box.addView(nameBox);
        box.addView(moveBox);
        box.addView(tipBox);
        box.addView(keepIconBox);
        box.addView(longPressBox);
        box.addView(tabOnlyBox);

        // 全屏打开模式：只决定桌面图标进来时的形态，两种形态各用各的布局参数
        CheckBox fullBox = new CheckBox(this);
        fullBox.setText("全屏打开模式");
        fullBox.setTextSize(12);
        fullBox.setMinimumHeight(Ui.dp(this, 32));
        fullBox.setChecked(prefs().getBoolean(KEY_FULLSCREEN_OPEN, true));
        fullBox.setOnCheckedChangeListener((b, on) -> {
            prefs().edit().putBoolean(KEY_FULLSCREEN_OPEN, on).apply();
            boolean switching = (on == panelMode);
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
            if (switching) {
                // 点击即切换：直接换到另一种形态的 Activity，不用退出重进；
                // 目标形态开着「隐藏后台」时走对应的隐藏入口，保证新任务从创建起就不进最近任务
                boolean hideTarget = prefs().getBoolean(
                        on ? KEY_PANEL_HIDE_RECENTS : "panel_" + KEY_PANEL_HIDE_RECENTS, false);
                Class<?> next = on
                        ? (hideTarget ? FreezeListHiddenActivity.class : FreezeListActivity.class)
                        : (hideTarget ? FreezePanelHiddenActivity.class : FreezePanelActivity.class);
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
        if (hideStatusBarBox != null) {
            box.addView(hideStatusBarBox);
        }
        if (quickRemoveBox != null) {
            box.addView(quickRemoveBox);
        }
        if (dismissBox != null) {
            box.addView(dismissBox);
        }
        box.addView(swipeBox);
        if (hideRowBox != null) {
            box.addView(hideRowBox);
        }
        // 两个 LSPosed 冻结开关统一排在「隐藏半屏标签文字」下面
        addSwipeFreezeSection(box);
        addLockFreezeSection(box);
        box.addView(tabTitle);
        box.addView(tabList);
        box.addView(newTab);
        box.addView(fabTitle);
        box.addView(sizeValue);
        box.addView(fabSizeBar);
        box.addView(xValue);
        box.addView(xBar);
        box.addView(yValue);
        box.addView(yBar);

        // 透明度：紧挨「距底部」，与上面几条同一套小字样式；两种形态各自记忆
        final TextView fabAlphaLabel = new TextView(this);
        fabAlphaLabel.setTextSize(11);
        fabAlphaLabel.setTextColor(getColor(R.color.text_secondary));
        // 行距统一成「按钮大小 / 左右偏移」那一档：小字不再额外加上间距

        final SeekBar fabAlphaBar = new SeekBar(this);
        fabAlphaBar.setMax(100);
        int fabAlphaPct = Math.max(0, Math.min(100,
                prefs().getInt(panelMode ? lk(KEY_FAB_SURFACE) : KEY_FULL_FAB_SURFACE,
                        panelMode ? PANEL_FAB_SURFACE_DEFAULT : SURFACE_SOLID)));
        fabAlphaBar.setProgress(fabAlphaPct);
        fabAlphaLabel.setText("透明度：" + fabAlphaPct + "%");
        fabAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                prefs().edit()
                        .putInt(panelMode ? lk(KEY_FAB_SURFACE) : KEY_FULL_FAB_SURFACE, p)
                        .apply();
                fabAlphaLabel.setText("透明度：" + p + "%");
                if (panelMode) {
                    applyFabSurface();
                } else {
                    applyFullscreenFabSurface();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        box.addView(fabAlphaLabel);
        box.addView(fabAlphaBar);

        // 列数滑块：4–8 档，和上面几个滑块同一套样式
        TextView iconAdjustTitle = new TextView(this);
        iconAdjustTitle.setText("列表图标调整");
        iconAdjustTitle.setTextSize(12);
        iconAdjustTitle.setTextColor(getColor(R.color.text_primary));
        iconAdjustTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

        final TextView colsValue = new TextView(this);
        colsValue.setTextSize(11);
        colsValue.setTextColor(getColor(R.color.text_secondary));
        colsValue.setText("列数：" + gridColumns + " 列");

        SeekBar colsBar = new SeekBar(this);
        colsBar.setMax(4);
        colsBar.setProgress(Math.max(0, Math.min(4, gridColumns - 4)));
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
        box.addView(iconAdjustTitle);
        box.addView(colsValue);
        box.addView(colsBar);
        box.addView(sizeLabel);
        box.addView(sizeBar);

        // 自定义壁纸：作为背景层的底图，透明度/模糊仍由上面的背景滑块叠加；半屏与全屏各存各的
        TextView wpTitle = new TextView(this);
        wpTitle.setText("自定义壁纸");
        wpTitle.setTextSize(12);
        wpTitle.setTextColor(getColor(R.color.text_primary));
        wpTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

        final TextView wpState = new TextView(this);
        wpState.setTextSize(11);
        wpState.setTextColor(getColor(R.color.text_secondary));
        wpState.setText(wallpaperUri == null || wallpaperUri.isEmpty()
                ? "当前：未设置（使用纯色背景）" : "当前：已设置");
        layoutWpState = wpState;

        LinearLayout wpRow = new LinearLayout(this);
        wpRow.setOrientation(LinearLayout.HORIZONTAL);
        wpRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView wpPick = new TextView(this);
        wpPick.setText("选择图片");
        wpPick.setTextSize(12);
        wpPick.setTextColor(getColor(R.color.blue));
        wpPick.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        wpPick.setOnClickListener(v -> wallpaperPicker.launch(new String[]{"image/*"}));

        final TextView wpClear = new TextView(this);
        wpClear.setText("清除");
        wpClear.setTextSize(12);
        wpClear.setTextColor(0xFFE5484D);
        wpClear.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        wpClear.setOnClickListener(v -> {
            clearCustomWallpaper();
            wpState.setText("当前：未设置（使用纯色背景）");
            layoutWpState = wpState;
        });

        wpRow.addView(wpPick);
        wpRow.addView(wpClear);
        box.addView(wpTitle);
        box.addView(wpState);
        box.addView(wpRow);

        // 壁纸自己的模糊度：0 完全清晰（默认），只影响这张壁纸，不动透明度/背景模糊那几条
        final TextView wpBlurLabel = new TextView(this);
        wpBlurLabel.setTextSize(11);
        wpBlurLabel.setTextColor(getColor(R.color.text_secondary));
        wpBlurLabel.setText("壁纸模糊度：" + wallpaperBlur + "%（左滑透明，右滑模糊）");
        SeekBar wpBlurBar = new SeekBar(this);
        wpBlurBar.setMax(100);
        wpBlurBar.setProgress(wallpaperBlur);
        wpBlurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                wpBlurLabel.setText("壁纸模糊度：" + p + "%（左滑透明，右滑模糊）");
                // 拖动过程中就用降采样快速模糊跟住：任何时候都是模糊图，不会回退到清晰帧
                wallpaperBlur = Math.max(0, Math.min(100, p));
                if (wallpaperSourceBitmap != null && wallpaperUri != null) {
                    swapWallpaperBitmap(WallpaperSource.quickBlur(
                            FreezeListActivity.this, wallpaperUri, wallpaperSourceBitmap,
                            wallpaperBlur, srcMode()));
                    applyWallpaperBackground();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                wallpaperBlur = Math.max(0, Math.min(100, sb.getProgress()));
                prefs().edit().putInt(lk(KEY_WALLPAPER_BLUR), wallpaperBlur).apply();
                rebuildWallpaperDrawable();
                applyWallpaperBackground();
            }
        });
        box.addView(wpBlurLabel);
        box.addView(wpBlurBar);

        // 长按应用的选项框是否也换成壁纸底：取半屏那张图（含它的模糊度），半屏/全屏共用这一个开关
        android.widget.CheckBox wpSheet = new android.widget.CheckBox(this);
        wpSheet.setText("同步长按选项框");
        wpSheet.setTextSize(12);
        wpSheet.setTextColor(getColor(R.color.text_primary));
        wpSheet.setChecked(prefs().getBoolean(WallpaperSource.KEY_SHEET_WALLPAPER, false));
        wpSheet.setOnCheckedChangeListener((b, on) ->
                prefs().edit().putBoolean(WallpaperSource.KEY_SHEET_WALLPAPER, on).apply());
        box.addView(wpSheet);

        // 半屏上方「非面板区域」的遮罩强度：越小越透明（看得清背后页面），越大越模糊
        if (panelMode) {
            TextView transTitle = new TextView(this);
            transTitle.setText("界面透明度：");
            transTitle.setTextSize(12);
            transTitle.setTextColor(getColor(R.color.text_primary));
            transTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

            final TextView blurLabel = new TextView(this);
            blurLabel.setTextSize(11);
            blurLabel.setTextColor(getColor(R.color.text_secondary));

            final SeekBar blurBar = new SeekBar(this);
            blurBar.setMax(100);
            int blurPct = Math.max(0, Math.min(100, prefs().getInt(lk(KEY_BLUR), 25)));
            blurBar.setProgress(blurPct);
            blurLabel.setText("半屏外背景：" + blurPct + "%");
            blurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(lk(KEY_BLUR), p).apply();
                    blurLabel.setText("半屏外背景：" + p + "%");
                    applyBackdropStrength();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            box.addView(transTitle);
            box.addView(blurLabel);
            box.addView(blurBar);

            // 半屏面板本体：0=完全透明、50=最模糊、100=实心；只改面板自己，外面那层遮罩不动
            final TextView panelLabel = new TextView(this);
            panelLabel.setTextSize(11);
            panelLabel.setTextColor(getColor(R.color.text_secondary));

            final SeekBar panelBar = new SeekBar(this);
            panelBar.setMax(100);
            int panelPct = Math.max(0, Math.min(100,
                    prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID)));
            panelBar.setProgress(panelPct);
            panelLabel.setText("半屏内背景：" + panelPct + "%");
            panelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(lk(KEY_SURFACE), p).apply();
                    panelLabel.setText("半屏内背景：" + p + "%");
                    applyPanelSurface();
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
        } else {
            // 全屏形态也补两条：全屏背景档位 + 雪花按钮底板档位。
            // 与半屏同一套语义（0 全透明 / 50 模糊 / 100 实心），键独立记忆，默认实心不变观感。
            TextView transTitle = new TextView(this);
            transTitle.setText("界面透明度：");
            transTitle.setTextSize(12);
            transTitle.setTextColor(getColor(R.color.text_primary));
            transTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

            final TextView fullBgLabel = new TextView(this);
            fullBgLabel.setTextSize(11);
            fullBgLabel.setTextColor(getColor(R.color.text_secondary));

            final SeekBar fullBgBar = new SeekBar(this);
            fullBgBar.setMax(100);
            int fullBgPct = Math.max(0, Math.min(100,
                    prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID)));
            fullBgBar.setProgress(fullBgPct);
            fullBgLabel.setText("全屏背景：" + fullBgPct + "%（左滑透明，右滑模糊）");
            fullBgBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(KEY_FULL_SURFACE, p).apply();
                    fullBgLabel.setText("全屏背景：" + p + "%（左滑透明，右滑模糊）");
                    applyFullscreenSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            // 顶栏（横幅）背景：没设过时跟随全屏背景，动过滑块后独立记忆
            final TextView headerLabel = new TextView(this);
            headerLabel.setTextSize(11);
            headerLabel.setTextColor(getColor(R.color.text_secondary));
            headerLabel.setPadding(0, Ui.dp(this, 8), 0, 0);

            final SeekBar headerBar = new SeekBar(this);
            headerBar.setMax(100);
            int headerPct = headerSurfacePct();
            headerBar.setProgress(headerPct);
            headerLabel.setText("横幅透明度：" + headerPct + "%（左滑透明，右滑模糊）");
            headerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(KEY_HEADER_SURFACE, p).apply();
                    headerLabel.setText("横幅透明度：" + p + "%（左滑透明，右滑模糊）");
                    applyFullscreenSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            // 标签页胶囊整条：选中/未选中一起缩放，与页面背景互不影响
            final TextView tabSurfLabel = new TextView(this);
            tabSurfLabel.setTextSize(11);
            tabSurfLabel.setTextColor(getColor(R.color.text_secondary));
            tabSurfLabel.setPadding(0, Ui.dp(this, 8), 0, 0);

            final SeekBar tabSurfBar = new SeekBar(this);
            tabSurfBar.setMax(100);
            int tabSurfPct = tabSurfacePct();
            tabSurfBar.setProgress(tabSurfPct);
            tabSurfLabel.setText("标签页透明度：" + tabSurfPct + "%（左滑透明，右滑模糊）");
            tabSurfBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(KEY_TAB_SURFACE, p).apply();
                    tabSurfLabel.setText("标签页透明度：" + p + "%（左滑透明，右滑模糊）");
                    applyFullscreenSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            // 冻结按钮透明度已统一放到「距底部」下面，这里不再单独占一行
            box.addView(transTitle);
            box.addView(fullBgLabel);
            box.addView(fullBgBar);
            box.addView(headerLabel);
            box.addView(headerBar);
            box.addView(tabSurfLabel);
            box.addView(tabSurfBar);
        }

        // 搜索框透明度：只属于全屏（半屏底部没有搜索框）；独立档位，与背景档位解耦
        if (!panelMode) {
            final TextView searchLabel = new TextView(this);
            searchLabel.setTextSize(11);
            searchLabel.setTextColor(getColor(R.color.text_secondary));

            final SeekBar searchBar = new SeekBar(this);
            searchBar.setMax(100);
            int searchStart = searchPct();
            searchBar.setProgress(searchStart);
            searchLabel.setText("搜索框透明度：" + searchStart + "%（左滑透明，右滑模糊）");
            searchBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(lk(KEY_SEARCH_SURFACE), p).apply();
                    searchLabel.setText("搜索框透明度：" + p + "%（左滑透明，右滑模糊）");
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
        }

        // 备份 / 恢复 / 旧版导入：放在所有开关与滑块之后，作为布局设置的最后入口
        TextView backupTitle = new TextView(this);
        backupTitle.setText("备份与恢复");
        backupTitle.setTextSize(12);
        backupTitle.setTextColor(getColor(R.color.text_primary));
        backupTitle.setPadding(0, Ui.dp(this, 12), 0, 0);

        TextView backupEntry = new TextView(this);
        backupEntry.setText("备份（文件或文本）");
        backupEntry.setTextSize(12);
        backupEntry.setTextColor(getColor(R.color.blue));
        backupEntry.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        TextView restoreEntry = new TextView(this);
        restoreEntry.setText("恢复（文件或文本）");
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

        // 中间留白，把右上角关闭入口推到最右；热区与文字版一致（48×34dp）
        header.addView(new View(this), new LinearLayout.LayoutParams(
                0, 1, 1f));
        final android.widget.ImageButton done = new android.widget.ImageButton(this);
        done.setImageResource(R.drawable.ic_close);
        done.setContentDescription("关闭");
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        done.setBackgroundResource(ripple.resourceId);
        done.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 内边距把图形收到约 16dp（小于「布局」二字占的宽高），外框仍是 48×34dp，点击热区不缩
        done.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        done.setTooltipText(null);
        done.setOnLongClickListener(v -> true);
        header.addView(done, new LinearLayout.LayoutParams(
                Ui.dp(this, 48), Ui.dp(this, 34)));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(gestureHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 点右侧箭头才展开的高度滑块：只改弹窗自身高度，和半屏高度、坐标分开存
        LinearLayout heightRow = new LinearLayout(this);
        heightRow.setOrientation(LinearLayout.VERTICAL);
        heightRow.setPadding(pad, 0, pad, Ui.dp(this, 2));
        heightRow.setVisibility(View.GONE);
        final TextView openValue = Ui.text(this, "设置面板范围", 11, Ui.TEXT_SECONDARY, false);
        final SeekBar openBar = new SeekBar(this);
        final TextView expandValue = Ui.text(this, "设置面板展开范围", 11, Ui.TEXT_SECONDARY, false);
        final SeekBar expandBar = new SeekBar(this);
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

        // 倒三角那一行的整行都是热区：图形本身不吃点击，点到两侧空白同样能展开/收起
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER);
        toggleRow.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        toggleRow.setBackground(null);
        toggleRow.setStateListAnimator(null);
        toggleRow.setElevation(0f);
        final android.widget.ImageButton sizeToggle = new android.widget.ImageButton(this);
        sizeToggle.setImageResource(R.drawable.ic_chevron_down);
        // 纯图形开关：不要水波纹、背景和阴影，按下只做展开/收起
        sizeToggle.setBackground(null);
        sizeToggle.setStateListAnimator(null);
        sizeToggle.setElevation(0f);
        sizeToggle.setClickable(false);
        sizeToggle.setFocusable(false);
        sizeToggle.setContentDescription("调节面板高度");
        sizeToggle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sizeToggle.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        toggleRow.addView(sizeToggle, new LinearLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34)));
        root.addView(toggleRow, new LinearLayout.LayoutParams(
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
        // 滚动显隐的防抖状态：程序化改弹窗高度也会触发 onScrollChange，
        // 因此这里留一个抑制窗口，避免点开 ▼ 后滑块被自己造成的滚动立刻收掉。
        // fingerDown：只有手指真的按住（DOWN/MOVE）时才算用户手势
        final boolean[] fingerDown = {false};
        final long[] suppressUntil = {0};
        // 只有 ▼ 展开过才参与滚动显隐；滚动收起时只藏内容，保留占位高度，面板不动
        final boolean[] rowExpanded = {false};
        final boolean[] rowSpaceHidden = {false};
        final int[] rowFullH = {0};
        final Runnable resetRowHeight = () -> {
            ViewGroup.LayoutParams lp = heightRow.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            heightRow.setLayoutParams(lp);
        };
        // 重新露出滑块：占位由滚动区自己让出，窗口高度全程没变，只需把行放回来
        final Runnable showRowSpace = () -> {
            if (!rowExpanded[0] || !rowSpaceHidden[0]) {
                return;
            }
            rowSpaceHidden[0] = false;
            heightRow.animate().cancel();
            resetRowHeight.run();
            heightRow.setVisibility(View.VISIBLE);
            heightRow.setAlpha(0f);
            heightRow.animate().alpha(1f).setDuration(120).start();
            suppressUntil[0] = android.os.SystemClock.uptimeMillis() + 400;
        };
        // 滚动收起滑块：行直接 GONE，把占位空间让给列表内容（顶部不留白、多显示一行）；
        // 窗口高度与位置一律不动，不调用 applyDialogHeight
        final Runnable hideRowSpace = () -> {
            if (!rowExpanded[0] || rowSpaceHidden[0]) {
                return;
            }
            int h = heightRow.getHeight();
            if (h > 0) {
                rowFullH[0] = h;
            }
            rowSpaceHidden[0] = true;
            resetRowHeight.run();
            heightRow.animate().cancel();
            heightRow.animate().alpha(0f).setDuration(100)
                    .withEndAction(() -> heightRow.setVisibility(View.GONE))
                    .start();
            suppressUntil[0] = android.os.SystemClock.uptimeMillis() + 400;
        };
        collapseHeightRow[0] = () -> {
            if (!rowExpanded[0]) {
                return;
            }
            rowExpanded[0] = false;
            rowSpaceHidden[0] = false;
            heightRow.animate().cancel();
            heightRowAnimating = false;
            heightRow.setAlpha(1f);
            heightRow.setTranslationY(0f);
            resetRowHeight.run();
            heightRow.setVisibility(View.GONE);
            sizeToggle.setImageResource(R.drawable.ic_chevron_down);
            applyDialogHeight(dialog, openDialogHeightPx(), shadowPad);
            suppressUntil[0] = android.os.SystemClock.uptimeMillis() + 400;
        };
        toggleRow.setOnClickListener(v -> {
            boolean show = !rowExpanded[0];
            rowExpanded[0] = show;
            rowSpaceHidden[0] = false;
            heightRow.animate().cancel();
            heightRowAnimating = false;
            heightRow.setAlpha(1f);
            heightRow.setTranslationY(0f);
            resetRowHeight.run();
            heightRow.setVisibility(show ? View.VISIBLE : View.GONE);
            sizeToggle.setImageResource(show
                    ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
            // 展开按「展开高度」记忆值，收起回「布局面板高度」记忆值；不再回默认自适应
            applyDialogHeight(dialog, show ? expandDialogHeightPx() : openDialogHeightPx(), shadowPad);
            // 显式点了 ▼：重置锁定，并抑制这次改高度引起的滚动回调
            suppressUntil[0] = android.os.SystemClock.uptimeMillis() + 400;
            if (show) {
                // 布局完成后记下真实行高，滚动收起/展开都以它为准
                heightRow.post(() -> {
                    int h = heightRow.getHeight();
                    if (h > 0) {
                        rowFullH[0] = h;
                    }
                });
            }
        });

        // 高度滑块那两行的显隐规则（用户最终口径，别再改回阈值/速度判定）：
        // 1. 只要列表开始往下滑（scrollY 增大）就立刻收起，慢滑、小幅也收，不看位移阈值与速度；
        // 2. 收起后一直保持隐藏：中途任何普通上滑、滚动停住、惯性结束都不显示；
        // 3. 只有列表真的滑到最顶部（canScrollVertically(-1) 为 false）才重新显示；
        // 4. 面板高度、位置全程不变：收起只把滑块藏起来，占位高度原样保留，不收缩窗口。
        dialogScroll.setOnTouchListener((tv, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_MOVE:
                    // 起点落在可点击子 View（开关/滑块）上时 DOWN 会被子 View 吃掉、
                    // 这里收不到；只要真的在动就同样算「手指按住」
                    fingerDown[0] = true;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    fingerDown[0] = false;
                    break;
                default:
                    break;
            }
            return false; // 不拦截，列表照常滚动
        });
        dialogScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            int dy = scrollY - oldScrollY;
            if (dy == 0 || !rowExpanded[0]) {
                return;
            }
            // 弹窗自身改高度/换尺寸引起的滚动不算用户动作，直接忽略
            if (android.os.SystemClock.uptimeMillis() < suppressUntil[0]) {
                return;
            }
            boolean shown = !rowSpaceHidden[0];
            if (shown) {
                // 往下滑一丁点就收起，不做累计、不等阈值；
                // 只是把滑块藏起来，面板仍是展开高度，箭头保持朝上不动
                if (dy > 0) {
                    hideRowSpace.run();
                }
                return;
            }
            // 已收起：普通上滑不显示，只有真正到达顶部才恢复（手指按住时判定，
            // 避免弹窗改高度导致的滚动回调把滑块自己放出来）
            if (fingerDown[0] && !dialogScroll.canScrollVertically(-1)) {
                showRowSpace.run();
            }
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

        // 滑块 1：设置面板范围 0–520dp，记忆下次点开布局设置时的高度，超出可用空间自动收窄
        openBar.setMax(LAYOUT_OPEN_MAX_DP);
        openBar.setProgress(openDialogDp());
        openValue.setText(heightLabel("设置面板范围", openDialogDp(), clampedOpenHeightPx(openDialogDp())));
        openBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int dp = clampInt(p, 0, LAYOUT_OPEN_MAX_DP);
                prefs().edit().putInt(lk(KEY_LAYOUT_OPEN_H), dp).apply();
                openValue.setText(heightLabel("设置面板范围", dp, clampedOpenHeightPx(dp)));
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

        // 滑块 2：设置面板展开范围 0–888dp，记忆点右上角倒三角时的高度，允许覆盖半屏（不盖冻结按钮）
        expandBar.setMax(LAYOUT_EXPAND_MAX_DP);
        expandBar.setProgress(expandDialogDp());
        expandValue.setText(heightLabel("设置面板展开范围", expandDialogDp(), clampedExpandHeightPx(expandDialogDp())));
        expandBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int dp = clampInt(p, 0, LAYOUT_EXPAND_MAX_DP);
                prefs().edit().putInt(lk(KEY_LAYOUT_EXPAND_H), dp).apply();
                expandValue.setText(heightLabel("设置面板展开范围", dp, clampedExpandHeightPx(dp)));
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

    /** 「展开高度」记忆值（dp）：新装/重置默认 888dp，手动调过的沿用记忆值。 */
    private int expandDialogDp() {
        return clampInt(prefs().getInt(lk(KEY_LAYOUT_EXPAND_H), LAYOUT_EXPAND_MAX_DP),
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

    /** 「展开高度」实际可用高度：允许覆盖半屏，受屏幕、888dp 与冻结按钮位置共同限制。 */
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

    /**
     * 展开高度上限：屏幕可用高度与 888dp 取小，再按底部冻结按钮的当前实际上沿夹紧。
     * 按钮位置受「按钮大小 / 距底部 / 左右偏移」影响，这里一律按运行期的屏幕坐标实时取，
     * 保证展开后的面板永远停在按钮上方，不会把按钮盖住。
     */
    private int expandHeightMaxPx() {
        int pad = Ui.dp(this, 12);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int max = screenH - statusBarHeightPx() - pad * 2 - Ui.dp(this, 8);
        max = Math.min(max, Ui.dp(this, LAYOUT_EXPAND_MAX_DP));
        int fabTop = freezeButtonTopPx();
        if (fabTop > 0) {
            // 弹窗内容顶边 = 默认 8dp + 记忆的拖动偏移，与 applyDialogGeometry 的定位口径一致
            int dialogTop = Ui.dp(this, 8) + prefs().getInt(lk(KEY_LAYOUT_DY), 0);
            max = Math.min(max, fabTop - dialogTop - Ui.dp(this, 12));
        }
        return Math.max(0, max);
    }

    /** 底部冻结按钮当前的上沿 y（屏幕坐标）；未加载/不可见时返回 -1。 */
    private int freezeButtonTopPx() {
        if (freezeBtn == null || freezeBtn.getVisibility() != View.VISIBLE
                || freezeBtn.getWidth() == 0) {
            return -1;
        }
        int[] loc = new int[2];
        freezeBtn.getLocationOnScreen(loc);
        return loc[1];
    }

    /** 布局弹窗当前内容高度（px，不含阴影留白）；未知时按 0 处理。 */
    private int dialogContentHeightPx(android.app.Dialog dialog, int shadowPad) {
        android.view.Window w = dialog.getWindow();
        if (w == null) {
            return 0;
        }
        int h = w.getAttributes().height;
        return Math.max(0, h - shadowPad * 2);
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

    // ---------------- 备份与恢复（新格式，与搞机助手同构） ----------------

    /** 只认这一套格式；旧的纯文本名单不再解析。 */
    private static final String BACKUP_FORMAT = "fwbackup";
    private static final int BACKUP_VERSION = 1;
    private static final String BACKUP_TEXT_PREFIX = "JIN:";
    private static final String BACKUP_DIR = "backup";
    /** 备份文件名前缀：JIN-yyyyMMdd-HHmmss.json，与文本前缀同一口径。 */
    private static final String BACKUP_FILE_PREFIX = "JIN-";
    private static final int RESTORE_SETTINGS = 1;
    private static final int RESTORE_FREEZE = 2;
    private static final int RESTORE_BOTH = 3;

    /** 名单类键归 freeze 段，其余键归 settings 段，不重复备份。 */
    private static final String[] BACKUP_LIST_KEYS = {
            KEY_PKGS, KEY_ORDER, KEY_CUSTOM, KEY_APP_TABS, KEY_TABS,
            KEY_CURRENT_TAB, KEY_DEFAULT_TAB, KEY_HIDE_TAB_ROW
    };

    /**
     * 只备份凚自己的配置：划卡/锁屏冻结（LSPosed 模块）的开关与标签、暂停运行态、
     * 壁纸本地资源（URI/缩放/模糊）都不进备份，避免碰别的模块或跨设备失效。
     */
    private static boolean ownSettingKey(String key) {
        if (key == null) {
            return false;
        }
        for (String k : BACKUP_LIST_KEYS) {
            if (k.equals(key)) {
                return false;
            }
        }
        if ("swipe_freeze_enabled".equals(key) || "swipe_freeze_tags".equals(key)
                || "lock_freeze_enabled".equals(key) || "lock_freeze_delay_seconds".equals(key)
                || "lock_freeze_tags".equals(key)
                || "suspended_pkgs".equals(key)) {
            return false;
        }
        return !key.contains("wallpaper");
    }

    /** 备份入口：只扩展原来的按钮，不新开页面。 */
    private void showBackup() {
        final String json = buildBackupJson();
        if (json == null) {
            Toast.makeText(this, "备份内容生成失败", Toast.LENGTH_SHORT).show();
            return;
        }
        final int listCount = items.size();
        final int settingCount = backupSettingCount();
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout optFile = optionRowCompact("存成文件",
                "写入 " + backupDir().getAbsolutePath() + "（" + BACKUP_FILE_PREFIX
                        + "时间.json），恢复时从文件列表选择");
        optFile.setOnClickListener(v -> {
            dismiss(ref);
            saveBackupFile(json, listCount, settingCount);
        });
        content.addView(optFile);
        LinearLayout optText = optionRowCompact("生成文本",
                "生成一串压缩后的短文本（自动复制），可存到备忘录 / 聊天里；恢复时粘回来即可");
        optText.setOnClickListener(v -> {
            dismiss(ref);
            showBackupText(json, listCount, settingCount);
        });
        content.addView(optText, topMargin(6));
        ref[0] = compactCard("备份",
                "冻结列表 " + listCount + " 个、设置 " + settingCount + " 项，会写进同一份备份",
                content, new String[]{"取消"}, new Runnable[]{null});
    }

    /** 恢复入口：先选来源（文件 / 文本），再决定恢复哪一部分。 */
    private void showRestore() {
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout optFile = optionRowCompact("选择备份文件",
                "从本机保存的备份文件恢复（" + backupDir().getAbsolutePath() + "）");
        optFile.setOnClickListener(v -> {
            dismiss(ref);
            showRestoreFiles();
        });
        content.addView(optFile);
        LinearLayout optText = optionRowCompact("粘贴备份文本",
                "粘贴之前复制的备份串（" + BACKUP_TEXT_PREFIX + " 开头）");
        optText.setOnClickListener(v -> {
            dismiss(ref);
            showRestorePaste();
        });
        content.addView(optText, topMargin(6));
        ref[0] = compactCard("恢复", "先选备份来源，再决定恢复配置还是冻结列表",
                content, new String[]{"取消"}, new Runnable[]{null});
    }

    /** 文本备份：压缩 + Base64 成尽量短的一串，进剪贴板并展示出来。 */
    private void showBackupText(String json, int listCount, int settingCount) {
        final String text = encodeBackupText(json);
        if (text == null) {
            Toast.makeText(this, "备份文本生成失败", Toast.LENGTH_SHORT).show();
            return;
        }
        final android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("凚备份", text));
        TextView view = Ui.text(this, text, 11, Ui.TEXT_SECONDARY, false);
        view.setTextIsSelectable(true);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(view);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 160)));
        int raw = json.length();
        int pct = raw == 0 ? 100 : Math.round(text.length() * 100f / raw);
        compactCard("备份文本（已复制）",
                "冻结列表 " + listCount + " 个 + 设置 " + settingCount + " 项\n"
                        + text.length() + " 字符（原始 " + raw + " 字符，约 " + pct + "%）",
                sv, new String[]{"关闭", "再复制一次"}, new Runnable[]{null, () -> {
                    cm.setPrimaryClip(ClipData.newPlainText("凚备份", text));
                    Toast.makeText(this, "备份文本已复制", Toast.LENGTH_SHORT).show();
                }});
    }

    /** 文件备份：内容与文本备份完全一致（JIN: 开头的压缩串），文件名沿用同一标识。 */
    private void saveBackupFile(final String json, final int listCount, final int settingCount) {
        final String text = encodeBackupText(json);
        if (text == null) {
            Toast.makeText(this, "备份内容生成失败", Toast.LENGTH_SHORT).show();
            return;
        }
        JinApp.IO.execute(() -> {
            File out = null;
            String err = null;
            try {
                File dir = backupDir();
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new java.io.IOException("无法创建目录 " + dir);
                }
                String name = BACKUP_FILE_PREFIX
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
                out = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(text.getBytes("UTF-8"));
                fos.close();
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final File f = out;
            final String msg = err;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (f == null || msg != null) {
                    compactCard("备份失败", "写入备份文件失败：" + msg, null,
                            new String[]{"知道了"}, new Runnable[]{null});
                    return;
                }
                final String path = f.getAbsolutePath();
                compactCard("备份完成",
                        "冻结列表 " + listCount + " 个 + 设置 " + settingCount + " 项\n" + path,
                        null, new String[]{"关闭", "复制路径"}, new Runnable[]{null, () -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("备份文件路径", path));
                            Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show();
                        }});
            });
        });
    }

    /** 文件来源：按修改时间从新到旧列出备份文件。 */
    private void showRestoreFiles() {
        File[] arr = backupDir().listFiles((d, n) ->
                n.startsWith(BACKUP_FILE_PREFIX) && n.endsWith(".json"));
        List<File> files = new ArrayList<>();
        if (arr != null) {
            Collections.addAll(files, arr);
        }
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (files.isEmpty()) {
            compactCard("还没有备份文件",
                    "可以先做一次备份，或者用「粘贴备份文本」恢复之前复制的备份串",
                    null, new String[]{"知道了"}, new Runnable[]{null});
            return;
        }
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (int i = 0; i < files.size(); i++) {
            final File f = files.get(i);
            LinearLayout row = optionRowCompact(
                    fmt.format(new Date(f.lastModified())) + "  ·  "
                            + Math.max(1, f.length() / 1024) + " KB", f.getName());
            row.setOnClickListener(v -> {
                dismiss(ref);
                readBackupFile(f);
            });
            list.addView(row, topMargin(i == 0 ? 0 : 6));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 260)));
        ref[0] = compactCard("选择备份文件", "共 " + files.size() + " 份，按时间从新到旧",
                sv, new String[]{"取消"}, new Runnable[]{null});
    }

    private void readBackupFile(final File f) {
        JinApp.IO.execute(() -> {
            String text = null;
            String err = null;
            try {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                in.close();
                text = new String(bo.toByteArray(), "UTF-8");
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String raw = text;
            final String msg = err;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (raw == null) {
                    compactCard("读取失败", "这个备份文件读不出来：" + msg, null,
                            new String[]{"知道了"}, new Runnable[]{null});
                    return;
                }
                if (parseBackup(decodeIfText(raw)) == null) {
                    compactCard("无法恢复",
                            "这个文件不是凚的备份（缺少 " + BACKUP_TEXT_PREFIX
                                    + " 标识或内容已损坏），未改动任何配置",
                            null, new String[]{"知道了"}, new Runnable[]{null});
                    return;
                }
                startRestore(raw, f.getName());
            });
        });
    }

    /** 文本来源：只认新备份串，不符合格式直接提示，不做旧版兼容。 */
    private void showRestorePaste() {
        EditText input = new EditText(this);
        input.setHint("粘贴 " + BACKUP_TEXT_PREFIX + " 开头的备份文本（备份文件内容也可直接粘进来）");
        input.setTextSize(12);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(6);
        input.setMaxLines(12);
        input.setBackground(null);
        input.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(input);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 200)));
        compactCard("粘贴备份", "整串粘贴，内容不会被改动", sv,
                new String[]{"取消", "解析"}, new Runnable[]{null, () -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) {
                        Toast.makeText(this, "请先粘贴备份内容", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (parseBackup(decodeIfText(raw)) == null) {
                        compactCard("无法恢复",
                                "这段内容不是凚的备份（缺少 format 标识或内容已损坏），未改动任何配置",
                                null, new String[]{"知道了"}, new Runnable[]{null});
                        return;
                    }
                    startRestore(raw, "粘贴的文本");
                }});
        // 打开面板即聚焦输入框并拉起输入法
        input.requestFocus();
        input.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    /** 解析成功后按范围三选一。 */
    private void startRestore(String raw, String source) {
        JSONObject data = parseBackup(decodeIfText(raw));
        if (data == null) {
            Toast.makeText(this, "格式不对：需要 " + BACKUP_TEXT_PREFIX + " 开头的备份文本",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject freeze = data.optJSONObject("freeze");
        JSONObject settings = data.optJSONObject("settings");
        int listCount = 0;
        if (freeze != null) {
            JSONArray arr = freeze.optJSONArray("pkgs");
            listCount = arr == null ? freeze.optInt("count", 0) : arr.length();
        }
        int setCount = settings == null ? 0 : settings.length();
        StringBuilder desc = new StringBuilder("来源：").append(source);
        String created = data.optString("createdAt", "");
        String ver = data.optString("appVersion", "");
        if (!created.isEmpty()) {
            desc.append("\n备份时间：").append(created);
        }
        if (!ver.isEmpty()) {
            desc.append("  ·  版本 ").append(ver);
        }
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout onlySettings = optionRowCompact("只恢复配置",
                setCount + " 项设置（布局、列数、图标大小、透明度、开关等），冻结列表不动");
        onlySettings.setOnClickListener(v -> {
            dismiss(ref);
            applyRestore(data, RESTORE_SETTINGS);
        });
        content.addView(onlySettings);
        LinearLayout onlyList = optionRowCompact("只恢复冻结列表",
                listCount + " 个应用（含顺序与标签归属），覆盖当前名单，设置不动");
        onlyList.setOnClickListener(v -> {
            dismiss(ref);
            applyRestore(data, RESTORE_FREEZE);
        });
        content.addView(onlyList, topMargin(6));
        LinearLayout both = optionRowCompact("两者一起恢复",
                "配置 " + setCount + " 项 + 冻结列表 " + listCount + " 个");
        both.setOnClickListener(v -> {
            dismiss(ref);
            applyRestore(data, RESTORE_BOTH);
        });
        content.addView(both, topMargin(6));
        ref[0] = compactCard("选择恢复内容", desc, content,
                new String[]{"取消"}, new Runnable[]{null});
    }

    /**
     * 恢复：只写凚自己的 prefs，不碰 /data/system/jin_freeze_list.json，
     * 也不主动调 SwipeFreeze 同步；插件名单由原有路径在下次进列表页时自然同步。
     */
    private void applyRestore(JSONObject data, int mode) {
        int setCount = 0;
        int listCount = 0;
        int missing = 0;
        if (mode != RESTORE_FREEZE) {
            setCount = writeBackupSettings(data.optJSONObject("settings"));
        }
        if (mode != RESTORE_SETTINGS) {
            int[] r = writeBackupFreeze(data.optJSONObject("freeze"));
            listCount = r[0];
            missing = r[1];
        }
        StringBuilder msg = new StringBuilder();
        if (mode != RESTORE_FREEZE) {
            msg.append("配置 ").append(setCount).append(" 项");
        }
        if (mode != RESTORE_SETTINGS) {
            if (msg.length() > 0) {
                msg.append('\n');
            }
            msg.append("冻结列表 ").append(listCount).append(" 个");
            if (missing > 0) {
                msg.append("（跳过未安装 ").append(missing).append(" 个）");
            }
        }
        final boolean settingsApplied = mode != RESTORE_FREEZE;
        compactCard("恢复完成", msg.toString(), null, new String[]{"完成"}, new Runnable[]{() -> {
            if (settingsApplied) {
                // 布局参数在 onCreate 读取，重建一次让恢复的设置立即生效
                recreate();
            } else {
                reload();
            }
        }});
    }

    /** 备份 JSON：与搞机助手同一套字段，freeze 段 + settings 段。 */
    private String buildBackupJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("format", BACKUP_FORMAT);
            root.put("version", BACKUP_VERSION);
            root.put("app", getPackageName());
            root.put("appVersion", appVersionName());
            root.put("createdAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date()));

            Set<String> cur = pkgs();
            Set<String> put = new HashSet<>();
            JSONArray pkgsArr = new JSONArray();
            for (Item it : items) {
                if (cur.contains(it.pkg) && put.add(it.pkg)) {
                    pkgsArr.put(it.pkg);
                }
            }
            for (String p : cur) {
                if (put.add(p)) {
                    pkgsArr.put(p);
                }
            }
            JSONArray orderArr = new JSONArray();
            for (String p : savedOrder()) {
                orderArr.put(p);
            }
            JSONObject freeze = new JSONObject();
            freeze.put("count", pkgsArr.length());
            freeze.put("pkgs", pkgsArr);
            freeze.put("order", orderArr);
            freeze.put("custom_order", customOrder());
            freeze.put("app_tabs", prefs().getString(KEY_APP_TABS, "{}"));
            freeze.put("tabs", prefs().getString(KEY_TABS, ""));
            freeze.put("current_tab", prefs().getString(KEY_CURRENT_TAB, ""));
            freeze.put("default_tab", prefs().getString(KEY_DEFAULT_TAB, ""));
            freeze.put("tab_bar_hidden", prefs().getBoolean(KEY_HIDE_TAB_ROW, false));
            root.put("freeze", freeze);

            JSONObject settings = new JSONObject();
            for (Map.Entry<String, ?> en : prefs().getAll().entrySet()) {
                if (!ownSettingKey(en.getKey())) {
                    continue;
                }
                Object v = en.getValue();
                JSONObject one = new JSONObject();
                if (v instanceof Boolean) {
                    one.put("type", "boolean");
                } else if (v instanceof Integer) {
                    one.put("type", "int");
                } else if (v instanceof Long) {
                    one.put("type", "long");
                } else if (v instanceof Float) {
                    one.put("type", "float");
                } else if (v instanceof Set) {
                    one.put("type", "string_set");
                    JSONArray arr = new JSONArray();
                    for (Object o : (Set<?>) v) {
                        arr.put(String.valueOf(o));
                    }
                    one.put("value", arr);
                    settings.put(en.getKey(), one);
                    continue;
                } else {
                    one.put("type", "string");
                }
                one.put("value", v);
                settings.put(en.getKey(), one);
            }
            root.put("settings", settings);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 新格式校验：format 必须匹配，不认识的版本直接拒绝。 */
    private JSONObject parseBackup(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(raw.trim());
            if (!BACKUP_FORMAT.equals(o.optString("format"))) {
                return null;
            }
            if (o.optInt("version", 0) > BACKUP_VERSION) {
                return null;
            }
            if (o.optJSONObject("freeze") == null && o.optJSONObject("settings") == null) {
                return null;
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private String decodeIfText(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.startsWith(BACKUP_TEXT_PREFIX) ? decodeBackupText(t) : t;
    }

    /** 文本编码：UTF-8 → Deflate(9) → Base64，无换行，整串可复制。 */
    private String encodeBackupText(String json) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DeflaterOutputStream out = new DeflaterOutputStream(bos, new Deflater(9));
            out.write(json.getBytes("UTF-8"));
            out.close();
            return BACKUP_TEXT_PREFIX + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本解码：只认 JIN: 前缀（旧 FWB1: 不兼容），解压后交给 parseBackup 校验。 */
    private String decodeBackupText(String raw) {
        if (raw == null || !raw.startsWith(BACKUP_TEXT_PREFIX)) {
            return null;
        }
        try {
            byte[] data = Base64.decode(raw.substring(BACKUP_TEXT_PREFIX.length()), Base64.NO_WRAP);
            InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** 写回配置：跳过名单键与划卡冻结/运行态/壁纸键，其余按原类型写。 */
    private int writeBackupSettings(JSONObject settings) {
        if (settings == null) {
            return 0;
        }
        SharedPreferences.Editor e = prefs().edit();
        int n = 0;
        Iterator<String> it = settings.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (!ownSettingKey(key)) {
                continue;
            }
            JSONObject one = settings.optJSONObject(key);
            if (one == null) {
                continue;
            }
            try {
                switch (one.optString("type")) {
                    case "boolean":
                        e.putBoolean(key, one.optBoolean("value"));
                        break;
                    case "int":
                        e.putInt(key, one.optInt("value"));
                        break;
                    case "long":
                        e.putLong(key, one.optLong("value"));
                        break;
                    case "float":
                        e.putFloat(key, (float) one.optDouble("value"));
                        break;
                    case "string_set": {
                        JSONArray arr = one.optJSONArray("value");
                        Set<String> set = new HashSet<>();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                set.add(arr.optString(i, ""));
                            }
                        }
                        e.putStringSet(key, set);
                        break;
                    }
                    default:
                        e.putString(key, one.optString("value"));
                        break;
                }
                n++;
            } catch (Exception ignored) {
            }
        }
        e.apply();
        return n;
    }

    /** 写回冻结列表：未安装的包跳过，返回 {写入数, 跳过数}。 */
    private int[] writeBackupFreeze(JSONObject freeze) {
        if (freeze == null) {
            return new int[]{0, 0};
        }
        JSONArray arr = freeze.optJSONArray("pkgs");
        PackageManager pm = getPackageManager();
        Set<String> next = new HashSet<>();
        int missing = 0;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (p.isEmpty()) {
                    continue;
                }
                try {
                    pm.getApplicationInfo(p, 0);
                    next.add(p);
                } catch (Exception e) {
                    missing++;
                }
            }
        }
        StringBuilder order = new StringBuilder();
        JSONArray orderArr = freeze.optJSONArray("order");
        if (orderArr != null) {
            for (int i = 0; i < orderArr.length(); i++) {
                String p = orderArr.optString(i, "");
                if (p.isEmpty()) {
                    continue;
                }
                if (order.length() > 0) {
                    order.append('\n');
                }
                order.append(p);
            }
        }
        SharedPreferences.Editor e = prefs().edit();
        e.putStringSet(KEY_PKGS, next);
        e.putString(KEY_ORDER, order.toString());
        e.putBoolean(KEY_CUSTOM, freeze.optBoolean("custom_order", false));
        putString(e, freeze, "app_tabs", KEY_APP_TABS);
        putString(e, freeze, "tabs", KEY_TABS);
        putString(e, freeze, "current_tab", KEY_CURRENT_TAB);
        putString(e, freeze, "default_tab", KEY_DEFAULT_TAB);
        if (freeze.has("tab_bar_hidden")) {
            e.putBoolean(KEY_HIDE_TAB_ROW, freeze.optBoolean("tab_bar_hidden", false));
        }
        e.apply();
        return new int[]{next.size(), missing};
    }

    private static void putString(SharedPreferences.Editor e, JSONObject o, String src, String key) {
        if (o.has(src) && !o.isNull(src)) {
            e.putString(key, o.optString(src, ""));
        }
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int backupSettingCount() {
        int n = 0;
        for (String key : prefs().getAll().keySet()) {
            if (ownSettingKey(key)) {
                n++;
            }
        }
        return n;
    }

    private File backupDir() {
        // 用户指定备份路径：/storage/emulated/0/Download/凚/备份
        File dir = new File("/storage/emulated/0/Download/凚/备份");
        if (!dir.exists()) dir.mkdirs();
        if (dir.exists() && dir.canWrite()) {
            return dir;
        }
        // 兜底：Download 目录不可写时退回应用内部存储
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File fallback = new File(base, BACKUP_DIR);
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    /** 紧凑卡片弹窗：宽度 76%、内边距与字号整体收一档，与搞机助手备份/恢复弹框同一档。 */
    private AlertDialog compactCard(String title, CharSequence desc, View content,
            String[] labels, final Runnable[] actions) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_dialog);
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 8));
        card.addView(Ui.text(this, title, 15, Ui.TEXT_PRIMARY, true));
        if (desc != null && desc.length() > 0) {
            card.addView(Ui.text(this, desc, 11, Ui.TEXT_SECONDARY, false), topMargin(6));
        }
        if (content != null) {
            LinearLayout.LayoutParams clp =
                    content.getLayoutParams() instanceof LinearLayout.LayoutParams
                            ? (LinearLayout.LayoutParams) content.getLayoutParams()
                            : new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Ui.dp(this, 10);
            card.addView(content, clp);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        card.addView(row, topMargin(8));
        final AlertDialog dialog = new AlertDialog.Builder(this).setView(card).create();
        for (int i = 0; i < labels.length; i++) {
            TextView b = cardButton(labels[i]);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 30));
            if (i > 0) {
                blp.leftMargin = Ui.dp(this, 4);
            }
            row.addView(b, blp);
            final Runnable act = actions[i];
            b.setOnClickListener(v -> {
                dialog.dismiss();
                if (act != null) {
                    act.run();
                }
            });
        }
        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.76f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    /** 卡片底部按钮：30dp 高、无最小宽度、透明底（保留水波纹）。 */
    private TextView cardButton(String label) {
        TextView b = Ui.text(this, label, 12, Ui.BLUE, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        android.util.TypedValue bg = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true)) {
            b.setBackgroundResource(bg.resourceId);
        }
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        return b;
    }

    /** 备份 / 恢复选项行：标题 13sp + 说明 10sp，行内边距收一档。 */
    private LinearLayout optionRowCompact(String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        android.util.TypedValue bg = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true)) {
            row.setBackgroundResource(bg.resourceId);
        }
        int v = Ui.dp(this, 8);
        row.setPadding(Ui.dp(this, 4), v, Ui.dp(this, 4), v);
        row.addView(Ui.text(this, title, 13, Ui.TEXT_PRIMARY, true));
        if (desc != null && !desc.isEmpty()) {
            row.addView(Ui.text(this, desc, 10, Ui.TEXT_SECONDARY, false), topMargin(2));
        }
        return row;
    }

    private LinearLayout.LayoutParams topMargin(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private static void dismiss(AlertDialog[] holder) {
        if (holder != null && holder[0] != null && holder[0].isShowing()) {
            holder[0].dismiss();
        }
    }

    // ---------------- 权限 ----------------

    /** 布局设置顶部的权限行：显示 root / Shizuku 状态，未授权时可点开授权。 */
    /** 标题整句：「设置（冻结权限：Root）」，括号内小一号、次要色、非加粗。 */
    private void bindPrivilegeTitle(TextView view) {
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            String head = "设置";
            String tail = "（权限：" + privilegeValue() + "）";
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
        privTitleRefresh = refresh[0];
        // 探测 LSPosed：设置页发 ping，system_server 里的模块回 pong；
        // 800ms 没回就当没激活，标题只按 root/Shizuku 显示
        lsposedAlive = null;
        sendBroadcast(new Intent(SwipeFreeze.ACTION_STATUS_PING).setPackage("android"));
        main.postDelayed(() -> {
            if (lsposedAlive == null) {
                lsposedAlive = false;
                if (privTitleRefresh != null) {
                    privTitleRefresh.run();
                }
            }
        }, 800);
        IO.execute(() -> {
            PrivShell.mode();
            main.post(refresh[0]);
        });
        view.setOnClickListener(v -> handlePrivilegeClick(refresh[0]));
    }

    /** 权限值：Root、Root+LSPosed、仅 LSPosed（仍需 Root）、Shizuku、未授权，检测中单独一档。 */
    private String privilegeValue() {
        PrivShell.Mode mode = PrivShell.peek();
        boolean lsposed = Boolean.TRUE.equals(lsposedAlive);
        if (mode == null) {
            return "检测中";
        }
        if (mode == PrivShell.Mode.ROOT) {
            return lsposed ? "Root、LSPosed" : "Root";
        }
        if (lsposed) {
            // LSPosed 只能挂 Hook，冻结仍要 Root 才能写 /data/system
            return "LSPosed，仍需Root权限";
        }
        if (mode == PrivShell.Mode.SHIZUKU) {
            return "Shizuku";
        }
        return "未激活";
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
        int padH = Ui.dp(this, 15);
        root.setPadding(padH, Ui.dp(this, 13), padH, Ui.dp(this, 4));

        TextView title = new TextView(this);
        title.setText("您未授权于凚");
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText("请在Shizuku或root授权凚后，强制停止凚，再打开凚，即可激活，如未激活成功，请再次检查授权状态。");
        message.setTextSize(11);
        message.setLineSpacing(Ui.dp(this, 2), 1f);
        message.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 7);
        root.addView(message, mlp);

        TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextSize(12);
        ok.setTypeface(null, android.graphics.Typeface.BOLD);
        ok.setTextColor(getColor(R.color.text_secondary));
        ok.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ok.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), 0, Ui.dp(this, 7));
        ok.setOnClickListener(v -> dialog.dismiss());

        final TextView retry = new TextView(this);
        retry.setText("重新检测");
        retry.setTextSize(12);
        retry.setTypeface(null, android.graphics.Typeface.BOLD);
        retry.setTextColor(getColor(R.color.blue));
        retry.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        retry.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 7));
        retry.setOnClickListener(v -> {
            retry.setEnabled(false);
            retry.setText("检测中…");
            recheckPrivilegeAsync(true, true, () -> {
                retry.setEnabled(true);
                retry.setText("重新检测");
                if (!isFinishing() && privDialog != null && privDialog.isShowing()) {
                    Toast.makeText(this, "仍未激活：" + PrivShell.failureHint(),
                            Toast.LENGTH_SHORT).show();
                }
            });
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.addView(retry);
        actions.addView(ok);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Ui.dp(this, 2);
        root.addView(actions, olp);

        dialog.setContentView(root);
        // 首帧就按圆角收窄落位，onShow 再兜一次，避免先出大块默认底再跳
        int insetDp = 98;
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

    /**
     * 重新探测权限：force=false 走缓存，force=true 立即重测。
     * 通过时关掉未授权弹窗并刷新「冻结权限」标题；仍失败时回调 onStillNone。
     */
    private void recheckPrivilegeAsync(boolean force, boolean dismissWhenGranted,
                                       Runnable onStillNone) {
        IO.execute(() -> {
            PrivShell.Mode mode = PrivShell.mode(force);
            main.post(() -> {
                if (mode != PrivShell.Mode.NONE) {
                    if (dismissWhenGranted && privDialog != null && privDialog.isShowing()) {
                        privDialog.dismiss();
                    }
                    if (privTitleRefresh != null) {
                        privTitleRefresh.run();
                    }
                    return;
                }
                if (onStillNone != null) {
                    onStillNone.run();
                }
            });
        });
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
            // 解冻两个通道都收：先撤暂停（非暂停状态下为空操作），再补启用
            PrivShell.Result r = execPm(pkg, userIdPart(pkg), false);
            boolean ok = applied(pkg, false, r);
            if (!ok) {
                android.util.Log.w("JinPm", "removeNow unfreeze failed:\n"
                        + (r.out == null ? "" : r.out.trim()));
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    AlertDialog failOne = new AlertDialog.Builder(this)
                            .setTitle("已移出名单，但解冻失败")
                            .setMessage(label + "：" + Ui.pmReason(r, false))
                            .setPositiveButton("知道了", null)
                            .create();
                    failOne.show();
                    Ui.roundAlertDialog(failOne);
                });
            }
        });
    }

    private void runBatch(boolean freeze, List<Item> shown) {
        if (working) {
            return;
        }
        final List<Item> targets = new ArrayList<>();
        int skippedCount = 0;
        for (Item it : shown) {
            // 已经到位的直接跳过，不发起 pm 调用，也不算失败
            if (freeze == it.frozen) {
                skippedCount++;
                continue;
            }
            targets.add(it);
        }
        final int skippedFinal = skippedCount;
        if (targets.isEmpty()) {
            if (freeze) {
                // 一键冻结没有可处理的目标（名单为空 / 全部已冻结）：
                // 用和「已冻结」完全同一套自绘提示，只换文案
                FrozenTip.show(this, "无需冻结");
            } else if (skippedCount == 0) {
                Toast.makeText(this, "没有需要处理的应用", Toast.LENGTH_SHORT).show();
            }
            // 「冻结后收起列表」的自动收起保持原有判断，不受提示影响
            if (skippedCount > 0 && panelDismissAfterFreeze) {
                finishAndRemoveTask();
                overridePendingTransition(0, 0);
            }
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
            Map<String, String> outputs = new HashMap<>();
            Set<String> blocked = new HashSet<>();
            // 主用户走一次性批量脚本；分身逐个带 --user 执行，没有 suspend 降级通道
            List<String> zeroKeys = new ArrayList<>();
            for (Item it : targets) {
                if (it.userId == 0) zeroKeys.add(it.pkg);
            }
            if (!zeroKeys.isEmpty()) {
                if (freeze && keepLauncherIcon) {
                    // 勾选「留桌面图标」：整批改走暂停通道，不执行 pm disable-user，图标才不会被摘掉
                    outputs.putAll(execSuspendBatch(zeroKeys));
                } else {
                    outputs.putAll(execPmBatch(zeroKeys, freeze));
                    if (freeze) {
                        // 只对被 ROM 拦住停用的包（两类固定文案）降级暂停，其余包行为不变
                        for (String key : zeroKeys) {
                            if (SuspendSupport.romBlocked(outputs.get(key))) {
                                blocked.add(key);
                            }
                        }
                        if (!blocked.isEmpty()) {
                            Map<String, String> suspendOut = execSuspendBatch(new ArrayList<>(blocked));
                            for (String key : blocked) {
                                String prev = outputs.get(key);
                                String now = suspendOut.get(key);
                                outputs.put(key, (prev == null ? "" : prev + "\n")
                                        + (now == null ? "" : now));
                            }
                        }
                    }
                }
            }
            for (Item it : targets) {
                if (it.userId == 0) continue;
                PrivShell.Result r = execPm(it.pkg, it.userId, freeze);
                outputs.put(it.pkg, r == null || r.out == null ? "" : r.out);
            }
            // 批量只回读一次系统真值，逐包按「是否已在目标状态」判成败；读不到才退回文本判断
            Set<String> truth = disabledPackages();
            // 降级通道真值同样只批量读一次：冻结时查「停用没成功的」，解冻时查本机暂停过的
            Set<String> suspendCandidates = new HashSet<>();
            Set<String> zeroNames = new HashSet<>();
            for (Item it : targets) {
                if (it.userId == 0) zeroNames.add(packagePart(it.pkg));
            }
            if (freeze) {
                for (String name : zeroNames) {
                    if (truth == null || !truth.contains(name)) {
                        suspendCandidates.add(name);
                    }
                }
            } else {
                suspendCandidates.addAll(SuspendSupport.cached(this));
                suspendCandidates.retainAll(zeroNames);
            }
            Set<String> suspendedTruth = SuspendSupport.suspendedAmong(suspendCandidates);
            if (freeze) {
                SuspendSupport.remember(this, suspendedTruth);
                // 暂停不结束已有进程：确认暂停生效后补一次 force-stop，让冻结立即生效
                if (keepLauncherIcon && !suspendedTruth.isEmpty()) {
                    StringBuilder stop = new StringBuilder();
                    for (String pkg : suspendedTruth) {
                        stop.append("am force-stop ").append(pkg).append("; ");
                    }
                    PrivShell.exec(stop.toString(), 30);
                }
            } else {
                Set<String> next = SuspendSupport.cached(this);
                next.removeAll(zeroNames);
                next.addAll(suspendedTruth);
                SuspendSupport.replace(this, next);
            }
            int okCount = 0;
            List<String> fails = new ArrayList<>();
            for (Item it : targets) {
                String key = it.pkg;
                String out = outputs.get(key);
                if (out == null) {
                    out = "pm 未返回该包的结果";
                }
                boolean ok;
                if (it.userId != 0) {
                    // 分身没有主用户禁用列表可用，按该条命令的输出判定
                    ok = isPmOkText(out);
                } else if (truth != null) {
                    String name = packagePart(key);
                    boolean dis = truth.contains(name);
                    // 暂停态与停用态等价：冻结命中任一即成功，解冻必须两者都退出
                    ok = freeze ? (dis || suspendedTruth.contains(name))
                            : (!dis && !suspendedTruth.contains(name));
                } else {
                    ok = isPmOkText(out);
                }
                if (ok) {
                    okCount++;
                } else {
                    // 批量失败清单只列应用名 + 短原因，不贴 pm 栈；
                    // 已降级仍失败的给「两条通道都被拒」那句，不再回放 disable 的原始报错
                    fails.add(it.label + "：" + (blocked.contains(key)
                            ? Ui.NO_FREEZE_CHANNEL : Ui.pmReason(out, freeze)));
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
                    for (String f : fails) {
                        sb.append("\n\n").append(f);
                    }
                    AlertDialog failBatch = new AlertDialog.Builder(this)
                            .setTitle(freeze ? "冻结失败" : "解冻失败")
                            .setMessage(sb.toString())
                            .setPositiveButton("知道了", null)
                            .create();
                    failBatch.show();
                    Ui.roundAlertDialog(failBatch);
                }
                // 冻结且没有失败项时，按设置在本轮刷新完成后收起列表
                if (freeze && fails.isEmpty()) {
                    if (showFrozenTip) {
                        FrozenTip.show(this);
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
        StringBuilder script = new StringBuilder();
        for (String pkg : pkgs) {
            script.append("echo '").append(MARK).append(pkg).append("'; ")
                    .append(pmCommand(pkg, 0, freeze))
                    .append(" 2>&1; ");
        }
        return splitMarked(PrivShell.exec(script.toString(),
                Math.max(30, 10 + pkgs.size() * 5)), pkgs);
    }

    /**
     * 降级通道的批量暂停：能走 SuspendDialogInfo 的逐包走它（标题/正文/按钮都可定制），
     * 剩下的才拼成一条脚本执行，输出格式与 execPmBatch 一致。
     */
    private Map<String, String> execSuspendBatch(List<String> pkgs) {
        Map<String, String> result = new HashMap<>();
        List<String> pending = new ArrayList<>();
        for (String pkg : pkgs) {
            String name = packagePart(pkg);
            if (SuspendDialog.suspend(this, name, labelOf(pkg))) {
                result.put(pkg, "");
            } else {
                pending.add(pkg);
            }
        }
        if (pending.isEmpty()) {
            return result;
        }
        StringBuilder script = new StringBuilder();
        for (String pkg : pending) {
            script.append("echo '").append(MARK).append(pkg).append("'; ")
                    .append(SuspendSupport.suspendCommand(packagePart(pkg), labelOf(pkg)))
                    .append(" 2>&1; ");
        }
        result.putAll(splitMarked(PrivShell.exec(script.toString(),
                Math.max(30, 10 + pending.size() * 5)), pending));
        return result;
    }

    private static final String MARK = "@@PKG@@";

    /** 按 @@PKG@@ 标记把一条脚本的输出切成「包名 → 该包的真实输出」。 */
    private Map<String, String> splitMarked(PrivShell.Result r, List<String> pkgs) {
        final String mark = MARK;
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

    /**
     * 冻结（含暂停）的应用点击后一步到位：撤销暂停 + 启用（需要时）后直接打开，
     * 不弹任何二次确认。成功路径完全静默，失败只给一句短原因。
     */
    private void unfreezeAndOpen(Item it) {
        if (working) {
            return;
        }
        working = true;
        IO.execute(() -> {
            PrivShell.Result r = execPm(it.pkg, it.userId, false);
            boolean ok = applied(it.pkg, false, r);
            // 撤销暂停失败时单独给一句，不落到通用的「启用未生效」
            final String failReason = !ok && SuspendSupport.cached(this).contains(it.pkg)
                    && Boolean.TRUE.equals(SuspendSupport.suspendedOne(it.pkg))
                    ? "本机不允许取消暂停，无法打开" : null;
            main.post(() -> {
                working = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    AlertDialog failOpen = new AlertDialog.Builder(this)
                            .setTitle("解冻失败，无法打开")
                            .setMessage(it.label + "：" + (failReason != null
                                    ? failReason : Ui.pmReason(r, false)))
                            .setPositiveButton("知道了", null)
                            .create();
                    failOpen.show();
                    Ui.roundAlertDialog(failOpen);
                    reload();
                    return;
                }
                it.frozen = false;
                it.suspended = false;
                rebindCells();
                launch(it);
                reload();
            });
        });
    }

    private void launch(Item it) {
        String name = packagePart(it.pkg);
        Intent i = getPackageManager().getLaunchIntentForPackage(name);
        if (i == null) {
            Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
            return;
        }
        // 分身必须在它自己的用户下启动，否则会打开主应用
        if (it.userId != 0 && i.getComponent() != null) {
            PrivShell.exec("am start --user " + it.userId + " -n "
                    + i.getComponent().flattenToShortString(), 15);
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

    /** 执行 pm，返回完整结果（含退出码与是否超时），成败不在这里判。 */
    private PrivShell.Result execPm(String pkg, boolean freeze) {
        return execPm(pkg, 0, freeze);
    }

    private PrivShell.Result execPm(String pkg, int userId, boolean freeze) {
        return PrivShell.exec(pmCommand(pkg, userId, freeze), 30);
    }

    /** 冻结和解冻都明确指定目标用户，避免主用户与分身互相影响。 */
    private static String pmCommand(String pkg, int userId, boolean freeze) {
        // 入参可能是「包名@用户」的实例键，真正交给 pm 的必须去掉用户后缀
        String name = packagePart(pkg);
        return freeze
                ? "pm disable-user --user " + userId + " " + name
                : "pm unsuspend --user " + userId + " " + name
                + "; pm enable --user " + userId + " " + name;
    }

    /**
     * 以系统真值判定这一步是否已生效：
     * 冻结 = 在禁用列表里，或（降级通道）处于暂停态；
     * 解冻 = 既不在禁用列表，也确实退出了暂停。
     * 禁用真值读不到才退回退出码 + 报错文本。
     */
    private boolean applied(String pkgOrKey, boolean freeze, PrivShell.Result r) {
        // 分身没有主用户的禁用真值可用，只能按命令输出判定
        if (userIdPart(pkgOrKey) != 0) {
            return isPmOk(r);
        }
        String pkg = packagePart(pkgOrKey);
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
        // 只对本机暂停过的包额外确认一次「确实退出暂停」；没暂停过的包不额外查
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
    private boolean suspendApplied(String pkgOrKey, PrivShell.Result r) {
        String pkg = packagePart(pkgOrKey);
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

    /** 批量脚本按包切出来的文本兜底判定（真值读不到时才用）。 */
    private boolean isPmOkText(String out) {
        String low = out.toLowerCase(Locale.ROOT);
        return !low.contains("error") && !low.contains("exception")
                && !low.contains("failure") && !low.contains("denied")
                && !low.contains("无输出") && !low.contains("超时");
    }
}
