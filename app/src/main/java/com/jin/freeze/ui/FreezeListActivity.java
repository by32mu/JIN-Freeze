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
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
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
import com.jin.freeze.core.DhizukuGate;
import com.jin.freeze.core.LaunchTarget;
import com.jin.freeze.core.Pinyin;
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
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 冻结列表：用户自选的名单，支持一键冻结/解冻整份名单。
 * 冻结状态一律以 pm 的系统真值为准；点击图标直接打开（冻结的先解冻再开）。
 * 长按图标拖拽调整顺序（自定义顺序持久化）；编辑模式下点减号移出名单。
 */
public class FreezeListActivity extends BaseActivity {

    private static final String PREFS = "freeze_list";
    private static final String KEY_PKGS = "pkgs";
    /** 记录 View 原始 elevation 的 tag 键（仅本类内部使用，避免阴影被反复缩放失真）。 */
    private static final int TAG_BASE_ELEVATION = 0x0A1B2C3D;

    private static String instanceKey(String pkg, int userId) {
        return pkg + "@" + userId;
    }

    /** 落盘用的键：主用户沿用纯包名（兼容旧名单），分身才带「@用户」后缀。 */
    private static String storageKey(String pkg, int userId) {
        return userId == 0 ? pkg : instanceKey(pkg, userId);
    }

    /** 多用户实例的名称后缀：999 是双开，其余（如 10）是分身。 */
    private static String cloneTag(int userId) {
        return userId == 999 ? "（双开）" : "（分身）";
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

    /** 取用户号：同时兼容「pkg@10」与「pkg (10)」。 */
    private static int userIdPart(String key) {
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
    private static final String KEY_ORDER = "order";
    private static final String KEY_CUSTOM = "custom_order";
    private static final int COLUMNS = 5;
    /** 自定义布局：列数 + 图标大小，间距由列宽自动均分。 */
    private static final String KEY_COLUMNS = "grid_columns";
    private static final String KEY_ICON_DP = "grid_icon_dp";
    private static final String KEY_ICON_MANUAL = "grid_icon_manual";
    private static final String KEY_SHOW_NAME = "grid_show_name";
    /** 长按空白处手势总开关：关闭后长按不再起手势，且相关设置项整体隐藏。 */
    private static final String KEY_GESTURE_ENABLED = "gesture_enabled";
    /** 长按空白后上滑（半屏进全屏）/ 下滑（全屏回半屏）的触发距离（dp）。 */
    private static final String KEY_GESTURE_UP = "gesture_up_dp";
    private static final String KEY_GESTURE_DOWN = "gesture_down_dp";
    /** 勾选后只有从屏幕左右边缘开始的长按滑动才切换形态。 */
    private static final String KEY_GESTURE_EDGE_ONLY = "gesture_edge_only";
    /** 设置页顶部「长按设置文字移动面板」提示：点过「不再提示」后不再显示。 */
    private static final String KEY_LAYOUT_HINT_HIDDEN = "layout_hint_hidden";
    /** 设置页顶部「长按◎展开…」提示：点过「不再提示」后不再显示。 */
    private static final String KEY_LAYOUT_HINT_EXPAND_HIDDEN = "layout_hint_expand_hidden";
    /** 设置页顶部「长按设置页底栏折叠…」提示：点过「不再提示」后不再显示。 */
    private static final String KEY_LAYOUT_HINT_COLLAPSE_HIDDEN = "layout_hint_collapse_hidden";
    /** 设置页顶部「长按底部冻结可触发一键解冻」提示：点过「不再提示」后不再显示。 */
    private static final String KEY_LAYOUT_HINT_UNFREEZE_HIDDEN = "layout_hint_unfreeze_hidden";
    /** 「（查看说明）」后缀：说明卡片里点过「不再提示」的条目永久不再显示后缀（按条目分键）。 */
    private static final String KEY_HINT_SUFFIX_HIDDEN = "hint_suffix_hidden_";
    /** 左右两侧「靠内」的边缘触发区宽度（dp，20–200），两边分开设置。 */
    private static final String KEY_GESTURE_EDGE_LEFT = "gesture_edge_left_dp";
    private static final String KEY_GESTURE_EDGE_RIGHT = "gesture_edge_right_dp";
    private static final int GESTURE_DP_DEFAULT = 88;
    private static final int GESTURE_DP_MAX = 200;
    /** 边缘触发区默认宽度与上下限；最小 20dp 也是真实范围，不再有「0dp 关闭」。 */
    private static final int GESTURE_EDGE_DP_DEFAULT = 32;
    private static final int GESTURE_EDGE_DP_MIN = 20;
    private static final int GESTURE_EDGE_DP_MAX = 200;
    /** 半屏专属：勾选后每个图标右上角常驻红色减号，点它即把该应用移出名单。 */
    private static final String KEY_PANEL_QUICK_REMOVE = "panel_quick_remove";
    /** 移动图标开关：打开后点图标选中、再点目标位置放下；全屏与半屏共用同一份状态。 */
    private static final String KEY_MOVE_MODE = "grid_move_mode";
    /** 悬浮冻结/解冻按钮的偏移（dp）：x 以底部居中的基准左右偏，y 从贴底往上偏。 */
    private static final String KEY_FAB_DX = "fab_dx";
    private static final String KEY_FAB_DY = "fab_dy";
    private static final String KEY_FAB_SIZE = "fab_size";
    /** 全屏悬浮冻结按钮默认直径（dp），大小滑块与恢复默认都以它为准。 */
    private static final int FAB_SIZE_DEFAULT = 39;
    private static final int FAB_SIZE_MIN = 32;
    private static final int FAB_SIZE_MAX = 64;
    /** 半屏形态的一键冻结按钮出厂值：直径 39dp、离底 4dp、底板不透明度 60%。 */
    private static final int PANEL_FAB_SIZE_DEFAULT = 39;
    private static final int PANEL_FAB_DY_DEFAULT = 4;
    /** 半屏冻结按钮出厂值从 38/5 调整为 39/4 的一次性迁移标记。 */
    private static final String KEY_PANEL_FAB_SEED_39 = "panel_fab_seed_39";
    private static final int PANEL_FAB_SURFACE_DEFAULT = 60;
    /** 全屏形态「距底部」出厂值：与半屏一致，首次安装或恢复默认时取 4dp。 */
    private static final int FAB_DY_DEFAULT = 4;
    /** 全屏悬浮冻结按钮出厂值（39dp / 距底 4dp / 透明度 15%）的一次性迁移标记。 */
    private static final String KEY_FULL_FAB_SEED = "full_fab_seed_39";
    /** 全屏底部按钮透明度默认 15% → 40% 的一次性迁移标记。 */
    private static final String KEY_FULL_FAB_SEED_40 = "full_fab_seed_40";
    /** 标签：内置「用户应用 / 系统应用」+ 自定义；顺序、显隐、名称与每个应用的归属都落盘。 */
    private static final String KEY_TABS = "tabs";
    private static final String KEY_APP_TABS = "app_tabs";
    /** 设置里「其他」分组的折叠状态：备份/恢复/关于凚/致谢名单/赞赏开发者只藏这一组。 */
    private static final String KEY_SETTINGS_OTHER_COLLAPSED = "settings_other_collapsed";
    /** 设置里「手势功能 / 列表图标 / 底部冻结按钮 / 标签」几块的折叠状态。 */
    private static final String KEY_SETTINGS_GESTURE_COLLAPSED = "settings_gesture_collapsed";
    private static final String KEY_SETTINGS_ICON_COLLAPSED = "settings_icon_collapsed";
    private static final String KEY_SETTINGS_FAB_COLLAPSED = "settings_fab_collapsed";
    private static final String KEY_SETTINGS_TAB_COLLAPSED = "settings_tab_collapsed";
    private static final String KEY_SETTINGS_PERSONAL_COLLAPSED = "settings_personal_collapsed";
    private static final String KEY_SETTINGS_LSP_COLLAPSED = "settings_lsp_collapsed";
    private static final String KEY_SETTINGS_PAIR_COLLAPSED = "settings_pair_collapsed";
    private static final String KEY_SETTINGS_WL_COLLAPSED = "settings_wl_collapsed";
    private static final String KEY_SETTINGS_SP_COLLAPSED = "settings_sp_collapsed";
    private static final String KEY_SETTINGS_OPACITY_COLLAPSED = "settings_opacity_collapsed";
    /** 设置页及其所有二级弹窗的底板透明度：100 = 实心，0 = 全透明；半屏/全屏用 lk() 各存一份。 */
    private static final String KEY_SETTINGS_SURFACE = "settings_surface";
    private static final String KEY_SETTINGS_WALLPAPER_COLLAPSED = "settings_wallpaper_collapsed";
    /** 设置面板里所有可折叠分组：长按左上角设置按钮时一键全部展开。 */
    private static final String[] SETTINGS_COLLAPSE_KEYS = {
            KEY_SETTINGS_OTHER_COLLAPSED, KEY_SETTINGS_GESTURE_COLLAPSED,
            KEY_SETTINGS_ICON_COLLAPSED, KEY_SETTINGS_FAB_COLLAPSED,
            KEY_SETTINGS_TAB_COLLAPSED, KEY_SETTINGS_PERSONAL_COLLAPSED,
            KEY_SETTINGS_LSP_COLLAPSED, KEY_SETTINGS_PAIR_COLLAPSED,
            KEY_SETTINGS_WL_COLLAPSED, KEY_SETTINGS_SP_COLLAPSED,
            KEY_SETTINGS_OPACITY_COLLAPSED, KEY_SETTINGS_WALLPAPER_COLLAPSED
    };
    /** 设置栏目「默认折叠」的一次性迁移标记：迁过一次后不再强改用户之后的显隐状态。 */
    private static final String KEY_SETTINGS_FOLD_SEEDED = "settings_fold_seeded";
    /** 应用自定义显示名：JSON「实例键 → 名称」，只影响列表显示，不改包名。 */
    private static final String KEY_APP_NAMES = "app_names";
    private static final String KEY_CURRENT_TAB = "current_tab";
    /** 默认标签：进入冻结列表时优先停在这里；空表示没设置，按顺序落到第一个可见标签。 */
    private static final String KEY_DEFAULT_TAB = "default_tab";
    /** 是否允许在列表区域左右滑动切换标签；关掉后只能点标签。 */
    private static final String KEY_TAB_SWIPE = "tab_swipe";
    /** 白名单标识位置：默认贴图标左下角，可切到右下角。 */
    private static final String KEY_WHITE_BADGE_RIGHT = "white_badge_right";
    /** 常用面板（快面板）条目：JSON 数组存条目 id，数组顺序=显示顺序。普通键，不分半屏/全屏。 */
    private static final String KEY_QUICK_PANEL_ITEMS = "quick_panel_items";
    /** 长按底栏空白打开快面板的总开关：设置里「个性设置」中，默认关闭。 */
    private static final String KEY_QUICK_PANEL_ENABLED = "quick_panel_enabled";
    /** 快面板底板透明度：0 = 不透明，越大越透明；独立于「设置页透明度」。 */
    private static final String KEY_QUICK_PANEL_SURFACE = "quick_panel_surface";
    /** 冻结图标样式：0=全彩，1..9=半灰半彩各方向与全灰（见 HalfGrayImageView.SPLIT_*）。 */
    private static final String KEY_ICON_STYLE = "icon_style";
    /** 取图来源：0=系统默认（APK 自带图标），1=当前主题图标（启动器实际显示的图标）。 */
    private static final String KEY_ICON_THEME_MODE = "icon_theme_mode";
    /** 列表图标透明度：0 = 不透明，100 = 全透明（只影响列表里的应用图标）。 */
    private static final String KEY_ICON_ALPHA = "icon_alpha";
    /** 全局美化：自定义壁纸 / 列表图标 / 界面透明度 / 底部冻结按钮 / 标签文字 / 应用名称在半屏、全屏之间同步。 */
    private static final String KEY_GLOBAL_THEME = "global_theme";
    private static final String[] ICON_THEME_MODE_NAMES = {"系统默认", "当前主题图标"};
    private static final String[] ICON_STYLE_NAMES = {
            "全彩", "全灰", "左灰右彩", "右灰左彩", "上灰下彩", "下灰上彩",
            "左上灰", "右上灰", "左下灰", "右下灰"
    };
    /** 与 ICON_STYLE_NAMES 一一对应的样式值（HalfGrayImageView.SPLIT_*，0=全彩）。 */
    private static final int[] ICON_STYLE_VALUES = {0, 9, 1, 2, 3, 4, 5, 6, 7, 8};
    /** 主副应用映射：每行「主应用实例键=副应用实例键」。 */
    private static final String KEY_SUB_PAIRS = "sub_app_pairs";
    /** 冻结白名单：名单里的实例键一律不参与冻结（单冻 / 一键冻结 / 多选冻结 / 副应用联动都跳过）。 */
    private static final String KEY_WHITELIST = "freeze_whitelist";
    /** 隐藏标签页文字：半屏存 panel_ 前缀、全屏存原名，两者独立生效。 */
    private static final String KEY_HIDE_TAB_ROW = "hide_tab_row";
    /** 全屏系统应用页的提示是否已被「不再提醒」关掉。 */
    private static final String KEY_SYS_HINT_HIDDEN = "hide_sys_hint";
    /** 全屏顶部标题（默认「冻结列表」）；允许空名，空名时只留可长按的空白区。 */
    private static final String KEY_LIST_TITLE = "list_title";
    /** 半屏浮层高度：拖把手松手后落盘，与布局设置那套参数互不影响。 */
    private static final String KEY_PANEL_HEIGHT = "panel_height_dp";
    /** 布局设置弹窗的拖动偏移（相对默认位置），与半屏高度分开存。 */
    private static final String KEY_LAYOUT_DX = "layout_dialog_dx";
    private static final String KEY_LAYOUT_DY = "layout_dialog_dy";
    /** 全屏未展开高度（dp）：0 表示自适应，手动拖过滑块才记忆。 */
    private static final String KEY_LAYOUT_OPEN_H = "layout_dialog_open_h";
    /** 未展开时的高度上限（dp）：自适应可用空间，且不覆盖半屏列表。 */
    private static final int LAYOUT_OPEN_MAX_DP = 520;
    /** 底部冻结按钮「距底部」可调范围上限（dp）。 */
    private static final int FAB_DY_MAX = 666;
    /** 半屏顶部小白条开关：关掉后隐藏把手、高度沿用记住的值。 */
    private static final String KEY_PANEL_HANDLE = "panel_handle_enabled";
    /** 冻结成功后是否自动收起列表（全屏/半屏各自记忆，回到进入前的应用）。 */
    private static final String KEY_PANEL_DISMISS = "dismiss_after_freeze";
    /** 「隐藏后台」：勾选后本任务不进最近任务；全屏/半屏各自记忆。 */
    private static final String KEY_PANEL_HIDE_RECENTS = "hide_recents";
    /** 「冻结后保留桌面图标」：勾选后冻结改走 pm suspend，桌面图标不消失；两种形态共用。 */
    private static final String KEY_KEEP_LAUNCHER_ICON = "keep_launcher_icon";
    /** 「底部按钮仅冻/解当前列表」：勾选后底部一键冻结与长按解冻只处理当前列表的应用。 */
    private static final String KEY_BATCH_CURRENT_TAB_ONLY = "batch_current_tab_only";

    /** 「半屏打开时隐藏状态栏」：只作用于半屏面板，不影响全屏页。 */
    private static final String KEY_PANEL_HIDE_STATUS_BAR = "panel_hide_status_bar";

    /** 模块已把系统预启动动画的实色底板透明化（pong 带回）：冷启动不用再套实色遮罩。 */
    private static final String KEY_PRESTARTUP_FIXED = "prestartup_fixed";

    /** 自定义壁纸：原图 URI + 显示范围（缩放/横向/纵向），半屏与全屏各存一份。 */
    private static final String KEY_WALLPAPER_URI = "wallpaper_uri";
    private static final String KEY_WALLPAPER_ZOOM = "wallpaper_zoom";
    private static final String KEY_WALLPAPER_DX = "wallpaper_dx";
    private static final String KEY_WALLPAPER_DY = "wallpaper_dy";
    /** 壁纸自己的模糊度：0 完全清晰，独立于背景透明度/模糊档位。 */
    private static final String KEY_WALLPAPER_BLUR = "wallpaper_blur";
    /** 自定义壁纸弹窗明暗度（0–200，100 = 原图），只作用于壁纸做底的弹窗。 */
    private static final String KEY_WALLPAPER_DIM = "wallpaper_dim";
    /** 明暗度语义从「压暗值」改成「明暗度」的一次性换算标记。 */
    private static final String KEY_WALLPAPER_BRIGHT_SEED = "wallpaper_bright_seed";
    /** 冻结成功后是否弹「已冻结」提示；两种形态共用同一个开关。 */
    private static final String KEY_SHOW_FROZEN_TIP = "show_frozen_toast";
    /** 「去掉 toast 提示」：勾选后凚 内所有 toast / 自绘短提示都不再显示。 */
    private static final String KEY_HIDE_TOASTS = "hide_toasts";
    /** 诊断包用：最近一次冻结/解冻的命令、返回与时间。 */
    private static final String KEY_DIAG_CMD = "diag_last_cmd";
    private static final String KEY_DIAG_OUT = "diag_last_out";
    private static final String KEY_DIAG_AT = "diag_last_at";
    /** 操作流水：JSON 数组，最多 20 条，冻结/解冻/暂停共用。 */
    private static final String KEY_DIAG_HIST = "diag_hist";
    /** 诊断包落地目录：下载目录下的「凚/诊断包」。 */
    private static final String DIAG_DIR = "/storage/emulated/0/Download/凚/诊断包";
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
    /** 全屏「外部模糊」档位：0=不模糊（外面看得清）、100=最模糊；没设过时跟随全屏背景。 */
    private static final String KEY_FULL_BLUR = "full_blur";
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
    /** 全屏悬浮冻结按钮底板默认档位：与半屏一致，「透明度」40%（100 = 实心）。 */
    private static final int FULL_FAB_SURFACE_DEFAULT = 60;
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
        /** 名称的拼音首字母（懒计算，供搜索用）。 */
        String py;
    }

    private static final class Cell {
        LinearLayout root;
        FrameLayout iconBox;
        ImageView icon;
        TextView name;
        /** 名称行与左侧小雪花：雪花和文字整行居中，保证短名称时也紧贴。 */
        LinearLayout nameRow;
        ImageView snow;
        View badge;
        /** 暂停标识：只在 pm suspend 降级冻结时显示，正常 disable-user 冻结不显示。 */
        SuspendBadge suspendBadge;
        /** 白名单标识：灰化/半灰半彩图标时固定在图标右下角。 */
        ImageView whiteBadge;
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
    /** 连续刷新只保留最后一次，避免重复排队 shell、图标解码和整表重绘。 */
    private final java.util.concurrent.atomic.AtomicBoolean reloadRunning =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean reloadQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
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
            // 模块会把「预启动底板是否已透明化」一起带回来：缓存下来给下次冷启动判断，
            // 本次若正盖着遮罩，说明没必要了，立刻回到设置的透明度
            boolean fixed = intent.getBooleanExtra(SwipeFreeze.EXTRA_PRESTARTUP_FIXED, false);
            if (fixed != prefs().getBoolean(KEY_PRESTARTUP_FIXED, false)) {
                prefs().edit().putBoolean(KEY_PRESTARTUP_FIXED, fixed).apply();
            }
            if (fixed && coldStartMask) {
                coldStartMask = false;
                applyWindowBackdrop();
            }
            Runnable refresh = privTitleRefresh;
            if (refresh != null) {
                refresh.run();
            }
        }
    };
    private final List<Item> items = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    /** 应用 → 所属标签集合（可同时属于多个标签）；没有映射时按 FLAG_SYSTEM 落回内置标签。 */
    private final Map<String, java.util.LinkedHashSet<String>> appTabs = new HashMap<>();
    private String currentTabId = TAB_USER;

    private TextView count;
    private View freezeBtn;
    private View settingsBtn;
    private android.widget.ImageButton editBtn;
    /** 全屏顶栏标题：长按可改名，名字可留空（不显示文字）。 */
    private TextView titleView;
    private androidx.recyclerview.widget.RecyclerView grid;
    private GridAdapter gridAdapter;
    private androidx.recyclerview.widget.ItemTouchHelper itemTouchHelper;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::render;
    private LinearLayout fabGroup;
    /** 全屏页底部导航栏（经典三键）高度：悬浮按钮贴底时要减掉，否则会被导航栏压住。 */
    private int navBottomInsetPx;
    /** 长按冻结按钮浮出的一键解冻小图标。 */
    private View unfreezeFab;
    /** 移动图标模式覆盖在冻结按钮上的「完成」勾选。 */
    private View moveDoneFab;
    private boolean fabExpanded;
    /** 冻结执行中的雪花旋转动画，执行完停止并复位。 */
    private android.animation.ObjectAnimator freezeSpin;
    private final Handler fabHandler = new Handler(Looper.getMainLooper());
    private final Runnable fabCollapseRunnable = this::collapseUnfreezeFab;
    private View scrollView;
    private EditText search;
    private LinearLayout tabBar;
    private TextView sysHint;
    /** 全屏系统应用页的提示行（提示文字 + 不再提醒），隐藏时整行收起。 */
    private View sysHintRow;
    /** 空态：名单为空时显示在面板中部（添加应用）。 */
    private View emptyState;
    /** 本次选应用从哪个标签点「+」进来：新加入的应用落到该标签（用户应用进系统标签会被拒绝）。 */
    private String pickerTargetTab;
    private android.app.Dialog layoutDialog;
    private int layoutShadowPad;
    /** 布局设置弹窗当前是否处于 ▼ 展开态：底部按钮位置/大小变化时要同步收放高度。 */
    private boolean layoutExpanded;
    /** 本次打开后是否自动进入「展开最大范围」态（长按设置按钮时用，一次性）。 */
    private boolean expandLayoutOnShow;
    /** 设置页展开/收起时的窗口高度与位置动画，快速切换时先取消上一段。 */
    private android.animation.ValueAnimator dialogFrameAnim;
    /** 打开设置页时的窗口 y（未展开位置）：收起展开时回到这里，不受展开态拖动影响。 */
    private int layoutOpenY = Integer.MIN_VALUE;
    /** 半屏本次打开时手动调短的未展开高度（dp）：-1 表示用最大值，不落盘。 */
    private int panelOpenHeightDp = -1;
    private boolean loadedOnce;
    /** 打开选应用页期间不让「隐藏半屏后台」把面板收掉，否则回传结果没人接。 */
    private boolean pickerOpen;

    private boolean working;
    /** 诊断包生成中：连续点按 logo 只跑一次，避免生成多份重复 zip。 */
    private boolean diagExporting;
    private boolean editMode;
    /** 移除模式下点红标是否同时解冻（长按减号选「移除列表并解冻」时为 true）。 */
    private boolean editUnfreeze;
    /** 半屏浮层承载形态（从快捷方式进入）；全屏页为 false。 */
    private boolean panelMode;
    /**
     * 冷启动遮罩：HyperOS 的「预启动动画」会在应用窗口下方铺一张纯白（夜间纯黑）底板，
     * 启动动画收尾前不会撤掉。页面是半透明窗口，会把这层底板透出来，观感就是「先闪白/黑，再变透明」。
     * 冷启动首帧先用 100% 实心底色盖住，等启动动画结束再淡入用户设置的透明度。
     */
    private boolean coldStartMask;
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
    /** 「底部按钮仅冻/解当前列表」开关：勾选后底部一键冻结/长按解冻只作用于当前列表。 */
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
    /** 自定义壁纸弹窗明暗度：0 全黑、100 原图、100–200 叠白提亮，出厂 100。 */
    private int wallpaperBright;
    /** 布局设置里壁纸状态那一行，选图/清除后就地刷新。 */
    private TextView layoutWpState;
    /** 设置面板里「主副应用关联」这一行，增删/暂停后即时刷新摘要。 */
    private TextView layoutPairEntry;
    /** 设置面板里「冻结白名单 → 白名单列表」这一行。 */
    private TextView layoutWhitelistEntry;
    /** 设置面板里「标识位置」这一行，选完即时刷新。 */
    private TextView layoutWhitePosEntry;
    /** 设置面板里「同步全局」这一行，选完即时刷新。 */
    private TextView layoutWpGlobalEntry;
    /** 设置面板里「桌面图标模式 → 冻结后是否保留桌面图标」这一行，选完即时刷新。 */
    private TextView layoutKeepIconEntry;
    /** 设置面板里「隐藏半屏/全屏的标签文字」：全局美化打开后文案变成「隐藏标签文字」。 */
    private CheckBox settingsHideTabRowBox;
    /** 主副关联 / 列表白名单的管理弹窗：添加应用的手势返回要能回到这一页。 */
    private AlertDialog pairManagerDialog;
    private AlertDialog whitelistManagerDialog;
    /** 管理页是否从快面板进入：手势取消时返回快面板，点「关闭」则直接关闭。 */
    private boolean pairManagerFromQuickPanel;
    private boolean whitelistManagerFromQuickPanel;
    /** 布局设置弹窗：长按空白上下滑切形态时先把它收起。 */
    private android.app.Dialog settingsDialog;
    /** 设置面板当前的底板：拖动「设置页透明度」时直接刷它，不重建弹窗。 */
    private View settingsSurfaceRoot;
    /** 从「所有文件访问」授权页返回后自动重扫恢复列表，不用用户再点一次。 */
    private boolean restoreAwaitAccess;
    /** 管理弹窗上面叠的添加选择页，逐层返回时统一关闭。 */
    private final List<AlertDialog> pairPickers = new ArrayList<>();
    /** 设置页「冻结图标样式」这一行：选中后即时刷新显示的当前选项。 */
    private TextView layoutIconStyleEntry;
    /** 设置页「主题图标样式」这一行：切换后即时刷新显示的当前选项。 */
    private TextView layoutThemeIconEntry;
    private WallpaperDrawable wallpaperDrawable;
    private boolean dismissAfterReload;
    /** 「去掉 toast 提示」开关：勾选后凚 内所有短提示都不显示。 */
    private boolean hideToasts;
    /** 列表区域左右滑动切标签；布局设置可关。 */
    private boolean tabSwipeEnabled = true;
    /** 长按空白处左右滑翻标签时是否振动；布局设置可关。 */
    private boolean scrubVibrate = true;
    /** 冻结图标样式：0=全彩，1..8=不同方向的半灰半彩。 */
    private int iconStyle;
    /** 列表图标透明度：0=不透明，100=全透明。 */
    private int iconAlpha;
    /** 隐藏标签文字：只收起标签名那一排，列表与滑动手势照常工作。 */
    private boolean hideTabRow;
    /** 白名单标识位置：false=贴图标左下角（默认），true=右下角。 */
    private boolean whiteBadgeRight;
    private float swipeDownX;
    private float swipeDownY;
    private boolean swipeTracking;
    /** 横向滑动按下的时刻：松手时据此判断是否属于快速轻扫（距离短也切标签）。 */
    private long swipeDownAt;
    /** 标签滑动切页动画进行中：不接管新的横向手势，避免叠加。 */
    private boolean tabAnimating;
    /** 标签切换动画开始的时间：用于动画回调丢失时（cancel 不触发 withEndAction）兜底复位。 */
    private long tabAnimatingAt;
    /** 长按空白处后左右滑快速翻标签：长按是否已到位 / 手势起点 / 位移锚点。 */
    private boolean scrubArmed;
    private float scrubDownX;
    private float scrubDownY;
    /** 本次长按手势已经翻过标签：之后不再触发上滑 / 下滑切形态。 */
    private boolean scrubTabMoved;
    /** 长按空白处手势总开关：关闭后长按不再起手势。 */
    private boolean gestureEnabled = false;
    /** 长按空白后上滑 / 下滑切形态的触发距离（dp），设置页滑块实时生效。 */
    private int gestureUpDp = GESTURE_DP_DEFAULT;
    private int gestureDownDp = GESTURE_DP_DEFAULT;
    /** 勾选后只在屏幕左右边缘区域长按滑动才切换形态。 */
    private boolean gestureEdgeOnly;
    /** 左右边缘触发区宽度（dp，20–200），可分开调节。 */
    private int gestureEdgeLeftDp = GESTURE_EDGE_DP_DEFAULT;
    private int gestureEdgeRightDp = GESTURE_EDGE_DP_DEFAULT;
    /** 已消耗位移的锚点：每翻一格就前移一格的距离，余量留给下一次。 */
    private float scrubAnchorX;
    private android.view.VelocityTracker scrubVelocity;
    private final Runnable scrubArmRunnable = () -> {
        scrubArmed = true;
        // 只在手势触发（长按到位）时震一下；与长按图标的反馈完全同一套
        if (scrubVibrate && visibleTabCount() > 1) {
            View target = grid != null ? grid : getWindow().getDecorView();
            target.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    };
    /** 快面板（常用面板）：长按底栏空白 / 全屏底部预留带弹出的对话框与内容列。 */
    private android.app.Dialog quickPanelDialog;
    /** 快面板的底板根视图（挂 bg_dialog / 全局壁纸底的那层）：换底刷新时要用。 */
    private View quickPanelRootView;
    private LinearLayout quickPanelContent;
    /** － 移除模式 / 排序模式：互斥，再点一次退出。 */
    private boolean quickPanelRemoveMode;
    private boolean quickPanelSortMode;
    /** 排序前的条目顺序快照：点 X 时按它还原。 */
    private List<String> quickPanelSortSnapshot;
    /** 面板里已展开的条目 id（滑块 / 透明度组），重绘后保持展开。 */
    private final HashSet<String> quickPanelExpanded = new HashSet<>();
    /** 全屏底部预留带长按弹快面板：长按是否已到位 / 本次触点归快面板 / 手势起点。 */
    private boolean quickPanelArmed;
    private boolean quickPanelTouch;
    /** 长按底栏空白打开快面板：设置里默认关闭。 */
    private boolean quickPanelEnabled;
    private float quickPanelDownX;
    private float quickPanelDownY;
    private final Runnable quickPanelArmRunnable = () -> {
        if (isFinishing()) {
            return;
        }
        quickPanelArmed = true;
        // 长按底栏空白直接弹面板，不做震动反馈
        showQuickPanel();
    };
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
    /** 本次进程是否已自动向 Shizuku 申请过授权，避免每次进页面重复弹窗。 */
    private static boolean shizukuAutoAsked;
    private static boolean dhizukuAutoAsked;
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

    /** 半灰半彩模式灰化半边用的滤镜：去饱和 + 0.45 透明度。 */
    private static final android.graphics.ColorMatrixColorFilter GRAY_SOFT;

    static {
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(0f);
        android.graphics.ColorMatrix alpha = new android.graphics.ColorMatrix();
        alpha.setScale(1f, 1f, 1f, 0.45f);
        cm.postConcat(alpha);
        GRAY_SOFT = new android.graphics.ColorMatrixColorFilter(cm);
    }

    private final ActivityResultLauncher<Intent> picker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                pickerOpen = false;
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    pickerTargetTab = null;
                    return;
                }
                ArrayList<String> picked = result.getData()
                        .getStringArrayListExtra(AppFreezeActivity.EXTRA_PICKED);
                if (picked == null || picked.isEmpty()) {
                    pickerTargetTab = null;
                    return;
                }
                List<String> normalized = new ArrayList<>();
                for (String key : picked) {
                    normalized.add(storageKey(packagePart(key), userIdPart(key)));
                }
                Set<String> cur = new HashSet<>(pkgs());
                // 添加应用是「把应用放进当前标签」的入口：已在名单里的应用也能再添加，
                // 从而同时出现在多个标签页；长按图标的「更改标签页」才是移动。
                Set<String> listedNow = new LinkedHashSet<>();
                boolean changed = false;
                boolean blocked = false;
                boolean allAlready = true;
                if (pickerTargetTab != null) {
                    PackageManager pm = getPackageManager();
                    for (String key : normalized) {
                        boolean system = false;
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(packagePart(key), 0);
                            system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                        } catch (Exception ignored) {
                        }
                        if (TAB_SYSTEM.equals(pickerTargetTab) && !system) {
                            blocked = true;
                            continue;
                        }
                        if (cur.contains(key)) {
                            // 已在名单：只补当前标签，原有标签保留
                            if (tabsOfKey(key, system).contains(pickerTargetTab)) {
                                continue;
                            }
                            if (addAppToTab(key, pickerTargetTab, system)) {
                                changed = true;
                                allAlready = false;
                            }
                        } else {
                            // 新加入名单的应用只进当前标签
                            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
                            set.add(pickerTargetTab);
                            appTabs.put(key, set);
                            listedNow.add(key);
                            changed = true;
                            allAlready = false;
                        }
                    }
                    if (changed) {
                        saveAppTabs();
                    }
                    if (blocked) {
                        toast("用户应用禁止移入系统应用标签");
                    } else if (allAlready && !normalized.isEmpty()) {
                        toast("选中项都已在「" + tabNameOf(pickerTargetTab) + "」");
                    }
                }
                String reveal = !listedNow.isEmpty() ? listedNow.iterator().next()
                        : (normalized.isEmpty() ? null : normalized.get(0));
                pickerTargetTab = null;
                if (reveal != null) {
                    pendingRevealPkg = reveal;
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
                    toast("这张图片读不出来，换一张试试");
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
                mirrorWallpaperToOtherIfGlobal();
                rebuildWallpaperDrawable();
                applyWallpaperBackground();
                if (layoutWpState != null) {
                    layoutWpState.setText("· 当前：已设置");
                }
            });

    /** 恢复时「导入文件」：系统文件选择器任选一份备份，不限目录与文件名。 */
    private final ActivityResultLauncher<String[]> backupImportPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                    // 个别提供方不给长期权限，本次读取仍可用
                }
                importBackupUri(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panelMode = isPanelMode();
        // 冷启动（桌面图标/抽屉进入）时先按实心出，避开系统预启动动画的纯白/纯黑底板。
        // 只在这个「消除启动白底」开关打开、且模块 hook 没装上时才兜底；
        // 开关关掉就完全按系统默认的启动动画走。
        coldStartMask = savedInstanceState == null && !panelMode && fullSurfacePct() < SURFACE_SOLID
                && SwipeFreeze.noLaunchBg(this)
                && !prefs().getBoolean(KEY_PRESTARTUP_FIXED, false);
        // 先让 ShizukuGate 拿到 Context：权限探测要用它读「用户点过允许」的记录
        ShizukuGate.installed(this);
        // 列表选中项不再有高亮框，清掉历史遗留的档位键
        prefs().edit().remove("accent_surface").remove("panel_accent_surface").apply();
        // 名单类数据都在应用内部目录（SharedPreferences），升级安装不会丢，不再做外部自动备份/恢复
        // 先在进内容之前把壁纸准备好：解码有进程内缓存，首帧就可以直接挂上，不会先露一帧白底
        loadWallpaperFromPrefs();
        // 整页底板在窗口第一次显示之前就挂到窗口背景上：首帧就带着「全屏背景」设定的透明度
        //（冷启动那一次是 100% 实心，等系统预启动动画收尾后再淡回设置档位，见 coldStartMask），
        // 不会先由系统/窗口兜一层实心（浅色模式是白）底、下一帧才被透明度盖回来
        applyWindowBackdrop();
        // 隐藏状态栏要在内容上屏之前就生效：窗口显示后再 hide 会触发一次布局，
        // 过渡期间系统栏那条区域被填成窗口底色，表现为打开半屏瞬间顶部一条白
        if (panelMode && prefs().getBoolean(KEY_PANEL_HIDE_STATUS_BAR, false)) {
            statusBarReservePx = introStatusInsetPx();
            statusBarHidden = true;
            WindowInsetsControllerCompat earlyController = statusIconsController();
            earlyController.hide(WindowInsetsCompat.Type.statusBars());
            earlyController.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            android.view.WindowManager.LayoutParams earlyLp = getWindow().getAttributes();
            earlyLp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(earlyLp);
        }
        setContentView(panelMode
                ? R.layout.activity_freeze_list_panel
                : R.layout.activity_freeze_list);
        // 权限行要区分「只激活 LSPosed」：模块在 system_server 回 pong 证明在线
        androidx.core.content.ContextCompat.registerReceiver(this, statusReceiver,
                new IntentFilter(SwipeFreeze.ACTION_STATUS_PONG),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        applyWallpaperBackground();
        // 顺手问一次模块「预启动底板透明化」的状态：刚装模块时缓存里还没有，
        // 收到 pong 后就按真实状态显示，并把结果留给下次冷启动
        if (SwipeFreeze.noLaunchBg(this)
                && !prefs().getBoolean(KEY_PRESTARTUP_FIXED, false)) {
            sendBroadcast(new Intent(SwipeFreeze.ACTION_STATUS_PING).setPackage("android"));
        }
        // 系统预启动动画在首帧之后还要收尾一小段（实测约 250ms），撤掉后再把实心底色淡回设置档位
        if (coldStartMask) {
            View maskRoot = findViewById(R.id.page_root);
            if (maskRoot != null) {
                maskRoot.postDelayed(this::releaseColdStartMask, 480);
            }
        }
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
                    // 半屏隐藏状态栏后不再补顶部让位：这条让位会把遮罩和入场黑底拦在下面，
                    // 状态栏那片只剩窗口背后的桌面（浅色壁纸下就是一条白）。
                    // 面板底部对齐、高度按屏幕算固定值，少这条 padding 也不会往上跑。
                    int top = panelMode && statusBarHidden
                            ? 0 : Math.max(bars.top, statusBarReservePx);
                    insetTarget.setPadding(bars.left, top, bars.right,
                            Math.max(bars.bottom, ime.bottom));
                    // 全屏页的悬浮冻结按钮直接挂在 page_root 上，不跟着内层 padding 走：
                    // 经典三键导航时底部要再让出一块，否则按钮会被导航栏压住（半屏面板自身已避让）
                    if (!panelMode) {
                        navBottomInsetPx = bars.bottom;
                        if (fabGroup != null) {
                            fabGroup.post(FreezeListActivity.this::applyFabPosition);
                        }
                    } else {
                        navBottomInsetPx = 0;
                    }
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
        // 底部留白（与半屏底栏同高）里滑进来的图标会被裁掉看不见，
        // 但触摸仍会落到那些图标上。这里把留白区的按下事件交给 RecyclerView 自己处理：
        // 点击不触发图标，拖动照常滚动。
        if (grid != null) {
            grid.addOnItemTouchListener(new androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(androidx.recyclerview.widget.RecyclerView rv,
                                                     android.view.MotionEvent e) {
                    return e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                            && e.getY() > rv.getHeight() - rv.getPaddingBottom();
                }
            });
        }
        fabGroup = findViewById(R.id.fab_group);
        search = findViewById(R.id.search);
        tabBar = findViewById(R.id.tab_bar);
        sysHint = findViewById(R.id.sys_hint);
        sysHintRow = findViewById(R.id.sys_hint_row);
        View sysHintDismiss = findViewById(R.id.sys_hint_dismiss);
        if (sysHintDismiss != null) {
            sysHintDismiss.setOnClickListener(v -> {
                prefs().edit().putBoolean(KEY_SYS_HINT_HIDDEN, true).apply();
                setSysHintVisible();
            });
        }

        // 全屏顶部标题：长按直接弹改名框（与标签改名同一套卡片），允许空名隐藏文字
        titleView = findViewById(R.id.title);
        if (titleView != null) {
            titleView.setMinimumWidth(Ui.dp(this, 88));
            applyListTitle();
            titleView.setOnLongClickListener(v -> {
                showRenameDialog("重命名标题", listTitle(), "标题名称", name -> {
                    prefs().edit().putString(KEY_LIST_TITLE, name).apply();
                    applyListTitle();
                    return true;
                });
                return true;
            });
        }

        if (editBtn != null) {
            // 减号只做状态切换：保留与「+」一致的点按水波纹，但不再叠蓝色高亮
            editBtn.setOnClickListener(v -> {
                // 当前列表没有应用时不进入移除模式：否则底部会弹出「完成」勾且没有可移除的目标
                if (filtered().isEmpty()) {
                    return;
                }
                setEditMode(!editMode);
            });
            // 长按减号：对当前列表做批量移除（只移出 / 移出并解冻）
            editBtn.setOnLongClickListener(v -> {
                showBatchRemoveDialog();
                return true;
            });
        }
        if (settingsBtn != null) {
            settingsBtn.setOnClickListener(v -> showLayoutDialog());
            // 长按设置按钮：面板回到默认位置，并把所有分组一键展开
            settingsBtn.setOnLongClickListener(v -> {
                showLayoutDialogAtDefault();
                return true;
            });
        }
        // 半屏形态底栏没有输入框：左边搜索图标直接进添加应用页（自带搜索与用户/系统切换）
        View panelSearch = findViewById(R.id.panel_search);
        if (panelSearch != null) {
            panelSearch.setOnClickListener(v -> openPicker());
        }
        // 底栏按钮之间的空白长按：弹出「快面板」（常用面板），不做震动反馈；开关默认关闭
        View panelBarBlank = findViewById(R.id.panel_bar_blank);
        if (panelBarBlank != null) {
            panelBarBlank.setOnLongClickListener(v -> {
                if (!quickPanelEnabled) {
                    return false;
                }
                showQuickPanel();
                return true;
            });
        }
        emptyState = findViewById(R.id.panel_empty);
        View emptyAdd = findViewById(R.id.empty_add);
        if (emptyAdd != null) {
            emptyAdd.setOnClickListener(v -> openPicker());
        }
        ensurePanelLayoutInit();
        migrateUpdatedDefaults();
        // 列数/图标大小：半屏与全屏各存一份；旧数据没有 panel_ 键时沿用全屏的值
        gridColumns = prefs().getInt(lk(KEY_COLUMNS), prefs().getInt(KEY_COLUMNS, COLUMNS));
        if (gridColumns < 4 || gridColumns > 8) {
            // 旧数据（2/3 列）按 4 列处理并修正落盘
            gridColumns = 4;
            prefs().edit().putInt(lk(KEY_COLUMNS), 4).apply();
        }
        iconDp = prefs().getInt(lk(KEY_ICON_DP), prefs().getInt(KEY_ICON_DP, 52));
        iconManual = prefs().getBoolean(lk(KEY_ICON_MANUAL),
                prefs().getBoolean(KEY_ICON_MANUAL, false));
        gestureEnabled = prefs().getBoolean(KEY_GESTURE_ENABLED, false);
        gestureUpDp = clampGestureDp(prefs().getInt(KEY_GESTURE_UP, GESTURE_DP_DEFAULT));
        gestureDownDp = clampGestureDp(prefs().getInt(KEY_GESTURE_DOWN, GESTURE_DP_DEFAULT));
        gestureEdgeOnly = prefs().getBoolean(KEY_GESTURE_EDGE_ONLY, false);
        gestureEdgeLeftDp = clampEdgeDp(
                prefs().getInt(KEY_GESTURE_EDGE_LEFT, GESTURE_EDGE_DP_DEFAULT));
        gestureEdgeRightDp = clampEdgeDp(
                prefs().getInt(KEY_GESTURE_EDGE_RIGHT, GESTURE_EDGE_DP_DEFAULT));
        showName = prefs().getBoolean(lk(KEY_SHOW_NAME), true);
        quickRemove = panelMode && prefs().getBoolean(KEY_PANEL_QUICK_REMOVE, false);
        quickPanelEnabled = prefs().getBoolean(KEY_QUICK_PANEL_ENABLED, false);
        // 移动图标改成一次性动作（长按操作单进入），启动时不再恢复上次状态
        moveMode = false;
        hideToasts = prefs().getBoolean(KEY_HIDE_TOASTS, false);
        // 左右滑动切换标签固定开启（线上不再提供关闭入口），旧版本关过的用户也一并恢复
        tabSwipeEnabled = true;
        // 长按空白处左右滑翻标签时的振动：固定开启，不再提供开关
        scrubVibrate = true;
        // 冻结图标样式默认全彩；1..8 为不同方向的半灰半彩
        iconStyle = clampInt(prefs().getInt(KEY_ICON_STYLE, 0), 0, ICON_STYLE_NAMES.length - 1);
        // 列表图标透明度：0 不透明，越大越透明；半屏与全屏各存一份
        iconAlpha = clampInt(prefs().getInt(lk(KEY_ICON_ALPHA), 0), 0, 100);
        // 隐藏标签页文字：半屏与全屏各存一份，互不影响
        hideTabRow = prefs().getBoolean(lk(KEY_HIDE_TAB_ROW), false);
        whiteBadgeRight = prefs().getBoolean(KEY_WHITE_BADGE_RIGHT, true);
        seedPanelFabDefaults();
        seedFullscreenFabDefaults();
        seedFullscreenFabSurface40();
        seedHeaderSurface();
        seedFoldedSettingsDefaults();
        fabDx = prefs().getInt(lk(KEY_FAB_DX), 0);
        fabDy = Math.max(0, prefs().getInt(lk(KEY_FAB_DY),
                panelMode ? PANEL_FAB_DY_DEFAULT : FAB_DY_DEFAULT));
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
        setupMoveDoneFab();
        panelDismissAfterFreeze = prefs().getBoolean(KEY_PANEL_DISMISS, false);
        panelHideRecents = prefs().getBoolean(KEY_PANEL_HIDE_RECENTS, false);
        keepLauncherIcon = prefs().getBoolean(KEY_KEEP_LAUNCHER_ICON, false);
        batchCurrentTabOnly = prefs().getBoolean(KEY_BATCH_CURRENT_TAB_ONLY, true);
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
        freezeBtn.setOnClickListener(v -> {
            if (working) {
                return;
            }
            List<Item> targets = batchTargets();
            if (targets.isEmpty() && !pkgs().isEmpty()) {
                // 冷启动 / 刚切标签时内存列表可能还没装载，第一次点击会被当成「没有目标」：
                // 先用 PackageInfo 快速补一份再取一次，避免用户要点两次
                Set<String> wanted = pkgs();
                items.clear();
                items.addAll(quickItems(wanted, savedOrder(), customOrder()));
                targets = batchTargets();
            }
            if (targets.isEmpty()) {
                FrozenTip.show(this, "冻结名单为空");
                return;
            }
            runBatch(true, targets);
        });

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

    /** 半屏出厂值升级：旧默认 38/5 → 39/4，只替换仍是旧默认值的设备，用户调过的不动。 */
    private void seedPanelFabDefaults() {
        if (prefs().getBoolean(KEY_PANEL_FAB_SEED_39, false)) {
            return;
        }
        if (prefs().getInt("panel_" + KEY_FAB_SIZE, PANEL_FAB_SIZE_DEFAULT) == 38) {
            prefs().edit().putInt("panel_" + KEY_FAB_SIZE, PANEL_FAB_SIZE_DEFAULT).apply();
        }
        if (prefs().getInt("panel_" + KEY_FAB_DY, PANEL_FAB_DY_DEFAULT) == 5) {
            prefs().edit().putInt("panel_" + KEY_FAB_DY, PANEL_FAB_DY_DEFAULT).apply();
        }
        prefs().edit().putBoolean(KEY_PANEL_FAB_SEED_39, true).apply();
    }

    /** 全屏出厂值升级：距底 9 → 4、底板由实心 → 15% 透明，只替换仍是旧默认值的设备。 */
    private void seedFullscreenFabDefaults() {
        if (prefs().getBoolean(KEY_FULL_FAB_SEED, false)) {
            return;
        }
        SharedPreferences.Editor e = prefs().edit();
        if (prefs().getInt(KEY_FAB_DY, FAB_DY_DEFAULT) == 9) {
            e.putInt(KEY_FAB_DY, FAB_DY_DEFAULT);
        }
        if (!prefs().contains(KEY_FULL_FAB_SURFACE)
                || prefs().getInt(KEY_FULL_FAB_SURFACE, FULL_FAB_SURFACE_DEFAULT) == SURFACE_SOLID) {
            e.putInt(KEY_FULL_FAB_SURFACE, FULL_FAB_SURFACE_DEFAULT);
        }
        e.putBoolean(KEY_FULL_FAB_SEED, true).apply();
    }

    /** 全屏底部冻结按钮透明度默认改为 40%：只把仍是旧默认 85（15% 透明）的设备换掉。 */
    private void seedFullscreenFabSurface40() {
        if (prefs().getBoolean(KEY_FULL_FAB_SEED_40, false)) {
            return;
        }
        if (prefs().getInt(KEY_FULL_FAB_SURFACE, FULL_FAB_SURFACE_DEFAULT) == 85) {
            prefs().edit().putInt(KEY_FULL_FAB_SURFACE, FULL_FAB_SURFACE_DEFAULT).apply();
        }
        prefs().edit().putBoolean(KEY_FULL_FAB_SEED_40, true).apply();
    }

    /** 横幅档位从「跟随全屏背景」改为独立记忆：首次升级按当前全屏背景落一次盘。 */
    private void seedHeaderSurface() {
        if (!prefs().contains(KEY_HEADER_SURFACE)) {
            prefs().edit().putInt(KEY_HEADER_SURFACE, fullSurfacePct()).apply();
        }
    }

    /** 设置栏目「默认折叠」升级：老版本默认展开的存量状态一次性收起，让「默认折叠」立刻生效。 */
    private void seedFoldedSettingsDefaults() {
        if (prefs().getBoolean(KEY_SETTINGS_FOLD_SEEDED, false)) {
            return;
        }
        SharedPreferences.Editor e = prefs().edit();
        for (String key : SETTINGS_COLLAPSE_KEYS) {
            e.putBoolean(key, true);
        }
        e.putBoolean(KEY_SETTINGS_FOLD_SEEDED, true).apply();
    }

    /** 半屏浮层形态：圆角面板默认占屏幕一半，拖把手可改高度，上方透出桌面。 */
    private void setupPanel() {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int handleH = panelHandleHeightPx();
        // 高度一律按「内容区」计算，把手那一截单独占位；上下限也只约束内容区
        panelMinHpx = Ui.dp(this, 300);
        panelMaxHpx = Math.max(panelMinHpx, Math.round(screenH * 0.94f) - handleH);
        int savedDp = prefs().getInt(KEY_PANEL_HEIGHT, 0);
        // 未保存过时按设备屏幕的一半定位：面板总高取屏高一半，内容区扣掉把手那一截
        int content = savedDp > 0 ? Ui.dp(this, savedDp)
                : Math.round(screenH / 2f) - handleH;
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
        // 默认 20：滑块显示 80% 透明度
        int pct = Math.max(0, Math.min(100, prefs().getInt(lk(KEY_BLUR), 20)));
        // 按 2.9.9 的做法：压暗用布局里的 panel_scrim 自己叠一层很淡的黑
        // （0x33 × 档位）。窗口级 FLAG_DIM_BEHIND 会由系统做一段淡入，
        // 切形态时新窗口头一两帧既没模糊也没压暗，看起来就是「先透明一下再回模糊」
        View scrim = findViewById(R.id.panel_scrim);
        if (scrim != null) {
            scrim.setBackgroundColor(panelScrimColor());
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                android.view.Window w = getWindow();
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                android.view.WindowManager.LayoutParams attrs = w.getAttributes();
                // 满档 28dp，与 2.9.9 一致：默认 20 档约 5.6dp。
                // 新窗口的头一两帧系统还没把模糊叠上，模糊半径越小这段过渡越看不出
                int maxRadius = Ui.dp(this, 28);
                attrs.setBlurBehindRadius(Math.round(maxRadius * pct / 100f));
                w.setAttributes(attrs);
            } catch (Exception ignored) {
            }
        }
    }

    /** 半屏外侧的压暗色：0x33 × 档位。面板遮罩与状态栏那条共用，避免顶部接缝。 */
    private int panelScrimColor() {
        int pct = Math.max(0, Math.min(100, prefs().getInt(lk(KEY_BLUR), 20)));
        return (0x33 * pct / 100) << 24;
    }

    /** 半屏面板本体：只动面板自己的背景透明度，不碰上方遮罩的深浅与窗口模糊。 */
    private void applyPanelSurface() {
        applySurface(findViewById(R.id.panel),
                prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID));
    }

    /** 底部雪花按钮底板：与面板同一套档位，值单独记忆；点按/长按行为不变。 */
    private void applyFabSurface() {
        int pct = prefs().getInt(lk(KEY_FAB_SURFACE),
                panelMode ? PANEL_FAB_SURFACE_DEFAULT : FULL_FAB_SURFACE_DEFAULT);
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
        // 老版本存的是「压暗值」（40 = 明亮度 60%），一次性换算成新的明暗度（100 = 原图）
        if (!prefs().getBoolean(KEY_WALLPAPER_BRIGHT_SEED, false)) {
            SharedPreferences.Editor se = prefs().edit();
            for (String k : new String[]{"panel_wallpaper_dim", "wallpaper_dim"}) {
                if (prefs().contains(k)) {
                    se.putInt(k, Math.max(0, 100 - prefs().getInt(k, 40)));
                }
            }
            se.putBoolean(KEY_WALLPAPER_BRIGHT_SEED, true).apply();
        }
        wallpaperBright = Math.max(0, Math.min(200,
                prefs().getInt(lk(KEY_WALLPAPER_DIM), WallpaperSource.BRIGHT_DEFAULT)));
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
        // 「同步全局」的弹窗底图跟随当前形态：形态/壁纸一变，开着的设置页、快面板一起换底
        WallpaperSource.setUiMode(srcMode());
        refreshGlobalSurfaceBg();
        applyPageWallpaperTextColors();
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

    /** 页面有壁纸背景时的主文字色：没有壁纸回落主题色。 */
    private int wpPageFg(int fallback) {
        return wallpaperBitmap == null ? fallback : Ui.wallpaperFg(wallpaperBitmap, 100);
    }

    /** 页面有壁纸背景时的次要文字色。 */
    private int wpPageFgSecondary(int fallback) {
        return wallpaperBitmap == null ? fallback : Ui.wallpaperFgSecondary(wallpaperBitmap, 100);
    }

    private boolean pageOnWallpaper() {
        return wallpaperBitmap != null;
    }

    /**
     * 主列表/面板上的文字与壁纸同步：应用名、标签、半屏底部搜索框。
     * 全屏横幅有自己的横幅条（底板），文字保持原来的深色，不跟随壁纸。
     */
    private void applyPageWallpaperTextColors() {
        if (panelMode) {
            // 半屏左下角「添加应用」、右下角「布局设置」两枚图标，以及顶部小白条，
            // 一起跟着壁纸明暗切换
            int fg2 = pageOnWallpaper()
                    ? wpPageFgSecondary(getColor(R.color.text_secondary))
                    : getColor(R.color.text_secondary);
            for (int id : new int[]{R.id.panel_search, R.id.layout}) {
                View v = findViewById(id);
                if (v instanceof ImageView) {
                    ((ImageView) v).setImageTintList(ColorStateList.valueOf(fg2));
                }
            }
            View handle = findViewById(R.id.panel_handle);
            if (handle != null && handle.getBackground() != null) {
                handle.getBackground().mutate().setTint(fg2);
            }
            if (search != null) {
                search.setTextColor(pageOnWallpaper()
                        ? wpPageFg(getColor(R.color.text_primary))
                        : getColor(R.color.text_primary));
                search.setHintTextColor(pageOnWallpaper()
                        ? wpPageFgSecondary(getColor(R.color.text_secondary))
                        : getColor(R.color.text_secondary));
            }
        }
        // 应用名/标签的颜色在重绑时生效：壁纸变更后重刷一遍
        if (gridAdapter != null) {
            gridAdapter.notifyDataSetChanged();
        }
        renderTabBar();
    }

    /** 当前「全屏背景」档位：0 = 整页透明，100 = 实心。半屏不参与。 */
    private int fullSurfacePct() {
        return Math.max(0, Math.min(100, prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID)));
    }

    /**
     * 全屏「外部模糊」档位：0 = 外面一点不糊（透明），100 = 最糊。
     * 没单独设过时跟随全屏背景，保持升级前后的观感一致。
     */
    private int fullBlurPct() {
        int v = prefs().getInt(KEY_FULL_BLUR, -1);
        return v < 0 ? fullSurfacePct() : Math.max(0, Math.min(100, v));
    }

    /**
     * 全屏顶栏（横幅）档位：独立记忆，不再跟随「全屏背景」。
     * 第一次升级时用当前全屏背景档位落一次盘，保证横幅观感不变且之后互不影响。
     */
    private int headerSurfacePct() {
        return Math.max(0, Math.min(100,
                prefs().getInt(KEY_HEADER_SURFACE, SURFACE_SOLID)));
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
        int alpha = coldStartMask ? 255 : Math.round(255f * pct / 100f);
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
        setPageBackdropAlpha(alpha);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                android.view.WindowManager.LayoutParams attrs = w.getAttributes();
                int maxRadius = Ui.dp(this, 28);
                // 外面糊到什么程度由「全屏外模糊」单独控制（0 = 外面看得清，100 = 最糊）
                attrs.setBlurBehindRadius(Math.round(maxRadius * fullBlurPct() / 100f));
                w.setAttributes(attrs);
            } catch (Exception ignored) {
            }
        }
        // 状态栏/导航栏压在页面背景上：背景偏实（浅色）时用深色图标，
        // 偏透明时页面可能压着深色壁纸，切成浅色图标保证可读；
        // 冷启动遮罩期间页面是实心底色，图标明暗直接跟页面底色走
        boolean lightBars = coldStartMask ? !nightMode() : pct >= 50;
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
        controller.setAppearanceLightStatusBars(lightBars);
        controller.setAppearanceLightNavigationBars(lightBars);
    }

    /** 只刷新 page_root 底色这一层：整页 alpha 始终只有这一个来源。 */
    private void setPageBackdropAlpha(int alpha) {
        View root = findViewById(R.id.page_root);
        if (root == null) {
            return;
        }
        if (wallpaperDrawable != null) {
            // 新实例：同一个 drawable 重复 setBackground 不会触发重绘
            WallpaperDrawable next = new WallpaperDrawable(wallpaperBitmap, 0);
            next.setAlpha(alpha);
            root.setBackground(next);
        } else {
            // 底色沿用 2.10.13 的 page_bg；列表区与底栏同色，透明度只由这一层承担
            int base = getColor(R.color.page_bg);
            root.setBackground(new ColorDrawable(android.graphics.Color.argb(alpha,
                    android.graphics.Color.red(base),
                    android.graphics.Color.green(base),
                    android.graphics.Color.blue(base))));
        }
    }

    /**
     * 系统预启动动画（那张铺在白/黑底板上的圆角卡片）撤除后，把遮罩用的实心底色
     * 淡回用户设置的档位：冷启动不再出现「白底整块 → 突然透明」的闪跳。
     */
    private void releaseColdStartMask() {
        if (!coldStartMask) {
            return;
        }
        coldStartMask = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        int target = Math.round(255f * fullSurfacePct() / 100f);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(255, target);
        va.setDuration(220);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(a -> setPageBackdropAlpha((int) a.getAnimatedValue()));
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                applyWindowBackdrop();
            }
        });
        va.start();
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
        mirrorWallpaperToOtherIfGlobal();
        applyWallpaperBackground();
    }

    /** 底栏的搜索/设置按钮跟着壁纸明暗走：暗壁纸白、亮壁纸黑；没壁纸用主题次要色。 */
    private void applyPanelBottomIconTone() {
        if (!panelMode) {
            return;
        }
        int tint = wallpaperBitmap != null
                ? Ui.wallpaperFgSecondary(wallpaperBitmap, 100)
                : getColor(R.color.text_secondary);
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
                attrs.setBlurBehindRadius(Math.round(maxRadius * fullBlurPct() / 100f));
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
                prefs().getInt(KEY_FULL_FAB_SURFACE, FULL_FAB_SURFACE_DEFAULT)));
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

    /** 拖把手实时改内容高度；拖到下限就停住，松手只记高度，不再因继续下拉而关闭面板。 */
    private final class PanelResizeListener implements View.OnTouchListener {
        private float downY;
        private int startContent;

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
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    int raw = startContent - Math.round(e.getRawY() - downY);
                    panelContentPx = Math.max(panelMinHpx, Math.min(panelMaxHpx, raw));
                    setPanelHeight(panel, panelContentPx + panelHandleHeightPx());
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    // 只落盘内容高度，和布局设置那套参数互不覆盖
                    int dp = Math.round(panelContentPx
                            / getResources().getDisplayMetrics().density);
                    prefs().edit().putInt(KEY_PANEL_HEIGHT, dp).apply();
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
        // 与 2.9.99 一致：整屏由透明慢慢压暗 → 停留 → 淡出
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
            // 已经隐藏过就不要再压一次：过渡动画期间重复改窗口属性会让系统用窗口底色补帧，露出白条
            if (statusBarHidden) {
                return;
            }
            statusBarReservePx = introStatusInsetPx();
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
            schedulePanelBackdrop();
            schedulePanelStatusTone();
        }
    }

    /**
     * 半屏回到前台后分多个时间点重压外侧遮罩与窗口模糊。
     * 系统在切后台/回前台的过程中会多次刷新窗口属性（模糊标志常被清掉），
     * 单次 onResume 压不住，这里补压到 1.2s，与状态栏外观的处理保持一致。
     */
    private void schedulePanelBackdrop() {
        if (!panelMode) {
            return;
        }
        View decor = getWindow().getDecorView();
        long[] delays = {0, 120, 350, 700, 1200};
        for (long delay : delays) {
            decor.postDelayed(() -> {
                if (panelMode && !isFinishing() && !isDestroyed()) {
                    applyBackdropStrength();
                }
            }, delay);
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
        pickerTargetTab = currentTabId;
        Intent i = new Intent(this, AppFreezeActivity.class);
        i.putExtra(AppFreezeActivity.EXTRA_PICK, true);
        // 选择页默认停在发起时所在的标签（用户应用/系统应用），选完回到这里
        i.putExtra(AppFreezeActivity.EXTRA_PICK_SYSTEM, TAB_SYSTEM.equals(currentTabId));
        picker.launch(i);
    }

    /** 半屏浮层底栏的设置图标直接进布局设置；备份/恢复收进布局弹窗底部，避免功能丢失。 */

    private void setSysHintVisible() {
        if (sysHintRow == null) {
            return;
        }
        boolean show = TAB_SYSTEM.equals(currentTabId)
                && !prefs().getBoolean(KEY_SYS_HINT_HIDDEN, false);
        sysHintRow.setVisibility(show ? View.VISIBLE : View.GONE);
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
        // 长按列表空白处再左右滑：快速逐格翻标签（不依赖「左右滑动切换标签」开关）
        if (handleBlankScrub(ev)) {
            return true;
        }
        // 动画回调可能被系统/复用打断（cancel 不触发 withEndAction），
        // 停留过久就复位，避免「左右滑标签有概率滑不动」；阈值与动画总时长（150+190）对齐
        if (tabAnimating
                && android.os.SystemClock.uptimeMillis() - tabAnimatingAt > 420) {
            tabAnimating = false;
        }
        // 冻结/解冻执行中（working）只锁批量动作本身，不再挡住左右滑切标签：
        // 批量慢时用户滑标签会被误当成「卡住」
        if (!tabSwipeEnabled || moveMode || editMode || tabAnimating) {
            return super.dispatchTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                swipeDownAt = android.os.SystemClock.uptimeMillis();
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
                    settleTabSwipe(ev.getX() - swipeDownX,
                            android.os.SystemClock.uptimeMillis() - swipeDownAt);
                    swipeTracking = false;
                    return true;
                }
                swipeTracking = false;
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                if (swipeTracking) {
                    // 手势被打断也回到原位，不留半截位移
                    settleTabSwipe(0f, 0L);
                }
                swipeTracking = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 长按列表空白处不松手再左右滑：按手指滑动速度翻标签。
     * 划得快就翻得快、慢慢拖就一格一格慢慢翻，速度直接跟随手指。
     * 和「左右滑动切换标签」并存，但长按手势优先；返回 true 表示本次事件已被这个手势消费。
     */
    private boolean handleBlankScrub(android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                cancelBlankScrub();
                // 全屏底部预留带（列表底部底栏上沿以下）长按：直接弹「快面板」，
                // 这个触点专用于弹面板，不再装长按滑标签手势；即使底栏透明、
                // 底下滚上来图标也优先归快面板，其余空白照旧
                if (inQuickPanelBar(ev.getRawX(), ev.getRawY())) {
                    quickPanelTouch = true;
                    quickPanelArmed = false;
                    quickPanelDownX = ev.getRawX();
                    quickPanelDownY = ev.getRawY();
                    getWindow().getDecorView().postDelayed(quickPanelArmRunnable,
                            android.view.ViewConfiguration.getLongPressTimeout());
                    return false;
                }
                quickPanelTouch = false;
                // 底栏区域不触发「长按列表空白处」手势：快面板开关关着也一视同仁，
                // 点击图标/滚动列表的行为照常
                if (inFullBottomBar(ev.getRawX(), ev.getRawY())) {
                    return false;
                }
                // 边缘触发模式：左右边缘条带内（含面板上方遮罩）都交给这个手势
                boolean edgeStart = edgeReserved(ev.getRawX());
                // 移动/编辑/多选/执行中不启用，留给原有手势与操作
                if (gestureEnabled && !moveMode && !editMode && !selectMode && !working
                        && (inBlankListArea(ev.getRawX(), ev.getRawY()) || edgeStart)) {
                    scrubDownX = ev.getRawX();
                    scrubDownY = ev.getRawY();
                    scrubAnchorX = ev.getRawX();
                    scrubTabMoved = false;
                    getWindow().getDecorView().postDelayed(scrubArmRunnable,
                            android.view.ViewConfiguration.getLongPressTimeout());
                }
                return false;
            case android.view.MotionEvent.ACTION_MOVE:
                if (quickPanelTouch) {
                    // 快面板触点：位移超过 12dp 取消武装；已武装后拖动原地不动，事件照单收下
                    if (!quickPanelArmed) {
                        float qdx = ev.getRawX() - quickPanelDownX;
                        float qdy = ev.getRawY() - quickPanelDownY;
                        if (Math.abs(qdx) > Ui.dp(this, 12) || Math.abs(qdy) > Ui.dp(this, 12)) {
                            cancelQuickPanelArm();
                            quickPanelTouch = false;
                        }
                        return false;
                    }
                    return true;
                }
                if (!scrubArmed) {
                    // 还没长按到位就移动了：交回原来的滚动 / 滑动手势
                    float dx0 = ev.getRawX() - scrubDownX;
                    float dy0 = ev.getRawY() - scrubDownY;
                    if (Math.abs(dx0) > Ui.dp(this, 12) || Math.abs(dy0) > Ui.dp(this, 12)) {
                        cancelBlankScrub();
                    }
                    return false;
                }
                scrubByFinger(ev);
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (quickPanelTouch) {
                    // 武装到点后松手：消费掉事件，不再落回列表
                    boolean wasQuickArmed = quickPanelArmed;
                    cancelQuickPanelArm();
                    quickPanelTouch = false;
                    return wasQuickArmed;
                }
                boolean armed = scrubArmed;
                cancelBlankScrub();
                return armed;
            default:
                return false;
        }
    }

    /** 取消快面板长按武装：摘掉到点回调并复位，面板已弹出后所有手势归对话框。 */
    private void cancelQuickPanelArm() {
        quickPanelArmed = false;
        getWindow().getDecorView().removeCallbacks(quickPanelArmRunnable);
    }

    /**
     * 长按到位后的滑动：位移按「每格所需距离」折算成翻页格数。
     * 手指越快，每格所需距离越小（慢拖约 22dp/格，快划最密约 12dp/格），
     * 所以慢慢拖一格一格走，快速划过则连续翻过多格。
     */
    private void scrubByFinger(android.view.MotionEvent ev) {
        // 长按空白后上下滑切换形态：半屏只认上滑（进全屏），全屏只认下滑（回半屏）。
        // 触发距离由设置页的「手势触发距离」两个滑块决定，实时生效；
        // 勾选「仅在屏幕边缘触发上下滑」后，只有从左右边缘开始的手势才切换形态。
        // 本次手势已经左右翻过标签的，不再触发上下滑切形态，避免一次长按里两种手势互相打断。
        float dy = ev.getRawY() - scrubDownY;
        boolean trigger = !scrubTabMoved && (panelMode
                ? dy < -Ui.dp(this, gestureUpDp)
                : dy > Ui.dp(this, gestureDownDp));
        if (trigger) {
            if (!gestureEdgeOnly || atScreenEdge(scrubDownX)) {
                cancelBlankScrub();
                // toFull = 当前是不是半屏：半屏上滑进全屏、全屏下滑回半屏
                switchForm(panelMode);
                return;
            }
        }
        if (scrubVelocity == null) {
            scrubVelocity = android.view.VelocityTracker.obtain();
        }
        scrubVelocity.addMovement(ev);
        scrubVelocity.computeCurrentVelocity(1000);
        float speed = Math.abs(scrubVelocity.getXVelocity());
        float dx = ev.getRawX() - scrubAnchorX;
        if (Math.abs(dx) < Ui.dp(this, 6)) {
            return;
        }
        float step = Ui.dp(this, 22) * 1000f / Math.max(speed, 450f);
        step = Math.max(Ui.dp(this, 12), Math.min(Ui.dp(this, 90), step));
        int turns = (int) (Math.abs(dx) / step);
        if (turns <= 0) {
            return;
        }
        // 单次事件最多翻 4 格，避免一帧跳太多；剩下的位移留给后续事件继续翻
        turns = Math.min(turns, 4);
        scrubAnchorX += Math.signum(dx) * turns * step;
        scrubSwitchTabs(dx > 0 ? -1 : 1, turns);
    }

    private void cancelBlankScrub() {
        scrubArmed = false;
        if (scrubVelocity != null) {
            scrubVelocity.recycle();
            scrubVelocity = null;
        }
        getWindow().getDecorView().removeCallbacks(scrubArmRunnable);
    }

    /** 手势触发距离限制在 0–520dp。 */
    private static int clampGestureDp(int dp) {
        return Math.max(0, Math.min(GESTURE_DP_MAX, dp));
    }

    /** 边缘触发区宽度限制在 20–200dp。 */
    private static int clampEdgeDp(int dp) {
        return Math.max(GESTURE_EDGE_DP_MIN, Math.min(GESTURE_EDGE_DP_MAX, dp));
    }

    /** 边缘距离文案：20dp 起就是真实触发范围，不再有「0dp 关闭」。 */
    private static String edgeDpLabel(int dp) {
        return dp + "dp";
    }

    /**
     * 手势起点是否落在左右边缘触发区。
     * 「屏幕左/右边缘靠内触发手势距离」= 从屏幕边缘向里这段距离，整段都可触发：
     * 左区 0..leftDp，右区 (屏宽-rightDp)..屏宽；最小 20dp，20dp 也是有效范围。
     */
    private boolean atScreenEdge(float rawX) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int leftWidth = Math.max(0, Ui.dp(this, gestureEdgeLeftDp));
        int rightWidth = Math.max(0, Ui.dp(this, gestureEdgeRightDp));
        return (leftWidth > 0 && rawX <= leftWidth)
                || (rightWidth > 0 && rawX >= width - rightWidth);
    }

    /**
     * 把左右边缘触发区从系统返回手势区里「要回来」。
     * Android 10+ 允许应用声明排除区（系统对单侧高度有上限），声明成功后贴边按下会先交给应用；
     * 关掉边缘触发时清空，恢复系统返回手势。
     */
    private void applyEdgeGestureExclusion() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return;
        }
        View decor = getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        if (!gestureEnabled || !gestureEdgeOnly) {
            decor.setSystemGestureExclusionRects(java.util.Collections.emptyList());
            return;
        }
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            decor.post(this::applyEdgeGestureExclusion);
            return;
        }
        int leftW = Math.min(w, Math.max(0, Ui.dp(this, gestureEdgeLeftDp)));
        int rightW = Math.min(w, Math.max(0, Ui.dp(this, gestureEdgeRightDp)));
        java.util.List<android.graphics.Rect> rects = new java.util.ArrayList<>();
        if (leftW > 0) {
            rects.add(new android.graphics.Rect(0, 0, leftW, h));
        }
        if (rightW > 0) {
            rects.add(new android.graphics.Rect(w - rightW, 0, w, h));
        }
        decor.setSystemGestureExclusionRects(rects);
    }

    /**
     * 全屏 / 半屏自由切换：设置里的形态开关与长按空白后的上下滑共用。
     * 目标形态开着「隐藏后台」时走对应隐藏入口，保证新任务从创建起就不进最近任务。
     */
    private void switchForm(boolean toFull) {
        prefs().edit().putBoolean(KEY_FULLSCREEN_OPEN, toFull).apply();
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
        }
        settingsDialog = null;
        boolean hideTarget = prefs().getBoolean(KEY_PANEL_HIDE_RECENTS, false);
        Class<?> next = toFull
                ? (hideTarget ? FreezeListHiddenActivity.class : FreezeListActivity.class)
                : (hideTarget ? FreezePanelHiddenActivity.class : FreezePanelActivity.class);
        Intent intent = new Intent(this, next);
        // 与 2.9.99 一致：瞬时切换（无过渡动画），两条路径（开关开/关）速度相同
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        finish();
    }

    /** 触点是否落在列表格子区域内（不区分图标/留白）。 */
    private boolean inGridArea(float rawX, float rawY) {
        if (grid == null || grid.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        grid.getLocationOnScreen(loc);
        float x = rawX - loc[0];
        float y = rawY - loc[1];
        return x >= 0 && y >= 0 && x < grid.getWidth() && y < grid.getHeight();
    }

    /** 手势起点是否落在列表的空白处（没压到应用图标本身，格子留白与名称行都算空白）。 */
    private boolean inBlankListArea(float rawX, float rawY) {
        // 悬浮按钮浮在列表之上，按在它上面不算空白：否则长按冻结按钮会同时武装
        // 「长按空白滑标签」，两边各震一次
        return inGridArea(rawX, rawY)
                && !inView(fabGroup, rawX, rawY)
                && !inView(unfreezeFab, rawX, rawY)
                && !onAppIcon(rawX, rawY);
    }

    /** 边缘触发模式下的专属条带：这里的长按交给切形态手势（面板上方的遮罩区也算）。 */
    private boolean edgeReserved(float rawX) {
        return gestureEdgeOnly && atScreenEdge(rawX);
    }

    /** 全屏底栏预留带（列表底部 54dp）：这里的长按一律不归「长按列表空白处」手势。 */
    private boolean inFullBottomBar(float rawX, float rawY) {
        return !panelMode && bottomBarTopPx() >= 0 && rawY >= bottomBarTopPx()
                && inGridArea(rawX, rawY);
    }

    /**
     * 全屏底栏预留区的长按归快面板：即使全屏背景透明、底下正好滚上来一个图标，
     * 长按也优先弹快面板，不穿透成图标操作单。
     */
    private boolean inQuickPanelBar(float rawX, float rawY) {
        return inFullBottomBar(rawX, rawY) && quickPanelEnabled
                && !moveMode && !editMode && !selectMode && !working
                && !inView(fabGroup, rawX, rawY)
                && !inView(unfreezeFab, rawX, rawY)
                && !edgeReserved(rawX);
    }

    /** 触点是否压在真正的图标方块上：只有这里长按才弹操作单，其他位置留给长按滑标签。 */
    private boolean onAppIcon(float rawX, float rawY) {
        if (grid == null || grid.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        grid.getLocationOnScreen(loc);
        float x = rawX - loc[0];
        float y = rawY - loc[1];
        if (x < 0 || y < 0 || x >= grid.getWidth() || y >= grid.getHeight()) {
            return false;
        }
        View child = grid.findChildViewUnder(x, y);
        if (child == null || !(child.getTag() instanceof Cell)) {
            return false;
        }
        View icon = ((Cell) child.getTag()).icon;
        if (icon == null || icon.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] ip = new int[2];
        icon.getLocationOnScreen(ip);
        return rawX >= ip[0] && rawX < ip[0] + icon.getWidth()
                && rawY >= ip[1] && rawY < ip[1] + icon.getHeight();
    }

    /** 长按手势切标签：一次可跨多格，按可见标签顺序前后翻，到头停住。 */
    private void scrubSwitchTabs(int dir, int count) {
        if (tabAnimating || working || count <= 0) {
            return;
        }
        List<Tab> visible = new ArrayList<>();
        for (Tab t : tabs) {
            if (t.visible) {
                visible.add(t);
            }
        }
        if (visible.size() < 2) {
            return;
        }
        int idx = 0;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).id.equals(currentTabId)) {
                idx = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(visible.size() - 1, idx + dir * count));
        if (next == idx) {
            return;
        }
        selectTab(visible.get(next).id);
        // 本次长按手势已经用过左右翻标签：后续位移不再触发上下滑切形态
        scrubTabMoved = true;
        // 每翻一格都震一下（与触发长按那一震同一套忽略系统触感开关的口径）
        if (scrubVibrate) {
            View target = grid != null ? grid : getWindow().getDecorView();
            target.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
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
     * 阈值放低到 30dp（原来 56dp 太远，短距离滑动会被误以为「卡住」）；
     * 快速轻扫（300ms 内完成）只要横向意图成立也直接切页。
     */
    private void settleTabSwipe(float dx, long dt) {
        final View v = tabSlideView();
        if (v == null) {
            return;
        }
        final int dir = dx < 0 ? 1 : -1;
        float dist = Math.abs(dx);
        boolean flick = dt > 0 && dt < 300 && dist > Ui.dp(this, 18);
        boolean canSwitch = (dist > Ui.dp(this, 30) || flick) && hasTabNeighbor(dir);
        if (!canSwitch) {
            v.animate().translationX(0f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            return;
        }
        final float w = v.getWidth();
        tabAnimating = true;
        tabAnimatingAt = android.os.SystemClock.uptimeMillis();
        // 兜底复位：视图被重建或动画被系统取消时 withEndAction 不执行，
        // 这里按动画总时长（150+190）留一点余量解锁，避免后续滑动一直被吞
        v.postDelayed(() -> tabAnimating = false, 420);
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
        // 半屏从后台回到前台（含从桌面图标再次拉起）时重压一次外侧遮罩与窗口模糊：
        // 系统在切后台时会把窗口模糊/遮罩相关属性刷掉，不重压就会出现「外面全透明、滑块失效」
        if (panelMode) {
            schedulePanelBackdrop();
        }
        // 选择应用页里也能改标签页，这里重读一次归属，否则返回列表后仍按旧内存数据分组
        loadAppTabs();
        // 每次回到页面都重新读一次系统真值
        reload();
        // 授权管理器里点完「允许」回到凚时自动重测：失败缓存有 TTL，这里强制重来一次
        if (privDialog != null && privDialog.isShowing()) {
            recheckPrivilegeAsync(true, true, null);
        } else if (PrivShell.peek() == null || PrivShell.peek() == PrivShell.Mode.NONE) {
            recheckPrivilegeAsync(false, false, null);
        }
        // 从「所有文件访问」授权页返回：已授权就直接重扫恢复列表
        if (restoreAwaitAccess) {
            restoreAwaitAccess = false;
            if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                main.postDelayed(this::showRestoreFiles, 300);
            }
        }
        // 半屏显示期间状态栏压在桌面上，按壁纸明暗决定图标深浅（过渡中不压，交给过渡自己控）
        if (panelMode && !introPlaying) {
            schedulePanelStatusTone();
        }
        syncRecentsExclusion();
        // 边缘触发开启时，把两侧条带从系统返回手势区里排除，贴边按下才收得到
        applyEdgeGestureExclusion();
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
        // 退后台前再压一次快照开关：系统是在这之后才给任务截图，
        // 默认半屏时不能把当前全屏页留成下一次启动的预览图
        if (!panelMode) {
            syncRecentsExclusion();
        }
        // 长按翻标签的计时器在离开页面时清掉
        cancelBlankScrub();
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
                // 冷启动的白底/黑底来自系统「预启动动画」的实色底板（见 coldStartMask），
                // 与最近任务快照无关，不要为了启动观感把最近任务截图整体关掉
                boolean allowShot = !panelHideRecents
                        && (panelMode || prefs().getBoolean(KEY_FULLSCREEN_OPEN, true));
                setRecentsScreenshotEnabled(allowShot);
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
        // 移动模式优先用返回键退出（入口已从设置挪到长按操作单）
        if (moveMode) {
            setMoveMode(false);
            return;
        }
        // 多选态优先退出多选，不直接关面板
        if (selectMode) {
            exitSelectMode();
            return;
        }
        closeSelf();
    }

    /** 关闭当前页：半屏走收起过渡并移除任务，全屏直接 finish，落点交给系统。 */
    private void closeSelf() {
        if (panelMode) {
            closeWithIntro(() -> {
                finishAndRemoveTask();
                overridePendingTransition(0, 0);
            });
        } else {
            finish();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** 统一的短提示：勾了「去掉 toast 提示」后静默，凚 里所有 toast 都走这里。 */
    private void toast(CharSequence text) {
        if (hideToasts) {
            return;
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /**
     * 布局参数按承载形态各存一份：半屏浮层加 panel_ 前缀。
     * 名单、标签归属、排序、冻结状态仍然共用同一套 key。
     */
    private String lk(String base) {
        return panelMode ? "panel_" + base : base;
    }

    /** 第一次进半屏浮层时把全屏的布局抄一份作为初始值；两形态共用的项不抄，直接读同一个 key。 */
    private void ensurePanelLayoutInit() {
        if (!panelMode || prefs().getBoolean("panel_layout_init", false)) {
            return;
        }
        prefs().edit()
                .putBoolean("panel_" + KEY_SHOW_NAME, prefs().getBoolean(KEY_SHOW_NAME, true))
                .putBoolean("panel_" + KEY_MOVE_MODE, prefs().getBoolean(KEY_MOVE_MODE, false))
                .putInt("panel_" + KEY_FAB_DX, prefs().getInt(KEY_FAB_DX, 0))
                .putInt("panel_" + KEY_FAB_DY,
                        prefs().getInt(KEY_FAB_DY, PANEL_FAB_DY_DEFAULT))
                .putInt("panel_" + KEY_FAB_SIZE,
                        prefs().getInt(KEY_FAB_SIZE, PANEL_FAB_SIZE_DEFAULT))
                .putInt("panel_" + KEY_COLUMNS, prefs().getInt(KEY_COLUMNS, COLUMNS))
                .putInt("panel_" + KEY_ICON_DP, prefs().getInt(KEY_ICON_DP, 52))
                .putBoolean("panel_" + KEY_ICON_MANUAL,
                        prefs().getBoolean(KEY_ICON_MANUAL, false))
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
        if (prefs().getInt("panel_fab_size", -1) == 40) {
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
                    // 名称允许为空（改名成空格即不显示文字），只要求 id 合法且不重复
                    if (t.id.isEmpty() || findTab(t.id) != null) {
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

    /** 标签排序：拖动完把新顺序写回 tabs 并重绘标签栏与当前列表。 */
    private void showTabSortDialog(Runnable refresh) {
        List<TabSortDialog.Tab> entries = new ArrayList<>();
        for (Tab t : tabs) {
            entries.add(new TabSortDialog.Tab(t.id, t.name));
        }
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int centeredLeft = (screenW - settingsDialogWidth()) / 2;
        int xOffset = settingsPanelLeft >= 0 ? settingsPanelLeft - centeredLeft : 0;
        TabSortDialog.show(this, entries, ordered -> {
            List<Tab> reordered = new ArrayList<>();
            for (TabSortDialog.Tab o : ordered) {
                Tab t = findTab(o.id);
                if (t != null) {
                    reordered.add(t);
                }
            }
            // 兜底：没出现在弹窗里的标签（理论上不会有）按原顺序补在末尾
            for (Tab t : tabs) {
                if (!reordered.contains(t)) {
                    reordered.add(t);
                }
            }
            tabs.clear();
            tabs.addAll(reordered);
            saveTabs();
            renderTabBar();
            render();
            if (refresh != null) {
                refresh.run();
            }
        }, xOffset, settingsSurfaceAlpha(),
                panelMode ? openBottomLimitPx() : -1, statusBarHeightPx());
    }

    /** 长按标签页：改名、设默认、排序、隐藏标签列表。 */
    private void showTabLongPressMenu(Tab t) {
        if (t == null) {
            return;
        }
        java.util.List<AppActionSheet.TextItem> items = new java.util.ArrayList<>();
        items.add(new AppActionSheet.TextItem(R.drawable.ic_action_tag, "重命名", null,
                () -> showTabNameDialog(t, this::renderTabBar)));
        // 只认显式设过的默认：没有设置时 defaultTabId() 会回落到第一个标签，
        // 这里不能拿它判断，否则永远显示「已开启」且点一下就变成取消
        final String explicitDefault = prefs().getString(KEY_DEFAULT_TAB, null);
        final boolean isDefault = t.id.equals(explicitDefault);
        items.add(new AppActionSheet.TextItem(R.drawable.ic_action_move, "排序标签", null,
                () -> showTabSortDialog(null)));
        items.add(new AppActionSheet.TextItem(R.drawable.ic_add, "新建标签", null,
                () -> showTabNameDialog(null, null)));
        // 隐藏该标签：与设置页「标签设定」里取消勾选完全一致（至少保留一个、当前标签被隐藏后切到第一个可见标签）
        items.add(new AppActionSheet.TextItem(R.drawable.ic_qp_eye_off, "隐藏该标签", null, () -> {
            if (t.visible && visibleTabCount() == 1) {
                toast("至少保留一个标签");
                return;
            }
            t.visible = false;
            Tab curTab = findTab(currentTabId);
            if (curTab == null || !curTab.visible) {
                currentTabId = firstVisibleTabId();
                prefs().edit().putString(KEY_CURRENT_TAB, currentTabId).apply();
            }
            saveTabs();
            setSysHintVisible();
            renderTabBar();
            render();
            toast("已隐藏「" + t.name + "」标签");
        }));
        items.add(new AppActionSheet.TextItem(R.drawable.ic_action_home,
                "每次默认进入此标签", isDefault ? "已开启" : "未开启", !isDefault, () -> {
            if (isDefault) {
                prefs().edit().remove(KEY_DEFAULT_TAB).apply();
                toast("已取消默认，进入时回到第一个标签");
            } else {
                prefs().edit().putString(KEY_DEFAULT_TAB, t.id).apply();
                toast("「" + t.name + "」已设为默认标签");
            }
            renderTabBar();
        }));
        // 与长按图标弹出的选项框同一套宽度、圆角、壁纸与背景透明度
        int sheetAlpha = panelMode
                ? prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID)
                : prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID);
        AppActionSheet.showTextMenu(this, "「" + t.name + "」", srcMode(), sheetAlpha, items);
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
                if (pkg.isEmpty()) {
                    continue;
                }
                java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
                // 旧版本存的是单个标签字符串，新版本存数组，两种都要能读
                Object v = o.opt(pkg);
                if (v instanceof JSONArray) {
                    JSONArray arr = (JSONArray) v;
                    for (int k = 0; k < arr.length(); k++) {
                        String tab = arr.optString(k, "");
                        // 标签已被删掉时不做映射，tabsOf 会按 FLAG_SYSTEM 落回内置标签
                        if (!tab.isEmpty() && findTab(tab) != null) {
                            set.add(tab);
                        }
                    }
                } else if (v instanceof String) {
                    String tab = (String) v;
                    if (!tab.isEmpty() && findTab(tab) != null) {
                        set.add(tab);
                    }
                }
                if (!set.isEmpty()) {
                    appTabs.put(pkg, set);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveAppTabs() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, java.util.LinkedHashSet<String>> e : appTabs.entrySet()) {
            try {
                JSONArray arr = new JSONArray();
                for (String tab : e.getValue()) {
                    arr.put(tab);
                }
                if (arr.length() > 0) {
                    o.put(e.getKey(), arr);
                }
            } catch (Exception ignored) {
            }
        }
        prefs().edit().putString(KEY_APP_TABS, o.toString()).apply();
        SwipeFreeze.syncAsync(this);
    }

    /** 该应用所在的全部标签；没有显式归属时按 FLAG_SYSTEM 落回内置标签。 */
    private Set<String> tabsOf(Item it) {
        return tabsOfKey(it.pkg, it.system);
    }

    /** 按实例键取标签归属，添加应用页拿到的是键、没有 Item 时用这个。 */
    private Set<String> tabsOfKey(String pkgKey, boolean system) {
        java.util.LinkedHashSet<String> set = appTabs.get(pkgKey);
        if (set != null) {
            java.util.LinkedHashSet<String> valid = new java.util.LinkedHashSet<>();
            for (String id : set) {
                if (findTab(id) != null) {
                    valid.add(id);
                }
            }
            if (!valid.isEmpty()) {
                return valid;
            }
        }
        java.util.LinkedHashSet<String> fallback = new java.util.LinkedHashSet<>();
        fallback.add(system ? TAB_SYSTEM : TAB_USER);
        return fallback;
    }

    private boolean inTab(Item it, String tabId) {
        return tabsOf(it).contains(tabId);
    }

    /** 应用自定义显示名（空 = 用系统名称）。 */
    private String customName(String pkgKey) {
        try {
            JSONObject o = new JSONObject(prefs().getString(KEY_APP_NAMES, "{}"));
            return o.optString(pkgKey, "");
        } catch (Exception e) {
            return "";
        }
    }

    private void setCustomName(String pkgKey, String name) {
        try {
            JSONObject o = new JSONObject(prefs().getString(KEY_APP_NAMES, "{}"));
            String v = name == null ? "" : name.trim();
            if (v.isEmpty()) {
                o.remove(pkgKey);
            } else {
                o.put(pkgKey, v);
            }
            prefs().edit().putString(KEY_APP_NAMES, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 把自定义名套到条目上；分身仍保留「(用户号)」后缀，便于区分。 */
    private void applyCustomLabel(Item it) {
        String cn = customName(it.pkg);
        if (cn.isEmpty()) {
            return;
        }
        it.label = cn + (it.userId != 0 ? cloneTag(it.userId) : "");
    }

    /** 长按菜单「重命名」：框里预填当前名称，留空恢复系统名称。 */
    private void showRenameAppDialog(Item it) {
        String system = systemLabel(it.pkg);
        String custom = customName(it.pkg);
        String initial = custom.isEmpty() ? system : custom;
        showRenameDialog("重命名应用", initial, "显示名称（留空恢复原名）", name -> {
            // 改回系统原名时不再存冗余的自定义名
            setCustomName(it.pkg, name.equals(system) ? "" : name);
            reload();
            toast(name.isEmpty() ? "已恢复系统名称" : "已重命名为「" + name + "」");
            return true;
        });
    }

    /** 系统给的应用名（不含分身后缀）；取不到时退回包名。 */
    private String systemLabel(String pkgKey) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(packagePart(pkgKey), 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return packagePart(pkgKey);
        }
    }

    /** 设置面板里每个分组的「只收不放」动作：长按底部展开键时一键全部收起。 */
    private final java.util.List<Runnable> settingsCollapsers = new java.util.ArrayList<>();

    /**
     * 设置里分组标题右侧的折叠把手：点箭头收起/展开 body，状态落盘。
     * 标识沿用设置底部展开/收起那颗三角图标，紧贴在标题文字右边。
     * 标题整行也可点：点「（查看说明）」才弹说明，点左边标题文字就是折叠/展开；
     * 没有说明的小标题，整行点击都只负责折叠/展开。
     * iconRes 为 0 时不加左侧图标；hint 为 null 表示标题没有「查看说明」。
     */
    private LinearLayout collapsibleHead(final TextView title, final View body, String key,
                                         final Runnable onExpand, final Runnable hint,
                                         int iconRes) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // 标题可能已经先加进了别的容器（设置里部分栏位先 add 再整理），
        // 直接 addView 会抛「child already has a parent」，先摘下来
        android.view.ViewParent tp = title.getParent();
        if (tp instanceof ViewGroup) {
            ((ViewGroup) tp).removeView(title);
        }
        // 标题自带的上下留白挪到整行上：文字与箭头都按整行垂直居中，才会在同一条线上
        int padTop = title.getPaddingTop();
        int padBottom = title.getPaddingBottom();
        title.setPadding(title.getPaddingLeft(), 0, title.getPaddingRight(), 0);
        head.setPadding(0, padTop, 0, padBottom);
        // 左侧小图标：与快面板条目同一档大小与色调，紧贴在标题文字左边
        if (iconRes != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
            int iconSize = Ui.dp(this, 16);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSize, iconSize);
            ilp.rightMargin = Ui.dp(this, 6);
            head.addView(icon, ilp);
            // 组内小字与标题文字共用一条左边线：整块缩进「图标 + 间距」，
            // 不跟着图标左沿对齐（否则下面的说明/滑块会比标题更靠左）
            body.setPadding(body.getPaddingLeft() + iconSize + Ui.dp(this, 6),
                    body.getPaddingTop(), body.getPaddingRight(), body.getPaddingBottom());
        }
        head.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 各栏目默认折叠：没动过的进来就收起，长按◎一键全展开、长按底栏一键全收起
        final boolean[] collapsed = {prefs().getBoolean(key, true)};
        final ImageView arrow = new ImageView(this);
        arrow.setImageResource(collapsed[0]
                ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_up);
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = Ui.dp(this, 20);
        arrow.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        arrow.setClickable(true);
        arrow.setContentDescription("展开或收起");
        body.setVisibility(collapsed[0] ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(size, size);
        alp.leftMargin = Ui.dp(this, 1);
        final Runnable toggle = () -> {
            collapsed[0] = !collapsed[0];
            prefs().edit().putBoolean(key, collapsed[0]).apply();
            body.setVisibility(collapsed[0] ? View.GONE : View.VISIBLE);
            arrow.setImageResource(collapsed[0]
                    ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_up);
            if (!collapsed[0]) {
                if (onExpand != null) {
                    onExpand.run();
                }
                // 展开后立刻把刚露出的内容滚进可视区，不用再手动往下滑
                revealExpandedBody(body, () -> !collapsed[0]);
            }
        };
        arrow.setOnClickListener(v -> toggle.run());
        head.addView(arrow, alp);
        // 登记「只收不放」的动作：长按设置底部展开键时把全部分组一键收起
        settingsCollapsers.add(() -> {
            if (!collapsed[0]) {
                toggle.run();
            }
        });
        // 标题：命中测试落在「（」及之后 = 点的「查看说明」，弹说明；
        // 落在左边标题文字上（或整行没有说明）= 折叠/展开
        title.setOnClickListener(null);
        title.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != android.view.MotionEvent.ACTION_UP) {
                return false;
            }
            android.text.Layout lay = title.getLayout();
            if (lay == null || lay.getLineCount() == 0) {
                return false;
            }
            int x = (int) ev.getX() + title.getScrollX() - title.getTotalPaddingLeft();
            int y = (int) ev.getY() + title.getScrollY() - title.getTotalPaddingTop();
            if (x < 0 || y < 0) {
                return false;
            }
            int line = lay.getLineForVertical(y);
            if (line >= lay.getLineCount()) {
                return false;
            }
            int off;
            try {
                off = lay.getOffsetForHorizontal(line, x);
            } catch (Exception e) {
                return false;
            }
            int hintIdx = android.text.TextUtils.indexOf(title.getText(), "（");
            // 后缀被「不再提醒」隐藏后标题里没有「（」，此时整行点击都是折叠/展开
            if (hint != null && hintIdx >= 0 && off >= hintIdx) {
                hint.run();
            } else {
                toggle.run();
            }
            return true;
        });
        return head;
    }

    /**
     * 设置分组展开后，把刚露出来的内容滚进可视区：
     * 交给滚动容器自己算，内容矮就滚到刚好露出，内容高就顶部对齐。
     */
    private void revealExpandedBody(View body, java.util.function.BooleanSupplier stillExpanded) {
        // 展开后布局要下一帧才落地，滚太早会把展开前的高度当成最终高度
        body.postDelayed(() -> {
            if (!stillExpanded.getAsBoolean() || body.getHeight() <= 0
                    || body.getWidth() <= 0) {
                return;
            }
            int margin = Ui.dp(FreezeListActivity.this, 10);
            // Rect 用的是 body 自身坐标：上下各多留一点，滚完不会紧贴边缘
            body.requestRectangleOnScreen(new android.graphics.Rect(
                    -margin, -margin, body.getWidth() + margin, body.getHeight() + margin),
                    true);
        }, 80);
    }

    /** 当前标签之外，该应用是否还属于别的标签（属于时只做当前标签的移除）。 */
    private boolean inOtherTabs(Item it) {
        for (String id : tabsOf(it)) {
            if (!id.equals(currentTabId)) {
                return true;
            }
        }
        return false;
    }

    private String tabNameOf(String id) {
        Tab t = findTab(id);
        return t == null ? id : t.name;
    }

    /** 加入标签：保留原有归属，一个应用可以同时出现在多个标签页。 */
    private boolean addAppToTab(String pkgKey, String tabId, boolean system) {
        java.util.LinkedHashSet<String> set = appTabs.get(pkgKey);
        if (set == null) {
            set = new java.util.LinkedHashSet<>();
            // 没有显式归属时应用本来落在「用户应用 / 系统应用」标签里：
            // 先把这份隐式归属写进集合，否则加入新标签后它会从原标签消失，
            // 看起来像被「移动」而不是「同时出现在多个标签」
            set.add(system ? TAB_SYSTEM : TAB_USER);
            appTabs.put(pkgKey, set);
        }
        return set.add(tabId);
    }

    /** 从某个标签移出；该应用在其他标签里的归属不受影响。 */
    private boolean removeAppFromTab(String pkgKey, String tabId) {
        java.util.LinkedHashSet<String> set = appTabs.get(pkgKey);
        if (set == null || !set.remove(tabId)) {
            return false;
        }
        if (set.isEmpty()) {
            appTabs.remove(pkgKey);
        }
        return true;
    }

    /** 加入名单后要跳去的标签：优先「+」发起时的标签，否则取它所在第一个可见标签。 */
    private String landingTabOf(Item it) {
        Set<String> mine = tabsOf(it);
        if (pickerTargetTab != null && mine.contains(pickerTargetTab)) {
            return pickerTargetTab;
        }
        for (Tab t : tabs) {
            if (t.visible && mine.contains(t.id)) {
                return t.id;
            }
        }
        for (String id : mine) {
            return id;
        }
        return firstVisibleTabId();
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
        // 隐藏标签页文字对两种形态独立生效
        applyTabRowVisibility(!hideTabRow);
        if (visibleTabCount() == 0) {
            return;
        }
        boolean firstPanelTab = true;
        View onView = null;
        for (Tab t : tabs) {
            if (!t.visible) {
                continue;
            }
            boolean on = t.id.equals(currentTabId);
            // 全屏与半屏统一同一套标签样式：文字 + 下划线，字号、颜色、间距完全一致
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView label = new TextView(this);
            label.setText(t.name);
            label.setTextSize(14);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8));
            // 有自定义壁纸时，标签文字跟着壁纸自动白/黑（全屏/半屏同一套）
            boolean tabOnWp = pageOnWallpaper();
            label.setTextColor(on
                    ? (tabOnWp ? wpPageFg(getColor(R.color.text_primary))
                    : getColor(R.color.text_primary))
                    : (tabOnWp ? wpPageFgSecondary(getColor(R.color.text_secondary))
                    : getColor(R.color.text_secondary)));
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
            // 名字留空时仍保留一小段可点/可长按区域，方便再改回来
            item.setMinimumWidth(Ui.dp(this, 24));
            item.setOnClickListener(v -> selectTab(t.id));
            item.setOnLongClickListener(v -> {
                // 无论手指落在哪个标签上，长按都只针对当前选中的标签
                Tab cur = findTab(currentTabId);
                showTabLongPressMenu(cur != null ? cur : t);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 只在非首个标签上加左间距，避免末尾多出一截导致整体看起来偏左
            if (!firstPanelTab) {
                lp.leftMargin = Ui.dp(this, 10);
            }
            firstPanelTab = false;
            tabBar.addView(item, lp);
            if (on) {
                onView = item;
            }
        }
        scrollTabIntoView(onView);
        reapplyFullscreenSurfaces();
    }

    /** 选中标签变化后把对应项滚进可视区：标签多到超出屏幕宽度时也能看到当前项。 */
    private void scrollTabIntoView(View item) {
        if (item == null || tabBar == null) {
            return;
        }
        if (!(tabBar.getParent() instanceof HorizontalScrollView)) {
            return;
        }
        final HorizontalScrollView hsv = (HorizontalScrollView) tabBar.getParent();
        hsv.post(() -> {
            int target = item.getLeft() - (hsv.getWidth() - item.getWidth()) / 2;
            hsv.smoothScrollTo(Math.max(0, target), 0);
        });
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

    /** 新建 / 重命名标签；允许空名（显示为无文字），只禁止与其它标签重名。 */
    private void showTabNameDialog(Tab tab, Runnable done) {
        showRenameDialog(tab == null ? "新建标签" : "重命名标签",
                tab == null ? "" : tab.name, "标签名称", name -> {
                    if (!name.isEmpty()) {
                        for (Tab t : tabs) {
                            if (t != tab && t.name.equals(name)) {
                                toast("已有同名标签");
                                return false;
                            }
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
                    return true;
                });
    }

    /**
     * 紧凑改名卡片：标签改名与全屏标题改名共用。
     * 输入框留空或只输空格即视为空名（标题/标签不显示文字）；onOk 返回 false 时保留弹窗。
     */
    private void showRenameDialog(String dlgTitle, String initial, String hint,
                                  java.util.function.Predicate<String> onOk) {
        // 紧凑卡片：自绘标题/输入/按钮，窗口宽度不撑满，内边距与间距统一压紧
        int padH = Ui.dp(this, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(padH, Ui.dp(this, 16), padH, Ui.dp(this, 6));

        TextView title = new TextView(this);
        title.setText(dlgTitle);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(13);
        input.setHint(hint);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));
        input.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        if (initial != null && !initial.isEmpty()) {
            input.setText(initial);
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
        // 软键盘模式必须在 show 之前进窗口参数，show 之后再设会被忽略
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
        Ui.roundAlertDialog(d);
        // 与白名单列表弹窗同一个宽度/对齐方式（原来固定 300dp 比白名单框窄）
        android.view.Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        applySettingsAlignment(d);
        // 弹窗的窗口拿到焦点后再聚焦输入框并拉起键盘，过早调用会被系统忽略
        input.postDelayed(() -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 300);
        buttons[0].setOnClickListener(v -> {
            hideIme(input);
            d.dismiss();
        });
        buttons[1].setOnClickListener(v -> {
            // 全空格也当作空名：允许不显示文字
            String name = input.getText().toString().trim();
            if (onOk.test(name)) {
                hideIme(input);
                d.dismiss();
            }
        });
    }

    private void hideIme(View v) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    /** 全屏顶部标题：默认「冻结列表」，可被长按改名成空名（不显示文字）。 */
    private String listTitle() {
        return prefs().getString(KEY_LIST_TITLE, "冻结列表");
    }

    /** 「（查看说明）」后缀是否已被该条目说明卡片里的「不再提醒」关掉。 */
    private boolean hintSuffixHidden(String id) {
        return prefs().getBoolean(KEY_HINT_SUFFIX_HIDDEN + id, false);
    }

    /** 设置小标题：主标题主色，「（查看说明）」后缀用次级色并略缩小，整行仍可点。 */
    private CharSequence hintTitle(String title, String id) {
        if (hintSuffixHidden(id)) {
            return title;
        }
        android.text.SpannableString s =
                new android.text.SpannableString(title + "（查看说明）");
        s.setSpan(new android.text.style.ForegroundColorSpan(Ui.wpSecondary(this)),
                title.length(), s.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.8f),
                title.length(), s.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /**
     * 设置页顶部提示行：左侧正文 + 右侧「不再提示」，右侧按钮固定靠右，
     * 多行提示的「不再提示」因此始终对齐；点掉后按 hideKey 永久隐藏该行。
     */
    private View hintRow(CharSequence prefix, String hideKey, List<View> rows) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4));
        TextView body = new TextView(this);
        body.setText(prefix);
        body.setTextSize(11);
        body.setTextColor(getColor(R.color.text_secondary));
        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView never = new TextView(this);
        never.setText("不再提示");
        never.setTextSize(11);
        never.setTextColor(getColor(R.color.blue));
        never.setOnClickListener(v -> {
            prefs().edit().putBoolean(hideKey, true).apply();
            row.setVisibility(View.GONE);
        });
        row.addView(never, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setVisibility(prefs().getBoolean(hideKey, false) ? View.GONE : View.VISIBLE);
        row.setTag(hideKey);
        rows.add(row);
        return row;
    }

    private void applyListTitle() {
        if (titleView != null) {
            titleView.setText(listTitle());
        }
    }

    /** 说明卡标题「xxx」加粗并用主文字色，说明文字用次要色，两者明显区分（同「关于凚」功能名）。 */
    private CharSequence boldHintTitle(String text) {
        android.text.SpannableString s = new android.text.SpannableString(text);
        int start = text.indexOf('「');
        int end = start < 0 ? -1 : text.indexOf('」', start);
        if (start >= 0 && end > start) {
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, end + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.ForegroundColorSpan(Ui.wpPrimary(this)),
                    start, end + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    /** 「（查看说明）」共用说明卡片：左下「不再提示」（永久隐藏该条后缀），右下「返回」。 */
    private void showHintCard(String bodyText, String id, TextView titleView, String baseTitle) {
        int padH = Ui.dp(this, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(padH, Ui.dp(this, 16), padH, Ui.dp(this, 6));

        TextView body = new TextView(this);
        body.setText(boldHintTitle(bodyText));
        body.setTextSize(12);
        body.setTextColor(getColor(R.color.text_secondary));
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView never = hintButton("不再提示", false);
        TextView ok = hintButton("返回", true);
        btnRow.addView(never, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        btnRow.addView(ok, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog d = new AlertDialog.Builder(this)
                .setView(card)
                .create();
        d.show();
        Ui.roundAlertDialog(d);
        applySettingsAlignment(d);
        never.setOnClickListener(v -> {
            // 「不再提示」：该条目标题的「（查看说明）」后缀永久消失，卡片随之关闭
            prefs().edit().putBoolean(KEY_HINT_SUFFIX_HIDDEN + id, true).apply();
            titleView.setText(baseTitle);
            d.dismiss();
        });
        ok.setOnClickListener(v -> d.dismiss());
    }

    /** 说明卡片底部按钮：与设置弹窗同款文字按钮；bold 那颗加粗（右下「返回」）。 */
    private TextView hintButton(String label, boolean bold) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(Ui.dp(this, 44));
        b.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        b.setTextColor(getColor(R.color.blue));
        if (bold) {
            b.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        return b;
    }

    /** 标签标题点开的说明卡片。 */
    private void showTabHintDialog(TextView titleView) {
        showHintCard("「标签设定」\n"
                + "·勾选显示列表；\n"
                + "·取消则隐藏；\n"
                + "·点亮星标，启动优先打开。",
                "tab", titleView, "标签设定");
    }

    /** 「专属LSPosed」标题点开的说明卡片。 */
    private void showLspHintDialog(TextView titleView) {
        String text = "「专属 LSPosed」\n"
                + "·激活后重启，无需常驻后台；\n"
                + "·划卡冻结:滑动最近任务卡片冻结；\n"
                + "·锁屏定时冻结:锁屏计时，亮屏重计；\n"
                + "·消除启动白底:HyperOS4 专属，修复透底冷启动白闪，优化半屏动画。";
        showHintCard(text, "lsp", titleView, "专属LSPosed");
    }

    /** 列表白名单标题点开的说明卡片。 */
    private void showWhitelistHintDialog(TextView titleView) {
        showHintCard("「列表白名单」\n"
                + "·不受底部按钮 / 划卡 / 锁屏冻结；\n"
                + "·长按图标、快捷手势冻结不受限制。",
                "wl", titleView, "列表白名单");
    }

    /** 桌面图标模式标题点开的说明卡片。 */
    private void showSpecialModeHintDialog(TextView titleView) {
        showHintCard("「桌面图标模式」\n"
                + "·默认冻结:能冻则冻，失败降级暂停；\n"
                + "·保留桌面图标:改用系统暂停，图标置灰保留，部分系统组件仍会唤醒。",
                "sp", titleView, "桌面图标模式");
    }

    /** 冻结后是否保留桌面图标：选「是」走系统暂停，选「否」恢复默认冻结。 */
    private void showKeepIconChoiceDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 16);
        col.setPadding(side, Ui.dp(this, 6), side, Ui.dp(this, 2));

        // 标题与「白名单列表」同一套：内容内左对齐，16sp 加粗，不再用系统标题的内缩
        TextView heading = Ui.text(this, "冻结后是否保留桌面图标", 16, Ui.TEXT_PRIMARY, true);
        col.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView yes = new TextView(this);
        yes.setText("保留，切换系统暂停");
        yes.setTextSize(13);
        yes.setTextColor(getColor(R.color.text_primary));
        yes.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        TextView no = new TextView(this);
        no.setText("不保留，恢复默认冻结");
        no.setTextSize(13);
        no.setTextColor(getColor(R.color.text_primary));
        no.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        col.addView(yes);
        col.addView(no);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(col)
                .setNegativeButton("返回", null)
                .create();
        yes.setOnClickListener(v -> {
            applyKeepLauncherIcon(true);
            dialog.dismiss();
        });
        no.setOnClickListener(v -> {
            applyKeepLauncherIcon(false);
            dialog.dismiss();
        });
        dialog.show();
        Ui.roundAlertDialog(dialog);
        applySettingsAlignment(dialog);
    }

    /** 白名单标识位置：左下角 / 右下角，弹窗与白名单列表同宽。 */
    private void showWhiteBadgePosDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 16);
        col.setPadding(side, Ui.dp(this, 6), side, Ui.dp(this, 2));

        // 标题与「白名单列表」同一套：内容内左对齐，16sp 加粗
        TextView heading = Ui.text(this, "标识位置", 16, Ui.TEXT_PRIMARY, true);
        col.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView left = new TextView(this);
        left.setText("左下角");
        left.setTextSize(13);
        left.setTextColor(getColor(R.color.text_primary));
        left.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        TextView right = new TextView(this);
        right.setText("右下角");
        right.setTextSize(13);
        right.setTextColor(getColor(R.color.text_primary));
        right.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        col.addView(left);
        col.addView(right);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(col)
                .setNegativeButton("返回", null)
                .create();
        left.setOnClickListener(v -> {
            applyWhiteBadgePos(false);
            dialog.dismiss();
        });
        right.setOnClickListener(v -> {
            applyWhiteBadgePos(true);
            dialog.dismiss();
        });
        dialog.show();
        Ui.roundAlertDialog(dialog);
        applySettingsAlignment(dialog);
    }

    /** 标识位置入口文案：和白名单入口同一行样式，只显示当前选项。 */
    private String whiteBadgePosText() {
        return "· 标识位置：" + (whiteBadgeRight ? "右下角" : "左下角");
    }

    /** 标识位置落地：换边后立刻重绑列表。 */
    private void applyWhiteBadgePos(boolean right) {
        whiteBadgeRight = right;
        prefs().edit().putBoolean(KEY_WHITE_BADGE_RIGHT, right).apply();
        if (layoutWhitePosEntry != null) {
            layoutWhitePosEntry.setText(whiteBadgePosText());
        }
        rebindCells();
    }

    /** 自定义壁纸：「同步全局」的入口文案（默认不同步）。 */
    private String wpGlobalEntryText() {
        return "· 同步全局：" + (WallpaperSource.globalOn(this) ? "同步" : "不同步");
    }

    /** 与「将壁纸同步长按选项框」同一套弹窗：同步 / 不同步 二选一。 */
    private void showWpGlobalDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 16);
        col.setPadding(side, Ui.dp(this, 2), side, Ui.dp(this, 2));

        TextView sync = new TextView(this);
        sync.setText("同步");
        sync.setTextSize(13);
        sync.setTextColor(getColor(R.color.text_primary));
        sync.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        TextView noSync = new TextView(this);
        noSync.setText("不同步");
        noSync.setTextSize(13);
        noSync.setTextColor(getColor(R.color.text_primary));
        noSync.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        col.addView(sync);
        col.addView(noSync);

        TextView tip = new TextView(this);
        tip.setText("选「同步」后，自定义壁纸会同步到所有界面：设置页、长按图标选择框、快面板、关于凚、更新日志、各种弹窗、各种弹出打字框等。");
        tip.setTextSize(11);
        tip.setTextColor(getColor(R.color.text_secondary));
        tip.setLineSpacing(Ui.dp(this, 2), 1f);
        tip.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        col.addView(tip);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("同步全局")
                .setView(col)
                .setNegativeButton("返回", null)
                .create();
        sync.setOnClickListener(v -> {
            applyWpGlobalSync(true);
            dialog.dismiss();
        });
        noSync.setOnClickListener(v -> {
            applyWpGlobalSync(false);
            dialog.dismiss();
        });
        dialog.show();
        Ui.roundAlertDialog(dialog);
        applySettingsAlignment(dialog);
    }

    /** 「同步全局」开关落地：只存偏好并刷新开着的设置页/快面板底板，之后打开的弹窗自动生效。 */
    private void applyWpGlobalSync(boolean on) {
        prefs().edit().putBoolean(WallpaperSource.KEY_WALLPAPER_GLOBAL, on).apply();
        if (layoutWpGlobalEntry != null) {
            layoutWpGlobalEntry.setText(wpGlobalEntryText());
        }
        refreshGlobalSurfaceBg();
    }

    /** 「同步全局」壁纸/开关变更后，把已经开着的设置页、快面板底板换成同款壁纸（或换回默认底色）。 */
    private void refreshGlobalSurfaceBg() {
        if (settingsSurfaceRoot != null) {
            Ui.applyDialogBg(settingsSurfaceRoot);
            applySettingsSurfaceAlpha(settingsSurfaceRoot);
        }
        if (quickPanelRootView != null) {
            Ui.applyDialogBg(quickPanelRootView);
            applyQuickPanelSurfaceAlpha(quickPanelRootView);
        }
    }

    /** 勾选全局美化同步时的说明卡片：确认才打开，取消保持未勾选；「不再提醒」以后直接点复选框生效。 */
    private void showGlobalBeautyHint(Runnable onConfirm, Runnable onCancel) {
        int padH = Ui.dp(this, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(padH, Ui.dp(this, 16), padH, Ui.dp(this, 6));

        TextView body = new TextView(this);
        body.setText(boldHintTitle("「全局美化同步」\n"
                + "·首次开启以当前列表数据同步。\n"
                + "·同步项:自定义壁纸、列表图标、界面透明度、底部冻结按钮、隐藏标签文字、显示应用名称。"));
        body.setTextSize(12);
        body.setTextColor(getColor(R.color.text_secondary));
        body.setLineSpacing(Ui.dp(this, 2), 1f);
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView never = hintButton("不再提醒", false);
        TextView confirm = hintButton("确认", true);
        TextView cancel = hintButton("返回", false);
        btnRow.addView(never, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        btnRow.addView(confirm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnRow.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog d = new AlertDialog.Builder(this)
                .setView(card)
                .create();
        d.show();
        Ui.roundAlertDialog(d);
        applySettingsAlignment(d);
        // 点弹窗外面/返回键关掉且没点「确认」，按取消处理：勾选要回退成未勾选
        final boolean[] handled = {false};
        d.setOnDismissListener(x -> {
            if (!handled[0]) {
                handled[0] = true;
                onCancel.run();
            }
        });
        never.setOnClickListener(v -> {
            // 不再提醒：以后勾选直接生效，这次按「确认」处理
            handled[0] = true;
            prefs().edit().putBoolean(KEY_HINT_SUFFIX_HIDDEN + "global_beauty", true).apply();
            onConfirm.run();
            d.dismiss();
        });
        confirm.setOnClickListener(v -> {
            handled[0] = true;
            onConfirm.run();
            d.dismiss();
        });
        cancel.setOnClickListener(v -> {
            handled[0] = true;
            onCancel.run();
            d.dismiss();
        });
    }

    /** 全局美化开关落盘并生效：说明弹窗点确认后打开，或不再提醒后直接勾选/取消。 */
    private void setGlobalTheme(boolean on) {
        prefs().edit().putBoolean(KEY_GLOBAL_THEME, on).apply();
        applyGlobalBeauty(on);
    }

    /** 全局美化打开时同步生效，否则只写当前形态。 */
    private void applyGlobalBeauty(boolean on) {
        if (on) {
            pushBeautyToOther();
        }
        refreshGlobalBeautyEntries();
    }

    /** 全局美化打开后，「隐藏半屏/全屏的标签文字」统一显示为「隐藏标签文字」。 */
    private String hideTabRowLabel() {
        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
            return "隐藏标签文字";
        }
        return panelMode ? "隐藏半屏的标签文字" : "隐藏全屏的标签文字";
    }

    /** 隐藏标签文字落盘：全局美化打开时两个形态一起写，否则只写当前形态。 */
    private void setHideTabRowPref(boolean on) {
        SharedPreferences.Editor e = prefs().edit().putBoolean(lk(KEY_HIDE_TAB_ROW), on);
        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
            e.putBoolean(otherKey(KEY_HIDE_TAB_ROW), on);
        }
        e.apply();
    }

    /** 显示应用名称落盘：全局美化打开时两个形态一起写，否则只写当前形态。 */
    private void setShowNamePref(boolean on) {
        SharedPreferences.Editor e = prefs().edit().putBoolean(lk(KEY_SHOW_NAME), on);
        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
            e.putBoolean(otherKey(KEY_SHOW_NAME), on);
        }
        e.apply();
    }

    /** 首次打开全局美化：壁纸、列表图标、界面透明度、底部按钮都以当前形态为准写到另一形态。 */
    private void pushBeautyToOther() {
        SharedPreferences.Editor e = prefs().edit();
        String wpUri = prefs().getString(lk(KEY_WALLPAPER_URI), null);
        if (wpUri == null || wpUri.isEmpty()) {
            e.remove(otherKey(KEY_WALLPAPER_URI));
        } else {
            e.putString(otherKey(KEY_WALLPAPER_URI), wpUri);
        }
        e.putInt(otherKey(KEY_WALLPAPER_BLUR), prefs().getInt(lk(KEY_WALLPAPER_BLUR), 0))
                .putInt(otherKey(KEY_WALLPAPER_DIM),
                        prefs().getInt(lk(KEY_WALLPAPER_DIM), WallpaperSource.BRIGHT_DEFAULT))
                .putBoolean(otherKey(KEY_HIDE_TAB_ROW),
                        prefs().getBoolean(lk(KEY_HIDE_TAB_ROW), false))
                .putBoolean(otherKey(KEY_SHOW_NAME),
                        prefs().getBoolean(lk(KEY_SHOW_NAME), true))
                .putInt(otherKey(KEY_COLUMNS), prefs().getInt(lk(KEY_COLUMNS), COLUMNS))
                .putInt(otherKey(KEY_ICON_DP), prefs().getInt(lk(KEY_ICON_DP), 52))
                .putBoolean(otherKey(KEY_ICON_MANUAL),
                        prefs().getBoolean(lk(KEY_ICON_MANUAL), false))
                .putInt(otherKey(KEY_SETTINGS_SURFACE),
                        prefs().getInt(lk(KEY_SETTINGS_SURFACE), 0))
                .putInt(otherKey(KEY_ICON_ALPHA), prefs().getInt(lk(KEY_ICON_ALPHA), 0));
        if (panelMode) {
            e.putInt(KEY_FULL_SURFACE, prefs().getInt("panel_" + KEY_SURFACE, SURFACE_SOLID));
        } else {
            e.putInt("panel_" + KEY_SURFACE, prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID));
        }
        e.putInt(otherKey(KEY_FAB_SIZE), prefs().getInt(lk(KEY_FAB_SIZE),
                        panelMode ? PANEL_FAB_SIZE_DEFAULT : FAB_SIZE_DEFAULT))
                .putInt(otherKey(KEY_FAB_DX), prefs().getInt(lk(KEY_FAB_DX), 0))
                .putInt(otherKey(KEY_FAB_DY), prefs().getInt(lk(KEY_FAB_DY),
                        panelMode ? PANEL_FAB_DY_DEFAULT : FAB_DY_DEFAULT));
        if (panelMode) {
            e.putInt(KEY_FULL_FAB_SURFACE,
                    prefs().getInt("panel_" + KEY_FAB_SURFACE, PANEL_FAB_SURFACE_DEFAULT));
        } else {
            e.putInt("panel_" + KEY_FAB_SURFACE,
                    prefs().getInt(KEY_FULL_FAB_SURFACE, FULL_FAB_SURFACE_DEFAULT));
        }
        e.apply();
        WallpaperSource.drop(panelMode ? WallpaperSource.MODE_FULL : WallpaperSource.MODE_PANEL);
    }

    /** 全局美化开关变化后，标签文字那一行的文案即时刷新。 */
    private void refreshGlobalBeautyEntries() {
        if (settingsHideTabRowBox != null) {
            settingsHideTabRowBox.setText(hideTabRowLabel());
        }
    }

    /** 全局美化打开时，壁纸的选图 / 清除 / 模糊度改动同样写到另一形态。 */
    private void mirrorWallpaperToOtherIfGlobal() {
        if (!prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
            return;
        }
        SharedPreferences.Editor e = prefs().edit();
        String uri = prefs().getString(lk(KEY_WALLPAPER_URI), null);
        if (uri == null || uri.isEmpty()) {
            e.remove(otherKey(KEY_WALLPAPER_URI));
        } else {
            e.putString(otherKey(KEY_WALLPAPER_URI), uri);
        }
        e.putInt(otherKey(KEY_WALLPAPER_BLUR), prefs().getInt(lk(KEY_WALLPAPER_BLUR), 0));
        e.putInt(otherKey(KEY_WALLPAPER_DIM),
                prefs().getInt(lk(KEY_WALLPAPER_DIM), WallpaperSource.BRIGHT_DEFAULT));
        e.apply();
        WallpaperSource.drop(panelMode ? WallpaperSource.MODE_FULL : WallpaperSource.MODE_PANEL);
    }

    /** 桌面图标模式入口文案：先显示「当前：」，再跟当前选项。 */
    private String keepIconEntryText() {
        return "· 当前：" + (keepLauncherIcon ? "保留，切换系统暂停" : "不保留，恢复默认冻结");
    }

    /** 保留桌面图标开关落地：是 → 冻结转系统暂停；否 → 暂停态转回默认冻结。 */
    private void applyKeepLauncherIcon(boolean on) {
        keepLauncherIcon = on;
        prefs().edit().putBoolean(KEY_KEEP_LAUNCHER_ICON, on).apply();
        if (layoutKeepIconEntry != null) {
            layoutKeepIconEntry.setText(keepIconEntryText());
        }
        if (on) {
            // 已有停用态的应用逐个迁到暂停态，否则图标仍缺失
            migrateFrozenToSuspend();
        } else {
            // 恢复默认冻结：暂停态应用立刻转回停用，图标随之消失
            migrateSuspendedToDisabled();
        }
    }

    /** 手势调节标题点开的说明卡片。 */
    private void showGestureHintDialog(TextView titleView) {
        showHintCard("「手势说明」\n"
                + "·左右滑:切换标签；\n"
                + "·上滑全屏 / 下滑半屏；\n"
                + "·触发距离:dp越大:触发距离越长；\n"
                + "·靠内触发:dp越大:可滑动区域越宽。",
                "gesture", titleView, "手势调节");
    }

    /** 删除自定义标签：只摘掉这个标签的归属，应用还留在其他标签里，不丢数据。 */
    private void confirmDeleteTab(Tab tab, Runnable done) {
        int count = 0;
        for (Item it : items) {
            if (tabsOf(it).contains(tab.id)) {
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
        body.setText("其中的 " + count + " 个应用只从这个标签移出；同时在其他标签里的应用照常保留，不会丢失。");
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
        applySettingsAlignment(del);
        buttons[0].setOnClickListener(v -> del.dismiss());
        buttons[1].setOnClickListener(v -> {
            // 只摘掉被删标签的归属，应用还留在其他标签里
            List<String> back = new ArrayList<>();
            for (Map.Entry<String, java.util.LinkedHashSet<String>> e : appTabs.entrySet()) {
                e.getValue().remove(tab.id);
                if (e.getValue().isEmpty()) {
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
        setEditMode(on, false);
    }

    /** unfreeze=true 时长按减号选的「移除列表并解冻」变体：点红标既移出名单也解冻。 */
    private void setEditMode(boolean on, boolean unfreeze) {
        editMode = on;
        editUnfreeze = on && unfreeze;
        // 减号只做移除模式开关，不再叠蓝色高亮
        editBtn.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.text_primary)));
        render();
        updateFabDone();
    }

    /** 长按右上角减号：选一种批量操作方式，进入移除模式后逐个点红标处理。 */
    private void showBatchRemoveDialog() {
        if (working) {
            return;
        }
        List<Item> shown = filtered();
        if (shown.isEmpty()) {
            toast("当前列表为空");
            return;
        }
        // 与长按图标弹出的选项框同一套样式：左图标右文字
        java.util.List<AppActionSheet.TextItem> items = new java.util.ArrayList<>();
        items.add(new AppActionSheet.TextItem(R.drawable.ic_minus, "从列表移除", null,
                () -> startBatchRemove(false)));
        items.add(new AppActionSheet.TextItem(R.drawable.ic_action_circle_unlock, "移除列表并解冻", null,
                () -> startBatchRemove(true)));
        int sheetAlpha = panelMode
                ? prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID)
                : prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID);
        AppActionSheet.showTextMenu(this, "批量操作当前标签",
                srcMode(), sheetAlpha, items);
    }

    /** 选完批量操作方式后进入移除模式：图标右上角出红标，逐个点击处理。 */
    private void startBatchRemove(boolean unfreeze) {
        setEditMode(true, unfreeze);
        toast(unfreeze
                        ? "点图标右上角红色减号逐个移除并解冻"
                        : "点图标右上角红色减号逐个移除");
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
                it.label += cloneTag(userId);
            }
            applyCustomLabel(it);
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
        if (!reloadRunning.compareAndSet(false, true)) {
            // 当前刷新尚未落地时只记一次待刷新；期间新增的重复调用不再排队执行。
            reloadQueued.set(true);
            return;
        }
        // 名单是白名单/关联的唯一依据：先把已经不在名单里的条目清掉
        pruneOrphans();
        Set<String> wanted = pkgs();
        List<String> order = savedOrder();
        boolean custom = customOrder();
        // 名单可能在另一个形态的实例里被增删（半屏 ↔ 全屏各有一份内存列表）：
        // 回到前台先把过期的内存列表换成磁盘上的当前名单，否则第一帧会闪出已被移除的应用，
        // 等下面后台真值回来才纠正。这里只用 PackageInfo 和图标缓存，不跑 shell。
        Set<String> shownKeys = new HashSet<>();
        for (Item it : items) {
            shownKeys.add(it.pkg);
        }
        if (!items.isEmpty() && !shownKeys.equals(wanted)) {
            items.clear();
            if (!wanted.isEmpty()) {
                items.addAll(quickItems(wanted, order, custom));
            }
            render();
        }
        // 冷启动首帧：不查 shell（su/pm 命令要几百毫秒），先用 PackageInfo 自带状态画出来，
        // 后台真值回来后再刷一次，避免白屏等命令。
        if (!loadedOnce && items.isEmpty() && !wanted.isEmpty()) {
            items.addAll(quickItems(wanted, order, custom));
            render();
        }
        // 首帧已经画出的图标：正式刷新重建 Item 时直接沿用，避免整表闪成占位图再补回来
        final Map<String, Drawable> iconMemo = new HashMap<>();
        for (Item it : items) {
            if (it.icon != null) {
                iconMemo.put(it.pkg, it.icon);
            }
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
                    if (it.icon == null) {
                        // 首帧已经画过这个包：沿用同一张图标，整表不闪
                        it.icon = iconMemo.get(key);
                    }
                    it.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                } else {
                    it.label = pkg;
                }
                if (userId == 0) {
                    it.suspended = suspended.contains(pkg);
                    // 兜底口径：暂停包 enabled 仍为 true，必须把暂停缓存一并算进冻结态
                    it.frozen = truthOk ? (disabled.contains(pkg) || it.suspended)
                            : ((ai != null && !ai.enabled) || it.suspended);
                } else {
                    Set<String> cloneTruth = cloneDisabled.get(userId);
                    it.frozen = cloneTruth != null ? cloneTruth.contains(pkg)
                            : (ai != null && !ai.enabled);
                }
                if (userId != 0) {
                    it.label += cloneTag(userId);
                }
                applyCustomLabel(it);
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
                boolean rerun = reloadQueued.getAndSet(false);
                reloadRunning.set(false);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (rerun) {
                    // 后台期间又收到刷新：直接按最新名单再跑一次，跳过本次过期渲染。
                    // 已退到后台时不主动补跑，onResume 会统一刷新。
                    if (!wasStopped) {
                        reload();
                    }
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
                // 冻结设置成「后收起」时，等这一轮状态刷新落地再关页面
                if (dismissAfterReload) {
                    dismissAfterReload = false;
                    closeSelf();
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

    /** 名称、包名、多用户后缀命中；中文名称再按拼音首字母命中（如 wx → 微信）。 */
    private boolean matchesQuery(Item it, String q) {
        if (it.label.toLowerCase(Locale.ROOT).contains(q)
                || it.pkg.toLowerCase(Locale.ROOT).contains(q)
                || (it.userId != 0 && String.valueOf(it.userId).contains(q))) {
            return true;
        }
        if (it.py == null) {
            it.py = Pinyin.initials(it.label);
        }
        return !it.py.isEmpty() && it.py.contains(q);
    }

    /** 当前标签 + 搜索取交集；标签内渲染与「仅当前标签」的批量操作都走这里。 */
    private List<Item> filtered() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Item> shown = new ArrayList<>();
        for (Item it : items) {
            if (!inTab(it, currentTabId)) {
                continue;
            }
            if (!q.isEmpty() && !matchesQuery(it, q)) {
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
     * 批量处理范围（底部一键冻结 / 长按解冻 / 多选等共用）：
     * 勾了「底部按钮仅冻/解当前列表」时只取当前标签，否则取整份名单；
     * 两种情况都保留搜索过滤。
     */
    private List<Item> batchTargets() {
        if (batchCurrentTabOnly) {
            return filtered();
        }
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Item> shown = new ArrayList<>();
        for (Item it : items) {
            if (!q.isEmpty() && !matchesQuery(it, q)) {
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
        String tabId = landingTabOf(added);
        Tab tab = findTab(tabId);
        if (tab == null || !tab.visible) {
            toast("已加入名单，但所在标签已隐藏");
            return;
        }
        if (!tabId.equals(currentTabId)) {
            // 实际类型与发起标签不一致：切到新项真正所属的标签，并说明去了哪
            currentTabId = tabId;
            prefs().edit().putString(KEY_CURRENT_TAB, tabId).apply();
            renderTabBar();
            setSysHintVisible();
            render();
            toast("已加入「" + tab.name + "」");
        }
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty() && !matchesQuery(added, q)) {
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
            // 当前标签（叠加搜索）为空就显示居中空态卡片；全屏与半屏口径一致
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
                // 全屏改用居中的空态卡片提示，这行小字不再显示；半屏布局没有 count 视图，保持原样
                hint = panelMode ? "名单为空，点右上角「+」添加应用" : null;
            } else if (empty) {
                hint = panelMode ? "当前筛选下没有应用" : null;
            } else if (editMode) {
                hint = editUnfreeze ? "编辑模式：点图标右上角减号移出名单并解冻"
                        : "编辑模式：点图标右上角减号移出名单";
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
        // 空列表也保持可用：长按仍要能触发一键解冻图标，点击在监听里拦掉
        freezeBtn.setEnabled(!working);
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
        // 半灰半彩是绘制期效果，用专用 ImageView 才能只给左半边套滤镜
        HalfGrayImageView icon = new HalfGrayImageView(this);
        icon.setGrayFilter(GRAY_SOFT);
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

        // 白名单标识：贴图标左下角，和右下角的暂停标识分开
        ImageView whiteBadge = new ImageView(this);
        whiteBadge.setImageResource(R.drawable.ic_whitelist_badge);
        FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 14), Ui.dp(this, 14));
        wLp.gravity = Gravity.BOTTOM | Gravity.START;
        wLp.bottomMargin = Ui.dp(this, 1);
        wLp.leftMargin = Ui.dp(this, 1);
        whiteBadge.setLayoutParams(wLp);
        whiteBadge.setVisibility(View.GONE);
        iconBox.addView(whiteBadge);
        box.addView(iconBox);

        TextView name = Ui.text(this, "", 11, Ui.TEXT_PRIMARY, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);

        // 雪花和名称放进同一行整体居中：短名称时雪花紧贴文字，
        // 不会像 TextView 左复合图标那样被钉在格子最左边
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER);
        ImageView snow = new ImageView(this);
        snow.setImageResource(R.drawable.ic_sc_freeze);
        snow.setVisibility(View.GONE);
        nameRow.addView(snow, new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)));
        nameRow.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams nameRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameRowLp.topMargin = Ui.dp(this, 6);
        box.addView(nameRow, nameRowLp);
        nameRow.setVisibility(showName ? View.VISIBLE : View.GONE);

        final Cell cell = new Cell();
        cell.root = box;
        cell.iconBox = iconBox;
        cell.icon = icon;
        cell.name = name;
        cell.nameRow = nameRow;
        cell.snow = snow;
        cell.badge = badge;
        cell.suspendBadge = suspendBadge;
        cell.whiteBadge = whiteBadge;
        // 把 Cell 挂到 root 上：多选态要靠它反查可见格子的抖动/选中圈
        box.setTag(cell);
        // 监听器不绑定具体应用：拖拽换位后只重绑数据，点击/长按跟着当前数据走
        badge.setOnClickListener(v -> {
            if (cell.item == null) {
                return;
            }
            // 长按减号选「移除列表并解冻」时点红标连带解冻；其余情况只移出名单（同步重建划卡名单）
            if (editMode && editUnfreeze) {
                removeAndUnfreeze(cell.item);
            } else {
                removeFromList(cell.item);
            }
        });
        box.setOnClickListener(v -> {
            Item cur = cell.item;
            // 全屏减号（移除模式）与半屏「快捷移除图标」都只响应红标，点图标本体不打开应用
            if (cur == null || editMode || (panelMode && quickRemove)) {
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
                    // 手指移动超过系统触摸抖动阈值时下面会取消长按计时，避免误弹菜单。
                    // 只有按在图标方块上才计长按；格子留白/名称行长按留给「长按空白滑动切标签」；
                    // 勾了边缘触发后，边缘条带内的长按让给切形态手势，不弹图标操作单；
                    // 全屏底栏预留区不触发图标长按（快面板开关开时归快面板，关时也不弹操作单）
                    if (onAppIcon(cell.downX, cell.downY) && !edgeReserved(cell.downX)
                            && !inFullBottomBar(cell.downX, cell.downY)) {
                        v.postDelayed(cell.longPress, LONG_PRESS_MS);
                    }
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

        /**
         * 整表失效：连同滑出屏幕、被 RecyclerView 视图缓存持有的旧格子一起标脏。
         * 样式这类对所有格子生效的改动必须走这里——只重绑屏幕上的格子时，
         * 缓存视图回到屏幕会直接复用旧绑定，看起来就是「被弹窗挡住的区域没刷新」。
         */
        void rebindAllCached() {
            notifyDataSetChanged();
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
            cell.icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        cell.name.setText(it.label);
        // 应用名直接落在壁纸上：有壁纸就按壁纸明暗自动白/黑
        cell.name.setTextColor(wpPageFg(getColor(R.color.text_primary)));
        cell.root.setContentDescription(it.label);
        // 编辑模式（全屏减号）或半屏勾选「快捷移除图标」时常驻红标；移动模式下让位给选中/拖动
        boolean showBadge = editMode || (panelMode && quickRemove && !moveMode);
        cell.badge.setVisibility(showBadge ? View.VISIBLE : View.GONE);
        // 暂停标识只跟随状态：正常 disable-user 冻结的图标不加任何标识
        cell.suspendBadge.setVisibility(it.suspended ? View.VISIBLE : View.GONE);
        // 冻结图标样式：0=全彩（名称左侧补雪花），1..8=按方向灰化半边
        int splitStyle = it.frozen ? iconStyle : 0;
        cell.icon.clearColorFilter();
        // 列表图标透明度：0 不透明，100 全透明
        cell.icon.setAlpha(iconAlpha <= 0 ? 1f : (100 - iconAlpha) / 100f);
        if (cell.icon instanceof HalfGrayImageView) {
            ((HalfGrayImageView) cell.icon).setSplitStyle(splitStyle);
        }
        boolean white = isWhitelisted(it.pkg);
        if (cell.snow != null) {
            // 全彩：冻结时名称左侧显示雪花；白名单标识统一挪到图标左下角（见下面的 whiteBadge）
            boolean showRow = !white && it.frozen && splitStyle == 0;
            cell.snow.setImageResource(R.drawable.ic_sc_freeze);
            cell.snow.setVisibility(showRow ? View.VISIBLE : View.GONE);
        }
        if (cell.whiteBadge != null) {
            cell.whiteBadge.setVisibility(white ? View.VISIBLE : View.GONE);
            if (white) {
                // 白名单标识贴左下角：图标位图自带一圈透明边，按位图里看得见的圆角来算，
                // 让蓝色圆的边正好卡在图标圆角内侧，不越过圆角
                int iconPx = computedIconPx();
                int artInset = Math.round(iconPx * 0.06f);
                int artCorner = Math.round(iconPx * 0.235f);
                int size = Math.min(Ui.dp(this, 18), Math.round(iconPx * 0.42f));
                int r = Math.round(size * 5 / 12f);
                int center = artCorner - (int) Math.floor((artCorner - r) / 1.414f);
                int inset = artInset + center - size / 2;
                FrameLayout.LayoutParams wlp =
                        (FrameLayout.LayoutParams) cell.whiteBadge.getLayoutParams();
                int side = whiteBadgeRight ? Gravity.END : Gravity.START;
                int leftMargin = whiteBadgeRight ? 0 : inset;
                int rightMargin = whiteBadgeRight ? inset : 0;
                if (wlp != null && (wlp.width != size || wlp.gravity != (Gravity.BOTTOM | side)
                        || wlp.leftMargin != leftMargin || wlp.rightMargin != rightMargin
                        || wlp.bottomMargin != inset)) {
                    wlp.width = size;
                    wlp.height = size;
                    wlp.gravity = Gravity.BOTTOM | side;
                    wlp.leftMargin = leftMargin;
                    wlp.rightMargin = rightMargin;
                    wlp.bottomMargin = inset;
                    cell.whiteBadge.setLayoutParams(wlp);
                }
            }
            // 谁占了右下角，暂停标识就让到另一角，互不遮挡
            if (cell.suspendBadge != null
                    && cell.suspendBadge.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams sLp =
                        (FrameLayout.LayoutParams) cell.suspendBadge.getLayoutParams();
                boolean toLeft = white && it.suspended && whiteBadgeRight;
                sLp.gravity = toLeft
                        ? (Gravity.BOTTOM | Gravity.START) : (Gravity.BOTTOM | Gravity.END);
                sLp.leftMargin = toLeft ? Ui.dp(this, 1) : 0;
                sLp.rightMargin = toLeft ? 0 : Ui.dp(this, 1);
                cell.suspendBadge.setLayoutParams(sLp);
            }
        }
        if (cell.name != null) {
            // 名称可用宽度跟着格子宽度走：过长省略，雪花始终贴着文字
            cell.name.setMaxWidth(Math.max(Ui.dp(this, 24),
                    computedCellPx() - Ui.dp(this, 8) - Ui.dp(this, 10)));
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
        cell.nameRow.setVisibility(showName ? View.VISIBLE : View.GONE);
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
            toast("已移动到「" + cur.label + "」前面");
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

    /** 长按不动松手：把应用移动到某个标签；要同时放在多个标签请走「添加应用」。 */
    private void showMoveDialog(Item it) {
        if (tabs.size() <= 1) {
            toast("只有一个标签，先在布局设置里新建标签");
            return;
        }
        Set<String> mine = tabsOf(it);
        List<MoveTabDialog.Tab> entries = new ArrayList<>();
        for (Tab t : tabs) {
            entries.add(new MoveTabDialog.Tab(t.id, t.name, t.visible, mine.contains(t.id)));
        }
        MoveTabDialog.show(this, it.label, it.icon, entries, target -> {
            Tab t = findTab(target.id);
            if (t == null) {
                return true;
            }
            // 用户应用不允许进入内置的「系统应用」标签
            if (!it.system && TAB_SYSTEM.equals(t.id)) {
                toast("用户应用禁止移入系统应用标签");
                return false;
            }
            if (mine.size() == 1 && mine.contains(t.id)) {
                toast("已在「" + t.name + "」");
                return true;
            }
            // 移动语义：覆盖原有归属，应用不会再留在原标签
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            set.add(t.id);
            appTabs.put(it.pkg, set);
            saveAppTabs();
            render();
            toast("已移动到「" + t.name + "」");
            return true;
        });
    }

    // ---------------- 长按底部操作单 ----------------

    /** 多选入口：长按菜单里点了「多选」→ sheet dismiss 后调用；进入多选态。 */
    private void enterSelectMode() {
        selectMode = true;
        selectedPkgs.clear();
        toast("已进入多选模式：点图标选择，长按已选图标操作");
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
                    : androidx.core.content.ContextCompat.getDrawable(this,
                    android.R.drawable.sym_def_app_icon));
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

    /** 给 iconBox 加选中圈：主色描边 + 半透明主色底，外面再垫一圈白色描边，淡色与深色图标上都能看清。 */
    private void addSelectRing(Cell c) {
        if (c.iconBox == null) return;
        if (c.selectRing != null) return;
        View ring = new View(this);
        int strokePx = Math.max(2, (int) (3f * getResources().getDisplayMetrics().density));
        int radius = Math.max(Ui.dp(this, 6), (int) (computedIconPx() * 0.18f));
        int primary = Ui.primary(this);

        // 内层：主色细环 + 很浅的主色底
        android.graphics.drawable.GradientDrawable inner =
                new android.graphics.drawable.GradientDrawable();
        inner.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        inner.setColor((primary & 0x00FFFFFF) | 0x33000000);
        inner.setCornerRadius(radius);
        inner.setStroke(strokePx, primary);

        // 外层：白色描边垫底，比主色环宽 2dp，露出一圈白边；
        // 解冻状态的应用图标是原色（经常偏黑），只有主色蓝环时几乎看不见。
        android.graphics.drawable.GradientDrawable halo =
                new android.graphics.drawable.GradientDrawable();
        halo.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        halo.setColor(0x00000000);
        halo.setCornerRadius(radius);
        halo.setStroke(strokePx + Math.max(2, (int) (2f * getResources().getDisplayMetrics().density)),
                0xFFFFFFFF);

        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{inner, halo});
        ring.setBackground(layers);
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
                // 长按图标菜单触发：提示走系统默认 Toast
                if (it.frozen) {
                    unfreezeSingle(it, true);
                } else {
                    freezeSingle(it, true);
                }
            }

            @Override
            public void moveTab() {
                showMoveDialog(it);
            }

            @Override
            public boolean moveIconEnabled() {
                return true;
            }

            @Override
            public void startMoveMode() {
                setMoveMode(true);
                toast("移动模式：点一下选中，再点目标位置放置，返回键退出");
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
            public boolean whitelistEnabled() {
                return true;
            }

            @Override
            public boolean whitelisted() {
                return isWhitelisted(it.pkg);
            }

            @Override
            public void toggleWhitelist() {
                boolean add = !isWhitelisted(it.pkg);
                setWhitelisted(it.pkg, add);
                refreshWhitelistEntry();
                toast((add ? "已加入冻结白名单：" : "已移出冻结白名单：") + it.label);
            }

            @Override
            public boolean quickRemoveEnabled() {
                return panelMode;
            }

            @Override
            public boolean quickRemoveOn() {
                return quickRemove;
            }

            @Override
            public void toggleQuickRemove() {
                setQuickRemove(!quickRemove);
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

            @Override
            public boolean renameEnabled() {
                return true;
            }

            @Override
            public void renameApp() {
                showRenameAppDialog(it);
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
                WallpaperSource.sheetBgOn(this)
                        ? WallpaperSource.sheetBitmap(this, srcMode()) : null;
        final boolean onWallpaper = wallpaper != null;
        final int wpDim = WallpaperSource.brightnessPct(this, srcMode());
        final int fg = onWallpaper ? Ui.wallpaperFg(wallpaper, wpDim) : Ui.TEXT_PRIMARY;
        final int iconFg = onWallpaper ? Ui.wallpaperFg(wallpaper, wpDim) : Ui.primary(this);
        final int lineColor = onWallpaper ? Ui.wallpaperDivider(wallpaper, wpDim) : Ui.color(this, R.color.divider);

        final com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_Jin_Sheet);

        android.widget.FrameLayout outer = new android.widget.FrameLayout(this);
        outer.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 56));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Drawable cardBg;
        if (onWallpaper) {
            cardBg = Ui.wallpaperWithScrim(wallpaper, wpDim);
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
                    : androidx.core.content.ContextCompat.getDrawable(this,
                    android.R.drawable.sym_def_app_icon));
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
        // 冻结白名单：选中项全在白名单时显示「移出白名单」，否则「添加到白名单」
        boolean whiteAll = true;
        for (Item it : batchTargets) {
            if (!isWhitelisted(it.pkg)) {
                whiteAll = false;
                break;
            }
        }
        final boolean allWhite = whiteAll;
        batchItem(allWhite ? R.drawable.ic_minus : R.drawable.ic_action_whitelist,
                allWhite ? "移出白名单" : "添加到白名单",
                () -> batchWhitelist(!allWhite), sheet, fg, iconFg, content, true);
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
            toast("正在执行其他操作，请稍后再试");
            return;
        }
        List<Item> todo = new ArrayList<>();
        int whiteSkipped = 0;
        for (Item it : batchTargets) {
            if (it.frozen) {
                continue;
            }
            if (isWhitelisted(it.pkg)) {
                whiteSkipped++;
                continue;
            }
            todo.add(it);
        }
        if (todo.isEmpty()) {
            toast(whiteSkipped > 0
                            ? "选中项都在冻结白名单中，不会冻结"
                            : "选中项都已经是冻结状态");
            exitSelectMode();
            return;
        }
        final int targetCount = todo.size();
        working = true;
        exitSelectMode();
        toast("正在冻结 " + targetCount + " 个应用…");
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
                    if (!ok && (SuspendSupport.romBlocked(r.out) || DhizukuGate.only(this))) {
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
                toast("已冻结 " + doneN + " 个应用"
                        + (doneN < targetCount ? "，失败 " + (targetCount - doneN) + " 个" : ""));
            });
        });
    }

    /** 批量解冻：IO 线程拼 pm 脚本一次性执行，跑完回读暂停缓存再 reload。 */
    private void batchUnfreeze() {
        if (working) {
            toast("正在执行其他操作，请稍后再试");
            return;
        }
        List<Item> frozen = new ArrayList<>();
        for (Item it : batchTargets) {
            if (it.frozen) {
                frozen.add(it);
            }
        }
        if (frozen.isEmpty()) {
            toast("选中项都不是冻结状态");
            exitSelectMode();
            return;
        }
        final int targetCount = frozen.size();
        working = true;
        exitSelectMode();
        toast("正在解冻 " + targetCount + " 个应用…");
        IO.execute(() -> {
            StringBuilder script = new StringBuilder();
            Set<String> suspended = new HashSet<>();
            boolean dhizuku = DhizukuGate.only(this);
            int dhizukuCount = 0;
            for (Item it : frozen) {
                String name = packagePart(it.pkg);
                if (it.suspended && it.userId == 0) {
                    suspended.add(name);
                }
                if (dhizuku && it.userId == 0) {
                    // Dhizuku-only：逐包走 DPM 恢复暂停（DO 只作用 user 0）
                    SuspendSupport.unsuspend(name);
                    dhizukuCount++;
                    continue;
                }
                script.append("pm unsuspend --user ").append(it.userId).append(' ')
                        .append(name).append(" >/dev/null 2>&1; ");
                script.append("pm enable --user ").append(it.userId).append(' ')
                        .append(name).append(" >/dev/null 2>&1; ");
            }
            if (dhizukuCount > 0) {
                recordDiagOp("dhizuku:setPackagesSuspended",
                        "setPackagesSuspended(*, false) × " + dhizukuCount, null);
            }
            if (script.length() > 0) {
                execPmScript(script.toString(), Math.max(30, 10 + frozen.size() * 5));
            }
            Set<String> truth = SuspendSupport.suspendedAmong(suspended);
            SuspendSupport.remember(this, truth);
            main.post(() -> {
                working = false;
                reload();
                toast("已解冻 " + targetCount + " 个应用");
            });
        });
    }

    /** 批量加标签：选中项全部加入目标标签，已在里面的应用保留原有归属、不会重复。 */
    private void batchMoveTab() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : tabs) {
            boolean all = true;
            for (Item it : batchTargets) {
                if (!inTab(it, t.id)) {
                    all = false;
                    break;
                }
            }
            if (!all) {
                targets.add(t);
            }
        }
        if (targets.isEmpty()) {
            toast("选中项已全部在这些标签里");
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
                .setTitle("把 " + n + " 个应用加入…")
                .setItems(names, (d, w) -> {
                    Tab target = targets.get(w);
                    // 用户应用不允许进入内置的「系统应用」标签
                    if (TAB_SYSTEM.equals(target.id)) {
                        for (Item it : batchTargets) {
                            if (!it.system) {
                                toast("用户应用禁止移入系统应用标签");
                                return;
                            }
                        }
                    }
                    int added = 0;
                    for (Item it : batchTargets) {
                        if (addAppToTab(it.pkg, target.id, it.system)) {
                            added++;
                        }
                    }
                    saveAppTabs();
                    exitSelectMode();
                    render();
                    toast("已加入 " + added + " 个到「" + target.name + "」");
                })
                .setNegativeButton("取消", (d, w) -> exitSelectMode())
                .setOnCancelListener(d -> exitSelectMode())
                .create();
        pick.show();
        Ui.roundAlertDialog(pick);
    }

    /**
     * 批量添加到桌面：交给 ShortcutPin 的串行队列，等上一个确认结果再发下一个。
     * 早先在一个循环里连续 requestPinShortcut，桌面确认页会互相顶掉，只有前面几个能加成功。
     */
    private void batchPinHome() {
        List<String> pkgs = new ArrayList<>();
        for (Item it : batchTargets) {
            // 实例键直接进队列，主应用与分身各自一个快捷方式
            pkgs.add(it.pkg);
        }
        final int n = pkgs.size();
        exitSelectMode();
        ShortcutPin.pinAll(this, pkgs, () -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (n > 1) {
                toast("已逐个请求添加 " + n + " 个，请在桌面确认");
            }
        });
    }

    /**
     * 批量从列表移除 / 移除并解冻。
     * @param alsoUnfreeze true = 移除后顺便解冻
     */
    private void batchRemove(boolean alsoUnfreeze) {
        if (working) {
            toast("正在执行其他操作，请稍后再试");
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
        int fullCount = 0;
        int tabOnly = 0;
        for (Item it : targets) {
            // 单纯「从列表移除」且还在其它标签页：只移出当前标签；
            // 「移除并解冻」是要真的解冻，必须把所有标签页里的归属一起清掉
            if (!alsoUnfreeze && inOtherTabs(it)) {
                removeAppFromTab(it.pkg, currentTabId);
                // 不动内存 items：整份名单仍要留给「用户应用」等标签用
                tabOnly++;
                continue;
            }
            cur.remove(it.pkg);
            appTabs.remove(it.pkg);
            order.remove(it.pkg);
            items.remove(it);
            fullCount++;
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
                boolean dhizuku = DhizukuGate.only(this);
                int dhizukuCount = 0;
                for (Item item : toUnfreeze) {
                    String name = packagePart(item.pkg);
                    if (dhizuku && item.userId == 0) {
                        // Dhizuku-only：逐包走 DPM 恢复暂停（DO 只作用 user 0）
                        SuspendSupport.unsuspend(name);
                        dhizukuCount++;
                        continue;
                    }
                    script.append("pm unsuspend --user ").append(item.userId).append(' ')
                            .append(name).append(" >/dev/null 2>&1; ");
                    script.append("pm enable --user ").append(item.userId).append(' ')
                            .append(name).append(" >/dev/null 2>&1; ");
                }
                if (dhizukuCount > 0) {
                    recordDiagOp("dhizuku:setPackagesSuspended",
                            "setPackagesSuspended(*, false) × " + dhizukuCount, null);
                }
                if (script.length() > 0) {
                    execPmScript(script.toString(), Math.max(30, 10 + toUnfreeze.size() * 5));
                }
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
        String tabName = tabNameOf(currentTabId);
        String tip;
        if (fullCount == 0) {
            tip = "已从「" + tabName + "」移除 " + tabOnly + " 个应用，其它标签页仍保留";
        } else if (tabOnly == 0) {
            tip = (alsoUnfreeze ? "已移除并解冻 " : "已从列表移除 ") + fullCount + " 个应用";
        } else {
            tip = "已从「" + tabName + "」移除 " + tabOnly + " 个，另 " + fullCount + " 个已从列表移除";
        }
        toast(tip);
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

    // ---------------- 主副应用关联 ----------------

    /** 一组主副应用关联；enabled=false 表示这组已暂停、联动不生效。 */
    private static final class Pair {
        String main;
        String sub;
        boolean enabled;

        Pair(String main, String sub, boolean enabled) {
            this.main = main;
            this.sub = sub;
            this.enabled = enabled;
        }
    }

    /** 全部关联（含暂停的），按行存放：「主键=副键」或「主键=副键|off」。 */
    private List<Pair> allPairs() {
        List<Pair> out = new ArrayList<>();
        String raw = prefs().getString(KEY_SUB_PAIRS, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0 || eq >= line.length() - 1) {
                continue;
            }
            String main = line.substring(0, eq);
            String value = line.substring(eq + 1);
            boolean enabled = true;
            if (value.endsWith("|off")) {
                value = value.substring(0, value.length() - 4);
                enabled = false;
            }
            if (value.isEmpty() || value.equals(main)) {
                continue;
            }
            out.add(new Pair(main, value, enabled));
        }
        return out;
    }

    private void persistPairs(List<Pair> list) {
        StringBuilder sb = new StringBuilder();
        for (Pair p : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p.main).append('=').append(p.sub);
            if (!p.enabled) {
                sb.append("|off");
            }
        }
        prefs().edit().putString(KEY_SUB_PAIRS, sb.toString()).apply();
        refreshPairEntry();
    }

    // ---------------- 冻结白名单 ----------------

    /** 白名单里的实例键集合（主用户纯包名、分身带 @用户）。 */
    private Set<String> whitelistSet() {
        Set<String> raw = prefs().getStringSet(KEY_WHITELIST, null);
        return raw == null ? new HashSet<>() : new HashSet<>(raw);
    }

    private boolean isWhitelisted(String key) {
        return key != null && whitelistSet().contains(key);
    }

    private void setWhitelisted(String key, boolean on) {
        if (key == null) {
            return;
        }
        Set<String> cur = whitelistSet();
        if (on) {
            cur.add(key);
        } else {
            cur.remove(key);
        }
        prefs().edit().putStringSet(KEY_WHITELIST, cur).apply();
        // 白名单变化要同步给 LSPosed 侧的划卡/锁屏冻结，白名单应用一律不冻
        SwipeFreeze.syncAsync(this);
        // 列表格子的白名单标识随状态即时刷新
        rebindCells();
    }

    /** 多选批量加入 / 移出白名单；列表图标本身不变，取消靠长按菜单或白名单列表。 */
    private void batchWhitelist(boolean add) {
        Set<String> cur = whitelistSet();
        int n = 0;
        for (Item it : batchTargets) {
            if (add) {
                if (cur.add(it.pkg)) {
                    n++;
                }
            } else {
                if (cur.remove(it.pkg)) {
                    n++;
                }
            }
        }
        prefs().edit().putStringSet(KEY_WHITELIST, cur).apply();
        SwipeFreeze.syncAsync(this);
        refreshWhitelistEntry();
        rebindCells();
        toast((add ? "已加入白名单 " : "已移出白名单 ") + n + " 个应用");
    }

    private void refreshWhitelistEntry() {
        if (layoutWhitelistEntry != null) {
            layoutWhitelistEntry.setText("· 白名单（" + whitelistSet().size() + "个）");
        }
    }

    /** 只返回生效中的映射：暂停的关联在这里查不到，冻结/解冻联动自然不触发。 */
    private Map<String, String> subPairs() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Pair p : allPairs()) {
            if (p.enabled) {
                map.put(p.main, p.sub);
            }
        }
        return map;
    }

    private String subOf(String mainKey) {
        if (mainKey == null) {
            return null;
        }
        String sub = subPairs().get(mainKey);
        return sub == null || sub.equals(mainKey) ? null : sub;
    }

    private void saveSubPair(String mainKey, String subKey) {
        List<Pair> list = allPairs();
        if (subKey == null || subKey.equals(mainKey)) {
            list.removeIf(p -> p.main.equals(mainKey));
        } else {
            boolean replaced = false;
            for (Pair p : list) {
                if (p.main.equals(mainKey)) {
                    p.sub = subKey;
                    p.enabled = true;
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                list.add(new Pair(mainKey, subKey, true));
            }
        }
        persistPairs(list);
    }

    private void setPairEnabled(String mainKey, boolean enabled) {
        List<Pair> list = allPairs();
        for (Pair p : list) {
            if (p.main.equals(mainKey)) {
                p.enabled = enabled;
            }
        }
        persistPairs(list);
    }

    private void removePair(String mainKey) {
        List<Pair> list = allPairs();
        list.removeIf(p -> p.main.equals(mainKey));
        persistPairs(list);
    }

    private void refreshPairEntry() {
        if (layoutPairEntry != null) {
            layoutPairEntry.setText(pairSummary());
        }
    }

    /**
     * 白名单与关联应用只认冻结列表里还在的应用。
     * 应用还留在任意一个标签页（即仍在名单里）就保留；从所有标签页移除后，
     * 对应的白名单条目与主副关联一起摘掉。恢复备份、卸载清理后同样靠这里兜底。
     */
    private void pruneOrphans() {
        Set<String> live = pkgs();
        boolean whiteChanged = false;
        boolean pairChanged = false;

        Set<String> white = whitelistSet();
        if (white.retainAll(live)) {
            prefs().edit().putStringSet(KEY_WHITELIST, white).apply();
            whiteChanged = true;
        }

        List<Pair> pairs = allPairs();
        List<Pair> kept = new ArrayList<>();
        for (Pair p : pairs) {
            if (live.contains(p.main) && live.contains(p.sub)) {
                kept.add(p);
            } else {
                pairChanged = true;
            }
        }
        if (pairChanged) {
            persistPairs(kept);
        }
        if (whiteChanged || pairChanged) {
            // 名单同步给 LSPosed 侧，白名单应用一律不冻
            SwipeFreeze.syncAsync(this);
            refreshWhitelistEntry();
            refreshPairEntry();
            rebindCells();
        }
    }

    /** 实例键对应的显示名：优先用列表里的名称，不在列表里就问系统要应用名。 */
    private String labelFor(String key) {
        for (Item it : items) {
            if (it.pkg.equals(key)) {
                return it.label;
            }
        }
        int uid = userIdPart(key);
        String suffix = uid == 0 ? "" : cloneTag(uid);
        String custom = customName(key);
        if (!custom.isEmpty()) {
            return custom + suffix;
        }
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(packagePart(key), 0);
            return getPackageManager().getApplicationLabel(ai) + suffix;
        } catch (Exception ignored) {
        }
        return packagePart(key) + suffix;
    }

    /** 联动冻结副应用：静默执行，成败以随后 reload 的真值为准。 */
    private void freezeQuiet(String key) {
        // 白名单里的副应用不参与联动冻结
        if (isWhitelisted(key)) {
            return;
        }
        int uid = userIdPart(key);
        if ((keepLauncherIcon || DhizukuGate.only(this)) && uid == 0) {
            SuspendSupport.suspend(this, packagePart(key), key);
        } else {
            execPm(key, uid, true);
        }
    }

    /** 静默解冻关联的副应用，成败不影响主应用的打开流程。 */
    private void unfreezeSubQuiet(String subKey) {
        IO.execute(() -> execPm(subKey, userIdPart(subKey), false));
    }

    /** 不在列表里的副应用用的临时 Item：只参与冻结执行与失败清单展示。 */
    private Item itemForKey(String key) {
        Item it = new Item();
        it.pkg = key;
        it.userId = userIdPart(key);
        it.label = labelFor(key);
        return it;
    }

    /** 只冻结当前应用；已冻结时直接提示，不重复执行。 */
    private void freezeSingle(Item it) {
        freezeSingle(it, false);
    }

    /** @param systemToast 长按图标菜单触发时用系统默认 Toast，其它入口沿用自绘提示 */
    private void freezeSingle(Item it, boolean systemToast) {
        if (working) {
            return;
        }
        if (isWhitelisted(it.pkg)) {
            toast("「" + it.label + "」在冻结白名单中，不会冻结");
            return;
        }
        if (it.frozen) {
            toast("「" + it.label + "」已经是冻结状态");
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
                recordDiagOp(suspendDiagChannel("单次暂停"),
                        SuspendSupport.suspendCommand(packagePart(it.pkg), it.label), r);
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
                blocked = it.userId == 0 && !ok
                        && (SuspendSupport.romBlocked(r.out) || DhizukuGate.only(this));
                if (blocked) {
                    PrivShell.Result s = SuspendSupport.suspend(this, packagePart(it.pkg), it.label);
                    recordDiagOp(suspendDiagChannel("降级暂停"),
                            SuspendSupport.suspendCommand(packagePart(it.pkg), it.label), s);
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
            // 主应用冻结成功后，副应用一并冻结；副应用失败不改变主应用的结果
            if (ok) {
                String subKey = subOf(it.pkg);
                if (subKey != null) {
                    freezeQuiet(subKey);
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
                if (systemToast) {
                    toast("已冻结");
                } else if (!hideToasts) {
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
        unfreezeSingle(it, false);
    }

    /** @param systemToast 长按图标菜单触发时用系统默认 Toast */
    private void unfreezeSingle(Item it, boolean systemToast) {
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
                keepOrderBeforeUnfreeze();
                it.frozen = false;
                it.suspended = false;
                rebindCells();
                if (systemToast) {
                    toast("已解冻");
                } else if (!hideToasts) {
                    FrozenTip.show(this, "已解冻");
                }
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
        toast("正在把 " + candidates.size() + " 个已冻结应用切到暂停…");
        IO.execute(() -> {
            execSuspendBatch(candidates);
            Set<String> suspended = SuspendSupport.suspendedAmong(candidates);
            SuspendSupport.remember(this, suspended);
            if (!suspended.isEmpty()) {
                StringBuilder script = new StringBuilder();
                for (String pkg : suspended) {
                    script.append("pm enable --user 0 ").append(pkg).append(" 2>&1; ");
                }
                execPmScript(script.toString(), Math.max(30, 10 + suspended.size() * 5));
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
                toast(failed == 0
                                ? "已切换 " + done + " 个应用：图标保留并变灰"
                                : "已切换 " + done + " 个，" + failed + " 个失败（仍保持停用）");
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
            toast("正在执行其他操作，请稍后再试");
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
            execPmScript(script.toString(), Math.max(30, 15 + suspended.size() * 6));
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
                toast(failed == 0
                                ? "已切回默认冻结 " + ok + " 个应用：图标已隐藏"
                                : "已切回 " + ok + " 个，" + failed + " 个失败（仍保持暂停）");
                reload();
            });
        });
    }

    /** 失败弹框只显示应用名 + 归类后的一句短原因，原始 pm 输出只进日志。 */
    private void showPmError(String title, Item it, PrivShell.Result r, boolean freeze) {
        showPmError(title, it, Ui.pmReason(r, freeze));
    }

    private void showPmError(String title, Item it, String reason) {
        Ui.smallDialog(this, title, it.label + "：" + reason, "知道了");
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
            toast("没有找到该应用的详情入口，已打开系统设置");
        } catch (Exception e) {
            toast("无法打开系统设置");
        }
    }

    /** 添加到桌面：照搬 Hail，只带包名建固定快捷方式，不预先解析启动页。 */
    private void pinToHome(Item it) {
        // 传实例键：分身会拿到独立的快捷方式 ID，主应用固定过也能再添分身
        // 重命名过的按自定义名添加，不再拼「（双开）」；没重命名沿用列表里的默认名称
        String custom = customName(it.pkg);
        ShortcutPin.pin(this, it.pkg, custom.isEmpty() ? it.label : custom, it.icon);
    }

    /**
     * 仅从名单移除：不解冻、不卸载，应用本身的冻结状态保持原样。
     * 连带清掉标签归属与自定义顺序里的这一项，并同步给划卡冻结的名单文件。
     */
    private void removeFromList(Item it) {
        if (working) {
            return;
        }
        // 应用还在别的标签页里：只从当前标签移出，别把它从整个名单删掉。
        // 注意不能动内存里的 items：它存的是整份名单，删掉后切到「用户应用」等标签
        // 也会看不到这个应用（要等重新加载才回来），只靠 render 按当前标签过滤即可。
        if (inOtherTabs(it)) {
            removeAppFromTab(it.pkg, currentTabId);
            saveAppTabs();
            if (it.pkg.equals(selectedPkg)) {
                selectedPkg = null;
            }
            if (it.pkg.equals(draggingPkg)) {
                draggingPkg = null;
            }
            render();
            toast("已从「" + tabNameOf(currentTabId) + "」移除");
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
        // 已从所有标签页移除：白名单与关联里对应的条目一并清掉
        pruneOrphans();
        SwipeFreeze.syncAsync(this);
        render();
        toast("已从列表移除");
    }

    /**
     * 移除列表并解冻：把应用从所有标签页与名单里一起摘掉，再真正解冻。
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
        // 已从所有标签页移除：白名单与关联里对应的条目一并清掉
        pruneOrphans();
        if (!it.frozen) {
            toast("已移出名单");
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
                    Ui.smallDialog(this, "已移出名单，但解冻失败",
                            it.label + "：" + Ui.pmReason(r, false), "知道了");
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

    /**
     * 解冻/打开前把当前顺序固化为自定义顺序：
     * 冻结状态变化后列表不再按「已冻结在前、名称升序」重排，图标留在原处。
     */
    private void keepOrderBeforeUnfreeze() {
        if (!customOrder()) {
            persistOrder();
        }
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
        // 偏移 0 就是真正贴底，安全区由根布局的 inset padding 让出，这里不再重复加；
        // 但 fab 挂在 page_root 上、不吃内层 padding，经典导航键的高度要在这里自己扣掉
        int bottomLimit = parent.getHeight() - fabGroup.getHeight() - navBottomInsetPx;
        int y = bottomLimit - Ui.dp(this, fabDy);
        y = Math.max(topLimit, Math.min(bottomLimit, y));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fabGroup.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = x;
        lp.topMargin = y;
        fabGroup.setLayoutParams(lp);
        // 等这一轮 layout 落地后再贴解冻图标，避免用到旧的 left/top
        fabGroup.post(this::positionUnfreezeFab);
        fabGroup.post(this::positionMoveDoneFab);
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
            // 只允许往上抬：上限取按钮到列表顶部的真实空间（大屏平板也能拖到顶，但绝不超过顶部）
            int maxUp = fabMaxLiftPx();
            int y = Math.max(-maxUp, Math.min(0, -Ui.dp(this, fabDy)));
            fabGroup.setTranslationX(x);
            fabGroup.setTranslationY(y);
            positionUnfreezeFab();
            positionMoveDoneFab();
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

    /** 移动模式：点一下选中（高亮），再点目标项即插到它前面并立即落盘。 */
    private void setMoveMode(boolean on) {
        moveMode = on;
        selectedPkg = null;
        prefs().edit().putBoolean(lk(KEY_MOVE_MODE), on).apply();
        render();
        updateFabDone();
    }

    /** 半屏快捷移除：打开后图标右上角常驻红标，点红标移除；再点底部「完成」勾选退出。 */
    private void setQuickRemove(boolean on) {
        quickRemove = panelMode && on;
        prefs().edit().putBoolean(KEY_PANEL_QUICK_REMOVE, quickRemove).apply();
        rebindCells();
        updateFabDone();
    }

    /** 移动模式 / 快捷移除 / 全屏移除模式共用的「完成」勾选：盖在底部冻结按钮上，点一下结束当前状态。 */
    private void setupMoveDoneFab() {
        moveDoneFab = findViewById(R.id.btn_move_done);
        if (moveDoneFab == null) {
            return;
        }
        moveDoneFab.setOnClickListener(v -> {
            if (editMode) {
                setEditMode(false);
            } else if (moveMode) {
                setMoveMode(false);
            } else if (panelMode && quickRemove) {
                setQuickRemove(false);
            }
        });
        updateFabDone();
    }

    private void updateFabDone() {
        if (moveDoneFab == null) {
            return;
        }
        boolean show = moveMode || editMode || (panelMode && quickRemove);
        moveDoneFab.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            moveDoneFab.setContentDescription(editMode ? "完成移除" : "完成移动图标");
            moveDoneFab.post(this::positionMoveDoneFab);
            if (fabGroup != null) {
                fabGroup.post(this::positionMoveDoneFab);
            }
        }
    }

    /** 把「完成」勾选对齐到冻结按钮：冻结按钮多大它就多大，冻结图标在哪它就在哪。 */
    private void positionMoveDoneFab() {
        if (moveDoneFab == null || fabGroup == null
                || moveDoneFab.getVisibility() != View.VISIBLE
                || !(fabGroup.getParent() instanceof View)) {
            return;
        }
        View freeze = findViewById(R.id.btn_freeze);
        if (freeze == null) {
            return;
        }
        int w = freeze.getWidth() > 0 ? freeze.getWidth() : Ui.dp(this, 40);
        int h = freeze.getHeight() > 0 ? freeze.getHeight() : Ui.dp(this, 40);
        ViewGroup.LayoutParams lp = moveDoneFab.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            moveDoneFab.setLayoutParams(lp);
        }
        int x = fabGroup.getLeft() + Math.round(fabGroup.getTranslationX())
                + freeze.getLeft() + Math.round(freeze.getTranslationX());
        int y = fabGroup.getTop() + Math.round(fabGroup.getTranslationY())
                + freeze.getTop() + Math.round(freeze.getTranslationY());
        moveDoneFab.setTranslationX(x);
        moveDoneFab.setTranslationY(y);
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
                // 与底部一键冻结同一范围：勾了「底部按钮仅冻/解当前列表」就只处理当前标签
                List<Item> targets = batchTargets();
                if (targets.isEmpty()) {
                    FrozenTip.show(this, "冻结名单为空");
                    return;
                }
                runBatch(false, targets);
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
            // 长按固定可用：弹出解冻图标并震动（原设置项已移除）
            // 关掉按钮自身的系统长按反馈，避免与下面显式的一次叠加成两段震动
            freezeBtn.setHapticFeedbackEnabled(false);
            freezeBtn.setOnLongClickListener(v -> {
                // 与长按空白处、长按图标用同一套长按反馈，手感保持一致
                if (fabGroup != null) {
                    fabGroup.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
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
        // 解冻按钮与底部冻结按钮共用同一档透明度：此处拿到视图后再刷一次，
        // 否则 onCreate 早期刷档位时这个按钮还没 inflate，会一直是实心
        if (panelMode) {
            applyFabSurface();
        } else {
            applyFullscreenFabSurface();
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
        enableBox.setText("划卡冻结");
        enableBox.setTextSize(11);
        enableBox.setTextColor(getColor(R.color.text_secondary));
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
        label.setTextSize(11);
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
     * 布局设置里的「消除启动白底」开关（HyperOS 4 专属，默认关）。
     *
     * 打开后模块会把系统预启动动画铺在窗口下方的白/黑底板 alpha 压成 0，
     * 冷启动第一帧直接是设置的透明度；关掉则完全按 2.10.14 的默认表现走
     * （系统动画自己铺什么颜色就是什么颜色，凚 不插手）。
     * 开关写进 /data/system/jin_freeze_list.json，hook 侧按 mtime 重读，不用重启 system_server。
     * 同样只在 root 通道可用（Shizuku 的 uid 2000 写不进 /data/system）。
     */
    private void addNoLaunchBgSection(LinearLayout box) {
        final CheckBox enableBox = new CheckBox(this);
        enableBox.setText("消除启动白底");
        enableBox.setTextSize(11);
        enableBox.setTextColor(getColor(R.color.text_secondary));
        enableBox.setMinimumHeight(Ui.dp(this, 32));
        enableBox.setChecked(SwipeFreeze.noLaunchBg(this));
        // 先置灰，root 检测完成后再决定是否可用
        enableBox.setEnabled(false);
        enableBox.setOnCheckedChangeListener((btn, on) -> {
            SwipeFreeze.setNoLaunchBg(this, on);
            SwipeFreeze.syncAsync(this);
        });
        box.addView(enableBox);

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

    /** HyperOS 4（OS4.x）才有这套预启动动画的实色底板，别的 ROM 不显示这个开关。 */
    private static boolean isHyperOs4() {
        String name = systemProp("ro.mi.os.version.name");
        if (name != null && name.trim().startsWith("OS4")) {
            return true;
        }
        return "4".equals(systemProp("ro.mi.os.version.code").trim());
    }

    /** 读系统属性：SystemProperties 是隐藏类，只能反射取。 */
    private static String systemProp(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Object value = cls.getMethod("get", String.class).invoke(null, key);
            return value instanceof String ? (String) value : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private void addLockFreezeSection(LinearLayout box) {
        final CheckBox enableBox = new CheckBox(this);
        enableBox.setText("锁屏冻结");
        enableBox.setTextSize(11);
        enableBox.setTextColor(getColor(R.color.text_secondary));
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
        tagLabel.setTextSize(11);
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
        label.setTextSize(11);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = Ui.dp(this, 16);
        root.setPadding(padH, Ui.dp(this, 6), padH, Ui.dp(this, 2));

        // 标题与「白名单列表」同一套：内容内左对齐，16sp 加粗，不再用系统标题的内缩
        TextView heading = Ui.text(this, forLock ? "锁屏冻结标签" : "按标签冻结",
                16, Ui.TEXT_PRIMARY, true);
        root.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // 与「主副应用关联」同一套 AlertDialog：标题在内容内左对齐，底部返回按钮
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .setNegativeButton("返回", null)
                .create();
        dialog.setOnDismissListener(d -> summary.setText(forLock
                ? lockTagSummaryText() : tagSummaryText()));
        dialog.show();
        Ui.roundAlertDialog(dialog);
        applySettingsAlignment(dialog);
    }

    /** 划卡冻结开关提示：与「白名单列表」同一套 AlertDialog。 */
    private void showSwipeFreezePrompt() {
        // 与「白名单列表」完全同一套 AlertDialog：同样的宽度、动画、圆角卡片和底部按钮
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 16);
        col.setPadding(side, Ui.dp(this, 2), side, Ui.dp(this, 2));

        TextView message = new TextView(this);
        message.setText("开关已记录，但还没在 system_server 里跑起来。\n\n"
                + "1. 在 LSPosed 里启用凚（作用域已预置，不用手动勾选）；\n"
                + "2. 重启手机；\n"
                + "3. 重启后划卡才会真正冻结。");
        message.setTextSize(13);
        message.setLineSpacing(Ui.dp(this, 2), 1f);
        message.setTextColor(getColor(R.color.text_primary));
        message.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        col.addView(message);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("划卡冻结需激活 LSPosed 并重启")
                .setView(col)
                .setNegativeButton("知道了", null)
                .create();
        dialog.show();
        Ui.roundAlertDialog(dialog);
        applySettingsAlignment(dialog);
    }

    /** 长按左上角设置按钮：把面板拖动偏移清零、所有分组置为展开，再按默认位置打开。 */
    private void showLayoutDialogAtDefault() {
        SharedPreferences.Editor e = prefs().edit();
        // 半屏本来就居中不记偏移，这里一并清掉无副作用
        e.putInt(lk(KEY_LAYOUT_DX), 0).putInt(lk(KEY_LAYOUT_DY), 0);
        for (String key : SETTINGS_COLLAPSE_KEYS) {
            e.putBoolean(key, false);
        }
        e.apply();
        // 长按入口：打开后连设置页一起进入「展开最大范围」态
        expandLayoutOnShow = true;
        showLayoutDialog();
    }

    private void showLayoutDialog() {
        // 每次重建设置面板都重新登记分组，清掉上一版的引用
        settingsCollapsers.clear();
        int pad = Ui.dp(this, 16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 2), pad, Ui.dp(this, 4));

        // 顶部操作提示：紧贴标题下方、与标题成一组，不随内容滚动；
        // 每行左侧正文、右侧「不再提示」，点掉后只隐藏所在行
        final java.util.List<View> hintRows = new ArrayList<>();
        // 半屏没有「展开最大范围」这一步，不显示这条提示
        View gestureHint = null;
        if (!panelMode) {
            gestureHint = hintRow("长按设置文字移动面板", KEY_LAYOUT_HINT_HIDDEN, hintRows);
        }
        // 下面三条常用操作提示：长按◎展开全部、长按底栏折叠全部、长按底部冻结触发一键解冻
        View gestureHintExpand = hintRow("长按◎，展开设置页与所有栏目",
                KEY_LAYOUT_HINT_EXPAND_HIDDEN, hintRows);
        View gestureHintCollapse = hintRow("长按设置页底栏，折叠所有栏目",
                KEY_LAYOUT_HINT_COLLAPSE_HIDDEN, hintRows);
        View gestureHintUnfreeze = hintRow("长按底部冻结，可触发一键解冻",
                KEY_LAYOUT_HINT_UNFREEZE_HIDDEN, hintRows);

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
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit().putInt(otherKey(KEY_ICON_DP), iconDp)
                            .putBoolean(otherKey(KEY_ICON_MANUAL), true).apply();
                }
            }
        });
        sizeLabel.setText("图标大小：" + (sizeBar.getProgress() + iconSizeMinDp()) + "dp");

        CheckBox nameBox = new CheckBox(this);
        nameBox.setText("显示应用名称");
        nameBox.setTextSize(11);
        nameBox.setTextColor(getColor(R.color.text_secondary));
        nameBox.setMinimumHeight(Ui.dp(this, 32));
        nameBox.setChecked(showName);
        nameBox.setOnCheckedChangeListener((btn, on) -> {
            showName = on;
            setShowNamePref(on);
            render();
        });

        // 「半屏高度调节」勾选瞬间要收起布局弹窗，先把实例留个引用
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        // 打开移动模式/快捷移除时收起已展开的面板（列表本身不动），引用在弹窗建好后填
        final Runnable[] collapseHeightRow = new Runnable[1];

        // 移动图标不再放设置里：入口改到长按图标弹出的操作单（多选上方），见 showAppSheet

        // 去掉 toast 提示：勾选后所有短提示都不显示；全屏/半屏共用
        CheckBox tipBox = new CheckBox(this);
        tipBox.setText("去掉 toast 提示");
        tipBox.setTextSize(11);
        tipBox.setTextColor(getColor(R.color.text_secondary));
        tipBox.setMinimumHeight(Ui.dp(this, 32));
        tipBox.setChecked(hideToasts);
        tipBox.setOnCheckedChangeListener((btn, on) -> {
            hideToasts = on;
            prefs().edit().putBoolean(KEY_HIDE_TOASTS, on).apply();
        });

        // 底部按钮仅冻/解当前列表：标签栏可见时长按标签就能切换，设置里不显示；
        // 勾选「隐藏标签页文字」收起标签行、或没有可见标签时才露出，并随勾选实时变化
        CheckBox tabOnlyBox = new CheckBox(this);
        tabOnlyBox.setText("底部按钮仅冻/解当前列表");
        tabOnlyBox.setTextSize(11);
        tabOnlyBox.setTextColor(getColor(R.color.text_secondary));
        tabOnlyBox.setMinimumHeight(Ui.dp(this, 32));
        tabOnlyBox.setChecked(batchCurrentTabOnly);
        tabOnlyBox.setOnCheckedChangeListener((btn, on) -> {
            batchCurrentTabOnly = on;
            prefs().edit().putBoolean(KEY_BATCH_CURRENT_TAB_ONLY, on).apply();
        });
        tabOnlyBox.setVisibility(View.VISIBLE);

        // 半屏专属：顶部小白条是否允许拖动改高度（开关与高度值分开存）
        CheckBox handleBox = null;
        CheckBox hideStatusBarBox = null;
        CheckBox dismissBox = new CheckBox(this);
        dismissBox.setText("一键冻结后收起列表");
        dismissBox.setTextSize(11);
        dismissBox.setTextColor(getColor(R.color.text_secondary));
        dismissBox.setMinimumHeight(Ui.dp(this, 32));
        dismissBox.setChecked(panelDismissAfterFreeze);
        dismissBox.setOnCheckedChangeListener((btn, on) -> {
            panelDismissAfterFreeze = on;
            prefs().edit().putBoolean(KEY_PANEL_DISMISS, on).apply();
        });

        CheckBox hideRecentsBox = new CheckBox(this);
        hideRecentsBox.setText("隐藏最近任务卡片");
        hideRecentsBox.setTextSize(11);
        hideRecentsBox.setTextColor(getColor(R.color.text_secondary));
        hideRecentsBox.setMinimumHeight(Ui.dp(this, 32));
        hideRecentsBox.setChecked(panelHideRecents);
        hideRecentsBox.setOnCheckedChangeListener((btn, on) -> {
            panelHideRecents = on;
            prefs().edit().putBoolean(KEY_PANEL_HIDE_RECENTS, on).apply();
            // 与冰箱拨开关时一样立刻同步任务属性：开着时当前任务马上退出最近任务，
            // 关掉时恢复；下次从桌面进入会由 LaunchActivity 改走对应的隐藏/普通入口
            syncRecentsExclusion();
        });

        if (panelMode) {
            handleBox = new CheckBox(this);
            handleBox.setText("半屏高度小白条调节");
            handleBox.setTextSize(11);
            handleBox.setTextColor(getColor(R.color.text_secondary));
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
            hideStatusBarBox.setText("隐藏半屏的状态栏");
            hideStatusBarBox.setTextSize(11);
            hideStatusBarBox.setTextColor(getColor(R.color.text_secondary));
            hideStatusBarBox.setMinimumHeight(Ui.dp(this, 32));
            hideStatusBarBox.setChecked(panelHideStatusBar);
            hideStatusBarBox.setOnCheckedChangeListener((btn, on) -> {
                panelHideStatusBar = on;
                prefs().edit().putBoolean(KEY_PANEL_HIDE_STATUS_BAR, on).apply();
                // 隐藏时不再反色；取消勾选立刻按壁纸亮度补一次
                applyPanelBarState();
            });

        }

        // 悬浮按钮位置：横向以「底部居中」为 0 左右偏，纵向以贴底为 0 往上抬，拖动即时生效
        final float density = getResources().getDisplayMetrics().density;
        final int xMinDp = Math.round((fabInsetPx() - fabCenterX()) / density);
        final int xMaxDp = Math.round((fabMaxX() - fabCenterX()) / density);
        // 「距底部」滑块最大值 = 按钮到列表顶部的真实空间：大屏平板可一直拖到顶部，但不会超过顶部
        final int maxLiftDp = Math.max(0, Math.round(fabMaxLiftPx() / density));

        TextView fabTitle = new TextView(this);
        fabTitle.setText("底部冻结按钮");
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
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit().putInt(otherKey(KEY_FAB_SIZE), fabSizeDp).apply();
                }
                sizeValue.setText("按钮大小：" + fabSizeDp + "dp");
                applyFabSize();
                fabGroup.post(FreezeListActivity.this::applyFabPosition);
                // 按钮变大/变小会改变上沿，展开范围随之上限实时跟着走
                getWindow().getDecorView().postDelayed(
                        FreezeListActivity.this::refreshExpandRange, 40);
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
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit().putInt(otherKey(KEY_FAB_DX), fabDx).apply();
                }
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
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit().putInt(otherKey(KEY_FAB_DY), fabDy).apply();
                }
                refreshFabLabels.run();
                applyFabPosition();
                // 按钮上下移动后，展开范围的上限与当前高度一起实时收放
                getWindow().getDecorView().postDelayed(
                        FreezeListActivity.this::refreshExpandRange, 40);
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
        tabTitle.setText(hintTitle("标签设定", "tab"));
        tabTitle.setTextSize(12);
        tabTitle.setTextColor(getColor(R.color.text_primary));
        tabTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

        // 隐藏标签页文字：半屏与全屏各自独立，只收起标签名那一排视图，列表与手势不受影响；
        // 全屏里这一行还放着布局/备份/恢复，收起后它们仍贴右
        CheckBox hideRowBox = new CheckBox(this);
        settingsHideTabRowBox = hideRowBox;
        hideRowBox.setText(hideTabRowLabel());
        hideRowBox.setTextSize(11);
        hideRowBox.setTextColor(getColor(R.color.text_secondary));
        hideRowBox.setMinimumHeight(Ui.dp(this, 32));
        hideRowBox.setChecked(hideTabRow);
        hideRowBox.setOnCheckedChangeListener((b, on) -> {
            if (on == hideTabRow) {
                return;
            }
            hideTabRow = on;
            setHideTabRowPref(on);
            renderTabBar();
            if (grid != null) {
                grid.requestLayout();
            }
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
                // 显式用主题主色，同步全局时才会跟着壁纸自动切白/黑
                cb.setTextColor(getColor(R.color.text_primary));
                cb.setGravity(Gravity.CENTER_VERTICAL);
                cb.setMinimumHeight(Ui.dp(FreezeListActivity.this, 24));
                styleSettingCheckBox(cb);
                cb.setChecked(t.visible);
                cb.setOnCheckedChangeListener((b, on) -> {
                    if (on == t.visible) {
                        return;
                    }
                    if (!on && t.visible && visibleTabCount() == 1) {
                        // 至少留一个可见标签，否则标签栏会整条空掉
                        b.setChecked(true);
                        toast("至少保留一个标签");
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
                // 默认标签只在入口按钮上体现状态，标签条本身不加任何标记；
                // 与长按标签菜单一致：只认显式设过的默认，不拿回落值判断
                final boolean isDefault = t.id.equals(prefs().getString(KEY_DEFAULT_TAB, null));
                // 删除（红色垃圾桶）放在星标左边，只有自定义标签有
                if (t.custom) {
                    ImageButton del = settingsRowIconBtn(R.drawable.ic_action_delete, "删除标签");
                    del.setImageTintList(ColorStateList.valueOf(0xFFE5484D));
                    del.setOnClickListener(v -> confirmDeleteTab(t, refreshTabSettings[0]));
                    row.addView(del);
                }
                // 默认星标：实心=当前默认，空心=未设默认；点一下切换默认
                ImageButton def = settingsRowIconBtn(
                        isDefault ? R.drawable.ic_star_filled : R.drawable.ic_star_outline,
                        isDefault ? "取消默认标签" : "设为默认标签");
                def.setImageTintList(ColorStateList.valueOf(
                        getColor(isDefault ? R.color.star_default : R.color.text_secondary)));
                if (!t.visible) {
                    // 隐藏的标签不能当默认，入口保持可读但不可点
                    def.setAlpha(0.4f);
                } else {
                    def.setOnClickListener(v -> {
                        // 与长按标签菜单同一判断：只认显式设过的默认
                        if (t.id.equals(prefs().getString(KEY_DEFAULT_TAB, null))) {
                            prefs().edit().remove(KEY_DEFAULT_TAB).apply();
                            toast("已取消默认，进入时回到第一个标签");
                        } else {
                            prefs().edit().putString(KEY_DEFAULT_TAB, t.id).apply();
                            toast("「" + t.name + "」已设为默认标签");
                        }
                        refreshTabSettings[0].run();
                    });
                }
                row.addView(def);
                // 顺序按「默认星标、重命名、排序」：重命名放在星标右侧、排序左侧；
                // 内置标签也能改名（只改显示名，不影响它承载用户/系统应用的性质）；删除仍只限自定义标签
                ImageButton rename = settingsRowIconBtn(R.drawable.ic_action_edit, "重命名标签");
                rename.setOnClickListener(v -> showTabNameDialog(t, refreshTabSettings[0]));
                row.addView(rename);
                // 排序：长按后直接在当前页面上下拖动，松手即生效，不弹窗口
                ImageButton sortBtn = settingsRowIconBtn(R.drawable.ic_drag_handle, "标签排序");
                // 视觉仍是 16dp、间距不变，只把按住的触摸区四周放大 10dp，方便长按起拖
                row.post(() -> {
                    Rect touch = new Rect(
                            sortBtn.getLeft() - Ui.dp(this, 10),
                            sortBtn.getTop() - Ui.dp(this, 10),
                            sortBtn.getRight() + Ui.dp(this, 10),
                            sortBtn.getBottom() + Ui.dp(this, 10));
                    row.setTouchDelegate(new android.view.TouchDelegate(touch, sortBtn));
                });
                final float[] downRaw = new float[2];
                final long[] downAt = new long[2];
                final boolean[] dragging = new boolean[2];
                sortBtn.setOnTouchListener((v, ev) -> {
                    switch (ev.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downRaw[0] = ev.getRawY();
                            downAt[0] = ev.getEventTime();
                            dragging[0] = false;
                            // 按住排序手柄时别再让外层 ScrollView 抢走滑动，否则拖动会变成滚页面
                            ViewParent vpSort = v.getParent();
                            if (vpSort != null) {
                                vpSort.requestDisallowInterceptTouchEvent(true);
                            }
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            if (!dragging[0]
                                    && (ev.getEventTime() - downAt[0] > 220
                                    || Math.abs(ev.getRawY() - downRaw[0]) > Ui.dp(this, 6))) {
                                dragging[0] = true;
                                row.setAlpha(0.85f);
                            }
                            if (dragging[0]) {
                                row.setTranslationY(ev.getRawY() - downRaw[0]);
                            }
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL: {
                            row.setTranslationY(0);
                            row.setAlpha(1f);
                            ViewParent vpUp = v.getParent();
                            if (vpUp != null) {
                                vpUp.requestDisallowInterceptTouchEvent(false);
                            }
                            if (dragging[0]) {
                                float dy = ev.getRawY() - downRaw[0];
                                int rowH = row.getHeight() > 0
                                        ? row.getHeight() : Ui.dp(this, 32);
                                int shift = Math.round(dy / rowH);
                                final int from = tabs.indexOf(t);
                                final int to = Math.max(0,
                                        Math.min(tabs.size() - 1, from + shift));
                                // 手势结束后再改数据和重建行：不能在触摸回调里直接移除
                                // 当前正被按住的这一行，否则会在下次绘制时闪退
                                sortBtn.post(() -> {
                                    if (to != from) {
                                        tabs.add(to, tabs.remove(from));
                                        saveTabs();
                                        renderTabBar();
                                    }
                                    refreshTabSettings[0].run();
                                });
                            } else if (ev.getActionMasked()
                                    == android.view.MotionEvent.ACTION_UP) {
                                toast("长按排序图标可上下拖动排序");
                            }
                            return true;
                        }
                        default:
                            return false;
                    }
                });
                row.addView(sortBtn);
                tabList.addView(row);
            }
        };
        refreshTabSettings[0].run();
        newTab.setOnClickListener(v -> showTabNameDialog(null, refreshTabSettings[0]));

        // 个性设置：与「列表图标调整」「底部冻结按钮调节」同一档小标题；
        // 默认形态改成下面的勾选项，不再点标题切换
        TextView personalTitle = new TextView(this);
        personalTitle.setText("个性设置");
        personalTitle.setTextSize(12);
        personalTitle.setTextColor(getColor(R.color.text_primary));
        // 与上方「面板未展开高度」保持与标题下方相同的间距（其它分组标题仍是 12dp）
        personalTitle.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));

        // 默认形态勾选：全屏显示「默认全屏列表」，半屏显示「默认半屏列表」，勾上即下次启动进这个形态
        CheckBox defaultFormBox = new CheckBox(this);
        defaultFormBox.setText(panelMode ? "默认半屏列表" : "默认全屏列表");
        defaultFormBox.setTextSize(11);
        defaultFormBox.setTextColor(getColor(R.color.text_secondary));
        defaultFormBox.setMinimumHeight(Ui.dp(this, 32));
        final boolean curIsDefaultForm = prefs().getBoolean(KEY_FULLSCREEN_OPEN, true) != panelMode;
        defaultFormBox.setChecked(curIsDefaultForm);
        defaultFormBox.setOnCheckedChangeListener((b, on) -> {
            if (on == curIsDefaultForm) {
                return;
            }
            // 全屏里勾上=默认全屏，取消=默认半屏；半屏反过来；改完立刻切到对应形态
            boolean toFull = panelMode ? !on : on;
            prefs().edit().putBoolean(KEY_FULLSCREEN_OPEN, toFull).apply();
            // 勾选状态代表的形态与当前形态不同才需要切换
            if (toFull == panelMode) {
                switchForm(toFull);
            }
        });

        // 个性设置整块可折叠：默认展开，只有标题右侧箭头负责收起
        final LinearLayout personalBody = new LinearLayout(this);
        personalBody.setOrientation(LinearLayout.VERTICAL);
        personalBody.addView(defaultFormBox);
        personalBody.addView(nameBox);
        personalBody.addView(tipBox);
        if (dismissBox != null) {
            personalBody.addView(dismissBox);
        }
        // 隐藏标签文字放在「一键冻结后收起列表」下面
        personalBody.addView(hideRowBox);
        // 仅冻结/解冻当前标签放在「一键冻结后收起列表」上面
        int tabOnlyIdx = dismissBox == null
                ? personalBody.getChildCount() : personalBody.indexOfChild(dismissBox);
        personalBody.addView(tabOnlyBox, Math.max(0, tabOnlyIdx));

        if (handleBox != null) {
            personalBody.addView(handleBox);
        }
        if (hideStatusBarBox != null) {
            personalBody.addView(hideStatusBarBox);
        }
        // 隐藏后台紧贴「划卡冻结（lsposed）」上面
        if (hideRecentsBox != null) {
            personalBody.addView(hideRecentsBox);
        }
        final LinearLayout personalHead = collapsibleHead(personalTitle, personalBody,
                KEY_SETTINGS_PERSONAL_COLLAPSED, null, null, R.drawable.ic_qp_personal);
        box.addView(personalHead);
        box.addView(personalBody);
        // 专属LSPosed：划卡冻结与锁屏冻结整体成块，稍后排到手势功能栏之后；
        // 标题右侧箭头负责折叠，默认展开，标题点击仍是看说明
        LinearLayout lspBody = new LinearLayout(this);
        lspBody.setOrientation(LinearLayout.VERTICAL);
        TextView lspTitle = new TextView(this);
        lspTitle.setText(hintTitle("专属LSPosed", "lsp"));
        lspTitle.setTextSize(12);
        lspTitle.setTextColor(getColor(R.color.text_primary));
        lspTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        final LinearLayout lspHead = collapsibleHead(lspTitle, lspBody,
                KEY_SETTINGS_LSP_COLLAPSED, null, () -> showLspHintDialog(lspTitle),
                R.drawable.ic_qp_lsposed_official);
        addSwipeFreezeSection(lspBody);
        addLockFreezeSection(lspBody);
        // 消除启动白底：只针对 HyperOS 4 的预启动动画底板，别的 ROM 不显示
        if (isHyperOs4()) {
            addNoLaunchBgSection(lspBody);
        }
        // 标签：标题右侧箭头负责折叠，与「手势调节」同一套把手；标题点击仍是看说明
        final LinearLayout tabBody = new LinearLayout(this);
        tabBody.setOrientation(LinearLayout.VERTICAL);
        tabBody.addView(tabList);
        tabBody.addView(newTab);
        final LinearLayout tabHead = collapsibleHead(tabTitle, tabBody,
                KEY_SETTINGS_TAB_COLLAPSED, null, () -> showTabHintDialog(tabTitle),
                R.drawable.ic_action_tag);
        box.addView(tabHead);
        box.addView(tabBody);
        // 按钮大小/偏移收进这一层，标题右侧箭头负责折叠
        final LinearLayout fabBody = new LinearLayout(this);
        fabBody.setOrientation(LinearLayout.VERTICAL);
        fabBody.addView(sizeValue);
        fabBody.addView(fabSizeBar);
        fabBody.addView(xValue);
        fabBody.addView(xBar);
        fabBody.addView(yValue);
        fabBody.addView(yBar);
        final LinearLayout fabHead = collapsibleHead(fabTitle, fabBody,
                KEY_SETTINGS_FAB_COLLAPSED, null, null, R.drawable.ic_sc_freeze);
        box.addView(fabHead);
        box.addView(fabBody);

        // 透明度：紧挨「距底部」，与上面几条同一套小字样式；两种形态各自记忆
        final TextView fabAlphaLabel = new TextView(this);
        fabAlphaLabel.setTextSize(11);
        fabAlphaLabel.setTextColor(getColor(R.color.text_secondary));
        // 行距统一成「按钮大小 / 左右偏移」那一档：小字不再额外加上间距

        final SeekBar fabAlphaBar = new SeekBar(this);
        fabAlphaBar.setMax(100);
        int fabAlphaStored = Math.max(0, Math.min(100,
                prefs().getInt(panelMode ? lk(KEY_FAB_SURFACE) : KEY_FULL_FAB_SURFACE,
                        panelMode ? PANEL_FAB_SURFACE_DEFAULT : FULL_FAB_SURFACE_DEFAULT)));
        int fabAlphaPct = 100 - fabAlphaStored;
        fabAlphaBar.setProgress(fabAlphaPct);
        fabAlphaLabel.setText("透明度：" + fabAlphaPct + "%");
        fabAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                // 滑块值 = 透明度：0 不透明（原观感），100 全透明
                prefs().edit()
                        .putInt(panelMode ? lk(KEY_FAB_SURFACE) : KEY_FULL_FAB_SURFACE, 100 - p)
                        .apply();
                // 底部按钮透明度：开了同步就一起写另一形态（半屏 panel_fab_surface ↔ 全屏 full_fab_surface）
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit()
                            .putInt(panelMode ? KEY_FULL_FAB_SURFACE : "panel_" + KEY_FAB_SURFACE,
                                    100 - p)
                            .apply();
                }
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
        // 按钮底板透明度也归「底部冻结按钮」这一组，跟着标题一起折叠
        fabBody.addView(fabAlphaLabel);
        fabBody.addView(fabAlphaBar);
        // 手势功能：标题可点开说明；下面是长按空白后上滑（半屏进全屏）/ 下滑（全屏回半屏）的
        // 触发距离滑块，实时生效；整块最后插到「标签」上面
        LinearLayout gestureSec = new LinearLayout(this);
        gestureSec.setOrientation(LinearLayout.VERTICAL);
        TextView gestureTitle = new TextView(this);
        gestureTitle.setText(hintTitle("手势调节", "gesture"));
        gestureTitle.setTextSize(12);
        gestureTitle.setTextColor(getColor(R.color.text_primary));
        gestureTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

        // 手势滑块与边缘设置收进这一层，标题右侧箭头负责折叠
        final LinearLayout gestureBody = new LinearLayout(this);
        gestureBody.setOrientation(LinearLayout.VERTICAL);

        final TextView upLabel = new TextView(this);
        upLabel.setTextSize(11);
        upLabel.setTextColor(getColor(R.color.text_secondary));
        upLabel.setText("上滑手势触发距离：" + gestureUpDp + "dp");

        SeekBar upBar = new SeekBar(this);
        upBar.setMax(GESTURE_DP_MAX);
        upBar.setProgress(gestureUpDp);
        upBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                gestureUpDp = p;
                prefs().edit().putInt(KEY_GESTURE_UP, p).apply();
                upLabel.setText("上滑手势触发距离：" + p + "dp");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        final TextView downLabel = new TextView(this);
        downLabel.setTextSize(11);
        downLabel.setTextColor(getColor(R.color.text_secondary));
        downLabel.setText("下滑手势触发距离：" + gestureDownDp + "dp");

        SeekBar downBar = new SeekBar(this);
        downBar.setMax(GESTURE_DP_MAX);
        downBar.setProgress(gestureDownDp);
        downBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                gestureDownDp = p;
                prefs().edit().putInt(KEY_GESTURE_DOWN, p).apply();
                downLabel.setText("下滑手势触发距离：" + p + "dp");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        gestureSec.addView(collapsibleHead(gestureTitle, gestureBody,
                KEY_SETTINGS_GESTURE_COLLAPSED, null,
                () -> showGestureHintDialog(gestureTitle), R.drawable.ic_qp_hold_head));
        gestureSec.addView(gestureBody);
        gestureBody.addView(upLabel);
        gestureBody.addView(upBar);
        gestureBody.addView(downLabel);
        gestureBody.addView(downBar);

        // 只在屏幕左右边缘区域长按滑动才切换形态，避免和列表里的滑动操作误触
        final LinearLayout edgeLeftBox = new LinearLayout(this);
        edgeLeftBox.setOrientation(LinearLayout.VERTICAL);
        final TextView edgeLeftLabel = new TextView(this);
        edgeLeftLabel.setTextSize(11);
        edgeLeftLabel.setTextColor(getColor(R.color.text_secondary));
        edgeLeftLabel.setText("屏幕左边缘靠内触发手势距离：" + edgeDpLabel(gestureEdgeLeftDp));
        SeekBar edgeLeftBar = new SeekBar(this);
        edgeLeftBar.setMax(GESTURE_EDGE_DP_MAX - GESTURE_EDGE_DP_MIN);
        edgeLeftBar.setProgress(Math.max(0, gestureEdgeLeftDp - GESTURE_EDGE_DP_MIN));
        edgeLeftBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int value = p + GESTURE_EDGE_DP_MIN;
                gestureEdgeLeftDp = value;
                prefs().edit().putInt(KEY_GESTURE_EDGE_LEFT, value).apply();
                edgeLeftLabel.setText("屏幕左边缘靠内触发手势距离：" + edgeDpLabel(value));
                applyEdgeGestureExclusion();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        edgeLeftBox.addView(edgeLeftLabel);
        edgeLeftBox.addView(edgeLeftBar);

        final LinearLayout edgeRightBox = new LinearLayout(this);
        edgeRightBox.setOrientation(LinearLayout.VERTICAL);
        final TextView edgeRightLabel = new TextView(this);
        edgeRightLabel.setTextSize(11);
        edgeRightLabel.setTextColor(getColor(R.color.text_secondary));
        edgeRightLabel.setText("屏幕右边缘靠内触发手势距离：" + edgeDpLabel(gestureEdgeRightDp));
        SeekBar edgeRightBar = new SeekBar(this);
        edgeRightBar.setMax(GESTURE_EDGE_DP_MAX - GESTURE_EDGE_DP_MIN);
        edgeRightBar.setProgress(Math.max(0, gestureEdgeRightDp - GESTURE_EDGE_DP_MIN));
        edgeRightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int value = p + GESTURE_EDGE_DP_MIN;
                gestureEdgeRightDp = value;
                prefs().edit().putInt(KEY_GESTURE_EDGE_RIGHT, value).apply();
                edgeRightLabel.setText("屏幕右边缘靠内触发手势距离：" + edgeDpLabel(value));
                applyEdgeGestureExclusion();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        edgeRightBox.addView(edgeRightLabel);
        edgeRightBox.addView(edgeRightBar);

        CheckBox edgeBox = new CheckBox(this);
        edgeBox.setText("仅在屏幕边缘触发上下滑");
        edgeBox.setTextSize(11);
        edgeBox.setTextColor(getColor(R.color.text_secondary));
        edgeBox.setMinimumHeight(Ui.dp(this, 32));
        edgeBox.setChecked(gestureEdgeOnly);
        edgeBox.setOnCheckedChangeListener((b, on) -> {
            gestureEdgeOnly = on;
            prefs().edit().putBoolean(KEY_GESTURE_EDGE_ONLY, on).apply();
            int vis = on ? View.VISIBLE : View.GONE;
            edgeLeftBox.setVisibility(vis);
            edgeRightBox.setVisibility(vis);
            applyEdgeGestureExclusion();
        });
        // 「仅在屏幕边缘触发上下滑」连同左右距离滑块收进「手势调节」卡片内部，
        // 直接排在标题下面、上下滑距离滑块之前；折叠卡片时一起收起
        final LinearLayout gestureTail = new LinearLayout(this);
        gestureTail.setOrientation(LinearLayout.VERTICAL);
        gestureTail.addView(edgeBox);
        gestureTail.addView(edgeLeftBox);
        gestureTail.addView(edgeRightBox);
        gestureBody.addView(gestureTail, 0);
        int edgeVis = gestureEdgeOnly ? View.VISIBLE : View.GONE;
        edgeLeftBox.setVisibility(edgeVis);
        edgeRightBox.setVisibility(edgeVis);

        // 长按手势总开关：放在手势栏外面，关掉后才能再点开；
        // 关闭后整个「手势触发距离」栏与下方边缘触发一起隐藏，长按空白也不再起手势
        CheckBox gestureEnabledBox = new CheckBox(this);
        gestureEnabledBox.setText("长按列表空白处手势");
        gestureEnabledBox.setTextSize(11);
        gestureEnabledBox.setTextColor(getColor(R.color.text_secondary));
        gestureEnabledBox.setMinimumHeight(Ui.dp(this, 32));
        gestureEnabledBox.setChecked(gestureEnabled);
        gestureEnabledBox.setOnCheckedChangeListener((b, on) -> {
            gestureEnabled = on;
            prefs().edit().putBoolean(KEY_GESTURE_ENABLED, on).apply();
            int vis = on ? View.VISIBLE : View.GONE;
            gestureTail.setVisibility(vis);
            gestureSec.setVisibility(vis);
            if (!on) {
                cancelBlankScrub();
            }
            applyEdgeGestureExclusion();
        });
        // 「列表空白处长按手势」收进「个性设置」，跟着整块折叠（默认展开）
        personalBody.addView(gestureEnabledBox);
        // 长按底栏空白打开快面板：默认关闭，关掉后长按底栏不弹面板
        CheckBox quickPanelBox = new CheckBox(this);
        quickPanelBox.setText("长按底栏空白处打开快面板");
        quickPanelBox.setTextSize(11);
        quickPanelBox.setTextColor(getColor(R.color.text_secondary));
        quickPanelBox.setMinimumHeight(Ui.dp(this, 32));
        quickPanelBox.setChecked(quickPanelEnabled);
        quickPanelBox.setOnCheckedChangeListener((b, on) -> {
            quickPanelEnabled = on;
            prefs().edit().putBoolean(KEY_QUICK_PANEL_ENABLED, on).apply();
            if (!on) {
                cancelQuickPanelArm();
            }
        });
        personalBody.addView(quickPanelBox);
        // 全局美化同步：打开后壁纸 / 列表图标 / 界面透明度 / 底部冻结按钮 / 标签文字 / 应用名称在半屏、全屏之间同步；
        // 勾选时弹一次说明，点过「不再提醒」后不再自动弹出
        CheckBox globalBox = new CheckBox(this);
        globalBox.setText("全局美化同步");
        globalBox.setTextSize(11);
        globalBox.setTextColor(getColor(R.color.text_secondary));
        globalBox.setMinimumHeight(Ui.dp(this, 32));
        final boolean[] globalOnNow = {prefs().getBoolean(KEY_GLOBAL_THEME, false)};
        globalBox.setChecked(globalOnNow[0]);
        final android.widget.CompoundButton.OnCheckedChangeListener[] globalListener =
                new android.widget.CompoundButton.OnCheckedChangeListener[1];
        globalListener[0] = (b, on) -> {
            if (on == globalOnNow[0]) {
                return;
            }
            if (on && !hintSuffixHidden("global_beauty")) {
                // 勾选先弹说明：确认才真正打开，取消则把勾去掉
                showGlobalBeautyHint(
                        () -> {
                            globalOnNow[0] = true;
                            setGlobalTheme(true);
                        },
                        () -> {
                            b.setOnCheckedChangeListener(null);
                            b.setChecked(false);
                            b.setOnCheckedChangeListener(globalListener[0]);
                        });
                return;
            }
            globalOnNow[0] = on;
            setGlobalTheme(on);
        };
        globalBox.setOnCheckedChangeListener(globalListener[0]);
        // 全局美化同步紧跟在「默认全屏/半屏列表」下面；长按列表空白处手势放在「长按底栏空白处打开快面板」下面
        personalBody.addView(globalBox, 1);
        personalBody.removeView(gestureEnabledBox);
        personalBody.addView(gestureEnabledBox,
                personalBody.indexOfChild(quickPanelBox) + 1);
        int gestureVis = gestureEnabled ? View.VISIBLE : View.GONE;
        gestureTail.setVisibility(gestureVis);
        gestureSec.setVisibility(gestureVis);

        // 列数滑块：4–8 档，和上面几个滑块同一套样式。
        // 整块先收进 ias，最后整体插到「底部冻结按钮调节」上面
        LinearLayout ias = new LinearLayout(this);
        ias.setOrientation(LinearLayout.VERTICAL);
        TextView iconAdjustTitle = new TextView(this);
        iconAdjustTitle.setText("列表图标");
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
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit().putInt(otherKey(KEY_COLUMNS), gridColumns)
                            .putBoolean(otherKey(KEY_ICON_MANUAL), false).apply();
                }
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
        // 图标样式/列数/大小收进这一层，标题右侧箭头负责折叠
        final LinearLayout iconBody = new LinearLayout(this);
        iconBody.setOrientation(LinearLayout.VERTICAL);
        ias.addView(collapsibleHead(iconAdjustTitle, iconBody,
                KEY_SETTINGS_ICON_COLLAPSED, null, null, R.drawable.ic_qp_grid));
        ias.addView(iconBody);
        // 主题图标样式：点开选系统默认 / 当前主题图标，切换后立即重读图标
        final TextView themeIconEntry = new TextView(this);
        themeIconEntry.setText(themeIconEntryText());
        themeIconEntry.setTextSize(11);
        themeIconEntry.setTextColor(getColor(R.color.text_secondary));
        // 行距与「自定义壁纸」下面的「当前：未设置 / 将壁纸同步…」两行一致：
        // 这一行不加上下边距，下一行（冻结图标样式）自己带 4dp 上边距
        themeIconEntry.setPadding(0, 0, 0, 0);
        // 点击直接刷新图标（换完主题图标后不用重新进设置）；长按才切换系统默认 / 当前主题图标
        themeIconEntry.setOnClickListener(v -> {
            reloadIconsAfterThemeChange();
            toast("图标已刷新");
        });
        themeIconEntry.setOnLongClickListener(v -> {
            showIconThemeDialog();
            return true;
        });
        layoutThemeIconEntry = themeIconEntry;
        iconBody.addView(themeIconEntry);
        // 冻结图标样式：与「关联应用」那一行同字号的入口，点开选全彩 / 一半灰度一半彩色
        final TextView iconStyleEntry = new TextView(this);
        iconStyleEntry.setText(iconStyleEntryText());
        iconStyleEntry.setTextSize(11);
        iconStyleEntry.setTextColor(getColor(R.color.text_secondary));
        // 行距与上一行（更换图标后点击刷新 → 冻结图标样式）保持一致：上下各 4dp
        iconStyleEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        iconStyleEntry.setOnClickListener(v -> showIconStyleDialog());
        layoutIconStyleEntry = iconStyleEntry;
        iconBody.addView(iconStyleEntry);
        iconBody.addView(colsValue);
        iconBody.addView(colsBar);
        iconBody.addView(sizeLabel);
        iconBody.addView(sizeBar);
        box.addView(ias, Math.max(0, box.indexOfChild(fabHead)));
        // 手势触发距离整栏放在「标签（查看说明）」上面（tabTitle 已包进 tabHead，按整块定位）
        box.addView(gestureSec, Math.max(0, box.indexOfChild(tabHead)));
        // LSPosed 专属整块排在手势功能栏之后
        int lspIdx = Math.max(0, box.indexOfChild(gestureSec) + 1);
        box.addView(lspHead, lspIdx);
        box.addView(lspBody, lspIdx + 1);

        // 自定义壁纸：作为背景层的底图，透明度/模糊仍由上面的背景滑块叠加；半屏与全屏各存各的。
        // 视图先收进 wps，最后整体插到「底部冻结按钮调节」上面
        LinearLayout wps = new LinearLayout(this);
        wps.setOrientation(LinearLayout.VERTICAL);
        TextView wpTitle = new TextView(this);
        wpTitle.setText("自定义壁纸");
        wpTitle.setTextSize(12);
        wpTitle.setTextColor(getColor(R.color.text_primary));
        wpTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));

        final TextView wpState = new TextView(this);
        wpState.setTextSize(11);
        wpState.setTextColor(getColor(R.color.text_secondary));
        wpState.setText(wallpaperUri == null || wallpaperUri.isEmpty()
                ? "· 当前：未设置" : "· 当前：已设置");
        layoutWpState = wpState;

        LinearLayout wpRow = new LinearLayout(this);
        wpRow.setOrientation(LinearLayout.HORIZONTAL);
        wpRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView wpPick = new TextView(this);
        wpPick.setText("选择图片");
        wpPick.setTextSize(12);
        wpPick.setTextColor(getColor(R.color.blue));
        wpPick.setPadding(0, Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 8));
        wpPick.setOnClickListener(v -> wallpaperPicker.launch(new String[]{"image/*"}));

        final TextView wpClear = new TextView(this);
        wpClear.setText("清除");
        wpClear.setTextSize(12);
        wpClear.setTextColor(0xFFE5484D);
        wpClear.setPadding(0, Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 8));
        wpClear.setOnClickListener(v -> {
            clearCustomWallpaper();
            wpState.setText("· 当前：未设置");
            layoutWpState = wpState;
        });

        wpRow.addView(wpPick);
        wpRow.addView(wpClear);
        // 壁纸状态/操作/模糊/同步选项都收进这一层，标题右侧箭头可折叠
        final LinearLayout wpBody = new LinearLayout(this);
        wpBody.setOrientation(LinearLayout.VERTICAL);
        wps.addView(collapsibleHead(wpTitle, wpBody, KEY_SETTINGS_WALLPAPER_COLLAPSED,
                null, null, R.drawable.ic_qp_wallpaper));
        wps.addView(wpBody);
        wpBody.addView(wpState);
        // 「同步全局」：壁纸铺满全部界面（设置页、长按图标选择框、快面板、关于凚、
        // 更新日志、各种弹窗、各种弹出打字框等都同步），紧跟「当前」下面，
        // 行距与「将壁纸同步长按选项框」和「当前」之间一致
        layoutWpGlobalEntry = new TextView(this);
        layoutWpGlobalEntry.setText(wpGlobalEntryText());
        layoutWpGlobalEntry.setTextSize(11);
        layoutWpGlobalEntry.setTextColor(getColor(R.color.text_secondary));
        layoutWpGlobalEntry.setPadding(0, Ui.dp(this, 4), 0, 0);
        layoutWpGlobalEntry.setOnClickListener(v -> showWpGlobalDialog());
        wpBody.addView(layoutWpGlobalEntry);
        wpBody.addView(wpRow);

        // 自定义壁纸弹窗明暗度：0 = 全黑，100 = 原图，100–200 叠白提亮；出厂 100。
        // 放在模糊度上面，改完立刻刷新开着的设置页/快面板
        final TextView wpDimLabel = new TextView(this);
        wpDimLabel.setTextSize(11);
        wpDimLabel.setTextColor(getColor(R.color.text_secondary));
        wpDimLabel.setText("自定义壁纸弹窗明暗度：" + wallpaperBright + "%");
        SeekBar wpDimBar = new SeekBar(this);
        wpDimBar.setMax(200);
        wpDimBar.setProgress(wallpaperBright);
        wpDimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                wpDimLabel.setText("自定义壁纸弹窗明暗度：" + p + "%");
                wallpaperBright = Math.max(0, Math.min(200, p));
                refreshGlobalSurfaceBg();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                wallpaperBright = Math.max(0, Math.min(200, sb.getProgress()));
                prefs().edit().putInt(lk(KEY_WALLPAPER_DIM), wallpaperBright).apply();
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit().putInt(otherKey(KEY_WALLPAPER_DIM), wallpaperBright).apply();
                }
                refreshGlobalSurfaceBg();
            }
        });
        wpBody.addView(wpDimLabel);
        wpBody.addView(wpDimBar);

        // 壁纸自己的模糊度：0 完全清晰（默认），只影响这张壁纸，不动透明度/背景模糊那几条
        final TextView wpBlurLabel = new TextView(this);
        wpBlurLabel.setTextSize(11);
        wpBlurLabel.setTextColor(getColor(R.color.text_secondary));
        wpBlurLabel.setText("壁纸模糊度：" + wallpaperBlur + "%");
        SeekBar wpBlurBar = new SeekBar(this);
        wpBlurBar.setMax(100);
        wpBlurBar.setProgress(wallpaperBlur);
        wpBlurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                wpBlurLabel.setText("壁纸模糊度：" + p + "%");
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
                mirrorWallpaperToOtherIfGlobal();
                rebuildWallpaperDrawable();
                applyWallpaperBackground();
            }
        });
        wpBody.addView(wpBlurLabel);
        wpBody.addView(wpBlurBar);

        // 关联应用：独立一栏，放在自定义壁纸上面；点「（查看说明）」进说明，点标题折叠
        TextView pairTitle = new TextView(this);
        pairTitle.setText(hintTitle("关联应用", "pair"));
        pairTitle.setTextSize(12);
        pairTitle.setTextColor(getColor(R.color.text_primary));
        pairTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        // 与壁纸那行「当前：未设置」同字号/同次要色，和栏目标题区分开
        layoutPairEntry = new TextView(this);
        layoutPairEntry.setText(pairSummary());
        layoutPairEntry.setTextSize(11);
        layoutPairEntry.setTextColor(getColor(R.color.text_secondary));
        layoutPairEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        layoutPairEntry.setOnClickListener(v -> showPairManager());
        LinearLayout pairBody = new LinearLayout(this);
        pairBody.setOrientation(LinearLayout.VERTICAL);
        pairBody.addView(layoutPairEntry);
        final LinearLayout pairHead = collapsibleHead(pairTitle, pairBody,
                KEY_SETTINGS_PAIR_COLLAPSED, null, () -> showPairHintDialog(pairTitle),
                R.drawable.ic_qp_link);
        int pairIdx = Math.max(0, box.indexOfChild(ias));
        box.addView(pairHead, pairIdx);
        box.addView(pairBody, pairIdx + 1);

        // 冻结白名单：紧跟关联应用下面；标题 + 与主副应用关联同字号的「白名单列表」入口
        TextView wlTitle = new TextView(this);
        wlTitle.setText(hintTitle("列表白名单", "wl"));
        wlTitle.setTextSize(12);
        wlTitle.setTextColor(getColor(R.color.text_primary));
        wlTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        layoutWhitelistEntry = new TextView(this);
        layoutWhitelistEntry.setText("· 白名单（" + whitelistSet().size() + "个）");
        layoutWhitelistEntry.setTextSize(11);
        layoutWhitelistEntry.setTextColor(getColor(R.color.text_secondary));
        layoutWhitelistEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        layoutWhitelistEntry.setOnClickListener(v -> showWhitelistManager());
        LinearLayout wlBody = new LinearLayout(this);
        wlBody.setOrientation(LinearLayout.VERTICAL);
        // 标识位置：与「白名单（N个）」同一行样式，放在白名单入口上面
        layoutWhitePosEntry = new TextView(this);
        layoutWhitePosEntry.setText(whiteBadgePosText());
        layoutWhitePosEntry.setTextSize(11);
        layoutWhitePosEntry.setTextColor(getColor(R.color.text_secondary));
        layoutWhitePosEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        layoutWhitePosEntry.setOnClickListener(v -> showWhiteBadgePosDialog());
        wlBody.addView(layoutWhitePosEntry);
        wlBody.addView(layoutWhitelistEntry);
        final LinearLayout wlHead = collapsibleHead(wlTitle, wlBody,
                KEY_SETTINGS_WL_COLLAPSED, null, () -> showWhitelistHintDialog(wlTitle),
                R.drawable.ic_action_whitelist);
        int wlIdx = Math.max(0, box.indexOfChild(pairBody) + 1);
        box.addView(wlHead, wlIdx);
        box.addView(wlBody, wlIdx + 1);


        // 桌面图标模式：紧跟列表白名单下面；标题点开说明，入口点开二选一，入口带出当前选项
        TextView spTitle = new TextView(this);
        spTitle.setText(hintTitle("桌面图标模式", "sp"));
        spTitle.setTextSize(12);
        spTitle.setTextColor(getColor(R.color.text_primary));
        spTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        layoutKeepIconEntry = new TextView(this);
        layoutKeepIconEntry.setText(keepIconEntryText());
        layoutKeepIconEntry.setTextSize(11);
        layoutKeepIconEntry.setTextColor(getColor(R.color.text_secondary));
        layoutKeepIconEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        layoutKeepIconEntry.setOnClickListener(v -> showKeepIconChoiceDialog());
        LinearLayout spBody = new LinearLayout(this);
        spBody.setOrientation(LinearLayout.VERTICAL);
        spBody.addView(layoutKeepIconEntry);
        final LinearLayout spHead = collapsibleHead(spTitle, spBody,
                KEY_SETTINGS_SP_COLLAPSED, null, () -> showSpecialModeHintDialog(spTitle),
                R.drawable.ic_action_home);
        int spIdx = Math.max(0, box.indexOfChild(wlBody) + 1);
        box.addView(spHead, spIdx);
        box.addView(spBody, spIdx + 1);

        // 整块插到「标签设定（查看说明）」下面：就在列表图标调整之前
        box.addView(wps, Math.max(0, box.indexOfChild(ias)));
        // 标签设定整块挪到「自定义壁纸」上面（手势、LSPosed 等仍在它上面）
        box.removeView(tabHead);
        box.removeView(tabBody);
        int wpsIdx = box.indexOfChild(wps);
        box.addView(tabHead, wpsIdx);
        box.addView(tabBody, wpsIdx + 1);

        // 半屏上方「非面板区域」的遮罩强度：越小越透明（看得清背后页面），越大越模糊。
        // 整个界面透明度区块先收进 its，最后整体插到「底部冻结按钮调节」上面
        LinearLayout its = new LinearLayout(this);
        its.setOrientation(LinearLayout.VERTICAL);
        // 「界面透明度」：下面收着背景与搜索框两条透明度，标题右侧箭头可折叠
        TextView uiOpacityTitle = new TextView(this);
        uiOpacityTitle.setText("界面透明度");
        uiOpacityTitle.setTextSize(12);
        uiOpacityTitle.setTextColor(getColor(R.color.text_primary));
        uiOpacityTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        final LinearLayout itsBody = new LinearLayout(this);
        itsBody.setOrientation(LinearLayout.VERTICAL);
        its.addView(collapsibleHead(uiOpacityTitle, itsBody,
                KEY_SETTINGS_OPACITY_COLLAPSED, null, null, R.drawable.ic_qp_opacity));
        its.addView(itsBody);

        // 设置页透明度：面板本身和它的所有二级弹窗一起变，半屏/全屏各记一份
        final TextView settingsLabel = new TextView(this);
        settingsLabel.setTextSize(11);
        settingsLabel.setTextColor(getColor(R.color.text_secondary));
        final SeekBar settingsBar = new SeekBar(this);
        settingsBar.setMax(100);
        int settingsStart = settingsSurfacePct();
        settingsBar.setProgress(settingsStart);
        settingsLabel.setText(settingsSurfaceText(settingsStart));
        settingsBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                prefs().edit().putInt(lk(KEY_SETTINGS_SURFACE), p).apply();
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit().putInt(otherKey(KEY_SETTINGS_SURFACE), p).apply();
                }
                settingsLabel.setText(settingsSurfaceText(p));
                applySettingsSurfaceAlpha(settingsSurfaceRoot);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        // 这一组滑块的展示顺序在最后统一排（全屏/半屏各自一套），这里只登记内容
        final java.util.LinkedHashMap<String, View[]> opacityRows = new java.util.LinkedHashMap<>();
        opacityRows.put("settings", new View[]{settingsLabel, settingsBar});

        // 列表图标透明度：0 不透明，越大越透明；只影响列表格子里的应用图标
        final TextView iconAlphaLabel = new TextView(this);
        iconAlphaLabel.setTextSize(11);
        iconAlphaLabel.setTextColor(getColor(R.color.text_secondary));
        iconAlphaLabel.setText(iconAlphaText(iconAlpha));
        final SeekBar iconAlphaBar = new SeekBar(this);
        iconAlphaBar.setMax(100);
        iconAlphaBar.setProgress(iconAlpha);
        iconAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                iconAlpha = clampInt(p, 0, 100);
                prefs().edit().putInt(lk(KEY_ICON_ALPHA), iconAlpha).apply();
                if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                    prefs().edit().putInt(otherKey(KEY_ICON_ALPHA), iconAlpha).apply();
                }
                iconAlphaLabel.setText(iconAlphaText(iconAlpha));
                // 可见格子与滑出屏幕的缓存格子都要重绑，否则滚回来还是旧透明度
                rebindCells();
                if (gridAdapter != null) {
                    gridAdapter.rebindAllCached();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        opacityRows.put("icon", new View[]{iconAlphaLabel, iconAlphaBar});

        if (panelMode) {
            // 半屏外侧（上方遮罩）透明度：越大越透明，背后页面看得越清
            final TextView outerLabel = new TextView(this);
            outerLabel.setTextSize(11);
            outerLabel.setTextColor(getColor(R.color.text_secondary));
            final SeekBar outerBar = new SeekBar(this);
            outerBar.setMax(100);
            int outerStart = 100 - Math.max(0, Math.min(100,
                    prefs().getInt(lk(KEY_BLUR), 20)));
            outerBar.setProgress(outerStart);
            outerLabel.setText("半屏外侧：" + outerStart + "%");
            outerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(lk(KEY_BLUR), 100 - p).apply();
                    outerLabel.setText("半屏外侧：" + p + "%");
                    applyBackdropStrength();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });
            opacityRows.put("outer", new View[]{outerLabel, outerBar});

            // 半屏内侧（面板本体）透明度
            final TextView innerLabel = new TextView(this);
            innerLabel.setTextSize(11);
            innerLabel.setTextColor(getColor(R.color.text_secondary));
            final SeekBar innerBar = new SeekBar(this);
            innerBar.setMax(100);
            int innerStart = 100 - Math.max(0, Math.min(100,
                    prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID)));
            innerBar.setProgress(innerStart);
            innerLabel.setText("半屏列表背景：" + innerStart + "%");
            innerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(lk(KEY_SURFACE), 100 - p).apply();
                    // 半屏面板透明度 ↔ 全屏背景：开了同步就一起写
                    if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                        prefs().edit().putInt(KEY_FULL_SURFACE, 100 - p).apply();
                    }
                    innerLabel.setText("半屏列表背景：" + p + "%");
                    applyPanelSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });
            opacityRows.put("inner", new View[]{innerLabel, innerBar});
        } else {
            // 全屏背景透明度：整页底板（含状态栏那条）一起淡出
            final TextView bgLabel = new TextView(this);
            bgLabel.setTextSize(11);
            bgLabel.setTextColor(getColor(R.color.text_secondary));
            final SeekBar bgBar = new SeekBar(this);
            bgBar.setMax(100);
            int bgStart = 100 - Math.max(0, Math.min(100,
                    prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID)));
            bgBar.setProgress(bgStart);
            bgLabel.setText("全屏列表背景：" + bgStart + "%");
            bgBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(KEY_FULL_SURFACE, 100 - p).apply();
                    // 全屏背景 ↔ 半屏面板透明度：开了同步就一起写
                    if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                        prefs().edit().putInt("panel_" + KEY_SURFACE, 100 - p).apply();
                    }
                    bgLabel.setText("全屏列表背景：" + p + "%");
                    // 真正改窗口底板 alpha 与模糊的是 applyFullscreenSurface（整页背景唯一出口）
                    applyFullscreenSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });
            opacityRows.put("bg", new View[]{bgLabel, bgBar});

            // 横幅（全屏顶部冻结列表那条）透明度：滑块与「全屏背景」同一套逻辑
            // （0 = 不透明、100 = 全透明），档位单独记忆，没设过时跟随全屏背景
            final TextView headerLabel = new TextView(this);
            headerLabel.setTextSize(11);
            headerLabel.setTextColor(getColor(R.color.text_secondary));
            final SeekBar headerBar = new SeekBar(this);
            headerBar.setMax(100);
            int headerStart = 100 - headerSurfacePct();
            headerBar.setProgress(headerStart);
            headerLabel.setText("横幅：" + headerStart + "%");
            headerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(KEY_HEADER_SURFACE, 100 - p).apply();
                    headerLabel.setText("横幅：" + p + "%");
                    // 顶栏档位在 applyFullscreenSurface 里统一铺（page_header 那一层）
                    applyFullscreenSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });
            opacityRows.put("header", new View[]{headerLabel, headerBar});
        }

        // 搜索框透明度：只属于全屏（半屏底部没有搜索框）；独立档位，与背景档位解耦
        if (!panelMode) {
            final TextView searchLabel = new TextView(this);
            searchLabel.setTextSize(11);
            searchLabel.setTextColor(getColor(R.color.text_secondary));

            final SeekBar searchBar = new SeekBar(this);
            searchBar.setMax(100);
            int searchStart = 100 - searchPct();
            searchBar.setProgress(searchStart);
            searchLabel.setText("搜索框：" + searchStart + "%");
            searchBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    // 滑块值 = 透明度：0 不透明，100 全透明
                    prefs().edit().putInt(lk(KEY_SEARCH_SURFACE), 100 - p).apply();
                    searchLabel.setText("搜索框：" + p + "%");
                    applySearchSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });
            opacityRows.put("search", new View[]{searchLabel, searchBar});

            // 全屏外模糊：0 = 外面一点不糊（透明），越大越模糊；与「全屏背景」解耦
            final TextView blurLabel = new TextView(this);
            blurLabel.setTextSize(11);
            blurLabel.setTextColor(getColor(R.color.text_secondary));
            final SeekBar blurBar = new SeekBar(this);
            blurBar.setMax(100);
            int blurStart = fullBlurPct();
            blurBar.setProgress(blurStart);
            blurLabel.setText("全屏外模糊：" + blurStart + "%");
            blurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    prefs().edit().putInt(KEY_FULL_BLUR, p).apply();
                    blurLabel.setText("全屏外模糊：" + p + "%");
                    // 窗口模糊半径在 applyWindowBackdrop 里统一挂
                    applyFullscreenSurface();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });
            opacityRows.put("blur", new View[]{blurLabel, blurBar});
        }
        // 快面板透明度：独立档位，0 不透明、越大越透明；不再跟随「设置页」那条
        final TextView qpAlphaLabel = new TextView(this);
        qpAlphaLabel.setTextSize(11);
        qpAlphaLabel.setTextColor(getColor(R.color.text_secondary));
        final SeekBar qpAlphaBar = new SeekBar(this);
        qpAlphaBar.setMax(100);
        int qpAlphaStart = quickPanelSurfacePct();
        qpAlphaBar.setProgress(qpAlphaStart);
        qpAlphaLabel.setText("快面板：" + qpAlphaStart + "%");
        qpAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                prefs().edit().putInt(KEY_QUICK_PANEL_SURFACE, p).apply();
                qpAlphaLabel.setText("快面板：" + p + "%");
                if (quickPanelDialog != null) {
                    applyQuickPanelSurfaceAlpha(quickPanelDialog);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        opacityRows.put("qp", new View[]{qpAlphaLabel, qpAlphaBar});
        // 统一按最终顺序铺进组体：
        // 全屏：横幅、搜索、列表图标、全屏背景、全屏外模糊、快面板、设置页
        // 半屏：列表图标、半屏面板、半屏外侧、快面板、设置页
        String[] opacityOrder = panelMode
                ? new String[]{"icon", "inner", "outer", "qp", "settings"}
                : new String[]{"header", "search", "icon", "bg", "blur", "qp", "settings"};
        for (String key : opacityOrder) {
            View[] row = opacityRows.get(key);
            if (row != null) {
                itsBody.addView(row[0]);
                itsBody.addView(row[1]);
            }
        }
        box.addView(its, Math.max(0, box.indexOfChild(fabHead)));

        // 备份 / 恢复 / 旧版导入：放在所有开关与滑块之后，作为布局设置的最后入口
        TextView backupTitle = new TextView(this);
        backupTitle.setText("其他");
        backupTitle.setTextSize(12);
        backupTitle.setTextColor(getColor(R.color.text_primary));
        backupTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));

        TextView backupEntry = new TextView(this);
        backupEntry.setText("备份");
        backupEntry.setTextSize(12);
        backupEntry.setTextColor(getColor(R.color.blue));
        backupEntry.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        TextView restoreEntry = new TextView(this);
        restoreEntry.setText("恢复");
        restoreEntry.setTextSize(12);
        restoreEntry.setTextColor(getColor(R.color.blue));
        restoreEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));

        box.addView(backupTitle);
        box.addView(backupEntry);
        box.addView(restoreEntry);

        // 恢复名单下方：关于凚（简要介绍与功能速览）
        TextView aboutEntry = new TextView(this);
        aboutEntry.setText("关于凚");
        aboutEntry.setTextSize(12);
        aboutEntry.setTextColor(getColor(R.color.blue));
        aboutEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        box.addView(aboutEntry);

        // 关于凚下方：致谢名单（赞赏与测试名单，点开查看）
        TextView thanksEntry = new TextView(this);
        thanksEntry.setText("致谢名单");
        thanksEntry.setTextSize(12);
        thanksEntry.setTextColor(getColor(R.color.blue));
        thanksEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        box.addView(thanksEntry);

        // 最底部：赞赏开发者（点开显示收款码，可保存到相册）
        TextView donateEntry = new TextView(this);
        donateEntry.setText("赞赏开发者");
        donateEntry.setTextSize(12);
        donateEntry.setTextColor(getColor(R.color.blue));
        donateEntry.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        box.addView(donateEntry);

        // 「其他」标题可点：折叠下面的备份、恢复、关于凚、致谢名单、赞赏开发者；
        // 版本号单独放在折叠区外，始终可见
        final LinearLayout otherGroup = new LinearLayout(this);
        otherGroup.setOrientation(LinearLayout.VERTICAL);
        for (View child : new View[]{backupEntry, restoreEntry, aboutEntry, thanksEntry, donateEntry}) {
            box.removeView(child);
            otherGroup.addView(child);
        }
        box.addView(collapsibleHead(backupTitle, otherGroup,
                KEY_SETTINGS_OTHER_COLLAPSED, null, null, R.drawable.ic_qp_info));
        box.addView(otherGroup);

        // 自定义底板：宽度收窄、固定高度内滚动、真圆角裁切（全屏与半屏共用同一套尺寸）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 弹窗外层留出阴影绘制空间：圆角 outline + elevation，阴影跟着圆角走
        final int shadowPad = Ui.dp(this, 12);
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        root.setElevation(Ui.dp(this, night ? 18 : 10));
        // 只保留四周淡淡的环境阴影，去掉主要落在下方的点光源阴影
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.setOutlineSpotShadowColor(android.graphics.Color.TRANSPARENT);
        }
        settingsSurfaceRoot = root;

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
        if (gestureHint != null) {
            root.addView(gestureHint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        root.addView(gestureHintExpand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(gestureHintCollapse, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(gestureHintUnfreeze, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.ScrollView dialogScroll = new android.widget.ScrollView(this);
        dialogScroll.setClipToPadding(false);
        dialogScroll.setFillViewport(true);
        // 去掉设置列表右侧滚动条：那条竖线在弹窗里像多出来的「尾巴」
        dialogScroll.setVerticalScrollBarEnabled(false);
        dialogScroll.addView(box, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(dialogScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 「面板未展开高度」滑块：放在设置列表下方（全屏/半屏都有，半屏仍受面板上沿限制）
        // 一行三段：左边标题，中间滑块，右边 dp 值；滑块贴近文字，dp 值贴近滑块
        LinearLayout heightRow = new LinearLayout(this);
        heightRow.setOrientation(LinearLayout.HORIZONTAL);
        heightRow.setGravity(Gravity.CENTER_VERTICAL);
        heightRow.setPadding(pad, Ui.dp(this, 2), pad, 0);
        final TextView openLabel = Ui.text(this, "面板未展开高度", 10, Ui.TEXT_SECONDARY, false);
        final TextView openValue = Ui.text(this, "", 10, Ui.TEXT_SECONDARY, false);
        openValue.setGravity(Gravity.START);
        openValue.setMinWidth(Ui.dp(this, 34));
        final SeekBar openBar = new SeekBar(this);
        // 左右内边距留够滑块半径再加 2dp，保证起止位置的小球完整不被裁掉
        int thumbPad = Ui.dp(this, 12);
        openBar.setPadding(thumbPad, openBar.getPaddingTop(), thumbPad, openBar.getPaddingBottom());
        LinearLayout.LayoutParams openBarLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 76), ViewGroup.LayoutParams.WRAP_CONTENT);
        openBarLp.leftMargin = 0;
        openBarLp.rightMargin = 0;
        heightRow.addView(openLabel);
        heightRow.addView(openBar, openBarLp);
        heightRow.addView(openValue);
        // 右侧留白吸收剩余宽度，dp 值始终紧贴滑块
        heightRow.addView(new View(this), new LinearLayout.LayoutParams(
                0, 1, 1f));
        // 高度行插到滚动列表上方（提示行与「个性设置」之间），列表本身不动；半屏不提供这一行
        if (!panelMode) {
            root.addView(heightRow, Math.max(0, root.indexOfChild(dialogScroll)),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));

        // 倒三角那一行的整行都是热区：图形本身不吃点击，点到两侧空白同样能展开/收起
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER);
        toggleRow.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
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
        sizeToggle.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        toggleRow.addView(sizeToggle, new LinearLayout.LayoutParams(
                Ui.dp(this, 28), Ui.dp(this, 28)));
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
        settingsDialog = dialog;
        Ui.setSettingsHost(dialog);
        dialog.setOnDismissListener(d -> {
            Ui.clearSettingsHost(dialog);
            if (settingsDialog == dialog) {
                settingsDialog = null;
            }
        });
        done.setOnClickListener(v -> dialog.dismiss());
        backupEntry.setOnClickListener(v -> showBackup());
        restoreEntry.setOnClickListener(v -> showRestore());
        donateEntry.setOnClickListener(v -> showDonateDialog());
        aboutEntry.setOnClickListener(v -> showAboutDialog());
        thanksEntry.setOnClickListener(v -> showThanksDialog());

        // 未展开范围滑块：半屏固定显示「不侵入半屏」的最大值；全屏可手动调节并记忆
        final int[] openMaxDp = {openSliderMaxDp()};
        openBar.setMax(openMaxDp[0]);
        openBar.setProgress(Math.min(openSliderDp(), openMaxDp[0]));
        openValue.setText(openBar.getProgress() + "dp");
        openBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int dp = clampInt(p, 0, openMaxDp[0]);
                if (!panelMode) {
                    prefs().edit().putInt(lk(KEY_LAYOUT_OPEN_H), dp).apply();
                }
                openValue.setText(dp + "dp");
                if (panelMode) {
                    // 半屏：只本次调短，不落盘；面板始终贴半屏上沿
                    panelOpenHeightDp = dp;
                    relayoutPanelDialog(dialog);
                } else {
                    applyDialogHeight(dialog, openDialogHeightPx(), shadowPad);
                }
                dialog.getWindow().getDecorView().post(() ->
                        enforceDialogBottomLimit(dialog, openBottomLimitPx(), shadowPad));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        // ▼ / ▲：在「未展开高度」与自适应最大高度之间切换，展开时按底栏上沿收窄
        final boolean[] rowExpanded = {false};
        final boolean[] rowHidden = {false};
        // 提示行整块随「展开/收起」收放，各行再按自己点过的「不再提示」决定显隐
        final Runnable applyHintRows = () -> {
            for (View row : hintRows) {
                String key = (String) row.getTag();
                row.setVisibility(rowExpanded[0] || prefs().getBoolean(key, false)
                        ? View.GONE : View.VISIBLE);
            }
        };
        collapseHeightRow[0] = () -> {
            if (!rowExpanded[0]) {
                return;
            }
            rowExpanded[0] = false;
            rowHidden[0] = false;
            layoutExpanded = false;
            sizeToggle.setImageResource(R.drawable.ic_chevron_down);
            heightRow.animate().cancel();
            heightRow.setAlpha(1f);
            heightRow.setVisibility(View.VISIBLE);
            applyHintRows.run();
            animateDialogFrame(dialog, openDialogHeightPx(),
                    panelMode ? panelDialogY(shadowPad) : openDialogTopY(shadowPad), shadowPad);
        };
        toggleRow.setOnClickListener(v -> {
            boolean show = !rowExpanded[0];
            rowExpanded[0] = show;
            layoutExpanded = show;
            sizeToggle.setImageResource(show
                    ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
            // 展开态不显示「未展开范围」滑块与展开提示，收起后恢复
            rowHidden[0] = false;
            heightRow.animate().cancel();
            heightRow.setAlpha(1f);
            heightRow.setVisibility(show ? View.GONE : View.VISIBLE);
            applyHintRows.run();
            int desired = show ? expandDialogHeightPx() : openDialogHeightPx();
            int targetY;
            if (show) {
                // 展开：顶部放得下就往下长；放不下（未展开时被移到下面）就整体上移，
                // 底边始终不超过底栏上沿，表现为往顶部延伸
                int screenH = getResources().getDisplayMetrics().heightPixels;
                int limit = bottomBarTopPx();
                int maxBottom = limit > 0 ? limit - Ui.dp(this, 8) : screenH;
                // 窗口坐标从状态栏下方起算，夹紧时要扣掉状态栏，否则底边会压进底栏
                int maxY = maxBottom - desired - shadowPad - statusBarHeightPx();
                int curY = dialog.getWindow() == null
                        ? -shadowPad : dialog.getWindow().getAttributes().y;
                // 全屏：展开后的顶边不能越过顶栏（横幅）下沿；半屏保持原逻辑
                targetY = panelMode
                        ? Math.max(-shadowPad, Math.min(curY, maxY))
                        : Math.max(expandedTopLimitY(shadowPad), maxY);
            } else {
                // 收起：回到打开时的未展开位置，而不是停在展开后的地方
                targetY = panelMode ? panelDialogY(shadowPad)
                        : (layoutOpenY != Integer.MIN_VALUE ? layoutOpenY
                        : openDialogTopY(shadowPad));
            }
            animateDialogFrame(dialog, desired, targetY, shadowPad);
        });
        // 长按底部展开键：一键把设置里全部分组标题收起（短按仍是展开/收起面板）
        toggleRow.setOnLongClickListener(v -> {
            for (Runnable collapse : settingsCollapsers) {
                collapse.run();
            }
            return true;
        });

        // 滑动隐藏沿用之前的规则：往下滑就收起「未展开范围」行，只有回到顶部才重新显示；
        // 窗口高度与位置全程不动。
        final boolean[] fingerDown = {false};
        final long[] suppressUntil = {0};
        final Runnable showToggleRow = () -> {
            if (!rowHidden[0] || layoutExpanded) {
                return;
            }
            rowHidden[0] = false;
            heightRow.animate().cancel();
            heightRow.setVisibility(View.VISIBLE);
            heightRow.setAlpha(0f);
            heightRow.animate().alpha(1f).setDuration(120).start();
            suppressUntil[0] = android.os.SystemClock.uptimeMillis() + 400;
        };
        final Runnable hideToggleRow = () -> {
            if (rowHidden[0]) {
                return;
            }
            rowHidden[0] = true;
            heightRow.animate().cancel();
            heightRow.animate().alpha(0f).setDuration(100)
                    .withEndAction(() -> heightRow.setVisibility(View.GONE))
                    .start();
            suppressUntil[0] = android.os.SystemClock.uptimeMillis() + 400;
        };
        dialogScroll.setOnTouchListener((tv, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_MOVE:
                    // 起点落在可点击子 View 上时 DOWN 会被子 View 吃掉，这里收不到；
                    // 只要真的在动就同样算「手指按住」
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
            if (dy == 0) {
                return;
            }
            // 弹窗自身改高度/换尺寸引起的滚动不算用户动作，直接忽略
            if (android.os.SystemClock.uptimeMillis() < suppressUntil[0]) {
                return;
            }
            if (!rowHidden[0]) {
                if (dy > 0) {
                    hideToggleRow.run();
                }
                return;
            }
            // 已收起：只有真正回到顶部（手指按住时判定）才重新显示
            if (fingerDown[0] && !dialogScroll.canScrollVertically(-1)) {
                showToggleRow.run();
            }
        });

        dialog.setOnShowListener(d -> {
            android.view.Window w = dialog.getWindow();
            if (w == null) {
                return;
            }
            // 首帧兜底：万一参数在 show 前没生效，这里再压一次同样的值，避免露默认位
            applyDialogGeometry(dialog, w, root, title, defaultDialogWidth(), shadowPad);
            // 布局落地后重新算「不侵入半屏」的最大值：半屏直接按最大值显示
            w.getDecorView().post(() -> {
                int maxDp = openSliderMaxDp();
                openMaxDp[0] = maxDp;
                if (openBar.getMax() != maxDp) {
                    openBar.setMax(maxDp);
                }
                int cur = panelMode ? maxDp : Math.min(openBar.getProgress(), maxDp);
                if (cur != openBar.getProgress()) {
                    openBar.setProgress(cur);
                }
                openValue.setText(cur + "dp");
                if (panelMode) {
                    relayoutPanelDialog(dialog);
                }
                enforceDialogBottomLimit(dialog, openBottomLimitPx(), shadowPad);
            });
        });
        // 在 show 之前就把坐标与高度写进窗口参数：第一帧直接落在记忆位置，不再先默认后跳
        android.view.Window pre = dialog.getWindow();
        if (pre != null) {
            applyDialogGeometry(dialog, pre, root, title, defaultDialogWidth(), shadowPad);
        }
        dialog.setOnDismissListener(d -> {
            if (dialogFrameAnim != null) {
                dialogFrameAnim.cancel();
                dialogFrameAnim = null;
            }
            layoutExpanded = false;
            layoutOpenY = Integer.MIN_VALUE;
            layoutDialog = null;
            panelOpenHeightDp = -1;
            settingsSurfaceRoot = null;
        });
        // 只把勾选框里的方块画小一点：字体、行距、文字位置都保持原样
        shrinkCheckBoxes(box);
        dialog.show();
        if (expandLayoutOnShow) {
            expandLayoutOnShow = false;
            // 等首帧几何落定后再进「展开最大范围」，否则会按未测量的高度算错位置
            toggleRow.postDelayed(toggleRow::performClick, 120);
        }
        // 记住设置面板卡片的真实左边缘与宽度，供二级弹窗对齐（面板可能被拖动过）
        android.view.Window shown = dialog.getWindow();
        if (shown != null) {
            settingsPanelLeft = shown.getAttributes().x + shadowPad;
        }
        root.post(() -> {
            if (root.getWidth() > 0) {
                settingsPanelCardWidth = root.getWidth();
            }
        });
        // 弹窗内容比可视区高，防止某个控件抢焦点把滚动区带到底部
        dialogScroll.post(() -> dialogScroll.scrollTo(0, 0));
    }

    /**
     * 设置面板里带方框的选项：文字略放大，方块画小一圈；方块沿用原布局尺寸，
     * 所以行高、行距、文字起始位置都不变。
     */
    private void shrinkCheckBoxes(View v) {
        if (v instanceof CheckBox) {
            styleSettingCheckBox((CheckBox) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                shrinkCheckBoxes(g.getChildAt(i));
            }
        }
    }

    /** 带方框的选项文字略大一点点，方块和右边的文字同样大、紧挨着文字。 */
    private void styleSettingCheckBox(CheckBox cb) {
        cb.setTextSize(TypedValue.COMPLEX_UNIT_PX, cb.getTextSize() * 1.02f);
        shrinkCheckBox(cb);
    }

    /** 单个勾选框的缩小处理；标签列表这种动态重建的勾选框也要单独调用，避免删标签后恢复成大方块。 */
    private void shrinkCheckBox(CheckBox cb) {
        Drawable d = cb.getButtonDrawable();
        if (d == null) {
            return;
        }
        int h = d.getIntrinsicHeight();
        if (h <= 0) {
            h = Ui.dp(this, 22);
        }
        // 方块比文字略大一点（矢量图自带 1/6 透明边），后面留 3dp 小间距，别贴太紧
        int side = Math.round(cb.getTextSize() * 1.55f);
        int slot = side + Ui.dp(this, 3);
        cb.setButtonDrawable(new SquareCheckDrawable(d, side, slot, h));
    }

    /** 布局宽 = 方块宽 + 小间距（文字与方块保持一点距离），高度沿用原图案（行高不变）。 */
    private static class SquareCheckDrawable extends DrawableWrapper {
        private final int side;
        private final int width;
        private final int height;

        SquareCheckDrawable(Drawable base, int side, int width, int height) {
            super(base);
            this.side = side;
            this.width = width;
            this.height = height;
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            int top = b.top + Math.max(0, (b.height() - side) / 2);
            getDrawable().setBounds(b.left, top, b.left + side, top + side);
            getDrawable().draw(canvas);
        }
    }

    /** 设置面板里「主副应用关联」这一行的摘要文案（前面带与关于页同款的小圆点）。 */
    private String pairSummary() {
        List<Pair> list = allPairs();
        if (list.isEmpty()) {
            return "· 主副应用关联";
        }
        int paused = 0;
        for (Pair p : list) {
            if (!p.enabled) {
                paused++;
            }
        }
        String text = "· 主副应用关联（" + list.size() + " 组";
        if (paused > 0) {
            text += "，" + paused + " 组已暂停";
        }
        return text + "）";
    }

    /** 「主题图标样式」这一行的文字：它本质是刷新入口，当前样式在长按弹窗里选。 */
    private String themeIconEntryText() {
        return "· 更换图标后点击刷新";
    }

    /** 主题图标样式：系统默认 / 当前主题图标；选完立即作废缓存并重载列表图标。 */
    private void showIconThemeDialog() {
        final int current = AppIconCache.getMode(this) == AppIconCache.MODE_THEME ? 1 : 0;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("主题图标样式")
                .setSingleChoiceItems(ICON_THEME_MODE_NAMES, current, (dialog, which) -> {
                    dialog.dismiss();
                    // 即使选的是当前项也重载一次：换完主题后点一下就能手动刷新图标
                    AppIconCache.setMode(this, which == 1
                            ? AppIconCache.MODE_THEME : AppIconCache.MODE_SYSTEM);
                    if (layoutThemeIconEntry != null) {
                        layoutThemeIconEntry.setText(themeIconEntryText());
                    }
                    reloadIconsAfterThemeChange();
                })
                .setNegativeButton("取消", null)
                .create();
        d.show();
        Ui.roundAlertDialog(d);
        applySettingsAlignment(d);
    }

    /** 换图标来源后：清两级缓存、丢掉内存里已渲染的旧图标，再整表重取。 */
    private void reloadIconsAfterThemeChange() {
        // 点击刷新时模式可能没变，这里主动作废缓存，保证一定重新解码
        AppIconCache.clear(this);
        for (Item it : items) {
            it.icon = null;
        }
        if (gridAdapter != null) {
            gridAdapter.rebindAllCached();
        }
        items.clear();
        reload();
    }

    /** 「冻结图标样式」行显示的当前选项。 */
    private String iconStyleEntryText() {
        return "· 冻结图标样式：" + ICON_STYLE_NAMES[iconStyleIndex()];
    }

    /** 当前样式在选项列表里的位置。 */
    private int iconStyleIndex() {
        for (int i = 0; i < ICON_STYLE_VALUES.length; i++) {
            if (ICON_STYLE_VALUES[i] == iconStyle) {
                return i;
            }
        }
        return 0;
    }

    /** 「冻结图标样式」：每项左侧先给样式示意图，再接名称；全彩排在最前。 */
    private void showIconStyleDialog() {
        final AlertDialog[] holder = new AlertDialog[1];
        final int current = iconStyleIndex();
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return ICON_STYLE_NAMES.length;
            }

            @Override
            public Object getItem(int position) {
                return ICON_STYLE_NAMES[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = new LinearLayout(FreezeListActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(FreezeListActivity.this, 20), Ui.dp(FreezeListActivity.this, 9),
                        Ui.dp(FreezeListActivity.this, 20), Ui.dp(FreezeListActivity.this, 9));

                // 示意图：蓝=彩色半边，灰=灰化半边，按方向切分，比直接灰化单色图标更容易分辨
                StylePreviewView preview = new StylePreviewView(FreezeListActivity.this,
                        ICON_STYLE_VALUES[position]);
                row.addView(preview, new LinearLayout.LayoutParams(
                        Ui.dp(FreezeListActivity.this, 28), Ui.dp(FreezeListActivity.this, 28)));

                TextView label = Ui.text(FreezeListActivity.this, ICON_STYLE_NAMES[position], 14,
                        position == current ? Ui.BLUE : Ui.TEXT_PRIMARY, position == current);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.leftMargin = Ui.dp(FreezeListActivity.this, 14);
                row.addView(label, tlp);
                return row;
            }
        };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("冻结图标样式")
                .setAdapter(adapter, (dialog, which) -> {
                    iconStyle = ICON_STYLE_VALUES[which];
                    prefs().edit().putInt(KEY_ICON_STYLE, iconStyle).apply();
                    if (layoutIconStyleEntry != null) {
                        layoutIconStyleEntry.setText(iconStyleEntryText());
                    }
                    rebindCells();
                    // 样式对所有冻结图标生效：滑出屏幕被缓存的旧格子也要重新绑定，
                    // 否则滚回屏幕时会直接复用旧样式（被设置弹窗挡住/列表底部时最明显）
                    if (gridAdapter != null) {
                        gridAdapter.rebindAllCached();
                    }
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                })
                .setNegativeButton("返回", null)
                .create();
        holder[0] = d;
        d.show();
        Ui.roundAlertDialog(d);
        applySettingsAlignment(d);
    }

    /** 冻结图标样式示意图：蓝色为彩色半边，灰色为灰化半边，按样式方向切分。 */
    private static class StylePreviewView extends View {
        private final int split;

        StylePreviewView(android.content.Context c, int split) {
            super(c);
            this.split = split;
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float r = Math.min(w, h) * 0.22f;
            android.graphics.RectF all = new android.graphics.RectF(0, 0, w, h);
            android.graphics.Path outline = new android.graphics.Path();
            outline.addRoundRect(all, r, r, android.graphics.Path.Direction.CW);

            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF3B82F6);
            cv.save();
            cv.clipPath(outline);
            cv.drawRect(all, p);
            cv.restore();

            android.graphics.Path gray = grayArea(w, h);
            if (gray != null) {
                p.setColor(0xFFAEB6C2);
                cv.save();
                cv.clipPath(outline);
                cv.clipPath(gray);
                cv.drawRect(all, p);
                cv.restore();
            }

            android.graphics.Paint stroke = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, w * 0.03f));
            stroke.setColor(0x1F000000);
            cv.drawPath(outline, stroke);
        }

        /** 灰化区域；返回 null 表示全彩。 */
        private android.graphics.Path grayArea(float w, float h) {
            android.graphics.Path p = new android.graphics.Path();
            float mx = w / 2f;
            float my = h / 2f;
            switch (split) {
                case 0:
                    return null;
                case 1:
                    p.addRect(0, 0, mx, h, android.graphics.Path.Direction.CW);
                    break;
                case 2:
                    p.addRect(mx, 0, w, h, android.graphics.Path.Direction.CW);
                    break;
                case 3:
                    p.addRect(0, 0, w, my, android.graphics.Path.Direction.CW);
                    break;
                case 4:
                    p.addRect(0, my, w, h, android.graphics.Path.Direction.CW);
                    break;
                case 5:
                    p.moveTo(0, 0);
                    p.lineTo(w, 0);
                    p.lineTo(0, h);
                    p.close();
                    break;
                case 6:
                    p.moveTo(0, 0);
                    p.lineTo(w, 0);
                    p.lineTo(w, h);
                    p.close();
                    break;
                case 7:
                    p.moveTo(0, 0);
                    p.lineTo(0, h);
                    p.lineTo(w, h);
                    p.close();
                    break;
                case 8:
                    p.moveTo(w, 0);
                    p.lineTo(w, h);
                    p.lineTo(0, h);
                    p.close();
                    break;
                default:
                    p.addRect(0, 0, w, h, android.graphics.Path.Direction.CW);
                    break;
            }
            return p;
        }
    }

    /** 「关联应用」标题点开的说明卡片，样式与标签说明一致。 */
    private void showPairHintDialog(TextView titleView) {
        showHintCard("「关联应用」\n"
                + "·启动主应用:后台解冻副应用；\n"
                + "·冻结主应用:静默冻结副应用；\n"
                + "·冻结副应用:仅作用于副应用；\n"
                + "·暂停:关闭该条的联动。",
                "pair", titleView, "关联应用");
    }

    /** 关联应用 → 生效筛选 + 单项暂停/删除 + 多选批量恢复/删除/暂停。 */
    private void showPairManager() {
        final int actionNone = 0;
        final int actionResume = 1;
        final int actionDelete = 2;
        final int actionPause = 3;
        final String[] actionNames = {"", "恢复", "删除", "暂停"};
        final int[] batchAction = {actionNone};
        final int[] filter = {0}; // 0=全部，1=生效，2=未生效
        final Set<String> checked = new HashSet<>();
        final AlertDialog[] holder = new AlertDialog[1];
        final Runnable[] renderList = new Runnable[1];

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 16);
        root.setPadding(side, Ui.dp(this, 6), side, Ui.dp(this, 2));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "主副应用关联", 16, Ui.TEXT_PRIMARY, true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final android.widget.ImageButton addBtn = whitelistHeaderBtn(R.drawable.ic_add, "添加");
        addBtn.setOnClickListener(v -> showPairMainPicker());
        header.addView(addBtn);
        final android.widget.ImageButton backBtn = whitelistHeaderBtn(R.drawable.ic_back, "返回");
        header.addView(backBtn);
        root.addView(header);

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        String[] filterNames = {"全部", "生效", "未生效"};
        TextView[] filterViews = new TextView[filterNames.length];
        for (int i = 0; i < filterNames.length; i++) {
            final int index = i;
            TextView item = Ui.text(this, filterNames[i], 12, Ui.TEXT_SECONDARY, false);
            // 第一个筛选项去掉左内边距，文字左边与顶部标题「主副应用关联」对齐
            item.setPadding(i == 0 ? 0 : Ui.dp(this, 8), Ui.dp(this, 5),
                    Ui.dp(this, 8), Ui.dp(this, 5));
            item.setOnClickListener(v -> {
                filter[0] = index;
                checked.clear();
                renderList[0].run();
            });
            filterViews[i] = item;
            filterRow.addView(item);
        }
        root.addView(filterRow);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);
        root.addView(sv);

        // 当前列表：普通态看筛选；批量恢复/暂停只看可执行该项的关联，删除看全部。
        final java.util.function.Supplier<List<Pair>> shownPairs = () -> {
            List<Pair> out = new ArrayList<>();
            for (Pair p : allPairs()) {
                if (batchAction[0] == actionResume && p.enabled) {
                    continue;
                }
                if (batchAction[0] == actionPause && !p.enabled) {
                    continue;
                }
                if (batchAction[0] == actionNone) {
                    if (filter[0] == 1 && !p.enabled) {
                        continue;
                    }
                    if (filter[0] == 2 && p.enabled) {
                        continue;
                    }
                }
                out.add(p);
            }
            return out;
        };

        renderList[0] = () -> {
            boolean multi = batchAction[0] != actionNone;
            title.setText(multi ? "批量" + actionNames[batchAction[0]] : "主副应用关联");
            backBtn.setVisibility(multi ? View.VISIBLE : View.GONE);
            filterRow.setVisibility(multi ? View.GONE : View.VISIBLE);
            addBtn.setVisibility(multi ? View.GONE : View.VISIBLE);
            for (int i = 0; i < filterViews.length; i++) {
                boolean selected = filter[0] == i;
                filterViews[i].setTextColor(getColor(selected ? R.color.blue : R.color.text_secondary));
                filterViews[i].setTypeface(null, selected
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            if (holder[0] != null) {
                android.widget.Button negative = holder[0].getButton(AlertDialog.BUTTON_NEGATIVE);
                if (negative != null) {
                    negative.setText(multi ? actionNames[batchAction[0]] : "关闭");
                }
                android.widget.Button neutral = holder[0].getButton(AlertDialog.BUTTON_NEUTRAL);
                if (neutral != null) {
                    int total = shownPairs.get().size();
                    neutral.setText(!multi ? "多选" : checked.isEmpty() ? "全选"
                            : checked.size() >= total ? "全不选" : "反选");
                }
            }

            col.removeAllViews();
            List<Pair> list = shownPairs.get();
            if (list.isEmpty()) {
                TextView empty = new TextView(this);
                if (multi) {
                    empty.setText("没有可" + actionNames[batchAction[0]] + "的关联");
                } else if (filter[0] != 0) {
                    empty.setText("没有" + filterNames[filter[0]] + "的关联");
                } else {
                    empty.setText("还没有关联，点右上角添加");
                }
                empty.setTextSize(12);
                empty.setTextColor(getColor(R.color.text_secondary));
                empty.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
                col.addView(empty);
                return;
            }

            for (Pair p : list) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                if (multi) {
                    TextView cb = new TextView(this);
                    cb.setTextSize(9);
                    cb.setTextColor(0xFFFFFFFF);
                    cb.setGravity(Gravity.CENTER);
                    cb.setText(checked.contains(p.main) ? "✓" : "");
                    cb.setBackgroundResource(checked.contains(p.main)
                            ? R.drawable.bg_check_on : R.drawable.bg_check_off);
                    LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                            Ui.dp(this, 15), Ui.dp(this, 15));
                    cbLp.rightMargin = Ui.dp(this, 8);
                    row.addView(cb, cbLp);
                }

                TextView name = new TextView(this);
                name.setText(labelFor(p.main) + " → " + labelFor(p.sub)
                        + (p.enabled ? "" : "（已暂停）"));
                name.setTextSize(12);
                name.setTextColor(getColor(p.enabled ? R.color.text_primary : R.color.text_secondary));
                row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (multi) {
                    row.setOnClickListener(v -> {
                        if (!checked.add(p.main)) {
                            checked.remove(p.main);
                        }
                        renderList[0].run();
                    });
                } else {
                    TextView toggle = new TextView(this);
                    toggle.setText(p.enabled ? "暂停" : "恢复");
                    toggle.setTextSize(12);
                    toggle.setTextColor(getColor(p.enabled ? R.color.blue : R.color.success));
                    toggle.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
                    toggle.setOnClickListener(v -> {
                        setPairEnabled(p.main, !p.enabled);
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        showPairManager();
                    });
                    row.addView(toggle);

                    TextView del = new TextView(this);
                    del.setText("删除");
                    del.setTextSize(12);
                    del.setTextColor(getColor(R.color.danger));
                    del.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
                    del.setOnClickListener(v -> {
                        removePair(p.main);
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        showPairManager();
                    });
                    row.addView(del);
                }
                col.addView(row);
            }
        };

        backBtn.setOnClickListener(v -> {
            batchAction[0] = actionNone;
            checked.clear();
            renderList[0].run();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .setNeutralButton("多选", null)
                .setNegativeButton("返回", null)
                .create();
        holder[0] = dialog;
        pairManagerDialog = dialog;
        dialog.setOnCancelListener(d -> {
            if (!pairManagerFromQuickPanel) {
                return;
            }
            pairManagerFromQuickPanel = false;
            // 等本页完成关闭后，再回到作为上一页的快面板
            main.post(this::showQuickPanel);
        });
        dialog.setOnDismissListener(d -> {
            if (pairManagerDialog == dialog) {
                pairManagerDialog = null;
            }
        });
        renderList[0].run();
        disableDialogWindowAnimation(dialog);
        dialog.show();
        if (pairManagerFromQuickPanel) {
            // 从快面板进来的：关闭时只回快面板，不去动设置页
            Ui.skipAutoHide(dialog);
        }
        Ui.roundAlertDialog(dialog);
        applySettingsAlignment(dialog);

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (batchAction[0] == actionNone) {
                final String[] actionLabels = {"恢复", "删除", "暂停"};
                // 标题与下面三项统一 16dp 左缩进，标题用小字号，选项用蓝色稍大字号
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));

                TextView pickTitle = Ui.text(this, "选择批量操作", 16, Ui.TEXT_PRIMARY, true);
                pickTitle.setPadding(Ui.dp(this, 16), Ui.dp(this, 10),
                        Ui.dp(this, 16), Ui.dp(this, 6));
                box.addView(pickTitle);

                final AlertDialog[] pickHolder = new AlertDialog[1];
                for (int i = 0; i < actionLabels.length; i++) {
                    final int which = i;
                    TextView row = Ui.text(this, actionLabels[i], 14, Ui.BLUE, false);
                    row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10),
                            Ui.dp(this, 16), Ui.dp(this, 10));
                    android.util.TypedValue tv = new android.util.TypedValue();
                    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                    row.setBackgroundResource(tv.resourceId);
                    row.setOnClickListener(rv -> {
                        if (pickHolder[0] != null) {
                            pickHolder[0].dismiss();
                        }
                        batchAction[0] = which + 1;
                        checked.clear();
                        renderList[0].run();
                    });
                    box.addView(row);
                }
                AlertDialog pick = new AlertDialog.Builder(this)
                        .setView(box)
                        .setNegativeButton("取消", null)
                        .create();
                pickHolder[0] = pick;
                // 选批量操作期间先藏起主副应用关联列表；取消或选完再恢复
                sv.setVisibility(View.GONE);
                pick.setOnDismissListener(pd -> sv.setVisibility(View.VISIBLE));
                pick.show();
                Ui.roundAlertDialog(pick);
                applySettingsAlignment(pick);
                return;
            }
            List<String> keys = new ArrayList<>();
            for (Pair p : shownPairs.get()) {
                keys.add(p.main);
            }
            if (checked.isEmpty()) {
                checked.addAll(keys);
            } else if (checked.size() >= keys.size()) {
                checked.clear();
            } else {
                Set<String> inverted = new HashSet<>(keys);
                inverted.removeAll(checked);
                checked.clear();
                checked.addAll(inverted);
            }
            renderList[0].run();
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            int action = batchAction[0];
            if (action == actionNone) {
                pairManagerFromQuickPanel = false;
                dialog.dismiss();
                return;
            }
            if (checked.isEmpty()) {
                toast("先勾选要" + actionNames[action] + "的关联");
                return;
            }
            int count = checked.size();
            for (Pair p : allPairs()) {
                if (!checked.contains(p.main)) {
                    continue;
                }
                if (action == actionDelete) {
                    removePair(p.main);
                } else {
                    setPairEnabled(p.main, action == actionResume);
                }
            }
            checked.clear();
            toast("已" + actionNames[action] + " " + count + " 组关联");
            renderList[0].run();
        });
    }

    /**
     * 主/副应用选择用的「图标 + 名称」列表。
     * 图标直接取冻结列表已加载的 Item.icon（复制成独立 Drawable，避免与列表共用 bounds）；
     * 行视图交给 ListView 回收复用，长名单也只创建可见的十几行。
     */
    private android.widget.ListView buildAppPickList(final List<Item> source,
                                                     final String[] labels,
                                                     final java.util.function.IntConsumer onPick) {
        final int iconPx = Ui.dp(this, 28);
        final android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setAdapter(new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return source.size();
            }

            @Override
            public Object getItem(int position) {
                return source.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                } else {
                    row = new LinearLayout(FreezeListActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(Ui.dp(FreezeListActivity.this, 18), Ui.dp(FreezeListActivity.this, 8),
                            Ui.dp(FreezeListActivity.this, 18), Ui.dp(FreezeListActivity.this, 8));
                    row.setMinimumHeight(Ui.dp(FreezeListActivity.this, 48));
                    row.setBackgroundResource(tv.resourceId);
                    ImageView icon = new ImageView(FreezeListActivity.this);
                    row.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
                    TextView name = Ui.text(FreezeListActivity.this, "", 14, Ui.TEXT_PRIMARY, false);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    nameLp.leftMargin = Ui.dp(FreezeListActivity.this, 14);
                    row.addView(name, nameLp);
                }
                Item it = source.get(position);
                ImageView icon = (ImageView) row.getChildAt(0);
                Drawable d = null;
                if (it.icon != null && it.icon.getConstantState() != null) {
                    d = it.icon.getConstantState().newDrawable(getResources()).mutate();
                }
                icon.setImageDrawable(d != null ? d
                        : androidx.core.content.ContextCompat.getDrawable(FreezeListActivity.this,
                        android.R.drawable.sym_def_app_icon));
                ((TextView) row.getChildAt(1)).setText(labels[position]);
                final int pos = position;
                row.setOnClickListener(v -> onPick.accept(pos));
                return row;
            }
        });
        // 最多占屏幕 60%，超出的部分滚动查看
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.6f);
        int h = Math.min(Ui.dp(this, 48) * Math.max(1, source.size()), maxH);
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return lv;
    }

    /** 第一步：从冻结列表里选主应用；已配对的在名称后标出当前副应用。 */
    private void showPairMainPicker() {
        final List<Item> source = new ArrayList<>(items);
        if (source.isEmpty()) {
            toast("冻结列表是空的");
            return;
        }
        Map<String, String> pairs = subPairs();
        final String[] names = new String[source.size()];
        for (int i = 0; i < source.size(); i++) {
            Item it = source.get(i);
            String sub = pairs.get(it.pkg);
            names[i] = sub == null ? it.label : it.label + " → " + labelFor(sub);
        }
        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog pick = new AlertDialog.Builder(this)
                .setTitle("选择主应用")
                .setView(buildAppPickList(source, names, pos -> showPairSubPicker(source.get(pos))))
                .setNegativeButton("取消", null)
                .create();
        holder[0] = pick;
        trackPairPicker(pick);
        pick.show();
        Ui.roundAlertDialog(pick);
        // 与「关联应用（点击看说明）」说明弹窗同一宽度
        applySettingsAlignment(pick);
    }

    /** 管理弹窗上的添加选择页：登记进来，手势返回只关这一页，完成添加后再整条链一起刷新。 */
    private void trackPairPicker(final AlertDialog pick) {
        pairPickers.add(pick);
        pick.setOnDismissListener(d -> pairPickers.remove(pick));
    }

    /** 添加流程结束：关掉选择页与管理弹窗，再按最新数据重开管理弹窗。 */
    private void closePairFlow() {
        for (AlertDialog d : new ArrayList<>(pairPickers)) {
            d.dismiss();
        }
        pairPickers.clear();
        if (pairManagerDialog != null) {
            pairManagerDialog.dismiss();
            pairManagerDialog = null;
        }
        showPairManager();
    }

    /** 第二步：选副应用（同样从冻结列表里选，排除主应用本身）。 */
    private void showPairSubPicker(Item main) {
        final List<Item> source = new ArrayList<>();
        for (Item it : items) {
            if (!it.pkg.equals(main.pkg)) {
                source.add(it);
            }
        }
        if (source.isEmpty()) {
            toast("冻结列表里没有可选的副应用");
            return;
        }
        final String[] names = new String[source.size()];
        for (int i = 0; i < source.size(); i++) {
            names[i] = source.get(i).label;
        }
        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog pick = new AlertDialog.Builder(this)
                .setTitle("选择「" + main.label + "」的副应用")
                .setView(buildAppPickList(source, names, pos -> {
                    Item sub = source.get(pos);
                    saveSubPair(main.pkg, sub.pkg);
                    toast("已关联：" + main.label + " → " + sub.label);
                    closePairFlow();
                }))
                .setNeutralButton("清除关联", (d, w) -> {
                    saveSubPair(main.pkg, null);
                    toast("已清除 " + main.label + " 的副应用关联");
                    closePairFlow();
                })
                .setNegativeButton("取消", null)
                .create();
        holder[0] = pick;
        trackPairPicker(pick);
        pick.show();
        Ui.roundAlertDialog(pick);
        applySettingsAlignment(pick);
    }

    /** 快面板「＋」选择器的分组目录：每组首项是组名，其余是条目 id，顺序即展示顺序。 */
    private static String[][] quickPanelCatalog() {
        return new String[][]{
                {"个性设置", "default_form", "show_name", "hide_toasts", "dismiss",
                        "hide_tab_row", "batch_tab_only", "panel_handle", "hide_status_bar",
                        "hide_recents", "gesture_enabled", "quick_panel_enabled"},
                {"专属LSPosed", "swipe_freeze", "lock_freeze"},
                {"特殊模式", "whitelist", "pair"},
        };
    }

    /** 条目所在分组序号：0 开关、1 滑块、2 界面透明度、3 白名单与关联、4 直接操作、5 入口；未知 id 返回 -1。 */
    private static int quickPanelGroupOf(String id) {
        String[][] catalog = quickPanelCatalog();
        for (int g = 0; g < catalog.length; g++) {
            for (int i = 1; i < catalog[g].length; i++) {
                if (catalog[g][i].equals(id)) {
                    return g;
                }
            }
        }
        return -1;
    }

    /** 条目显示名：与设置弹窗/操作单同一份文案。 */
    private CharSequence quickPanelItemText(String id) {
        switch (id) {
            case "default_form":
                return panelMode ? "默认半屏列表" : "默认全屏列表";
            case "show_name":
                return "显示应用名称";
            case "hide_toasts":
                return "去掉 toast 提示";
            case "dismiss":
                return "一键冻结后收起列表";
            case "hide_tab_row":
                return hideTabRowLabel();
            case "batch_tab_only":
                return "底部按钮仅冻/解当前列表";
            case "panel_handle":
                return "半屏高度小白条调节";
            case "hide_status_bar":
                return "隐藏半屏的状态栏";
            case "hide_recents":
                return "隐藏最近任务卡片";
            case "quick_remove":
                return "批量移除应用";
            case "gesture_enabled":
                return "长按列表空白处手势";
            case "quick_panel_enabled":
                return "长按底栏空白处打开快面板";
            case "edge_only":
                return "仅在屏幕边缘触发上下滑";
            case "swipe_freeze":
                return "划卡冻结";
            case "lock_freeze":
                return "锁屏冻结";
            case "icon_size":
                return "图标大小";
            case "columns":
                return "列数";
            case "fab_size":
                return "按钮大小";
            case "fab_dx":
                return "左右偏移";
            case "fab_dy":
                return "距底部";
            case "fab_alpha":
                return "透明度";
            case "gesture_up":
                return "上滑手势触发距离";
            case "gesture_down":
                return "下滑手势触发距离";
            case "edge_left":
                return "屏幕左边缘靠内触发手势距离";
            case "edge_right":
                return "屏幕右边缘靠内触发手势距离";
            case "wp_dim":
                return "自定义壁纸弹窗明暗度";
            case "wp_blur":
                return "壁纸模糊度";
            case "icon_group":
                return "列表图标";
            case "fab_group":
                return "底部冻结按钮";
            case "gesture_group":
                return "手势调节";
            case "wallpaper_group":
                return "自定义壁纸";
            case "ui_opacity":
                return "界面透明度";
            case "whitelist":
                return "列表白名单";
            case "pair":
                return "关联应用";
            case "select":
                return "多选";
            case "tab_settings":
                return "标签设定";
            case "theme_icon":
                // 快面板里点它就是选「系统默认 / 当前主题图标」，文案不再带「点击刷新」的说明
                return "主题图标";
            case "icon_style":
                return iconStyleEntryText();
            case "keep_icon":
                return keepIconEntryText();
            case "white_pos":
                return whiteBadgePosText();
            case "backup":
                return "备份";
            case "restore":
                return "恢复";
            case "about":
                return "关于凚";
            case "credits":
                return "致谢名单";
            case "donate":
                return "赞赏开发者";
            default:
                return id;
        }
    }

    /** 开关条目当前状态。 */
    private boolean quickPanelToggleOn(String id) {
        switch (id) {
            case "default_form":
                return prefs().getBoolean(KEY_FULLSCREEN_OPEN, true) != panelMode;
            case "show_name":
                return showName;
            case "hide_toasts":
                return hideToasts;
            case "dismiss":
                return panelDismissAfterFreeze;
            case "hide_tab_row":
                return hideTabRow;
            case "batch_tab_only":
                return batchCurrentTabOnly;
            case "panel_handle":
                return panelHandleEnabled;
            case "hide_status_bar":
                return panelHideStatusBar;
            case "hide_recents":
                return panelHideRecents;
            case "quick_remove":
                return quickRemove;
            case "gesture_enabled":
                return gestureEnabled;
            case "edge_only":
                return gestureEdgeOnly;
            case "swipe_freeze":
                return SwipeFreeze.enabled(this);
            case "lock_freeze":
                return SwipeFreeze.lockEnabled(this);
            case "quick_panel_enabled":
                return quickPanelEnabled;
            default:
                return false;
        }
    }

    /** 开关行为体：逐字复制 showLayoutDialog 对应勾选框的 OnCheckedChangeListener 体。 */
    private void quickPanelToggle(String id) {
        if ("default_form".equals(id)) {
            final boolean curIsDefaultForm = prefs().getBoolean(KEY_FULLSCREEN_OPEN, true) != panelMode;
            boolean on = !curIsDefaultForm;
            if (on == curIsDefaultForm) {
                return;
            }
            // 全屏里勾上=默认全屏，取消=默认半屏；半屏反过来；改完立刻切到对应形态
            boolean toFull = panelMode ? !on : on;
            prefs().edit().putBoolean(KEY_FULLSCREEN_OPEN, toFull).apply();
            // 勾选状态代表的形态与当前形态不同才需要切换
            if (toFull == panelMode) {
                dismissQuickPanel();
                switchForm(toFull);
            }
            return;
        }
        boolean on = !quickPanelToggleOn(id);
        switch (id) {
            case "swipe_freeze": {
                SwipeFreeze.setEnabled(this, on);
                SwipeFreeze.syncAsync(this);
                break;
            }
            case "lock_freeze": {
                SwipeFreeze.setLockEnabled(this, on);
                SwipeFreeze.syncAsync(this);
                break;
            }
            case "quick_panel_enabled": {
                quickPanelEnabled = on;
                prefs().edit().putBoolean(KEY_QUICK_PANEL_ENABLED, on).apply();
                break;
            }
            case "show_name": {
                showName = on;
                setShowNamePref(on);
                render();
                break;
            }
            case "hide_toasts": {
                hideToasts = on;
                prefs().edit().putBoolean(KEY_HIDE_TOASTS, on).apply();
                break;
            }
            case "dismiss": {
                panelDismissAfterFreeze = on;
                prefs().edit().putBoolean(KEY_PANEL_DISMISS, on).apply();
                break;
            }
            case "hide_tab_row": {
                if (on == hideTabRow) {
                    break;
                }
                hideTabRow = on;
                setHideTabRowPref(on);
                renderTabBar();
                if (grid != null) {
                    grid.requestLayout();
                }
                break;
            }
            case "batch_tab_only": {
                batchCurrentTabOnly = on;
                prefs().edit().putBoolean(KEY_BATCH_CURRENT_TAB_ONLY, on).apply();
                break;
            }
            case "panel_handle": {
                boolean turningOn = on && !panelHandleEnabled;
                panelHandleEnabled = on;
                prefs().edit().putBoolean(KEY_PANEL_HANDLE, on).apply();
                applyPanelHandleState();
                if (turningOn) {
                    dismissQuickPanel();
                    main.postDelayed(FreezeListActivity.this::flashPanelHandle, 160);
                }
                break;
            }
            case "hide_status_bar": {
                panelHideStatusBar = on;
                prefs().edit().putBoolean(KEY_PANEL_HIDE_STATUS_BAR, on).apply();
                applyPanelBarState();
                break;
            }
            case "hide_recents": {
                panelHideRecents = on;
                prefs().edit().putBoolean(KEY_PANEL_HIDE_RECENTS, on).apply();
                // 与冰箱拨开关时一样立刻同步任务属性：开着时当前任务马上退出最近任务，
                // 关掉时恢复；下次从桌面进入会由 LaunchActivity 改走对应的隐藏/普通入口
                syncRecentsExclusion();
                break;
            }
            case "quick_remove": {
                setQuickRemove(on);
                break;
            }
            case "gesture_enabled": {
                gestureEnabled = on;
                prefs().edit().putBoolean(KEY_GESTURE_ENABLED, on).apply();
                if (!on) {
                    cancelBlankScrub();
                }
                applyEdgeGestureExclusion();
                break;
            }
            case "edge_only": {
                gestureEdgeOnly = on;
                prefs().edit().putBoolean(KEY_GESTURE_EDGE_ONLY, on).apply();
                applyEdgeGestureExclusion();
                break;
            }
            default:
                break;
        }
    }

    /** 滑块体：数值 label + SeekBar，行为体逐字复制 showLayoutDialog 对应滑块的 listener。 */
    private View quickPanelSliderBody(String id) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 2));
        switch (id) {
            case "icon_size": {
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
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt(otherKey(KEY_ICON_DP), iconDp)
                                    .putBoolean(otherKey(KEY_ICON_MANUAL), true).apply();
                        }
                    }
                });
                sizeLabel.setText("图标大小：" + (sizeBar.getProgress() + iconSizeMinDp()) + "dp");
                body.addView(sizeLabel);
                body.addView(sizeBar);
                return body;
            }
            case "columns": {
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
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt(otherKey(KEY_COLUMNS), gridColumns)
                                    .putBoolean(otherKey(KEY_ICON_MANUAL), false).apply();
                        }
                        colsValue.setText("列数：" + gridColumns + " 列");
                        render();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(colsValue);
                body.addView(colsBar);
                return body;
            }
            case "fab_size": {
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
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt(otherKey(KEY_FAB_SIZE), fabSizeDp).apply();
                        }
                        sizeValue.setText("按钮大小：" + fabSizeDp + "dp");
                        applyFabSize();
                        fabGroup.post(FreezeListActivity.this::applyFabPosition);
                        // 按钮变大/变小会改变上沿，展开范围随之上限实时跟着走
                        getWindow().getDecorView().postDelayed(
                                FreezeListActivity.this::refreshExpandRange, 40);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(sizeValue);
                body.addView(fabSizeBar);
                return body;
            }
            case "fab_dx": {
                final TextView xValue = new TextView(this);
                xValue.setTextSize(11);
                xValue.setTextColor(getColor(R.color.text_secondary));

                final float density = getResources().getDisplayMetrics().density;
                final int xMinDp = Math.round((fabInsetPx() - fabCenterX()) / density);
                final int xMaxDp = Math.round((fabMaxX() - fabCenterX()) / density);

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
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt(otherKey(KEY_FAB_DX), fabDx).apply();
                        }
                        xValue.setText("左右偏移：" + (fabDx > 0 ? "+" : "") + fabDx + "dp");
                        applyFabPosition();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                xValue.setText("左右偏移：" + (fabDx > 0 ? "+" : "") + fabDx + "dp");
                body.addView(xValue);
                body.addView(xBar);
                return body;
            }
            case "fab_dy": {
                final TextView yValue = new TextView(this);
                yValue.setTextSize(11);
                yValue.setTextColor(getColor(R.color.text_secondary));

                final float density = getResources().getDisplayMetrics().density;
                // 「距底部」滑块最大值 = 按钮到列表顶部的真实空间：大屏平板可一直拖到顶部，但不会超过顶部
                final int maxLiftDp = Math.max(0, Math.round(fabMaxLiftPx() / density));

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
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt(otherKey(KEY_FAB_DY), fabDy).apply();
                        }
                        yValue.setText("距底部：" + fabDy + "dp");
                        applyFabPosition();
                        // 按钮上下移动后，展开范围的上限与当前高度一起实时收放
                        getWindow().getDecorView().postDelayed(
                                FreezeListActivity.this::refreshExpandRange, 40);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                yValue.setText("距底部：" + fabDy + "dp");
                body.addView(yValue);
                body.addView(yBar);
                return body;
            }
            case "fab_alpha": {
                final TextView fabAlphaLabel = new TextView(this);
                fabAlphaLabel.setTextSize(11);
                fabAlphaLabel.setTextColor(getColor(R.color.text_secondary));

                final SeekBar fabAlphaBar = new SeekBar(this);
                fabAlphaBar.setMax(100);
                int fabAlphaStored = Math.max(0, Math.min(100,
                        prefs().getInt(panelMode ? lk(KEY_FAB_SURFACE) : KEY_FULL_FAB_SURFACE,
                                panelMode ? PANEL_FAB_SURFACE_DEFAULT : FULL_FAB_SURFACE_DEFAULT)));
                int fabAlphaPct = 100 - fabAlphaStored;
                fabAlphaBar.setProgress(fabAlphaPct);
                fabAlphaLabel.setText("透明度：" + fabAlphaPct + "%");
                fabAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        // 滑块值 = 透明度：0 不透明（原观感），100 全透明
                        prefs().edit()
                                .putInt(panelMode ? lk(KEY_FAB_SURFACE) : KEY_FULL_FAB_SURFACE, 100 - p)
                                .apply();
                        // 底部按钮透明度：开了同步就一起写另一形态（半屏 panel_fab_surface ↔ 全屏 full_fab_surface）
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit()
                                    .putInt(panelMode ? KEY_FULL_FAB_SURFACE : "panel_" + KEY_FAB_SURFACE,
                                            100 - p)
                                    .apply();
                        }
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
                body.addView(fabAlphaLabel);
                body.addView(fabAlphaBar);
                return body;
            }
            case "gesture_up": {
                final TextView upLabel = new TextView(this);
                upLabel.setTextSize(11);
                upLabel.setTextColor(getColor(R.color.text_secondary));
                upLabel.setText("上滑手势触发距离：" + gestureUpDp + "dp");

                SeekBar upBar = new SeekBar(this);
                upBar.setMax(GESTURE_DP_MAX);
                upBar.setProgress(gestureUpDp);
                upBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        gestureUpDp = p;
                        prefs().edit().putInt(KEY_GESTURE_UP, p).apply();
                        upLabel.setText("上滑手势触发距离：" + p + "dp");
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(upLabel);
                body.addView(upBar);
                return body;
            }
            case "gesture_down": {
                final TextView downLabel = new TextView(this);
                downLabel.setTextSize(11);
                downLabel.setTextColor(getColor(R.color.text_secondary));
                downLabel.setText("下滑手势触发距离：" + gestureDownDp + "dp");

                SeekBar downBar = new SeekBar(this);
                downBar.setMax(GESTURE_DP_MAX);
                downBar.setProgress(gestureDownDp);
                downBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        gestureDownDp = p;
                        prefs().edit().putInt(KEY_GESTURE_DOWN, p).apply();
                        downLabel.setText("下滑手势触发距离：" + p + "dp");
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(downLabel);
                body.addView(downBar);
                return body;
            }
            case "edge_left": {
                final TextView edgeLeftLabel = new TextView(this);
                edgeLeftLabel.setTextSize(11);
                edgeLeftLabel.setTextColor(getColor(R.color.text_secondary));
                edgeLeftLabel.setText("屏幕左边缘靠内触发手势距离：" + edgeDpLabel(gestureEdgeLeftDp));
                SeekBar edgeLeftBar = new SeekBar(this);
                edgeLeftBar.setMax(GESTURE_EDGE_DP_MAX - GESTURE_EDGE_DP_MIN);
                edgeLeftBar.setProgress(Math.max(0, gestureEdgeLeftDp - GESTURE_EDGE_DP_MIN));
                edgeLeftBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        int value = p + GESTURE_EDGE_DP_MIN;
                        gestureEdgeLeftDp = value;
                        prefs().edit().putInt(KEY_GESTURE_EDGE_LEFT, value).apply();
                        edgeLeftLabel.setText("屏幕左边缘靠内触发手势距离：" + edgeDpLabel(value));
                        applyEdgeGestureExclusion();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(edgeLeftLabel);
                body.addView(edgeLeftBar);
                return body;
            }
            case "edge_right": {
                final TextView edgeRightLabel = new TextView(this);
                edgeRightLabel.setTextSize(11);
                edgeRightLabel.setTextColor(getColor(R.color.text_secondary));
                edgeRightLabel.setText("屏幕右边缘靠内触发手势距离：" + edgeDpLabel(gestureEdgeRightDp));
                SeekBar edgeRightBar = new SeekBar(this);
                edgeRightBar.setMax(GESTURE_EDGE_DP_MAX - GESTURE_EDGE_DP_MIN);
                edgeRightBar.setProgress(Math.max(0, gestureEdgeRightDp - GESTURE_EDGE_DP_MIN));
                edgeRightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        int value = p + GESTURE_EDGE_DP_MIN;
                        gestureEdgeRightDp = value;
                        prefs().edit().putInt(KEY_GESTURE_EDGE_RIGHT, value).apply();
                        edgeRightLabel.setText("屏幕右边缘靠内触发手势距离：" + edgeDpLabel(value));
                        applyEdgeGestureExclusion();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(edgeRightLabel);
                body.addView(edgeRightBar);
                return body;
            }
            case "wp_dim": {
                final TextView wpDimLabel = new TextView(this);
                wpDimLabel.setTextSize(11);
                wpDimLabel.setTextColor(getColor(R.color.text_secondary));
                wpDimLabel.setText("自定义壁纸弹窗明暗度：" + wallpaperBright + "%");
                SeekBar wpDimBar = new SeekBar(this);
                wpDimBar.setMax(200);
                wpDimBar.setProgress(wallpaperBright);
                wpDimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        wpDimLabel.setText("自定义壁纸弹窗明暗度：" + p + "%");
                        wallpaperBright = Math.max(0, Math.min(200, p));
                        refreshGlobalSurfaceBg();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                        wallpaperBright = Math.max(0, Math.min(200, sb.getProgress()));
                        prefs().edit().putInt(lk(KEY_WALLPAPER_DIM), wallpaperBright).apply();
                        mirrorWallpaperToOtherIfGlobal();
                        refreshGlobalSurfaceBg();
                    }
                });
                body.addView(wpDimLabel);
                body.addView(wpDimBar);
                return body;
            }
            case "wp_blur": {
                final TextView wpBlurLabel = new TextView(this);
                wpBlurLabel.setTextSize(11);
                wpBlurLabel.setTextColor(getColor(R.color.text_secondary));
                wpBlurLabel.setText("壁纸模糊度：" + wallpaperBlur + "%");
                SeekBar wpBlurBar = new SeekBar(this);
                wpBlurBar.setMax(100);
                wpBlurBar.setProgress(wallpaperBlur);
                wpBlurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        wpBlurLabel.setText("壁纸模糊度：" + p + "%");
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
                        mirrorWallpaperToOtherIfGlobal();
                        rebuildWallpaperDrawable();
                        applyWallpaperBackground();
                    }
                });
                body.addView(wpBlurLabel);
                body.addView(wpBlurBar);
                return body;
            }
            case "op_settings": {
                final TextView settingsLabel = new TextView(this);
                settingsLabel.setTextSize(11);
                settingsLabel.setTextColor(getColor(R.color.text_secondary));
                final SeekBar settingsBar = new SeekBar(this);
                settingsBar.setMax(100);
                int settingsStart = settingsSurfacePct();
                settingsBar.setProgress(settingsStart);
                settingsLabel.setText(settingsSurfaceText(settingsStart));
                settingsBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        prefs().edit().putInt(lk(KEY_SETTINGS_SURFACE), p).apply();
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt(otherKey(KEY_SETTINGS_SURFACE), p).apply();
                        }
                        settingsLabel.setText(settingsSurfaceText(p));
                        applySettingsSurfaceAlpha(settingsSurfaceRoot);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(settingsLabel);
                body.addView(settingsBar);
                return body;
            }
            case "op_icon_alpha": {
                final TextView iconAlphaLabel = new TextView(this);
                iconAlphaLabel.setTextSize(11);
                iconAlphaLabel.setTextColor(getColor(R.color.text_secondary));
                iconAlphaLabel.setText(iconAlphaText(iconAlpha));
                final SeekBar iconAlphaBar = new SeekBar(this);
                iconAlphaBar.setMax(100);
                iconAlphaBar.setProgress(iconAlpha);
                iconAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        iconAlpha = clampInt(p, 0, 100);
                        prefs().edit().putInt(lk(KEY_ICON_ALPHA), iconAlpha).apply();
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt(otherKey(KEY_ICON_ALPHA), iconAlpha).apply();
                        }
                        iconAlphaLabel.setText(iconAlphaText(iconAlpha));
                        // 可见格子与滑出屏幕的缓存格子都要重绑，否则滚回来还是旧透明度
                        rebindCells();
                        if (gridAdapter != null) {
                            gridAdapter.rebindAllCached();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(iconAlphaLabel);
                body.addView(iconAlphaBar);
                return body;
            }
            case "op_outer": {
                final TextView outerLabel = new TextView(this);
                outerLabel.setTextSize(11);
                outerLabel.setTextColor(getColor(R.color.text_secondary));
                final SeekBar outerBar = new SeekBar(this);
                outerBar.setMax(100);
                int outerStart = 100 - Math.max(0, Math.min(100,
                        prefs().getInt(lk(KEY_BLUR), 20)));
                outerBar.setProgress(outerStart);
                outerLabel.setText("半屏外侧：" + outerStart + "%");
                outerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        prefs().edit().putInt(lk(KEY_BLUR), 100 - p).apply();
                        outerLabel.setText("半屏外侧：" + p + "%");
                        applyBackdropStrength();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(outerLabel);
                body.addView(outerBar);
                return body;
            }
            case "op_inner": {
                final TextView innerLabel = new TextView(this);
                innerLabel.setTextSize(11);
                innerLabel.setTextColor(getColor(R.color.text_secondary));
                final SeekBar innerBar = new SeekBar(this);
                innerBar.setMax(100);
                int innerStart = 100 - Math.max(0, Math.min(100,
                        prefs().getInt(lk(KEY_SURFACE), SURFACE_SOLID)));
                innerBar.setProgress(innerStart);
                innerLabel.setText("半屏列表背景：" + innerStart + "%");
                innerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        prefs().edit().putInt(lk(KEY_SURFACE), 100 - p).apply();
                        // 半屏面板透明度 ↔ 全屏背景：开了同步就一起写
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt(KEY_FULL_SURFACE, 100 - p).apply();
                        }
                        innerLabel.setText("半屏列表背景：" + p + "%");
                        applyPanelSurface();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(innerLabel);
                body.addView(innerBar);
                return body;
            }
            case "op_bg": {
                final TextView bgLabel = new TextView(this);
                bgLabel.setTextSize(11);
                bgLabel.setTextColor(getColor(R.color.text_secondary));
                final SeekBar bgBar = new SeekBar(this);
                bgBar.setMax(100);
                int bgStart = 100 - Math.max(0, Math.min(100,
                        prefs().getInt(KEY_FULL_SURFACE, SURFACE_SOLID)));
                bgBar.setProgress(bgStart);
                bgLabel.setText("全屏列表背景：" + bgStart + "%");
                bgBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        prefs().edit().putInt(KEY_FULL_SURFACE, 100 - p).apply();
                        // 全屏背景 ↔ 半屏面板透明度：开了同步就一起写
                        if (prefs().getBoolean(KEY_GLOBAL_THEME, false)) {
                            prefs().edit().putInt("panel_" + KEY_SURFACE, 100 - p).apply();
                        }
                        bgLabel.setText("全屏列表背景：" + p + "%");
                        // 真正改窗口底板 alpha 与模糊的是 applyFullscreenSurface（整页背景唯一出口）
                        applyFullscreenSurface();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(bgLabel);
                body.addView(bgBar);
                return body;
            }
            case "op_header": {
                final TextView headerLabel = new TextView(this);
                headerLabel.setTextSize(11);
                headerLabel.setTextColor(getColor(R.color.text_secondary));
                final SeekBar headerBar = new SeekBar(this);
                headerBar.setMax(100);
                int headerStart = 100 - headerSurfacePct();
                headerBar.setProgress(headerStart);
                headerLabel.setText("横幅：" + headerStart + "%");
                headerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        prefs().edit().putInt(KEY_HEADER_SURFACE, 100 - p).apply();
                        headerLabel.setText("横幅：" + p + "%");
                        // 顶栏档位在 applyFullscreenSurface 里统一铺（page_header 那一层）
                        applyFullscreenSurface();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(headerLabel);
                body.addView(headerBar);
                return body;
            }
            case "op_blur": {
                final TextView blurLabel = new TextView(this);
                blurLabel.setTextSize(11);
                blurLabel.setTextColor(getColor(R.color.text_secondary));
                final SeekBar blurBar = new SeekBar(this);
                blurBar.setMax(100);
                int blurStart = fullBlurPct();
                blurBar.setProgress(blurStart);
                blurLabel.setText("全屏外模糊：" + blurStart + "%");
                blurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        // 滑块值 = 模糊度：0 外面看得清，100 最糊
                        prefs().edit().putInt(KEY_FULL_BLUR, p).apply();
                        blurLabel.setText("全屏外模糊：" + p + "%");
                        applyFullscreenSurface();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(blurLabel);
                body.addView(blurBar);
                return body;
            }
            case "op_search": {
                final TextView searchLabel = new TextView(this);
                searchLabel.setTextSize(11);
                searchLabel.setTextColor(getColor(R.color.text_secondary));

                final SeekBar searchBar = new SeekBar(this);
                searchBar.setMax(100);
                int searchStart = 100 - searchPct();
                searchBar.setProgress(searchStart);
                searchLabel.setText("搜索框：" + searchStart + "%");
                searchBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        // 滑块值 = 透明度：0 不透明，100 全透明
                        prefs().edit().putInt(lk(KEY_SEARCH_SURFACE), 100 - p).apply();
                        searchLabel.setText("搜索框：" + p + "%");
                        applySearchSurface();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                body.addView(searchLabel);
                body.addView(searchBar);
                return body;
            }
            default:
                return body;
        }
    }

    /** 「界面透明度」组体：itsBody 实际包含的全部滑块，按当前形态取对应那几条。 */
    private View quickPanelOpacityBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        // 顺序与设置页「界面透明度」保持一致（快面板里没有「快面板」这一条）
        String[] order = panelMode
                ? new String[]{"op_icon_alpha", "op_inner", "op_outer", "op_settings"}
                : new String[]{"op_header", "op_search", "op_icon_alpha", "op_bg",
                        "op_blur", "op_settings"};
        for (String id : order) {
            body.addView(quickPanelSliderBody(id));
        }
        return body;
    }

    /** 大标题组的组体：点开标题后才展开这一组里的全部调节项，选择器里只按大标题出现。 */
    private View quickPanelGroupBody(String id) {
        switch (id) {
            case "icon_group": {
                LinearLayout body = new LinearLayout(this);
                body.setOrientation(LinearLayout.VERTICAL);
                body.addView(quickPanelEntryRow("theme_icon"));
                body.addView(quickPanelEntryRow("icon_style"));
                body.addView(quickPanelSliderBody("icon_size"));
                body.addView(quickPanelSliderBody("columns"));
                return body;
            }
            case "fab_group": {
                LinearLayout body = new LinearLayout(this);
                body.setOrientation(LinearLayout.VERTICAL);
                body.addView(quickPanelSliderBody("fab_size"));
                body.addView(quickPanelSliderBody("fab_dx"));
                body.addView(quickPanelSliderBody("fab_dy"));
                body.addView(quickPanelSliderBody("fab_alpha"));
                return body;
            }
            case "gesture_group": {
                LinearLayout body = new LinearLayout(this);
                body.setOrientation(LinearLayout.VERTICAL);
                body.addView(quickPanelToggleRow("edge_only"));
                body.addView(quickPanelSliderBody("edge_left"));
                body.addView(quickPanelSliderBody("edge_right"));
                body.addView(quickPanelSliderBody("gesture_up"));
                body.addView(quickPanelSliderBody("gesture_down"));
                return body;
            }
            case "wallpaper_group": {
                LinearLayout body = new LinearLayout(this);
                body.setOrientation(LinearLayout.VERTICAL);
                body.addView(quickPanelWallpaperActions());
                body.addView(quickPanelSliderBody("wp_dim"));
                body.addView(quickPanelSliderBody("wp_blur"));
                return body;
            }
            case "ui_opacity":
                return quickPanelOpacityBody();
            default:
                return quickPanelSliderBody(id);
        }
    }

    /** 组内一行入口：标题 + ›，点击执行对应动作。 */
    private View quickPanelEntryRow(final String id) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        row.addView(Ui.text(this, quickPanelItemText(id), 12, Ui.TEXT_PRIMARY, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(this, "›", 15, Ui.TEXT_SECONDARY, false));
        row.setOnClickListener(v -> {
            dismissQuickPanel();
            quickPanelRunEntry(id);
        });
        return row;
    }

    /** 组内一行开关：右侧显示已开启 / 未开启，点行切换。 */
    private View quickPanelToggleRow(final String id) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        row.addView(Ui.text(this, quickPanelItemText(id), 12, Ui.TEXT_PRIMARY, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean on = quickPanelToggleOn(id);
        row.addView(Ui.text(this, on ? "已开启" : "未开启", 12,
                on ? getColor(R.color.blue) : Ui.TEXT_SECONDARY, false));
        row.setOnClickListener(v -> {
            quickPanelToggle(id);
            rebuildQuickPanelContent();
        });
        return row;
    }

    /** 组内的壁纸操作：选择图片 / 清除。 */
    private View quickPanelWallpaperActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView pick = Ui.text(this, "选择图片", 12, getColor(R.color.blue), false);
        pick.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        pick.setOnClickListener(v -> {
            dismissQuickPanel();
            wallpaperPicker.launch(new String[]{"image/*"});
        });
        TextView clear = Ui.text(this, "清除", 12, 0xFFE5484D, false);
        clear.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        clear.setOnClickListener(v -> {
            clearCustomWallpaper();
            toast("已清除壁纸");
            dismissQuickPanel();
        });
        row.addView(pick);
        row.addView(clear);
        return row;
    }

    /** 快面板条目清单：JSON 数组存 id，顺序即显示顺序；未知 id 跳过，坏数据按空数组。 */
    private List<String> quickPanelItems() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString(KEY_QUICK_PANEL_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (!out.contains(id) && quickPanelGroupOf(id) >= 0 && quickPanelAllowed(id)) {
                    out.add(id);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 当前形态下可用的条目：半屏专属设置只在半屏出现，全屏专属只在全屏出现。 */
    private boolean quickPanelAllowed(String id) {
        if ("panel_handle".equals(id) || "hide_status_bar".equals(id)) {
            return panelMode;
        }
        return true;
    }

    private void saveQuickPanelItems(List<String> items) {
        JSONArray arr = new JSONArray();
        for (String id : items) {
            if (quickPanelGroupOf(id) >= 0) {
                arr.put(id);
            }
        }
        prefs().edit().putString(KEY_QUICK_PANEL_ITEMS, arr.toString()).apply();
    }

    private void dismissQuickPanel() {
        if (quickPanelDialog != null) {
            quickPanelDialog.dismiss();
        }
    }

    /** 设置页「标签设定」每行右侧的小图标按钮：默认星标 / 重命名 / 排序共用同一套尺寸与配色。 */
    private ImageButton settingsRowIconBtn(int drawable, String desc) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(drawable);
        btn.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
        btn.setBackground(null);
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 标签设定这一排图标与左侧「用户应用」的 12sp 文字同高（16dp）、间距贴紧
        btn.setPadding(0, 0, 0, 0);
        btn.setContentDescription(desc);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16));
        // 四个图标之间留一点间距，离左边的标签文字也留 10dp
        // 间距别太大，但要保证长按排序手柄时不容易误触到重命名
        lp.setMarginStart(Ui.dp(this, 10));
        // 与左边的标签文字同一水平中线对齐
        lp.gravity = Gravity.CENTER_VERTICAL;
        btn.setLayoutParams(lp);
        return btn;
    }

    private android.widget.ImageButton quickPanelIconBtn(int drawable, String desc) {
        android.widget.ImageButton btn = new android.widget.ImageButton(this);
        btn.setImageResource(drawable);
        btn.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
        btn.setBackground(null);
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 右上角这一排再收小一档、间距几乎贴紧，跟左侧标题更协调
        btn.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        btn.setContentDescription(desc);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22));
        btn.setLayoutParams(lp);
        return btn;
    }

    /** 条目左侧的小图标：按功能挑一个语义相近的现有图标；形态项按半屏 / 全屏分用不同图标。 */
    private int quickPanelItemIcon(String id) {
        switch (id) {
            case "default_form":
                return panelMode ? R.drawable.ic_qp_half : R.drawable.ic_qp_full;
            case "show_name":
                return R.drawable.ic_qp_name;
            case "hide_toasts":
                return R.drawable.ic_close;
            case "dismiss":
                return R.drawable.ic_qp_collapse;
            case "hide_tab_row":
                return R.drawable.ic_qp_eye_off;
            case "batch_tab_only":
            case "tab_settings":
                return R.drawable.ic_action_tag;
            case "panel_handle":
                return R.drawable.ic_drag_handle;
            case "hide_status_bar":
                return R.drawable.ic_chevron_up;
            case "hide_recents":
                return R.drawable.ic_qp_recents_off;
            case "gesture_enabled":
                return R.drawable.ic_qp_hold;
            case "quick_panel_enabled":
                return R.drawable.ic_qp_hold_bar;
            case "swipe_freeze":
                return R.drawable.ic_qp_recents;
            case "lock_freeze":
                return R.drawable.ic_qp_lock;
            case "restore":
                return R.drawable.ic_action_circle_unlock;
            case "whitelist":
                return R.drawable.ic_action_whitelist;
            case "pair":
                return R.drawable.ic_qp_link;
            case "select":
                return R.drawable.ic_check_badge;
            case "theme_icon":
                return R.drawable.ic_launcher;
            case "icon_style":
                return R.drawable.ic_action_edit;
            case "keep_icon":
                return R.drawable.ic_action_home;
            case "white_pos":
                return R.drawable.ic_action_tag;
            case "backup":
                return R.drawable.ic_download;
            case "about":
                return R.drawable.ic_action_app_info;
            case "credits":
                return R.drawable.ic_action_tag;
            case "donate":
                return R.drawable.ic_action_run;
            default:
                return R.drawable.ic_settings;
        }
    }

    /** 行首图标视图：统一 18dp、次级色。 */
    private View quickPanelRowIcon(String id) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(quickPanelItemIcon(id));
        iv.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** 快面板：常用设置与入口的集中弹窗，条目由用户在「＋」里挑选并排序。 */
    private void showQuickPanel() {
        if (isFinishing() || quickPanelDialog != null) {
            return;
        }
        // 全屏快面板是从列表调起的浮层：设置页还开着就先关掉，
        // 否则快面板和「白名单/关联应用」来回切时，自动隐藏逻辑会把设置页重新弹出来
        if (!panelMode) {
            Ui.dismissSettingsHost();
            if (settingsDialog != null) {
                settingsDialog.dismiss();
                settingsDialog = null;
            }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 16);
        root.setPadding(side, Ui.dp(this, 4), side, Ui.dp(this, 4));
        // 与设置面板同一套阴影：圆角 outline + elevation，外层 shell 留出绘制空间
        boolean qpNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        root.setElevation(Ui.dp(this, qpNight ? 18 : 10));
        // 只保留四周淡淡的环境阴影，去掉主要落在下方的点光源阴影
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.setOutlineSpotShadowColor(android.graphics.Color.TRANSPARENT);
        }

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final TextView qpTitle = Ui.text(this, "快面板", 15, Ui.TEXT_PRIMARY, true);
        top.addView(qpTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final android.widget.ImageButton addBtn = quickPanelIconBtn(R.drawable.ic_add, "添加");
        final android.widget.ImageButton removeBtn = quickPanelIconBtn(R.drawable.ic_minus, "移除");
        final android.widget.ImageButton sortBtn = quickPanelIconBtn(R.drawable.ic_drag_handle, "排序");
        final android.widget.ImageButton doneBtn = quickPanelIconBtn(R.drawable.ic_check_badge, "完成排序");
        final android.widget.ImageButton cancelBtn = quickPanelIconBtn(R.drawable.ic_cross_badge, "取消排序");
        doneBtn.setVisibility(View.GONE);
        cancelBtn.setVisibility(View.GONE);
        // 右上角顺序：排序、减号、加号
        top.addView(sortBtn);
        top.addView(removeBtn);
        top.addView(addBtn);
        top.addView(doneBtn);
        top.addView(cancelBtn);
        root.addView(top);

        quickPanelContent = new LinearLayout(this);
        quickPanelContent.setOrientation(LinearLayout.VERTICAL);
        // 排序拖动时行会临时移出内容高度：不裁切，后面还有空位就完整显示
        quickPanelContent.setClipChildren(false);
        quickPanelContent.setClipToPadding(false);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(quickPanelContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 与设置页一样固定高度、内容超高时在弹窗内滚动
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 面板未展开高度：与设置页同一套三合一显示（标题 + 滑块 + dp 值）
        final android.app.Dialog[] qpHolder = new android.app.Dialog[1];
        final int[] qpOpenMaxDp = {openSliderMaxDp()};
        LinearLayout qpHeightRow = new LinearLayout(this);
        qpHeightRow.setOrientation(LinearLayout.HORIZONTAL);
        qpHeightRow.setGravity(Gravity.CENTER_VERTICAL);
        qpHeightRow.setPadding(0, Ui.dp(this, 2), 0, 0);
        final TextView qpOpenLabel = Ui.text(this, "面板未展开高度", 10, Ui.TEXT_SECONDARY, false);
        final TextView qpOpenValue = Ui.text(this, "", 10, Ui.TEXT_SECONDARY, false);
        qpOpenValue.setGravity(Gravity.START);
        qpOpenValue.setMinWidth(Ui.dp(this, 34));
        final SeekBar qpOpenBar = new SeekBar(this);
        int qpThumbPad = Ui.dp(this, 12);
        qpOpenBar.setPadding(qpThumbPad, qpOpenBar.getPaddingTop(),
                qpThumbPad, qpOpenBar.getPaddingBottom());
        qpOpenBar.setMax(qpOpenMaxDp[0]);
        qpOpenBar.setProgress(Math.min(openSliderDp(), qpOpenMaxDp[0]));
        qpOpenValue.setText(qpOpenBar.getProgress() + "dp");
        LinearLayout.LayoutParams qpOpenBarLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 76), ViewGroup.LayoutParams.WRAP_CONTENT);
        qpHeightRow.addView(qpOpenLabel);
        qpHeightRow.addView(qpOpenBar, qpOpenBarLp);
        qpHeightRow.addView(qpOpenValue);
        qpHeightRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        qpOpenBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int dp = clampInt(p, 0, qpOpenMaxDp[0]);
                if (!panelMode) {
                    prefs().edit().putInt(lk(KEY_LAYOUT_OPEN_H), dp).apply();
                }
                qpOpenValue.setText(dp + "dp");
                android.app.Dialog d = qpHolder[0];
                if (d == null) {
                    return;
                }
                int shadow = Ui.dp(FreezeListActivity.this, 12);
                if (panelMode) {
                    panelOpenHeightDp = dp;
                    applyQuickPanelFrame(d, openDialogHeightPx(), panelDialogY(shadow), shadow);
                } else {
                    applyQuickPanelFrame(d, openDialogHeightPx(),
                            d.getWindow() == null ? shadow
                                    : d.getWindow().getAttributes().y, shadow);
                }
                applyQuickPanelSurfaceAlpha(root);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        // 底部展开底栏：与设置页一致，居中箭头在「未展开高度 / 展开最大范围」之间切换
        // 「面板未展开高度」三合一与设置页同位置：放在标题下面、内容滚动区上方，仅全屏提供
        if (!panelMode) {
            root.addView(qpHeightRow, Math.max(0, root.indexOfChild(sv)),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        View qpDivider = new View(this);
        qpDivider.setBackgroundColor(getColor(R.color.divider));
        root.addView(qpDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));
        LinearLayout qpToggleRow = new LinearLayout(this);
        qpToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        qpToggleRow.setGravity(Gravity.CENTER);
        qpToggleRow.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        final android.widget.ImageButton qpSizeToggle = new android.widget.ImageButton(this);
        qpSizeToggle.setImageResource(R.drawable.ic_chevron_down);
        qpSizeToggle.setBackground(null);
        qpSizeToggle.setStateListAnimator(null);
        qpSizeToggle.setElevation(0f);
        qpSizeToggle.setClickable(false);
        qpSizeToggle.setFocusable(false);
        qpSizeToggle.setContentDescription("调节面板高度");
        qpSizeToggle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qpSizeToggle.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        qpToggleRow.addView(qpSizeToggle, new LinearLayout.LayoutParams(
                Ui.dp(this, 28), Ui.dp(this, 28)));
        root.addView(qpToggleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable exitSort = () -> {
            quickPanelSortMode = false;
            // 退出排序恢复滚动区裁切：正常滚动时内容不会画到标题/底栏上
            sv.setClipChildren(true);
            sv.setClipToPadding(true);
            sv.setTranslationZ(0f);
            addBtn.setVisibility(View.VISIBLE);
            removeBtn.setVisibility(View.VISIBLE);
            sortBtn.setVisibility(View.VISIBLE);
            doneBtn.setVisibility(View.GONE);
            cancelBtn.setVisibility(View.GONE);
        };
        addBtn.setOnClickListener(v -> showQuickPanelAddPicker());
        removeBtn.setOnClickListener(v -> {
            quickPanelRemoveMode = !quickPanelRemoveMode;
            if (quickPanelRemoveMode) {
                exitSort.run();
            }
            rebuildQuickPanelContent();
        });
        // 点排序直接在面板里调节：右上角换成打勾 / X；打勾保留顺序，X 还原进入前的顺序
        sortBtn.setOnClickListener(v -> {
            if (quickPanelSortMode) {
                return;
            }
            quickPanelSortMode = true;
            quickPanelRemoveMode = false;
            quickPanelSortSnapshot = rawQuickPanelItems();
            // 排序拖动时行可以移出滚动区：临时取消滚动区裁切并抬到标题/底栏之上，
            // 保证拖到哪里都完整显示、不被截断
            sv.setClipChildren(false);
            sv.setClipToPadding(false);
            sv.setTranslationZ(Ui.dp(this, 4));
            addBtn.setVisibility(View.GONE);
            removeBtn.setVisibility(View.GONE);
            sortBtn.setVisibility(View.GONE);
            doneBtn.setVisibility(View.VISIBLE);
            cancelBtn.setVisibility(View.VISIBLE);
            rebuildQuickPanelContent();
        });
        doneBtn.setOnClickListener(v -> {
            quickPanelSortSnapshot = null;
            exitSort.run();
            rebuildQuickPanelContent();
        });
        cancelBtn.setOnClickListener(v -> {
            if (quickPanelSortSnapshot != null) {
                saveQuickPanelItems(quickPanelSortSnapshot);
                quickPanelSortSnapshot = null;
            }
            exitSort.run();
            rebuildQuickPanelContent();
        });

        // 与设置页相同的容器结构：外层留阴影空间，内容按圆角裁切
        final int qpShadowPad = Ui.dp(this, 12);
        FrameLayout shell = new FrameLayout(this);
        shell.setClipChildren(false);
        shell.setClipToPadding(false);
        shell.setPadding(qpShadowPad, qpShadowPad, qpShadowPad, qpShadowPad);
        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(shell);
        // 快面板不是设置页的二级弹窗：开关都不碰设置页
        Ui.skipAutoHide(dialog);
        quickPanelDialog = dialog;
        quickPanelRootView = root;
        qpHolder[0] = dialog;
        dialog.setOnDismissListener(d -> {
            if (quickPanelDialog == dialog) {
                quickPanelDialog = null;
                quickPanelRootView = null;
                quickPanelContent = null;
                quickPanelRemoveMode = false;
                quickPanelSortMode = false;
                quickPanelSortSnapshot = null;
            }
        });
        // 展开底栏：与设置页同一套高度与位置规则
        final boolean[] qpExpanded = {false};
        // 三合一在展开时消失；未展开时下滑自动隐藏，回到顶部再出现（与全屏设置页一致）
        final boolean[] qpRowHidden = {false};
        final boolean[] qpFingerDown = {false};
        final long[] qpSuppressUntil = {0};
        final Runnable qpShowHeightRow = () -> {
            if (panelMode || qpExpanded[0] || !qpRowHidden[0]) {
                return;
            }
            qpRowHidden[0] = false;
            qpHeightRow.animate().cancel();
            qpHeightRow.setVisibility(View.VISIBLE);
            qpHeightRow.setAlpha(0f);
            qpHeightRow.animate().alpha(1f).setDuration(120).start();
            qpSuppressUntil[0] = android.os.SystemClock.uptimeMillis() + 400;
        };
        final Runnable qpHideHeightRow = () -> {
            if (panelMode || qpExpanded[0] || qpRowHidden[0]) {
                return;
            }
            qpRowHidden[0] = true;
            qpHeightRow.animate().cancel();
            qpHeightRow.animate().alpha(0f).setDuration(100)
                    .withEndAction(() -> qpHeightRow.setVisibility(View.GONE))
                    .start();
            qpSuppressUntil[0] = android.os.SystemClock.uptimeMillis() + 400;
        };
        sv.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_MOVE:
                    qpFingerDown[0] = true;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    qpFingerDown[0] = false;
                    break;
                default:
                    break;
            }
            return false;
        });
        sv.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            int dy = scrollY - oldScrollY;
            if (dy == 0) {
                return;
            }
            if (android.os.SystemClock.uptimeMillis() < qpSuppressUntil[0]) {
                return;
            }
            if (!qpRowHidden[0]) {
                if (dy > 0) {
                    qpHideHeightRow.run();
                }
                return;
            }
            if (qpFingerDown[0] && !sv.canScrollVertically(-1)) {
                qpShowHeightRow.run();
            }
        });
        qpToggleRow.setOnClickListener(v -> {
            qpExpanded[0] = !qpExpanded[0];
            qpSizeToggle.setImageResource(qpExpanded[0]
                    ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
            qpRowHidden[0] = false;
            qpHeightRow.animate().cancel();
            qpHeightRow.setAlpha(1f);
            qpHeightRow.setVisibility(panelMode || qpExpanded[0]
                    ? View.GONE : View.VISIBLE);
            int desired = qpExpanded[0] ? expandDialogHeightPx() : openDialogHeightPx();
            int targetY;
            if (qpExpanded[0]) {
                int screenH = getResources().getDisplayMetrics().heightPixels;
                int limit = bottomBarTopPx();
                int maxBottom = limit > 0 ? limit - Ui.dp(this, 8) : screenH;
                int maxY = maxBottom - desired - qpShadowPad - statusBarHeightPx();
                int curY = dialog.getWindow() == null
                        ? -qpShadowPad : dialog.getWindow().getAttributes().y;
                targetY = panelMode ? Math.max(-qpShadowPad, Math.min(curY, maxY))
                        : Math.max(expandedTopLimitY(qpShadowPad), maxY);
            } else {
                targetY = panelMode ? panelDialogY(qpShadowPad)
                        : (collapsedDefaultY(qpShadowPad)
                        + prefs().getInt(lk(KEY_LAYOUT_DY), 0));
            }
            applyQuickPanelFrame(dialog, desired, targetY, qpShadowPad);
            applyQuickPanelSurfaceAlpha(root);
        });
        dialog.show();
        // 显示位置完全按设置页那套规则：半屏贴半屏上沿、全屏用设置页的记忆偏移
        applyQuickPanelGeometry(dialog, root);
        // 布局落地后重新算「不侵入半屏」的最大值：半屏直接按最大值显示
        qpOpenBar.post(() -> {
            int maxDp = openSliderMaxDp();
            qpOpenMaxDp[0] = maxDp;
            qpOpenBar.setMax(Math.max(1, maxDp));
            int cur = panelMode ? maxDp : Math.min(qpOpenBar.getProgress(), maxDp);
            qpOpenBar.setProgress(cur);
            qpOpenValue.setText(cur + "dp");
            if (panelMode) {
                panelOpenHeightDp = -1;
                applyQuickPanelGeometry(dialog, root);
            }
        });
        final android.view.Window qw = dialog.getWindow();
        // 全屏才允许拖标题移动，位置与设置页共用同一份偏移（半屏设置页本来就不能拖）
        if (!panelMode && qw != null) {
            final boolean[] dragging = {false};
            final float[] dragDownX = {0f};
            final float[] dragDownY = {0f};
            final int[] dragStartX = {0};
            final int[] dragStartY = {0};
            qpTitle.setOnLongClickListener(v -> {
                dragging[0] = true;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                return true;
            });
            qpTitle.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dragDownX[0] = e.getRawX();
                        dragDownY[0] = e.getRawY();
                        dragStartX[0] = qw.getAttributes().x;
                        dragStartY[0] = qw.getAttributes().y;
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (!dragging[0]) {
                            return false;
                        }
                        android.view.WindowManager.LayoutParams lp = qw.getAttributes();
                        lp.x = dragStartX[0] + Math.round(e.getRawX() - dragDownX[0]);
                        lp.y = dragStartY[0] + Math.round(e.getRawY() - dragDownY[0]);
                        qw.setAttributes(lp);
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragging[0]) {
                            dragging[0] = false;
                            // 与设置页共用偏移，下次打开设置页 / 快面板都在同一位置
                            prefs().edit()
                                    .putInt(lk(KEY_LAYOUT_DX), qw.getAttributes().x
                                            - ((getResources().getDisplayMetrics().widthPixels
                                            - settingsDialogWidth()) / 2 - Ui.dp(this, 12)))
                                    .putInt(lk(KEY_LAYOUT_DY), qw.getAttributes().y)
                                    .apply();
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            });
        }
        // 部分 ROM 会在首帧后把系统对话框的窗口底板重铺回不透明：这里再压一次窗口背景并重刷，
        // 快面板用独立的「快面板透明度」档位，不跟随设置页
        root.post(() -> {
            if (qw != null) {
                qw.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            }
            applyQuickPanelSurfaceAlpha(root);
            applyQuickPanelSurfaceAlpha(dialog);
        });
        rebuildQuickPanelContent();
    }

    private void rebuildQuickPanelContent() {
        if (quickPanelContent == null) {
            return;
        }
        quickPanelContent.removeAllViews();
        List<String> items = quickPanelItems();
        if (items.isEmpty()) {
            TextView empty = Ui.text(this, "点右上角 ＋ 添加", 13, Ui.TEXT_SECONDARY, false);
            empty.setGravity(Gravity.CENTER);
            quickPanelContent.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 120)));
            return;
        }
        for (String id : items) {
            quickPanelContent.addView(quickPanelRow(id));
        }
    }

    private View quickPanelRow(final String id) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18));
        iconLp.rightMargin = Ui.dp(this, 8);
        row.addView(quickPanelRowIcon(id), iconLp);
        TextView name = Ui.text(this, quickPanelRowText(id), 13, Ui.TEXT_PRIMARY, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (quickPanelRemoveMode) {
            ImageView del = new ImageView(this);
            del.setImageResource(R.drawable.ic_cross_badge);
            del.setScaleType(ImageView.ScaleType.FIT_CENTER);
            del.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
            del.setOnClickListener(v -> {
                List<String> items = quickPanelItems();
                if (items.remove(id)) {
                    saveQuickPanelItems(items);
                }
                rebuildQuickPanelContent();
            });
            row.addView(del, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            wrap.addView(row);
            return wrap;
        }
        if (quickPanelSortMode) {
            // 排序模式：长按该行后上下拖动，松手按跨越的行数换位并保存
            final boolean[] dragging = {false};
            final float[] downRawY = {0f};
            row.setOnLongClickListener(v -> {
                dragging[0] = true;
                // 拖起后置于其它行之上，向上拖也不会被后面的兄弟行盖住
                wrap.setTranslationZ(Ui.dp(this, 6));
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                return true;
            });
            row.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downRawY[0] = e.getRawY();
                        dragging[0] = false;
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (!dragging[0]) {
                            return false;
                        }
                        wrap.setTranslationY(e.getRawY() - downRawY[0]);
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        if (!dragging[0]) {
                            return false;
                        }
                        dragging[0] = false;
                        float dy = e.getRawY() - downRawY[0];
                        wrap.setTranslationY(0f);
                        wrap.setTranslationZ(0f);
                        int rowH = Math.max(1, wrap.getHeight());
                        int steps = Math.round(dy / rowH);
                        if (steps != 0) {
                            List<String> list = quickPanelItems();
                            int from = list.indexOf(id);
                            // 拖过首/尾就吸到第一/最后一项，不因越界被丢掉
                            int to = clampInt(from + steps, 0, Math.max(0, list.size() - 1));
                            if (from >= 0 && to != from) {
                                list.remove(from);
                                list.add(to, id);
                                saveQuickPanelItems(list);
                            }
                        }
                        rebuildQuickPanelContent();
                        return true;
                    }
                    default:
                        return false;
                }
            });
            wrap.addView(row);
            return wrap;
        }

        // 分类按条目 id 判断，不依赖分组序号，选择器分组顺序调整不会影响行的行为
        if (quickPanelIsToggle(id)) {
            boolean on = quickPanelToggleOn(id);
            row.addView(Ui.text(this, on ? "已开启" : "未开启", 12,
                    on ? getColor(R.color.blue) : Ui.TEXT_SECONDARY, false));
            row.setOnClickListener(v -> {
                quickPanelToggle(id);
                rebuildQuickPanelContent();
            });
            wrap.addView(row);
            return wrap;
        }
        if (quickPanelIsGroup(id)) {
            row.setOnClickListener(v -> {
                if (!quickPanelExpanded.remove(id)) {
                    quickPanelExpanded.add(id);
                }
                rebuildQuickPanelContent();
            });
            wrap.addView(row);
            if (quickPanelExpanded.contains(id)) {
                wrap.addView(quickPanelGroupBody(id));
            }
            return wrap;
        }
        if ("whitelist".equals(id) || "pair".equals(id)) {
            // 参考搞机助手：标题在左，白名单图标 / 关联列表同排显示在右侧；
            // 两行标题列宽固定，保证白名单第一个图标与关联应用主图标左对齐
            LinearLayout.LayoutParams nlp = (LinearLayout.LayoutParams) name.getLayoutParams();
            nlp.width = Ui.dp(this, 84);
            nlp.weight = 0f;
            name.setLayoutParams(nlp);
            View summary = "whitelist".equals(id)
                    ? quickPanelWhitelistSummary() : quickPanelPairSummary();
            row.addView(summary, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if ("whitelist".equals(id)) {
                row.setOnClickListener(v -> {
                    whitelistManagerFromQuickPanel = true;
                    dismissQuickPanel();
                    showWhitelistManager();
                });
            } else {
                row.setOnClickListener(v -> {
                    pairManagerFromQuickPanel = true;
                    dismissQuickPanel();
                    showPairManager();
                });
            }
            wrap.addView(row);
            return wrap;
        }
        if ("select".equals(id)) {
            row.setOnClickListener(v -> {
                dismissQuickPanel();
                enterSelectMode();
            });
            wrap.addView(row);
            return wrap;
        }
        row.addView(Ui.text(this, "›", 15, Ui.TEXT_SECONDARY, false));
        row.setOnClickListener(v -> {
            dismissQuickPanel();
            quickPanelRunEntry(id);
        });
        wrap.addView(row);
        return wrap;
    }

    /** 快面板行内文案：白名单在选择器里叫「列表白名单」，面板里只显示「白名单」。 */
    private String quickPanelRowText(String id) {
        if ("whitelist".equals(id)) {
            return "白名单";
        }
        return quickPanelItemText(id).toString();
    }

    /** 条目是否是开关类：个性设置组 + 专属LSPosed 的两个开关。 */
    private static boolean quickPanelIsToggle(String id) {
        if ("swipe_freeze".equals(id) || "lock_freeze".equals(id)) {
            return true;
        }
        String[][] catalog = quickPanelCatalog();
        for (int i = 1; i < catalog[0].length; i++) {
            if (catalog[0][i].equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 条目是否是「点开标题才展开」的大分组。 */
    private static boolean quickPanelIsGroup(String id) {
        return "icon_group".equals(id) || "fab_group".equals(id) || "gesture_group".equals(id)
                || "wallpaper_group".equals(id) || "ui_opacity".equals(id);
    }

    private void quickPanelMoveItem(String id, int delta) {
        List<String> items = quickPanelItems();
        int index = items.indexOf(id);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= items.size()) {
            return;
        }
        Collections.swap(items, index, target);
        saveQuickPanelItems(items);
        rebuildQuickPanelContent();
    }

    private Drawable quickPanelIconFor(String key) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(packagePart(key), 0);
            return AppIconCache.load(this, packagePart(key), ai);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 「列表白名单」行内摘要：已加入的图标按行铺开，有多少显示多少，不限行数。 */
    private View quickPanelWhitelistSummary() {
        Set<String> set = whitelistSet();
        if (set.isEmpty()) {
            return Ui.text(this, "未添加", 12, Ui.TEXT_SECONDARY, false);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int size = Ui.dp(this, 20);
        int gap = Ui.dp(this, 4);
        final int perLine = 6;
        LinearLayout line = null;
        int i = 0;
        for (String key : set) {
            if (i % perLine == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                col.addView(line);
            }
            ImageView icon = new ImageView(this);
            Drawable d = quickPanelIconFor(key);
            icon.setImageDrawable(d != null ? d
                    : androidx.core.content.ContextCompat.getDrawable(this,
                    android.R.drawable.sym_def_app_icon));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = gap;
            line.addView(icon, lp);
            i++;
        }
        return col;
    }

    /** 「关联应用」行内摘要：每条关联一行「主图标→副图标 名称」，空则「未添加」。 */
    private View quickPanelPairSummary() {
        List<Pair> list = allPairs();
        if (list.isEmpty()) {
            return Ui.text(this, "未添加", 12, Ui.TEXT_SECONDARY, false);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int size = Ui.dp(this, 16);
        for (Pair p : list) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            ImageView mainIcon = new ImageView(this);
            Drawable mainD = quickPanelIconFor(p.main);
            mainIcon.setImageDrawable(mainD != null ? mainD
                    : androidx.core.content.ContextCompat.getDrawable(this,
                    android.R.drawable.sym_def_app_icon));
            line.addView(mainIcon, new LinearLayout.LayoutParams(size, size));

            // 主 → 副 之间用链环标识；这组关联暂停后改为双竖杠暂停图标
            ImageView linkIcon = new ImageView(this);
            linkIcon.setImageResource(p.enabled ? R.drawable.ic_qp_link : R.drawable.ic_qp_pause);
            linkIcon.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
            LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 12), Ui.dp(this, 12));
            linkLp.leftMargin = Ui.dp(this, 2);
            linkLp.rightMargin = Ui.dp(this, 2);
            line.addView(linkIcon, linkLp);

            ImageView subIcon = new ImageView(this);
            Drawable subD = quickPanelIconFor(p.sub);
            subIcon.setImageDrawable(subD != null ? subD
                    : androidx.core.content.ContextCompat.getDrawable(this,
                    android.R.drawable.sym_def_app_icon));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(size, size);
            subLp.leftMargin = Ui.dp(this, 2);
            line.addView(subIcon, subLp);

            TextView nm = Ui.text(this,
                    labelFor(p.main) + " → " + labelFor(p.sub), 11, Ui.TEXT_SECONDARY, false);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = Ui.dp(this, 6);
            line.addView(nm, nlp);
            col.addView(line);
        }
        return col;
    }

    /** 入口条目行为：先 dismiss 快面板，再打开对应弹窗/页面（与设置弹窗「其他」组一致）。 */
    private void quickPanelRunEntry(String id) {
        switch (id) {
            case "tab_settings":
                prefs().edit().putBoolean(KEY_SETTINGS_TAB_COLLAPSED, false).apply();
                showLayoutDialog();
                break;
            case "theme_icon":
                showIconThemeDialog();
                break;
            case "icon_style":
                showIconStyleDialog();
                break;
            case "keep_icon":
                showKeepIconChoiceDialog();
                break;
            case "white_pos":
                showWhiteBadgePosDialog();
                break;
            case "backup":
                showBackup();
                break;
            case "restore":
                showRestore();
                break;
            case "about":
                showAboutDialog();
                break;
            case "credits":
                showThanksDialog();
                break;
            case "donate":
                showDonateDialog();
                break;
            default:
                break;
        }
    }

    /** 「＋」选择器：勾选只是暂存，点「确定」才写入；「取消」不做任何操作。 */
    private void showQuickPanelAddPicker() {
        // 以落盘顺序为基准（含当前形态不显示的条目，避免确定时被裁掉）
        final List<String> current = rawQuickPanelItems();
        final Set<String> checked = new LinkedHashSet<>(current);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 16);
        col.setPadding(side, Ui.dp(this, 2), side, Ui.dp(this, 2));

        for (String[] group : quickPanelCatalog()) {
            // 只有一个条目的组（大分组 / 单入口）：不再单列组标题，勾选这一行就是添加整个栏目
            boolean single = group.length == 2;
            if (!single) {
                TextView head = Ui.text(this, group[0], 12, Ui.TEXT_PRIMARY, true);
                head.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
                col.addView(head);
            }
            for (int i = 1; i < group.length; i++) {
                final String id = group[i];
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
                LinearLayout.LayoutParams pickIconLp = new LinearLayout.LayoutParams(
                        Ui.dp(this, 18), Ui.dp(this, 18));
                pickIconLp.rightMargin = Ui.dp(this, 8);
                row.addView(quickPanelRowIcon(id), pickIconLp);
                TextView name = Ui.text(this, quickPanelItemText(id), 12, Ui.TEXT_SECONDARY, false);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                final CheckBox cb = new CheckBox(this);
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setChecked(checked.contains(id));
                row.addView(cb, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

                row.setOnClickListener(v -> {
                    if (!checked.remove(id)) {
                        checked.add(id);
                    }
                    cb.setChecked(checked.contains(id));
                });
                col.addView(row);
            }
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(sv)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    // 原有顺序保留，新勾选的按选择器顺序追加
                    List<String> result = new ArrayList<>();
                    for (String id : current) {
                        if (checked.contains(id)) {
                            result.add(id);
                        }
                    }
                    for (String[] group : quickPanelCatalog()) {
                        for (int i = 1; i < group.length; i++) {
                            String id = group[i];
                            if (checked.contains(id) && !result.contains(id)) {
                                result.add(id);
                            }
                        }
                    }
                    saveQuickPanelItems(result);
                    rebuildQuickPanelContent();
                })
                .create();
        dialog.show();
        Ui.roundAlertDialog(dialog);
        applySettingsAlignment(dialog);
    }

    /** 落盘里的全部条目（只过滤未知 id，不按当前形态过滤）。 */
    private List<String> rawQuickPanelItems() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString(KEY_QUICK_PANEL_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (!out.contains(id) && quickPanelGroupOf(id) >= 0) {
                    out.add(id);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 冻结白名单 → 白名单列表：右上角多选 / 宫格切换 / 减号 / 加号；减号进入单删模式，多选勾选后减号批量移除。 */
    private void showWhitelistManager() {
        final int modeNormal = 0;
        final int modeRemove = 1;
        final int modeMulti = 2;
        final int[] mode = {modeNormal};
        final Set<String> checked = new HashSet<>();
        final Runnable[] renderList = new Runnable[1];
        final AlertDialog[] dlgHolder = new AlertDialog[1];
        // 左下角按钮文案：普通态「多选」，多选态按勾选情况在全选 / 全不选 / 反选之间切换
        final java.util.function.IntConsumer syncNeutral = total -> {
            if (dlgHolder[0] == null) {
                return;
            }
            android.widget.Button nb = dlgHolder[0].getButton(AlertDialog.BUTTON_NEUTRAL);
            if (nb == null) {
                return;
            }
            if (mode[0] != modeMulti) {
                nb.setText("多选");
            } else if (checked.isEmpty()) {
                nb.setText("全选");
            } else if (checked.size() >= total) {
                nb.setText("全不选");
            } else {
                nb.setText("反选");
            }
        };

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int side = Ui.dp(this, 16);
        root.setPadding(side, Ui.dp(this, 6), side, Ui.dp(this, 2));

        // 标题行：左侧标题；右侧减号 / 加号，多选态只留「减号 / 返回」
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "白名单列表", 16, Ui.TEXT_PRIMARY, true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final android.widget.ImageButton minusBtn =
                whitelistHeaderBtn(R.drawable.ic_minus, "移除");
        final android.widget.ImageButton plusBtn =
                whitelistHeaderBtn(R.drawable.ic_add, "添加");
        final android.widget.ImageButton backBtn =
                whitelistHeaderBtn(R.drawable.ic_back, "返回");
        header.addView(minusBtn);
        header.addView(plusBtn);
        header.addView(backBtn);
        root.addView(header);

        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        MaxHeightScrollView sv = new MaxHeightScrollView(this,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.58f));
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(listBox, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv);

        // 单删模式：点行右侧的红减号立即移出该应用
        final java.util.function.Consumer<String> removeOne = key -> {
            Set<String> cur = whitelistSet();
            if (!cur.remove(key)) {
                return;
            }
            prefs().edit().putStringSet(KEY_WHITELIST, cur).apply();
            SwipeFreeze.syncAsync(this);
            refreshWhitelistEntry();
            rebindCells();
            checked.remove(key);
            toast("已移出白名单：" + labelFor(key));
            renderList[0].run();
        };

        renderList[0] = () -> {
            listBox.removeAllViews();
            final boolean multi = mode[0] == modeMulti;
            final boolean remove = mode[0] == modeRemove;
            plusBtn.setVisibility(multi ? View.GONE : View.VISIBLE);
            backBtn.setVisibility(multi ? View.VISIBLE : View.GONE);
            minusBtn.setImageTintList(ColorStateList.valueOf(mode[0] == modeNormal
                    ? getColor(R.color.text_primary) : getColor(R.color.danger)));

            List<String> keys = new ArrayList<>(whitelistSet());
            Collections.sort(keys, (a, b) -> labelFor(a).compareToIgnoreCase(labelFor(b)));
            syncNeutral.accept(keys.size());
            if (keys.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("白名单为空，点右上角＋添加");
                empty.setTextSize(12);
                empty.setTextColor(getColor(R.color.text_secondary));
                empty.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 12));
                listBox.addView(empty);
                return;
            }
            final int cols = 5;
            final int rowIconPx = Ui.dp(this, 40);
            // 宫格：每行 5 个，多选小方框在图标右下、单删红减号在右上
            LinearLayout gridRow = null;
            for (int i = 0; i < keys.size(); i++) {
                if (i % cols == 0) {
                    gridRow = new LinearLayout(this);
                    gridRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i > 0) {
                        rlp.topMargin = Ui.dp(this, 2);
                    }
                    listBox.addView(gridRow, rlp);
                }
                final String key = keys.get(i);
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));

                FrameLayout iconBox = new FrameLayout(this);
                ImageView icon = new ImageView(this);
                Drawable d = quickPanelIconFor(key);
                if (d != null) {
                    icon.setImageDrawable(d);
                } else {
                    icon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
                iconBox.addView(icon, new FrameLayout.LayoutParams(
                        rowIconPx, rowIconPx));
                // 宫格里用自绘小方框：CheckBox 自带 48dp 触点画布，缩小后会裁掉半个框
                final TextView cb = new TextView(this);
                cb.setTextSize(9);
                cb.setTextColor(0xFFFFFFFF);
                cb.setGravity(Gravity.CENTER);
                cb.setBackgroundResource(checked.contains(key)
                        ? R.drawable.bg_check_on : R.drawable.bg_check_off);
                cb.setText(checked.contains(key) ? "✓" : "");
                cb.setVisibility(multi ? View.VISIBLE : View.GONE);
                FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(
                        Ui.dp(this, 15), Ui.dp(this, 15));
                cbLp.gravity = Gravity.BOTTOM | Gravity.END;
                iconBox.addView(cb, cbLp);
                final TextView rm = new TextView(this);
                rm.setText("−");
                rm.setTextColor(0xFFFFFFFF);
                rm.setTextSize(12);
                rm.setGravity(Gravity.CENTER);
                rm.setBackgroundResource(R.drawable.bg_remove_badge);
                rm.setVisibility(remove ? View.VISIBLE : View.GONE);
                FrameLayout.LayoutParams rmLp = new FrameLayout.LayoutParams(
                        Ui.dp(this, 18), Ui.dp(this, 18));
                rmLp.gravity = Gravity.TOP | Gravity.END;
                rm.setOnClickListener(v -> removeOne.accept(key));
                iconBox.addView(rm, rmLp);

                cell.addView(iconBox, new LinearLayout.LayoutParams(
                        rowIconPx, rowIconPx));
                TextView name = new TextView(this);
                name.setText(labelFor(key));
                name.setTextSize(10);
                name.setTextColor(getColor(R.color.text_primary));
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER);
                name.setPadding(0, Ui.dp(this, 4), 0, 0);
                cell.addView(name, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setOnClickListener(v -> {
                    if (mode[0] != modeMulti) {
                        return;
                    }
                    if (checked.contains(key)) {
                        checked.remove(key);
                    } else {
                        checked.add(key);
                    }
                    boolean on = checked.contains(key);
                    cb.setText(on ? "✓" : "");
                    cb.setBackgroundResource(on
                            ? R.drawable.bg_check_on : R.drawable.bg_check_off);
                    syncNeutral.accept(keys.size());
                });
                gridRow.addView(cell, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            if (gridRow != null) {
                int rest = cols - keys.size() % cols;
                if (rest > 0 && rest < cols) {
                    for (int i = 0; i < rest; i++) {
                        gridRow.addView(new View(this), new LinearLayout.LayoutParams(
                                0, 1, 1f));
                    }
                }
            }
        };

        backBtn.setOnClickListener(v -> {
            mode[0] = modeNormal;
            checked.clear();
            renderList[0].run();
        });
        minusBtn.setOnClickListener(v -> {
            if (mode[0] == modeMulti) {
                if (checked.isEmpty()) {
                    toast("先勾选要移出的应用");
                    return;
                }
                int n = checked.size();
                Set<String> cur = whitelistSet();
                cur.removeAll(checked);
                prefs().edit().putStringSet(KEY_WHITELIST, cur).apply();
                SwipeFreeze.syncAsync(this);
                refreshWhitelistEntry();
                rebindCells();
                checked.clear();
                toast("已移出白名单 " + n + " 个应用");
                renderList[0].run();
            } else {
                mode[0] = mode[0] == modeRemove ? modeNormal : modeRemove;
                renderList[0].run();
            }
        });
        plusBtn.setOnClickListener(v -> showWhitelistAddPicker());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .setNeutralButton("多选", null)
                .setNegativeButton("返回", (d, w) -> whitelistManagerFromQuickPanel = false)
                .create();
        dlgHolder[0] = dialog;
        whitelistManagerDialog = dialog;
        dialog.setOnCancelListener(d -> {
            if (!whitelistManagerFromQuickPanel) {
                return;
            }
            whitelistManagerFromQuickPanel = false;
            // 等本页完成关闭后，再回到作为上一页的快面板
            main.post(this::showQuickPanel);
        });
        dialog.setOnDismissListener(d -> {
            if (whitelistManagerDialog == dialog) {
                whitelistManagerDialog = null;
            }
        });
        renderList[0].run();
        disableDialogWindowAnimation(dialog);
        dialog.show();
        if (whitelistManagerFromQuickPanel) {
            // 从快面板进来的：关闭时只回快面板，不去动设置页
            Ui.skipAutoHide(dialog);
        }
        Ui.roundAlertDialog(dialog);
        applySettingsAlignment(dialog);
        // 左下角按钮：普通态进入多选；多选态按勾选情况执行全选 / 全不选 / 反选
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (mode[0] != modeMulti) {
                mode[0] = modeMulti;
                checked.clear();
                renderList[0].run();
                return;
            }
            List<String> keys = new ArrayList<>(whitelistSet());
            if (checked.isEmpty()) {
                checked.addAll(keys);
            } else if (checked.size() >= keys.size()) {
                checked.clear();
            } else {
                Set<String> inverted = new HashSet<>(keys);
                inverted.removeAll(checked);
                checked.clear();
                checked.addAll(inverted);
            }
            renderList[0].run();
        });
        renderList[0].run();
    }

    /** 白名单列表标题行的小图标按钮：32dp 点击区，图形跟随正文色。 */
    private android.widget.ImageButton whitelistHeaderBtn(int drawable, String desc) {
        android.widget.ImageButton btn = new android.widget.ImageButton(this);
        btn.setImageResource(drawable);
        btn.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_primary)));
        btn.setBackground(null);
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        btn.setContentDescription(desc);
        btn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        return btn;
    }

    /** 限制最大高度的滚动容器：内容少时按内容收缩，内容多时在弹窗内滚动，标题行保持固定。 */
    private static class MaxHeightScrollView extends android.widget.ScrollView {
        private final int maxH;

        MaxHeightScrollView(android.content.Context c, int maxH) {
            super(c);
            this.maxH = maxH;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }

    /** 白名单添加应用：与「主副应用关联」选主应用同一种「图标 + 名称」列表。 */
    private void showWhitelistAddPicker() {
        final Set<String> white = whitelistSet();
        final List<Item> source = new ArrayList<>();
        for (Item it : items) {
            if (!white.contains(it.pkg)) {
                source.add(it);
            }
        }
        if (source.isEmpty()) {
            toast("冻结列表里没有可加入的应用");
            return;
        }
        final String[] names = new String[source.size()];
        for (int i = 0; i < source.size(); i++) {
            names[i] = source.get(i).label;
        }
        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog pick = new AlertDialog.Builder(this)
                .setTitle("添加白名单应用")
                .setView(buildAppPickList(source, names, pos -> {
                    Item it = source.get(pos);
                    setWhitelisted(it.pkg, true);
                    refreshWhitelistEntry();
                    toast("已加入白名单：" + it.label);
                    // 完成添加：关掉选择页与管理弹窗，再按最新白名单重开管理弹窗
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    if (whitelistManagerDialog != null) {
                        whitelistManagerDialog.dismiss();
                        whitelistManagerDialog = null;
                    }
                    showWhitelistManager();
                }))
                .setNegativeButton("取消", null)
                .create();
        holder[0] = pick;
        pick.show();
        Ui.roundAlertDialog(pick);
        // 与关联应用的说明/选择弹窗同一宽度
        applySettingsAlignment(pick);
    }

    /** 关于凚：图标、版本与简版介绍。 */
    private void showAboutDialog() {
        int pad = Ui.dp(this, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, 0);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        // logo 圆角：按圆角矩形裁切，与弹窗卡片的圆角观感一致
        final int logoRadius = Ui.dp(this, 14);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), logoRadius);
            }
        });
        // 长按 logo：一键导出诊断包（设备/ROM、设置快照、logcat+hook tag、
        // 名单冻结状态、hook 配置真值、LSPosed 作用域、操作流水），不再单独做「日志包」
        icon.setOnLongClickListener(v -> {
            exportDiagnostics();
            return true;
        });
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 56), Ui.dp(this, 56));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(icon, iconLp);

        TextView name = Ui.text(this, "凚", 20, Ui.TEXT_PRIMARY, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(name);

        TextView version = Ui.text(this, aboutVersionText(), 12, Ui.TEXT_SECONDARY, false);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, Ui.dp(this, 2), 0, 0);
        card.addView(version);

        TextView developer = Ui.text(this, "酷安@大火", 12, Ui.TEXT_SECONDARY, false);
        developer.setGravity(Gravity.CENTER);
        developer.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(developer);

        TextView changelogEntry = Ui.text(this, "更新日志", 12, getColor(R.color.blue), false);
        changelogEntry.setGravity(Gravity.CENTER);
        changelogEntry.setPadding(0, Ui.dp(this, 4), 0, 0);
        changelogEntry.setOnClickListener(v -> showChangelogDialog());
        card.addView(changelogEntry);

        TextView intro = Ui.text(this,
                "集百家之长，合凚之美\n做最人性化的应用冻结工具",
                13, Ui.TEXT_PRIMARY, true);
        intro.setGravity(Gravity.CENTER);
        intro.setLineSpacing(Ui.dp(this, 4), 1f);
        intro.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(intro);

        TextView featureTitle = Ui.text(this, "功能介绍", 12, Ui.TEXT_SECONDARY, true);
        featureTitle.setPadding(0, Ui.dp(this, 16), 0, 0);
        card.addView(featureTitle);

        String featureText = "· 使用先知：需 root 或 Shizuku，无联网，尚未开源；\n"
                + "· 名单与标签：可自建、重命名、隐藏、排序并可设每次默认进入的标签页，长按标签页可重命名、排序等；\n"
                + "· 白名单：加入白名单的应用不会被冻结，列表仍保留图标，有白名单标识；\n"
                + "· 关联应用：打开主可静默解冻副，冻结主也冻结副；\n"
                + "· 一键冻结：底部按钮默认冻住全部列表；\n"
                + "· 一键解冻：长按底部冻结按钮，可触发解冻选项；\n"
                + "· 底部按钮仅冻/解当前列表：开启后一键冻结与长按解冻只处理当前列表，其他列表不受影响；\n"
                + "· 两种形态：半屏浮层随手打开，全屏列表适合慢慢整理；\n"
                + "· 长按列表空白处手势：左右滑快速翻标签，上滑半屏进全屏、下滑回半屏，可自行开启；\n"
                + "· 长按底栏空白处手势：快面板，可添加快捷设置，一目了然看完当前状态；\n"
                + "· 半屏调节：开启调节后，拖动顶部小白条改高度；\n"
                + "· 全屏与半屏美化：自定义壁纸、透明度等；\n"
                + "· 冻结图标样式：全彩、全灰，左灰右彩等八种半灰半彩；\n"
                + "· 主题图标样式：可选系统默认图标或当前主题图标，切换后自动刷新列表图标；\n"
                + "· 保留桌面图标（转暂停）：冻结转为暂停模式，桌面图标保留并置灰，点击图标可继续打开；\n"
                + "· 划卡冻结：标签应用划掉最近任务卡片即冻结；\n"
                + "· 锁屏冻结：锁屏后按标签页冻结，可设定时间；\n"
                + "· 备份恢复：可单独恢复添加的桌面图标、白名单、关联应用、列表、配置。";
        // 每条开头的功能名用主色加粗，后面的说明文字用次要色，两者明显区分
        android.text.SpannableString feats = new android.text.SpannableString(featureText);
        int nameColor = Ui.wpPrimary(this);
        java.util.regex.Matcher fm = java.util.regex.Pattern
                .compile("· ([^：\\n]+)：").matcher(featureText);
        while (fm.find()) {
            int ns = fm.start(1), ne = fm.end(1);
            feats.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    ns, ne, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            feats.setSpan(new android.text.style.ForegroundColorSpan(nameColor),
                    ns, ne, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView features = Ui.text(this, feats, 12, Ui.TEXT_SECONDARY, false);
        features.setLineSpacing(Ui.dp(this, 5), 1f);
        features.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(features);

        TextView close = new TextView(this);
        close.setText("返回");
        close.setTextSize(14);
        close.setTypeface(null, android.graphics.Typeface.BOLD);
        close.setTextColor(getColor(R.color.blue));
        close.setGravity(Gravity.CENTER);
        // 底栏高度与设置面板底部（倒三角那一行：28dp 图形 + 上下各 2dp）保持一致
        close.setPadding(0, 0, 0, 0);

        showAboutSizedDialog(card, close);
    }

    /** 更新日志：使用文档中的版本内容，最新版本在最上方。 */
    private void showChangelogDialog() {
        int pad = Ui.dp(this, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, 0);

        TextView title = Ui.text(this, "更新日志", 19, Ui.TEXT_PRIMARY, true);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        StringBuilder raw = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(getResources().openRawResource(R.raw.changelog),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (raw.length() > 0) {
                    raw.append('\n');
                }
                raw.append(line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取更新日志失败", e);
        }

        String changelog = raw.toString();
        android.text.SpannableString log = new android.text.SpannableString(changelog);
        Matcher versions = Pattern.compile("(?m)^v\\S+ · \\d{4}-\\d{2}-\\d{2}$")
                .matcher(changelog);
        while (versions.find()) {
            log.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    versions.start(), versions.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            log.setSpan(new android.text.style.ForegroundColorSpan(Ui.wpPrimary(this)),
                    versions.start(), versions.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            log.setSpan(new android.text.style.RelativeSizeSpan(1.12f),
                    versions.start(), versions.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView content = Ui.text(this, log, 12, Ui.TEXT_SECONDARY, false);
        content.setLineSpacing(Ui.dp(this, 5), 1f);
        content.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(content);

        TextView close = new TextView(this);
        close.setText("返回");
        close.setTextSize(14);
        close.setTypeface(null, android.graphics.Typeface.BOLD);
        close.setTextColor(getColor(R.color.blue));
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, 0, 0, 0);
        showAboutSizedDialog(card, close);
    }

    /** 关于凚与更新日志共用：同宽同高、同位置、内部滚动、同一透明度。 */
    private void showAboutSizedDialog(LinearLayout card, TextView close) {
        int pad = Ui.dp(this, 18);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int widthPx = Math.min(Math.round(screenW * 0.86f), Ui.dp(this, 340));

        // 圆角底板固定在窗口内，只有内部内容滚动，滚动时圆角不会被裁掉
        final int radiusPx = Ui.dp(this, 24);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        Ui.applyDialogBg(shell);
        shell.setClipToOutline(true);
        shell.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });

        card.setPadding(pad, pad, pad, Ui.dp(this, 6));
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.setFillViewport(false);
        scroller.addView(card);
        shell.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        shell.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 32)));

        // 高度按可用区算：底部统一到「底栏最上沿」。
        // 半屏跟随设置页的展开状态：设置页展开时对话框也展开、未展开时也贴合设置页高度；
        // 全屏则从状态栏下沿铺到顶栏上沿（与原来一致）。
        final int statusTop = statusBarHidden ? 0 : introStatusInsetPx();
        int barTopWin = -1;
        View barAnchor = findViewById(panelMode ? R.id.panel_bottom_bar : R.id.fab_group);
        if (barAnchor != null && barAnchor.getHeight() > 0) {
            int[] loc = new int[2];
            barAnchor.getLocationInWindow(loc);
            barTopWin = loc[1];
        }
        int settingsTopWin = -1;
        if (panelMode && settingsSurfaceRoot != null
                && settingsSurfaceRoot.getHeight() > 0
                && settingsSurfaceRoot.getVisibility() == View.VISIBLE) {
            int[] loc = new int[2];
            settingsSurfaceRoot.getLocationInWindow(loc);
            settingsTopWin = loc[1];
        }
        int topLimit = settingsTopWin > 0 ? settingsTopWin : statusTop;
        int availH = barTopWin - topLimit;
        final int dialogTopY = topLimit;
        if (availH <= Ui.dp(this, 200)) {
            // 取不到底栏位置时退回按内容量：超过屏幕 80% 才滚动
            card.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            availH = Math.min(card.getMeasuredHeight() + Ui.dp(this, 80),
                    Math.round(screenH * 0.8f));
        }

        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(shell);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        close.setOnClickListener(v -> d.dismiss());
        close.setFocusable(false);
        d.show();
        // 打开期间隐藏设置页；关掉（最后一个二级弹窗）后设置页自动回来
        Ui.autoHideSettings(d);
        int shadowPad = applySettingsDialogShadow(d);
        applySettingsSurfaceAlpha(d);
        // 内容超高时默认可能被焦点带到尾部，这里强制回到顶部
        scroller.post(() -> scroller.scrollTo(0, 0));
        if (w != null) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = widthPx + shadowPad * 2;
            lp.height = availH + shadowPad * 2;
            // 顶部与设置页顶对齐（全屏即状态栏下沿），底部正好停在底栏上沿
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dialogTopY - shadowPad;
            w.setAttributes(lp);
            // 每次布局后按真实位置校正：卡片最下沿绝不越过底栏最上沿（首帧高度还没落地也管用）
            shell.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                int limit = bottomBarTopOnScreen();
                if (limit <= 0) {
                    return;
                }
                int[] loc = new int[2];
                shell.getLocationOnScreen(loc);
                // 底部那圈阴影也算进去：卡片下沿 + 12dp 阴影不能越过底栏
                int overflow = loc[1] + shell.getHeight() + shadowPad - limit;
                if (overflow > 0) {
                    android.view.WindowManager.LayoutParams cur = w.getAttributes();
                    int target = Math.max(Ui.dp(this, 200), cur.height - overflow);
                    if (target < cur.height) {
                        cur.height = target;
                        w.setAttributes(cur);
                    }
                }
            });
        }
    }

    /** 底栏最上沿的屏幕 y（全屏取悬浮冻结按钮顶边，半屏取底部状态条顶边）；取不到返回 -1。 */
    private int bottomBarTopOnScreen() {
        View anchor = findViewById(panelMode ? R.id.panel_bottom_bar : R.id.fab_group);
        if (anchor == null || anchor.getHeight() <= 0) {
            return -1;
        }
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        return loc[1];
    }

    /** 致谢名单：与关于同款圆角卡片，高度随名单内容自适应，过高时内部滚动。 */
    private void showThanksDialog() {
        int pad = Ui.dp(this, 20);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, Ui.dp(this, 22), pad, Ui.dp(this, 8));

        TextView title = Ui.text(this, "致谢名单", 19, Ui.TEXT_PRIMARY, true);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView subtitle = Ui.text(this, "愿你每一天都如你所愿", 12, Ui.TEXT_SECONDARY, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(subtitle);

        addThanksSection(card, "赞赏名单",
                new String[][]{{"酷安@知岁的里昂", null}});
        addThanksSection(card, "测试专员",
                new String[][]{{"酷安@知岁的里昂", null}});

        TextView close = new TextView(this);
        close.setText("感谢");
        close.setTextSize(14);
        close.setTypeface(null, android.graphics.Typeface.BOLD);
        close.setTextColor(getColor(R.color.blue));
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 12));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // 与「（查看说明）」弹窗同宽：复用设置面板的宽度（屏宽减 104dp、上限 304dp）
        int widthPx = settingsDialogWidth();

        final int radiusPx = Ui.dp(this, 24);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        Ui.applyDialogBg(shell);
        shell.setClipToOutline(true);
        shell.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });

        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.setFillViewport(false);
        scroller.addView(card);
        shell.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(shell);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        close.setOnClickListener(v -> d.dismiss());
        close.setFocusable(false);
        d.show();
        applySettingsSurfaceAlpha(d);
        // 与设置面板、「（查看说明）」弹窗同宽，并保持居中
        applySettingsWidthCentered(d);
        // 名单多时收在屏幕 70% 以内，超出部分交给内部滚动
        shell.post(() -> {
            int maxH = Math.round(screenH * 0.70f);
            if (shell.getHeight() > maxH && close.getHeight() > 0) {
                LinearLayout.LayoutParams slp =
                        (LinearLayout.LayoutParams) scroller.getLayoutParams();
                slp.height = maxH - close.getHeight();
                scroller.setLayoutParams(slp);
            }
        });
    }

    /** 致谢卡片里的小节：小节标题 + 浅底药丸行（左侧名单，右侧可带金额）。 */
    private void addThanksSection(LinearLayout card, String heading, String[][] rows) {
        TextView h = Ui.text(this, heading, 12, Ui.TEXT_SECONDARY, true);
        h.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 8));
        card.addView(h);
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            int hpad = Ui.dp(this, 14);
            int vpad = Ui.dp(this, 12);
            line.setPadding(hpad, vpad, hpad, vpad);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(getColor(R.color.soft_bg));
            bg.setCornerRadius(Ui.dp(this, 14));
            line.setBackground(bg);

            TextView name = Ui.text(this, row[0], 13, Ui.TEXT_PRIMARY, false);
            line.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (row.length > 1 && row[1] != null) {
                TextView amount = Ui.text(this, row[1], 12, Ui.primary(this), true);
                line.addView(amount);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 8);
            card.addView(line, lp);
        }
    }

    /** 版本名与版本号，读取失败时降级显示。 */
    private String aboutVersionText() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode;
            return "版本 " + pi.versionName + "（vc" + code + "）";
        } catch (PackageManager.NameNotFoundException e) {
            return "版本未知";
        }
    }

    // ---------------- 诊断包（关于页长按 logo） ----------------

    /**
     * 长按关于页 logo：一键收集设备/ROM、凚版本与偏好、最近 5 分钟 logcat、
     * 最近一次冻结/解冻的命令与返回，打包成 zip 落到 Download/凚/诊断包。
     * 名单类偏好只记数量，其余字符串里的包名做短哈希，不收集账号等隐私。
     */
    private void exportDiagnostics() {
        if (diagExporting) {
            return;
        }
        diagExporting = true;
        toast("正在生成诊断包…");
        JinApp.IO.execute(() -> {
            File cache = new File(getCacheDir(), "diag");
            String err = null;
            File saved = null;
            String name = "凚诊断_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".zip";
            try {
                deleteRecursively(cache);
                if (!cache.exists() && !cache.mkdirs()) {
                    throw new IOException("无法创建缓存目录");
                }
                writeDiagFile(new File(cache, "device_info.txt"), buildDiagDeviceInfo());
                writeDiagFile(new File(cache, "prefs.txt"), buildDiagPrefs());
                writeDiagFile(new File(cache, "logcat_5min.txt"), collectDiagLogcat());
                writeDiagFile(new File(cache, "last_pm.txt"), buildDiagLastPm());
                writeDiagFile(new File(cache, "freeze_state.txt"), buildDiagFreezeState());
                writeDiagFile(new File(cache, "freeze_actions.txt"), buildDiagActions());
                writeDiagFile(new File(cache, "说明.txt"), buildDiagReadme());
                File zip = new File(getCacheDir(), name);
                zipDiagDir(cache, zip);
                saved = saveDiagZip(zip, name);
                zip.delete();
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            deleteRecursively(cache);
            final File f = saved;
            final String msg = err;
            main.post(() -> {
                diagExporting = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (f == null) {
                    compactCard("诊断包导出失败",
                            "写入 " + DIAG_DIR + " 失败：" + msg,
                            null, new String[]{"知道了"}, new Runnable[]{null});
                    return;
                }
                compactCard("诊断包已导出", f.getAbsolutePath()
                                + "\n\n包含：设备与 ROM 信息、设置快照、最近 5 分钟 logcat"
                                + "（含 hook 专用 tag）、名单应用冻结状态、hook 配置真值、"
                                + "LSPosed 作用域、最近 50 条操作流水。"
                                + "\n不收集：账号、IMEI、手机号、通讯录权限；不含名单外的应用。",
                        null, new String[]{"关闭", "复制路径"}, new Runnable[]{null, () -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("诊断包路径", f.getAbsolutePath()));
                            toast("路径已复制");
                        }});
            });
        });
    }

    /** 落盘到 Download/凚/诊断包：先直接写，没有「所有文件访问」时交给 root/Shizuku 复制。 */
    private File saveDiagZip(File zip, String name) throws IOException {
        File dir = new File(DIAG_DIR);
        File target = new File(dir, name);
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建 " + DIAG_DIR);
            }
            FileInputStream in = new FileInputStream(zip);
            FileOutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            out.close();
            return target;
        } catch (Exception direct) {
            PrivShell.Result r = PrivShell.exec("mkdir -p '" + DIAG_DIR + "' && cp -f '"
                    + zip.getAbsolutePath() + "' '" + target.getAbsolutePath()
                    + "' && chmod 666 '" + target.getAbsolutePath() + "'", 30);
            if (r != null && r.code == 0) {
                return target;
            }
            throw new IOException("直接写入失败（" + direct.getMessage()
                    + "），root/Shizuku 复制也失败");
        }
    }

    /** 设备与 ROM 信息：Build 字段、屏幕、shell 通道、LSPosed 状态、桌面包名版本、getprop 节选。 */
    private String buildDiagDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("凚版本: ").append(aboutVersionText()).append('\n');

        sb.append('\n').append("[设备]\n");
        sb.append("厂商: ").append(Build.MANUFACTURER).append('\n');
        sb.append("品牌: ").append(Build.BRAND).append('\n');
        sb.append("型号: ").append(Build.MODEL).append('\n');
        sb.append("设备代号: ").append(Build.DEVICE).append('\n');
        sb.append("产品: ").append(Build.PRODUCT).append('\n');
        sb.append("主板/硬件: ").append(Build.BOARD).append(" / ").append(Build.HARDWARE).append('\n');
        sb.append("ABI: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        if (Build.VERSION.SDK_INT >= 23) {
            sb.append("安全补丁: ").append(Build.VERSION.SECURITY_PATCH).append('\n');
        }
        sb.append("ROM 版本: ").append(Build.DISPLAY).append(" / ").append(Build.ID)
                .append(" / ").append(Build.VERSION.INCREMENTAL).append('\n');
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n');

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Configuration c = getResources().getConfiguration();
        sb.append('\n').append("[屏幕与显示]\n");
        sb.append("分辨率: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                .append("，density ").append(dm.density)
                .append("，dpi ").append(dm.densityDpi).append('\n');
        sb.append("最小宽度: ").append(c.smallestScreenWidthDp).append("dp")
                .append("，字体缩放 ").append(c.fontScale)
                .append("，深色模式 ")
                .append((c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        ? "是" : "否")
                .append('\n');

        sb.append('\n').append("[权限通道]\n");
        sb.append("shell 模式: ").append(PrivShell.mode())
                .append("（").append(PrivShell.label()).append("）\n");
        PrivShell.Result id = PrivShell.exec("id", 8);
        sb.append("id: ").append(id == null || id.out == null ? "无返回" : id.out.trim()).append('\n');
        sb.append("LSPosed 模块: ")
                .append(SwipeFreeze.moduleActive() ? "已生效" : "未生效/未检测到").append('\n');

        sb.append('\n').append("[桌面]\n");
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                String lp = ri.activityInfo.packageName;
                sb.append("桌面包名: ").append(lp).append('\n');
                sb.append("桌面入口: ").append(ri.activityInfo.name).append('\n');
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(lp, 0);
                long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                sb.append("桌面版本: ").append(pi.versionName)
                        .append("（vc").append(code).append("）\n");
            } else {
                sb.append("桌面: 无法解析\n");
            }
        } catch (Exception e) {
            sb.append("桌面信息读取失败: ").append(e).append('\n');
        }

        sb.append('\n').append("[系统属性节选]\n");
        try {
            PrivShell.Result props = PrivShell.exec("getprop", 15);
            if (props != null && props.out != null) {
                int n = 0;
                for (String line : props.out.split("\\r?\\n")) {
                    String t = line.trim();
                    if (!(t.contains("ro.build.") || t.contains("ro.product.")
                            || t.contains("ro.miui.") || t.contains("ro.soc")
                            || t.contains("ro.system.") || t.contains("ro.vendor.")
                            || t.contains("ro.boot.") || t.contains("ro.config.")
                            || t.contains("persist.sys."))) {
                        continue;
                    }
                    sb.append(t).append('\n');
                    if (++n >= 240) {
                        sb.append("…（已截断）\n");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            sb.append("读取失败: ").append(e).append('\n');
        }
        return sb.toString();
    }

    /** 偏好键值：名单类只记数量，其他字符串里的包名统一做短哈希。 */
    private String buildDiagPrefs() {
        StringBuilder sb = new StringBuilder();
        sb.append("凚版本: ").append(aboutVersionText()).append('\n');
        sb.append("偏好文件: ").append(PREFS).append(".xml\n");
        sb.append("说明: 名单/标签/白名单/关联等只记数量；其余字符串里的包名替换为短哈希。\n");
        sb.append("不收集: 账号、IMEI、手机号、完整应用名单原文、通讯录权限。\n\n");
        Map<String, ?> all = prefs().getAll();
        List<String> keys = new ArrayList<>(all.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            sb.append(key).append(" = ").append(diagValueText(key, all.get(key))).append('\n');
        }
        if (keys.isEmpty()) {
            sb.append("（没有任何偏好）\n");
        }
        return sb.toString();
    }

    private static String diagValueText(String key, Object v) {
        if (v == null) {
            return "null";
        }
        if (KEY_DIAG_HIST.equals(key)) {
            return "（操作流水见 freeze_actions.txt）";
        }
        if (v instanceof Set) {
            return ((Set<?>) v).size() + " 项（内容略）";
        }
        if (!(v instanceof String)) {
            return String.valueOf(v);
        }
        String s = (String) v;
        if (KEY_ORDER.equals(key) || KEY_CUSTOM.equals(key)) {
            return nonEmptyLines(s) + " 行（内容略）";
        }
        if (KEY_SUB_PAIRS.equals(key)) {
            return nonEmptyLines(s) + " 组（内容略）";
        }
        if (KEY_APP_TABS.equals(key) || KEY_TABS.equals(key) || KEY_APP_NAMES.equals(key)) {
            return diagJsonCount(s);
        }
        String masked = maskPackageTokens(s);
        return masked.length() > 400 ? masked.substring(0, 400) + "…（截断）" : masked;
    }

    private static int nonEmptyLines(String s) {
        int n = 0;
        for (String line : s.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static String diagJsonCount(String s) {
        try {
            if (s.trim().startsWith("[")) {
                return "JSON 数组 " + new JSONArray(s).length() + " 项（内容略）";
            }
            return "JSON 对象 " + new JSONObject(s).length() + " 个键（内容略）";
        } catch (Exception e) {
            return "JSON 解析失败（" + s.length() + " 字符）";
        }
    }

    /** 把包名样式的 token 换成短哈希，保留结构但不泄露具体应用清单。 */
    private static String maskPackageTokens(String s) {
        Matcher m = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(hashToken(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String hashToken(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder("pkg#");
            for (int i = 0; i < 4 && i < d.length; i++) {
                hex.append(String.format(Locale.US, "%02x", d[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "pkg#?";
        }
    }

    /**
     * 最近 5 分钟 logcat，并额外摘出 hook 专用 tag。
     * Android 16/17 的 logcat CLI 有变化（-d 可能不被接受），这里按候选命令逐个探测，
     * 都不可用时退回流式抓 6 秒；--help 原文一并写进文件，便于下次调整参数。
     */
    private String collectDiagLogcat() {
        StringBuilder sb = new StringBuilder();
        sb.append("生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        PrivShell.Result help = PrivShell.exec("logcat --help 2>&1 | head -60", 15);
        sb.append("\n[logcat --help]\n").append(oneLineDiag(help, 4000)).append('\n');

        String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(System.currentTimeMillis() - 5 * 60 * 1000L));
        String[] candidates = {
                "logcat -d -v threadtime -T '" + since + "' 2>&1",
                "logcat -d -v threadtime -t 4000 2>&1",
                "logcat -d -b all -t 4000 2>&1"
        };
        String full = null;
        String used = null;
        for (String cmd : candidates) {
            PrivShell.Result r = PrivShell.exec(cmd, 45);
            if (r != null && !r.timeout && usableLogcat(r.out)) {
                full = r.out;
                used = cmd;
                break;
            }
        }
        if (full == null) {
            // 最老也最通用的写法：流式抓 6 秒后由 timeout 结束
            PrivShell.Result s = PrivShell.exec("timeout 6 logcat -v threadtime 2>&1", 20);
            if (s != null && usableLogcat(s.out)) {
                full = s.out;
                used = "timeout 6 logcat -v threadtime（流式兜底）";
            }
        }
        if (full == null) {
            sb.append("\n[抓取失败]\n所有候选命令都不可用（见上方 --help 原文），"
                    + "请在凚 里复现一次冻结/解冻后再导出。\n");
            return sb.toString();
        }
        sb.append("\n[使用命令] ").append(used).append('\n');

        // 专用 tag 摘要：锁屏/划卡 hook、暂停、配置读取，排查时先看这一块
        StringBuilder hits = new StringBuilder();
        int hitCount = 0;
        for (String line : full.split("\r?\n")) {
            if (line.contains("JinLockFreeze") || line.contains("JinSwipeFreeze")
                    || line.contains("JinFreeze") || line.contains("JinHookConfig")
                    || line.contains("JinPm") || line.contains("com.jin.freeze")
                    || line.contains("suspend") || line.contains("disable-user")) {
                hits.append(line).append('\n');
                hitCount++;
            }
        }
        sb.append("\n[专用 tag 命中 ").append(hitCount).append(" 行]\n");
        sb.append(hitCount == 0
                ? "（没有命中：说明这段时间 hook 没打日志，或复现操作发生在 5 分钟之前）\n"
                : tailLines(hits.toString(), 400));
        sb.append("\n[完整日志尾部]\n").append(tailChars(full, 1500 * 1024)).append('\n');
        return sb.toString();
    }

    private static boolean usableLogcat(String out) {
        if (out == null || out.trim().isEmpty()) {
            return false;
        }
        String head = (out.length() > 400 ? out.substring(0, 400) : out).toLowerCase(Locale.ROOT);
        return !(head.contains("unexpected argument") || head.contains("unknown option")
                || head.contains("usage: logcat") || head.contains("invalid option"));
    }

    private static String tailLines(String s, int maxLines) {
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - maxLines); i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static String tailChars(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    /**
     * 冻结链路真值：名单应用 enabled/suspended 快照 + hook 配置文件 +
     * LSPosed 心跳 + 模块作用域。用于区分「没触发」和「触发了但失败」。
     */
    private String buildDiagFreezeState() {
        StringBuilder sb = new StringBuilder();
        sb.append("生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("凚版本: ").append(aboutVersionText()).append('\n');

        sb.append("\n【1. 名单应用 enabled/suspended 快照】\n");
        sb.append("说明: enabled=false 是停用（桌面图标消失）；suspended=true 是暂停；\n");
        sb.append("      enabled设置=DISABLED_USER 就是 pm disable-user 的结果。\n");
        Set<String> raw = prefs().getStringSet(KEY_PKGS, Collections.emptySet());
        List<String> keys = new ArrayList<>(raw);
        Collections.sort(keys);
        sb.append("名单条目: ").append(keys.size()).append('\n');
        PackageManager pm = getPackageManager();
        int susp = 0, disabled = 0, normal = 0, missing = 0, otherUser = 0;
        int shown = 0;
        for (String key : keys) {
            String pkg = packagePart(key);
            int uid = userIdPart(key);
            String label = labelFor(key);
            boolean installed = true;
            boolean enabled = true;
            String setting = "?";
            Boolean suspended = null;
            try {
                ApplicationInfo ai = Build.VERSION.SDK_INT >= 24
                        ? pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        : pm.getApplicationInfo(pkg, 0);
                enabled = ai.enabled;
                try {
                    setting = enabledSettingName(pm.getApplicationEnabledSetting(pkg));
                } catch (Throwable ignore) {
                }
                if (uid == 0) {
                    try {
                        suspended = pm.isPackageSuspended(pkg);
                    } catch (Throwable ignore) {
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                installed = false;
            }
            if (!installed) {
                missing++;
            } else if (uid != 0) {
                otherUser++;
            } else if (Boolean.TRUE.equals(suspended)) {
                susp++;
            } else if (!enabled) {
                disabled++;
            } else {
                normal++;
            }
            if (shown < 200) {
                sb.append("  ").append(key).append(" | ").append(label)
                        .append(" | 安装=").append(installed ? "是" : "否")
                        .append(" | enabled=").append(installed ? String.valueOf(enabled) : "-")
                        .append(" | 设置=").append(setting)
                        .append(" | suspended=").append(suspended == null ? "-" : String.valueOf(suspended))
                        .append('\n');
                shown++;
            }
        }
        if (keys.size() > shown) {
            sb.append("  …（还有 ").append(keys.size() - shown).append(" 条未展开）\n");
        }
        sb.append("汇总: 暂停 ").append(susp).append("，停用 ").append(disabled)
                .append("，正常 ").append(normal).append("，未安装 ").append(missing)
                .append("，分身 ").append(otherUser).append('\n');

        sb.append("\n【2. hook 配置文件（system_server 实际读取的真值）】\n");
        String cfgPath = "/data/system/jin_freeze_list.json";
        sb.append("状态: ").append(oneLineDiag(PrivShell.exec(
                "stat -c '大小=%s 字节  修改=%y' " + cfgPath + " 2>&1", 10), 300)).append('\n');
        PrivShell.Result cfg = PrivShell.exec("cat " + cfgPath + " 2>&1", 20);
        String rawCfg = cfg == null || cfg.out == null ? "" : cfg.out.trim();
        if (rawCfg.isEmpty()) {
            sb.append("内容: （空）\n");
        } else if (rawCfg.toLowerCase(Locale.ROOT).contains("no such file")) {
            sb.append("内容: 文件不存在 —— 划卡/锁屏冻结一定不会生效（凚 没能把配置写进 /data/system）。\n");
        } else {
            try {
                JSONObject o = new JSONObject(rawCfg);
                JSONArray pk = o.optJSONArray("packages");
                JSONObject tags = o.optJSONObject("tags");
                JSONArray sel = o.optJSONArray("selected");
                JSONArray lockTags = o.optJSONArray("lock_tags");
                sb.append("enabled(划卡冻结)=").append(o.optBoolean("enabled")).append('\n');
                sb.append("lock_enabled(锁屏冻结)=").append(o.optBoolean("lock_enabled")).append('\n');
                sb.append("lock_delay(锁屏延时秒)=").append(o.optInt("lock_delay")).append('\n');
                sb.append("packages=").append(pk == null ? 0 : pk.length()).append(" 个\n");
                sb.append("tags=").append(tags == null ? 0 : tags.length()).append(" 个应用有标签\n");
                sb.append("selected(划卡标签)=")
                        .append(sel == null ? "未设置（全选）" : sel.length() + " 个").append('\n');
                sb.append("lock_tags(锁屏标签)=")
                        .append(lockTags == null ? "未设置（全选）" : lockTags.length() + " 个").append('\n');
            } catch (Exception e) {
                sb.append("解析失败（").append(e.getClass().getSimpleName())
                        .append("），原文前 2000 字:\n").append(cutDiagText(rawCfg, 2000)).append('\n');
            }
        }
        sb.append("App 侧偏好: lock_freeze_enabled=")
                .append(String.valueOf(prefs().getAll().get("lock_freeze_enabled")))
                .append("，lock_freeze_delay_seconds=")
                .append(String.valueOf(prefs().getAll().get("lock_freeze_delay_seconds")))
                .append("，swipe_freeze_enabled=")
                .append(String.valueOf(prefs().getAll().get("swipe_freeze_enabled"))).append('\n');
        sb.append("判定: 两者不一致时以文件为准——system_server 只认文件；开关没写进文件就不会生效。\n");

        sb.append("\n【3. LSPosed 心跳（证明 hook 进了 system_server）】\n");
        String hb = "/data/system/jin_freeze_hb";
        sb.append("状态: ").append(oneLineDiag(PrivShell.exec(
                "stat -c '大小=%s 字节  修改=%y' " + hb + " 2>&1", 10), 300)).append('\n');
        sb.append("内容: ").append(oneLineDiag(
                PrivShell.exec("cat " + hb + " 2>&1 | head -c 500", 10), 500)).append('\n');
        sb.append("说明: 心跳只证明模块本体进了 system_server；\n"
                + "      锁屏冻结是否注册成功要看 logcat 里的 JinLockFreeze（已注册 / 等待 AMS 就绪超时）。\n");

        sb.append("\n【4. LSPosed 作用域（锁屏/划卡冻结的前置条件）】\n");
        sb.append("说明: 作用域必须勾「系统框架（android）」，否则锁屏/划卡冻结不会触发。\n");
        sb.append("目录:\n").append(indentDiag(PrivShell.exec(
                "ls -ld /data/adb/lspd /data/adb/lspd/config /data/adb/modules/zygisk_lsposed 2>&1", 10)))
                .append('\n');
        String scopeCmd = "for f in /data/adb/lspd/config/modules.list /data/adb/lspd/config/modules_config.db; do "
                + "[ -f \"$f\" ] && { echo \"== $f\"; strings \"$f\" 2>/dev/null "
                + "| grep -n -A 40 -m1 'com.jin.freeze' | head -45; }; done 2>&1";
        PrivShell.Result scope = PrivShell.exec(scopeCmd, 20);
        String scopeOut = scope == null || scope.out == null ? "" : scope.out;
        sb.append("模块条目 + 后续 40 个字符串:\n").append(indentDiag(scope)).append('\n');
        boolean hasAndroid = false;
        for (String line : scopeOut.split("\n")) {
            String t = line.trim();
            int idx = t.lastIndexOf(':');
            if (idx > 0 && "android".equals(t.substring(idx + 1).trim())) {
                hasAndroid = true;
                break;
            }
        }
        if (scopeOut.trim().isEmpty()) {
            sb.append("判定: 无法读取（modules.list 不存在或为二进制），请在 LSPosed 里手动确认作用域。\n");
        } else if (hasAndroid) {
            sb.append("判定: 作用域包含 android（系统框架），锁屏冻结前置条件满足。\n");
        } else {
            sb.append("判定: 未发现 android —— 很可能没勾「系统框架」，锁屏冻结不会触发。\n");
        }
        return sb.toString();
    }

    /** 最近 20 条冻结/解冻/暂停操作流水，新 → 旧。 */
    private String buildDiagActions() {
        StringBuilder sb = new StringBuilder();
        sb.append("最近操作流水（最多 50 条，新 → 旧）\n");
        sb.append("通道: 单次pm=pm disable-user/enable；单次暂停/降级暂停/批量暂停=pm suspend；\n");
        sb.append("      批量脚本=批量冻结/解冻脚本。\n\n");
        JSONArray arr = null;
        try {
            arr = new JSONArray(prefs().getString(KEY_DIAG_HIST, "[]"));
        } catch (Exception ignored) {
        }
        boolean hasAppOps = arr != null && arr.length() > 0;
        if (!hasAppOps) {
            sb.append("（还没有手动操作记录）\n");
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        for (int i = 0; hasAppOps && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            sb.append('#').append(i + 1).append("  ")
                    .append(fmt.format(new Date(o.optLong("t", 0))))
                    .append("  通道: ").append(o.optString("ch", "?"))
                    .append("  退出码: ").append(o.optInt("code", -1));
            if (o.optBoolean("to", false)) {
                sb.append("（超时）");
            }
            sb.append('\n').append("命令:\n").append(o.optString("cmd", "")).append('\n');
            String out = o.optString("out", "");
            if (!out.isEmpty()) {
                sb.append("返回:\n").append(out).append('\n');
            }
            sb.append('\n');
        }
        sb.append("\n【自动冻结流水（hook 侧 /data/system/jin_freeze_ops.log 最近 40 行）】\n");
        sb.append("说明: 锁屏冻结/划卡冻结由 system_server 内的 hook 执行，每次批量只记 1 行；\n");
        sb.append("      格式「时间 | 原因 | 共 N，成功 M，失败: 包名…」，失败明细即被系统拒绝的应用。\n");
        PrivShell.Result ops = PrivShell.exec(
                "tail -40 /data/system/jin_freeze_ops.log 2>&1", 10);
        String opsOut = ops == null || ops.out == null ? "" : ops.out.trim();
        if (opsOut.isEmpty()) {
            sb.append("（没有记录：到目前还没发生过锁屏/划卡自动冻结）\n");
        } else if (opsOut.toLowerCase(Locale.ROOT).contains("no such file")) {
            sb.append("（文件不存在：到目前还没发生过锁屏/划卡自动冻结，或 hook 未写入）\n");
        } else {
            sb.append(opsOut).append('\n');
        }
        return sb.toString();
    }

    private static String oneLineDiag(PrivShell.Result r, int max) {
        if (r == null || r.out == null) {
            return "（无输出）";
        }
        String s = r.out.trim().replace('\n', ' ');
        return s.isEmpty() ? "（空）" : cutDiagText(s, max);
    }

    private static String indentDiag(PrivShell.Result r) {
        if (r == null || r.out == null) {
            return "  （无输出）";
        }
        String s = r.out.trim();
        if (s.isEmpty()) {
            return "  （空）";
        }
        return "  " + cutDiagText(s, 3000).replace("\n", "\n  ");
    }

    private static String enabledSettingName(int v) {
        switch (v) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return "DEFAULT";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return "ENABLED";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return "DISABLED";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                return "DISABLED_USER";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "DISABLED_UNTIL_USED";
            default:
                return String.valueOf(v);
        }
    }

    /** 最近一次冻结/解冻的命令与返回（单次操作与批量脚本都会记录）。 */
    private String buildDiagLastPm() {
        String cmd = prefs().getString(KEY_DIAG_CMD, null);
        if (cmd == null || cmd.trim().isEmpty()) {
            return "还没有记录到冻结/解冻操作。\n"
                    + "在凚里冻结或解冻一个应用后，再来长按 logo 导出，就会包含命令与返回原文。\n";
        }
        long at = prefs().getLong(KEY_DIAG_AT, 0);
        return "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(at)) + "\n\n命令:\n" + cmd + "\n\n返回:\n"
                + prefs().getString(KEY_DIAG_OUT, "");
    }

    /** 包内说明：内容清单 + 隐私边界，发给开发者时可直接看。 */
    private static String buildDiagReadme() {
        return "凚 诊断包\n"
                + "==========\n\n"
                + "内容：\n"
                + "1. device_info.txt   设备与 ROM（Build、屏幕、root/Shizuku、LSPosed、桌面版本、getprop 节选）\n"
                + "2. prefs.txt         凚 版本与设置键值（名单只记数量，其它包名做短哈希）\n"
                + "3. logcat_5min.txt   最近 5 分钟 logcat；含 logcat --help 原文、\n"
                + "                     JinLockFreeze/JinSwipeFreeze/JinPm 等专用 tag 摘要、完整尾部\n"
                + "4. last_pm.txt       最近一次冻结/解冻的命令、返回与时间\n"
                + "5. freeze_state.txt  名单应用 enabled/suspended 快照、hook 配置文件真值与修改时间、\n"
                + "                     LSPosed 心跳、模块作用域是否含 android（系统框架）\n"
                + "6. freeze_actions.txt 最近 50 条手动冻结/解冻/暂停流水（时间、通道、退出码、返回），\n"
                + "                     附 hook 侧自动冻结流水（锁屏/划卡，每次批量 1 行汇总 + 失败明细）\n\n"
                + "排查顺序建议：\n"
                + "· 「没自动冻结」→ 先看 5 的配置文件/心跳/作用域，再看 3 的 JinLockFreeze 命中行；\n"
                + "· 「冻结了但不是暂停」→ 看 5 的 enabled设置/suspended，区分停用与暂停；\n"
                + "· 「操作失败」→ 看 6 的通道与退出码，定位是哪个入口失败。\n\n"
                + "不收集：账号、IMEI、手机号、通讯录权限；\n"
                + "只导出名单内应用的状态，不含名单外的应用清单。\n"
                + "适配只需要系统行为和报错，与个人隐私无关。\n";
    }

    private static void writeDiagFile(File f, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write((text == null ? "" : text).getBytes("UTF-8"));
        out.close();
    }

    private static void zipDiagDir(File dir, File zip) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip));
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            byte[] buf = new byte[8192];
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(f.getName()));
                FileInputStream in = new FileInputStream(f);
                int n;
                while ((n = in.read(buf)) > 0) {
                    zos.write(buf, 0, n);
                }
                in.close();
                zos.closeEntry();
            }
        }
        zos.close();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 赞赏开发者：收款码按屏幕宽度 50% 展示，圆角卡片承载；取消 / 保存都直接返回上一页。 */
    private void showDonateDialog() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        final int imgW = Math.round(screenW * 0.50f);
        int cardPad = Ui.dp(this, 14);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPad, cardPad, cardPad, Ui.dp(this, 4));
        Ui.applyDialogBg(card);

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
        cancel.setText("返回");
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
        // 保存：照常写相册，同时关掉弹窗回到上一页
        save.setOnClickListener(v -> {
            saveDonateImage();
            d.dismiss();
        });
        d.show();
        // 打开期间隐藏设置页；关闭后设置页自动回来
        Ui.autoHideSettings(d);
        applySettingsSurfaceAlpha(d);
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
            toast("图片读取失败");
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
            runOnUiThread(() -> toast(out));
        });
    }

    private int defaultDialogWidth() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        return Math.min(screenW - Ui.dp(this, 104), Ui.dp(this, 304));
    }

    /** 另一形态的 key：半屏加 panel_ 前缀。 */
    private String otherKey(String base) {
        return panelMode ? base : "panel_" + base;
    }

    /** 设置面板卡片的真实左边缘（px），二级弹窗按它对齐。 */
    private int settingsPanelLeft = -1;
    /** 设置面板卡片的真实宽度（px）：二级弹窗直接复用它，保证肉眼宽度完全一致。 */
    private int settingsPanelCardWidth = -1;

    /** 设置页及其二级弹窗的统一宽度：优先用实测的面板宽度。 */
    private int settingsDialogWidth() {
        return settingsPanelCardWidth > 0 ? settingsPanelCardWidth : defaultDialogWidth();
    }

    /** 「界面透明度」组内的条目文案：一律不带「透明度」三个字，只显示百分比。 */
    private String settingsSurfaceText(int p) {
        return "设置页：" + p + "%";
    }

    private String iconAlphaText(int p) {
        return "列表图标：" + p + "%";
    }

    /** 设置页透明度：0 = 不透明，100 = 全透明；半屏/全屏各存一份。 */
    private int settingsSurfacePct() {
        return clampInt(prefs().getInt(lk(KEY_SETTINGS_SURFACE), 0), 0, 100);
    }

    /** 透明度百分比换算成 drawable alpha：0% 不透明、100% 全透明。 */
    private int settingsSurfaceAlpha() {
        return Math.round(255f * (100 - settingsSurfacePct()) / 100f);
    }

    /**
     * 把「设置页透明度」铺到整棵视图树：外框、分组卡片、按钮底板一起变，
     * 避免只透明外层、里面的分组还是实心，看起来边缘和中间不一致。
     */
    private void applySettingsSurfaceAlpha(View v) {
        if (v == null) {
            return;
        }
        Drawable bg = v.getBackground();
        if (bg != null) {
            bg.mutate().setAlpha(settingsSurfaceAlpha());
        }
        fadeElevation(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applySettingsSurfaceAlpha(g.getChildAt(i));
            }
        }
    }

    /**
     * 阴影跟着透明度一起消失：卡片一旦开始变透明就直接撤掉外圈阴影，
     * 否则阴影会残留成一圈「边框」，看起来像只有边缘变了。
     * 原始阴影高度记在 tag 里，拉回 0% 时原样恢复。
     */
    private void fadeElevation(View v) {
        boolean transparent = settingsSurfacePct() > 0;
        Object tag = v.getTag(TAG_BASE_ELEVATION);
        if (tag instanceof Float) {
            v.setElevation(transparent ? 0f : (Float) tag);
            return;
        }
        float base = v.getElevation();
        if (base > 0f) {
            v.setTag(TAG_BASE_ELEVATION, base);
            v.setElevation(transparent ? 0f : base);
        }
    }

    /** 二级弹窗同步「设置页透明度」：从弹窗内容根整棵刷，边框与内层分组一起变。 */
    private void applySettingsSurfaceAlpha(android.app.Dialog d) {
        if (d == null) {
            return;
        }
        android.view.Window w = d.getWindow();
        if (w == null) {
            return;
        }
        View decor = w.getDecorView();
        if (decor == null) {
            return;
        }
        // 系统 AlertDialog 的阴影画在窗口根上，只刷内容树会留下一圈白边；
        // 从 decor 整棵树刷，连内容之外的底板一起变，快面板不会再是一块实心白
        fadeElevation(decor);
        applySettingsSurfaceAlpha(decor);
        // 首帧兜底：部分系统会在 show 后的第一帧重新铺一次底板，这里再刷一遍
        decor.post(() -> {
            fadeElevation(decor);
            applySettingsSurfaceAlpha(decor);
        });
    }

    /** 与设置面板同款的弹窗阴影：外层壳留 12dp + 圆角卡片 elevation。 */
    private int applySettingsDialogShadow(android.app.Dialog d) {
        if (d == null || d == settingsDialog) {
            return Ui.dp(this, 12);
        }
        return Ui.wrapShadow(d);
    }

    /** 二级弹窗统一宽度（与设置面板一致），水平居中显示。 */
    private void applySettingsWidthCentered(android.app.Dialog d) {
        android.view.Window w = d == null ? null : d.getWindow();
        if (w == null) {
            return;
        }
        int pad = applySettingsDialogShadow(d);
        w.setLayout(settingsDialogWidth() + pad * 2, ViewGroup.LayoutParams.WRAP_CONTENT);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.x = 0;
        lp.gravity = Gravity.CENTER;
        w.setAttributes(lp);
        applySettingsSurfaceAlpha(d);
        applyPanelBottomLimit(d);
        Ui.autoHideSettings(d);
        hideUntilPositioned(d);
    }

    /**
     * 二级弹窗先整窗透明，等首帧定位（宽度、水平偏移、半屏上沿）都落定再显形，
     * 避免先按默认居中位置画出来、再跳到正确位置的闪烁。
     */
    private void hideUntilPositioned(final android.app.Dialog d) {
        final android.view.Window w = d == null ? null : d.getWindow();
        if (w == null) {
            return;
        }
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.alpha = 0f;
        w.setAttributes(lp);
        // 等窗口位置真正落到 Surface（几帧）再显形，否则可能先在旧位置显一帧再跳
        w.getDecorView().postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            android.view.WindowManager.LayoutParams cur = w.getAttributes();
            cur.alpha = 1f;
            w.setAttributes(cur);
        }, 80);
    }

    /** 二级弹窗统一宽度，并水平对齐设置面板（面板拖动过的偏移也一并跟随）。 */
    private void applySettingsAlignment(android.app.Dialog d) {
        android.view.Window w = d == null ? null : d.getWindow();
        if (w == null) {
            return;
        }
        int width = settingsDialogWidth();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int centeredLeft = (screenW - width) / 2;
        int pad = applySettingsDialogShadow(d);
        w.setLayout(width + pad * 2, ViewGroup.LayoutParams.WRAP_CONTENT);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        int left = settingsPanelLeft >= 0 ? settingsPanelLeft : centeredLeft;
        lp.x = left - centeredLeft;
        w.setAttributes(lp);
        applySettingsSurfaceAlpha(d);
        applyPanelBottomLimit(d);
        Ui.autoHideSettings(d);
        hideUntilPositioned(d);
    }

    /** 半屏形态：二级弹窗整体抬到半屏面板上沿之上，不压住下面的列表。 */
    private void applyPanelBottomLimit(android.app.Dialog d) {
        if (!panelMode || d == null) {
            return;
        }
        android.view.Window w = d.getWindow();
        int limit = openBottomLimitPx();
        if (w == null || limit <= 0) {
            return;
        }
        // 底边锚定：窗口底边直接停在面板上沿之外。
        // 与「按弹窗高度算 y」不同，这个位置和高度无关，首帧就在外面，不需要事后再挪。
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int statusInset = statusBarHidden ? 0 : statusBarHeightPx();
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.max(0, screenH + statusInset - (limit - Ui.dp(this, 8)));
        w.setAttributes(lp);
    }

    /**
     * 关掉弹窗自带的进出场动画。动画期间系统把窗口挂在 leash 上，
     * 改位置/透明度要等动画结束才真正生效，表现就是「先出现在半屏里，再跳到半屏外」。
     */
    private void disableDialogWindowAnimation(android.app.Dialog dialog) {
        android.view.Window w = dialog == null ? null : dialog.getWindow();
        if (w != null) {
            w.setWindowAnimations(0);
        }
    }

    /** 快面板透明度：0 = 不透明，100 = 全透明；与「设置页透明度」完全独立。 */
    private int quickPanelSurfacePct() {
        return clampInt(prefs().getInt(KEY_QUICK_PANEL_SURFACE, 0), 0, 100);
    }

    /** 快面板透明度百分比换算成 drawable alpha。 */
    private int quickPanelSurfaceAlphaValue() {
        return Math.round(255f * (100 - quickPanelSurfacePct()) / 100f);
    }

    /** 把「快面板透明度」铺到整棵视图树：底板、卡片、按钮一起变。 */
    private void applyQuickPanelSurfaceAlpha(View v) {
        if (v == null) {
            return;
        }
        Drawable bg = v.getBackground();
        if (bg != null) {
            bg.mutate().setAlpha(quickPanelSurfaceAlphaValue());
        }
        boolean transparent = quickPanelSurfacePct() > 0;
        Object tag = v.getTag(TAG_BASE_ELEVATION);
        if (tag instanceof Float) {
            v.setElevation(transparent ? 0f : (Float) tag);
        } else {
            float base = v.getElevation();
            if (base > 0f) {
                v.setTag(TAG_BASE_ELEVATION, base);
                v.setElevation(transparent ? 0f : base);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyQuickPanelSurfaceAlpha(g.getChildAt(i));
            }
        }
    }

    /** 快面板弹窗专用：整窗刷「快面板透明度」，首帧后再兜底一次。 */
    private void applyQuickPanelSurfaceAlpha(android.app.Dialog d) {
        if (d == null) {
            return;
        }
        android.view.Window w = d.getWindow();
        if (w == null) {
            return;
        }
        View decor = w.getDecorView();
        if (decor == null) {
            return;
        }
        applyQuickPanelSurfaceAlpha(decor);
        decor.post(() -> applyQuickPanelSurfaceAlpha(decor));
    }

    /** 快面板窗口位置与设置面板完全同一套规则（半屏贴上沿、全屏用记忆偏移），透明度走快面板档位。 */
    private void applyQuickPanelGeometry(android.app.Dialog dialog, View root) {
        android.view.Window w = dialog == null ? null : dialog.getWindow();
        if (w == null) {
            return;
        }
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        // 宽度与设置面板完全一致
        int width = defaultDialogWidth();
        int shadowPad = Ui.dp(this, 12);
        int insetDp = Math.round((screenW - width) / density);
        int defaultX = (screenW - width) / 2 - shadowPad;
        int defaultY = collapsedDefaultY(shadowPad);
        int height = openDialogHeightPx();

        int lpX;
        int lpY;
        if (panelMode) {
            lpX = defaultX;
            lpY = panelDialogY(shadowPad);
        } else {
            lpX = clampInt(defaultX + prefs().getInt(lk(KEY_LAYOUT_DX), 0),
                    -shadowPad, Math.max(-shadowPad, screenW - width - shadowPad));
            lpY = clampInt(defaultY + prefs().getInt(lk(KEY_LAYOUT_DY), 0),
                    defaultY, Math.max(defaultY, screenH - height - shadowPad));
        }

        Ui.roundDialog(dialog, root, insetDp, height);
        applyQuickPanelSurfaceAlpha(root);
        root.post(() -> applyQuickPanelSurfaceAlpha(root));
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = width + shadowPad * 2;
        lp.height = height + shadowPad * 2;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = lpX;
        lp.y = lpY;
        w.setAttributes(lp);
    }

    /** 展开 / 收起快面板：只改窗口高度与纵向位置，与设置页的展开底栏同一套算法。 */
    private void applyQuickPanelFrame(android.app.Dialog dialog, int heightPx, int y, int shadowPad) {
        android.view.Window w = dialog == null ? null : dialog.getWindow();
        if (w == null) {
            return;
        }
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.height = heightPx + shadowPad * 2;
        lp.y = y;
        w.setAttributes(lp);
    }

    /** 把记忆坐标、自适应高度一次性写进窗口参数；show 前调用即首帧到位，show 后再调一次做兜底。 */
    private void applyDialogGeometry(android.app.Dialog dialog, android.view.Window w,
                                     final View root, final View title,
                                     int width, int shadowPad) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        int insetDp = Math.round((screenW - width) / density);
        final int defaultX = (screenW - width) / 2 - shadowPad;
        // 全屏：未展开的默认位置也不能越过横幅下沿
        final int defaultY = collapsedDefaultY(shadowPad);
        // 未展开时高度自适应可用空间，且绝不覆盖半屏列表
        int height = openDialogHeightPx();

        int lpX;
        int lpY;
        if (panelMode) {
            // 半屏：不跟手拖动、不记偏移，水平居中；顶边按「最大高度」定位，调短时从底部往上缩
            lpX = defaultX;
            lpY = panelDialogY(shadowPad);
        } else {
            lpX = clampInt(defaultX + prefs().getInt(lk(KEY_LAYOUT_DX), 0),
                    -shadowPad, Math.max(-shadowPad, screenW - width - shadowPad));
            lpY = clampInt(defaultY + prefs().getInt(lk(KEY_LAYOUT_DY), 0),
                    defaultY, Math.max(defaultY, screenH - height - shadowPad));
        }

        Ui.roundDialog(dialog, root, insetDp, height);
        applySettingsSurfaceAlpha(root);
        // 首帧兜底：roundDialog 会重设底板，下一帧再刷一次透明度，避免闪回不透明
        root.post(() -> applySettingsSurfaceAlpha(root));
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = width + shadowPad * 2;
        lp.height = height + shadowPad * 2;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = lpX;
        lp.y = lpY;
        w.setAttributes(lp);
        layoutOpenY = lpY;

        // 半屏不允许拖动，位置由「贴半屏上沿 + 居中」决定
        if (!panelMode) {
            attachDialogDrag(w, title, defaultX, defaultY, width, height, shadowPad);
        }
        layoutDialog = dialog;
        layoutShadowPad = shadowPad;
    }

    /** 半屏下弹窗的窗口 y：按最大高度定位，底部正好停在半屏面板上沿之外，调短时底边往上缩。 */
    private int panelDialogY(int shadowPad) {
        int panelTop = openBottomLimitPx();
        if (panelTop <= 0) {
            return Ui.dp(this, 8) - shadowPad;
        }
        int maxH = Ui.dp(this, openSliderMaxDp());
        int y = panelTop - Ui.dp(this, 8) - maxH - statusBarHeightPx() - shadowPad;
        return Math.max(-shadowPad, y);
    }

    /** 滑块改高度后重新定位（半屏用）：顶边不动，只改高度。 */
    private void relayoutPanelDialog(android.app.Dialog dialog) {
        if (!panelMode || dialog == null) {
            return;
        }
        android.view.Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        int shadowPad = layoutShadowPad;
        int height = openDialogHeightPx();
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.height = height + shadowPad * 2;
        lp.y = panelDialogY(shadowPad);
        w.setAttributes(lp);
    }

    /** 冻结按钮位置/大小变化后：展开态的高度随按钮上沿实时收放，绝不盖住按钮。 */
    private void refreshExpandRange() {
        if (!layoutExpanded || layoutDialog == null) {
            return;
        }
        applyDialogHeight(layoutDialog, expandDialogHeightPx(), layoutShadowPad);
        layoutDialog.getWindow().getDecorView().post(() ->
                enforceDialogBottomLimit(layoutDialog, bottomBarTopPx(), layoutShadowPad));
    }

    /** 未展开高度（px）：半屏用本次调短的值（默认最大值）；全屏按记忆值，未存过 520dp。 */
    private int openDialogHeightPx() {
        int maxDp = openSliderMaxDp();
        if (panelMode) {
            int dp = panelOpenHeightDp >= 0 ? panelOpenHeightDp : maxDp;
            dp = clampInt(dp, 0, maxDp);
            return clampInt(Ui.dp(this, dp), Ui.dp(this, 170), Ui.dp(this, maxDp));
        }
        // -1 表示从未调过；0 是合法值（高度按下限 170dp 落地）
        int saved = prefs().getInt(lk(KEY_LAYOUT_OPEN_H), -1);
        int dp = clampInt(saved >= 0 ? saved : LAYOUT_OPEN_MAX_DP, 0, maxDp);
        return clampInt(Ui.dp(this, dp), Ui.dp(this, 170), Ui.dp(this, maxDp));
    }

    /** 未展开范围滑块当前值（dp）：半屏本次调短过的值，否则最大值；全屏按记忆值。 */
    private int openSliderDp() {
        if (panelMode) {
            int maxDp = openSliderMaxDp();
            return panelOpenHeightDp >= 0 ? clampInt(panelOpenHeightDp, 0, maxDp) : maxDp;
        }
        int saved = prefs().getInt(lk(KEY_LAYOUT_OPEN_H), -1);
        return clampInt(saved >= 0 ? saved : openSliderMaxDp(), 0, openSliderMaxDp());
    }

    /** 未展开范围滑块最大值（dp）：半屏以「不侵入半屏」的可用高度为上限，全屏 520dp。 */
    private int openSliderMaxDp() {
        int maxPx = openHeightMaxPx();
        if (panelMode) {
            int limit = openBottomLimitPx();
            if (limit > 0) {
                maxPx = Math.min(maxPx,
                        limit - statusBarHeightPx() - Ui.dp(this, 24) - Ui.dp(this, 8));
            }
        }
        int dp = Math.round(maxPx / getResources().getDisplayMetrics().density);
        return clampInt(dp, 170, LAYOUT_OPEN_MAX_DP);
    }

    /** 点展开时的高度（px）：按当前屏幕自适应撑到最大，只以底栏上沿为上限。 */
    private int expandDialogHeightPx() {
        return Math.max(Ui.dp(this, 170), expandHeightMaxPx());
    }

    /** 未展开高度上限（px）：只按屏幕可用高度算；半屏/底栏限制由显示后的真实位置夹紧。 */
    private int openHeightMaxPx() {
        int pad = Ui.dp(this, 12);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int max = screenH - statusBarHeightPx() - pad * 2 - Ui.dp(this, 8);
        max = Math.min(max, Ui.dp(this, LAYOUT_OPEN_MAX_DP));
        return Math.max(0, max);
    }

    /** 展开高度上限：按屏幕尺寸取，再以底栏上沿夹紧（不再看冻结按钮位置）。 */
    private int expandHeightMaxPx() {
        int pad = Ui.dp(this, 12);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int max = screenH - statusBarHeightPx() - pad * 2 - Ui.dp(this, 8);
        int barTop = bottomBarTopPx();
        if (barTop > 0) {
            max = Math.min(max, barTop - statusBarHeightPx() - pad * 2 - Ui.dp(this, 12));
        }
        // 全屏：展开态顶边不能越过顶栏（横幅）下沿，可用高度只剩它到底栏之间
        int headerBottom = panelMode ? -1 : headerBottomPx();
        if (headerBottom > 0 && barTop > 0) {
            int available = (barTop - Ui.dp(this, 8))
                    - (headerBottom + Ui.dp(this, 8));
            max = Math.min(max, available);
        }
        return Math.max(0, max);
    }

    /** 全屏顶栏（横幅）的下沿 y（屏幕坐标）；未加载时返回 -1。 */
    private int headerBottomPx() {
        View header = findViewById(R.id.page_header);
        if (header == null || header.getHeight() <= 0) {
            return -1;
        }
        int[] loc = new int[2];
        header.getLocationOnScreen(loc);
        return loc[1] + header.getHeight();
    }

    /** 全屏未展开态窗口 y 的默认值：内容顶部停在顶栏（横幅）下沿之外，留 8dp 间距。 */
    private int collapsedDefaultY(int shadowPad) {
        int base = Ui.dp(this, 8) - shadowPad;
        if (panelMode) {
            return base;
        }
        int headerBottom = headerBottomPx();
        if (headerBottom <= 0) {
            return base;
        }
        return Math.max(base,
                headerBottom + Ui.dp(this, 8) - shadowPad - statusBarHeightPx());
    }

    /** 全屏展开态窗口 y 的下限：内容顶部停在顶栏（横幅）下沿之外。 */
    private int expandedTopLimitY(int shadowPad) {
        int headerBottom = headerBottomPx();
        if (panelMode || headerBottom <= 0) {
            return -shadowPad;
        }
        return Math.max(-shadowPad,
                headerBottom + Ui.dp(this, 8) - shadowPad - statusBarHeightPx());
    }

    /** 未展开时内容底边不得越过的 y：半屏取半屏面板上沿，其它形态不额外限制（返回 -1）。 */
    private int openBottomLimitPx() {
        if (!panelMode) {
            return -1;
        }
        View panel = findViewById(R.id.panel);
        if (panel == null || panel.getHeight() == 0) {
            return -1;
        }
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        return loc[1];
    }

    /** 按窗口真实位置把弹窗底边夹到 limitY 之上：优先整体上移，实在放不下才收高度。 */
    private void enforceDialogBottomLimit(android.app.Dialog dialog, int limitY, int shadowPad) {
        if (dialog == null || limitY <= 0) {
            return;
        }
        android.view.Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        int[] loc = new int[2];
        w.getDecorView().getLocationOnScreen(loc);
        int contentTop = loc[1] + shadowPad;
        int contentH = Math.max(0, lp.height - shadowPad * 2);
        int maxBottom = limitY - Ui.dp(this, 8) - shadowPad;
        if (contentTop + contentH <= maxBottom) {
            return;
        }
        // 先整体往上挪，保持高度；挪到顶仍放不下再收高度
        int shift = contentTop + contentH - maxBottom;
        int newY = lp.y - shift;
        if (newY >= -shadowPad) {
            lp.y = newY;
        } else {
            int newContentTop = contentTop - (lp.y + shadowPad);
            lp.y = -shadowPad;
            int newH = Math.max(Ui.dp(this, 170), maxBottom - newContentTop);
            lp.height = newH + shadowPad * 2;
        }
        w.setAttributes(lp);
    }

    /** 底部底栏的上沿 y（屏幕坐标）；未加载时返回 -1。
     * 半屏取 54dp 状态条，全屏取列表底部预留的同高区域。
     */
    private int bottomBarTopPx() {
        int[] loc = new int[2];
        if (panelMode) {
            View bar = findViewById(R.id.panel_bottom_bar);
            if (bar != null && bar.getHeight() > 0) {
                bar.getLocationOnScreen(loc);
                return loc[1];
            }
            return -1;
        }
        if (grid != null && grid.getHeight() > 0) {
            grid.getLocationOnScreen(loc);
            // 底栏是列表底部预留的 54dp 区域（与半屏底栏同高、与布局里的分割线一致），
            // 不能用列表自身的 4dp 内边距，否则只剩最底下一条几像素能触发长按
            return loc[1] + grid.getHeight() - Ui.dp(this, 54);
        }
        return -1;
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

    /** 未展开态窗口应回到的 y：默认位置（不越横幅下沿） + 记忆的拖动偏移。 */
    private int openDialogTopY(int shadowPad) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int h = openDialogHeightPx();
        int defaultY = collapsedDefaultY(shadowPad);
        return clampInt(defaultY + prefs().getInt(lk(KEY_LAYOUT_DY), 0),
                defaultY, Math.max(defaultY, screenH - h - shadowPad));
    }

    /**
     * 展开/收起时让窗口高度与 y 一起平滑过渡：
     * 未展开位置在顶部时表现为底边往上收，在底部时表现为顶边往下收。
     */
    private void animateDialogFrame(final android.app.Dialog dialog, int heightPx,
                                    final int targetY, int shadowPad) {
        android.view.Window w = dialog == null ? null : dialog.getWindow();
        if (w == null) {
            return;
        }
        final int endH = heightPx + shadowPad * 2;
        final android.view.WindowManager.LayoutParams lp = w.getAttributes();
        final int startH = lp.height;
        final int startY = lp.y;
        if (startH == endH && startY == targetY) {
            return;
        }
        if (dialogFrameAnim != null) {
            dialogFrameAnim.cancel();
        }
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(200);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            android.view.WindowManager.LayoutParams cur = w.getAttributes();
            cur.height = Math.round(startH + (endH - startH) * f);
            cur.y = Math.round(startY + (targetY - startY) * f);
            w.setAttributes(cur);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (dialogFrameAnim == a) {
                    dialogFrameAnim = null;
                }
            }
        });
        dialogFrameAnim = va;
        va.start();
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
                        // 展开态拖动不能越过顶栏（横幅）下沿；未展开态同理，保持默认位置这条下限
                        if (layoutExpanded) {
                            lp.y = Math.max(lp.y, expandedTopLimitY(pad));
                        } else {
                            lp.y = Math.max(lp.y, collapsedDefaultY(pad));
                        }
                        w.setAttributes(lp);
                        // 拖动时同样限制：未展开不能压到半屏列表，展开不能压到底栏
                        enforceDialogBottomLimit(layoutDialog,
                                layoutExpanded ? bottomBarTopPx() : openBottomLimitPx(), pad);
                        lp = w.getAttributes();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        h.removeCallbacks(activate);
                        if (dragging) {
                            handle.setAlpha(1f);
                            // 以夹紧后的真实位置落盘，下次打开不会再越界；
                            // 展开态拖动不覆盖未展开位置，收起时仍回到打开时的位置
                            lp = w.getAttributes();
                            if (!layoutExpanded) {
                                layoutOpenY = lp.y;
                                prefs().edit()
                                        .putInt(lk(KEY_LAYOUT_DX), lp.x - baseX)
                                        .putInt(lk(KEY_LAYOUT_DY), lp.y - baseY)
                                        .apply();
                            }
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
    /** 只恢复桌面快捷方式：备份里有、当前桌面没有的，逐个重新请求固定。 */
    private static final int RESTORE_SHORTCUTS = 4;
    /** 只恢复列表白名单：覆盖当前白名单。 */
    private static final int RESTORE_WHITELIST = 8;
    /** 只恢复关联应用（主副应用列表）：覆盖当前关联。 */
    private static final int RESTORE_PAIRS = 16;
    /** 全部恢复：配置 + 冻结列表 + 桌面快捷方式 + 白名单 + 关联应用。 */
    private static final int RESTORE_ALL = RESTORE_SETTINGS | RESTORE_FREEZE | RESTORE_SHORTCUTS
            | RESTORE_WHITELIST | RESTORE_PAIRS;

    /** 名单类键归 freeze 段，其余键归 settings 段，不重复备份。 */
    private static final String[] BACKUP_LIST_KEYS = {
            KEY_PKGS, KEY_ORDER, KEY_CUSTOM, KEY_APP_TABS, KEY_TABS,
            KEY_CURRENT_TAB, KEY_DEFAULT_TAB, KEY_HIDE_TAB_ROW,
            "panel_hide_tab_row",
            // 白名单与主副关联单独成段备份，不再混进「配置」
            KEY_WHITELIST, KEY_SUB_PAIRS
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
            toast("备份内容生成失败");
            return;
        }
        final int listCount = items.size();
        final int settingCount = backupSettingCount();
        final int shortcutCount = ShortcutPin.pinnedPackages(this).size();
        final int whiteCount = whitelistSet().size();
        final int pairCount = allPairs().size();
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout optFile = optionRowCompact("存成文件",
                "写入 " + backupDir().getAbsolutePath() + "（" + BACKUP_FILE_PREFIX
                        + "时间.json），恢复时从文件列表选择");
        optFile.setOnClickListener(v -> {
            dismiss(ref);
            saveBackupFile(json, listCount, settingCount, shortcutCount, whiteCount, pairCount);
        });
        content.addView(optFile);
        LinearLayout optText = optionRowCompact("生成文本",
                "生成一串压缩后的短文本（自动复制），可存到备忘录 / 聊天里；恢复时粘回来即可");
        optText.setOnClickListener(v -> {
            dismiss(ref);
            showBackupText(json, listCount, settingCount, shortcutCount, whiteCount, pairCount);
        });
        content.addView(optText, topMargin(6));
        ref[0] = compactCard("备份",
                "冻结列表 " + listCount + " 个、设置 " + settingCount + " 项、桌面快捷方式 "
                        + shortcutCount + " 个、白名单 " + whiteCount + " 个、关联应用 "
                        + pairCount + " 组，会写进同一份备份",
                content, new String[]{"返回"}, new Runnable[]{null});
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
        ref[0] = compactCard("恢复",
                "先选备份来源，再决定恢复配置、冻结列表、白名单、关联应用还是桌面快捷方式",
                content, new String[]{"返回"}, new Runnable[]{null});
    }

    /** 文本备份：压缩 + Base64 成尽量短的一串，进剪贴板并展示出来。 */
    private void showBackupText(String json, int listCount, int settingCount, int shortcutCount,
                                int whiteCount, int pairCount) {
        final String text = encodeBackupText(json);
        if (text == null) {
            toast("备份文本生成失败");
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
                "冻结列表 " + listCount + " 个 + 设置 " + settingCount + " 项 + 快捷方式 "
                        + shortcutCount + " 个 + 白名单 " + whiteCount + " 个 + 关联应用 "
                        + pairCount + " 组\n"
                        + text.length() + " 字符（原始 " + raw + " 字符，约 " + pct + "%）",
                sv, new String[]{"关闭", "再复制一次"}, new Runnable[]{null, () -> {
                    cm.setPrimaryClip(ClipData.newPlainText("凚备份", text));
                    toast("备份文本已复制");
                }});
    }

    /** 文件备份：内容与文本备份完全一致（JIN: 开头的压缩串），文件名沿用同一标识。 */
    private void saveBackupFile(final String json, final int listCount, final int settingCount,
                                final int shortcutCount, final int whiteCount, final int pairCount) {
        final String text = encodeBackupText(json);
        if (text == null) {
            toast("备份内容生成失败");
            return;
        }
        JinApp.IO.execute(() -> {
            File out = null;
            String err = null;
            String path = null;
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
                path = out.getAbsolutePath();
                fixBackupPerms(dir, out);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (path == null) {
                // 分区存储写不进 Download 时，用 root/Shizuku 写同一路径
                File dir = new File("/storage/emulated/0/Download/凚/备份");
                String name = BACKUP_FILE_PREFIX
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
                File target = new File(dir, name);
                PrivShell.Result r = PrivShell.exec("mkdir -p '" + dir.getAbsolutePath()
                        + "' && printf '%s' '" + text + "' > '" + target.getAbsolutePath() + "'", 20);
                if (r != null && r.code == 0) {
                    out = target;
                    path = target.getAbsolutePath();
                    err = null;
                    fixBackupPerms(dir, target);
                }
            }
            final File f = out;
            final String msg = err;
            final String savedPath = path;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (savedPath == null) {
                    compactCard("备份失败", "写入备份文件失败：" + msg, null,
                            new String[]{"知道了"}, new Runnable[]{null});
                    return;
                }
                compactCard("备份完成",
                        "冻结列表 " + listCount + " 个 + 设置 " + settingCount + " 项 + 快捷方式 "
                                + shortcutCount + " 个 + 白名单 " + whiteCount + " 个 + 关联应用 "
                                + pairCount + " 组\n" + savedPath,
                        null, new String[]{"关闭", "复制路径"}, new Runnable[]{null, () -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("备份文件路径", savedPath));
                            toast("路径已复制");
                        }});
            });
        });
    }

    /**
     * 备份文件落在公共 Download 目录，属主会写成当时的应用 uid（旧安装的 uid）。
     * 清数据 / 重装后 uid 变了，新安装就读不到这些文件（FUSE 按属主/组校验）。
     * 统一改成 media_rw(1023) 属主与组，任何安装、任何文件管理器都能读。
     */
    private void fixBackupPerms(File dir, File file) {
        try {
            PrivShell.exec("chmod 0775 '" + dir.getAbsolutePath() + "' 2>/dev/null; "
                    + "chmod 0664 '" + file.getAbsolutePath() + "' 2>/dev/null; "
                    + "chown 1023:1023 '" + dir.getAbsolutePath() + "' '" + file.getAbsolutePath()
                    + "' 2>/dev/null", 15);
        } catch (Exception ignored) {
        }
    }

    /** 文件来源：后台确认能否读取备份目录，再按修改时间从新到旧列出备份文件。 */
    private void showRestoreFiles() {
        JinApp.IO.execute(() -> {
            // Android 11+ 分区存储：非本应用创建的 Download 文件要「所有文件访问」才读得到；
            // root/Shizuku 可用时直接读，不需要该权限（Shizuku 能列能读，只是不能 chown）。
            boolean noAccess = android.os.Build.VERSION.SDK_INT >= 30
                    && !android.os.Environment.isExternalStorageManager()
                    && PrivShell.mode() == PrivShell.Mode.NONE;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (noAccess) {
                    showAllFilesAccessCard();
                    return;
                }
                showRestoreFilesNow();
            });
        });
    }

    /** 缺少「所有文件访问」时的替代入口：去授权，或手动导入备份文件。 */
    private void showAllFilesAccessCard() {
        compactCard("读取备份需要授权",
                "Android 11 起，应用默认读不到 Download 里旧版本创建的文件，卸载重装后更明显。"
                        + "开启「所有文件访问」即可直接读取 /storage/emulated/0/Download/凚/备份；"
                        + "也可以点「导入文件」手动选备份。",
                null, new String[]{"返回", "去授权", "导入文件"}, new Runnable[]{null, () -> {
                    restoreAwaitAccess = true;
                    try {
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Exception ignored) {
                        }
                    }
                }, () -> backupImportPicker.launch(new String[]{"*/*"})});
    }

    private void showRestoreFilesNow() {
        List<File> files = collectBackupFiles();
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (files.isEmpty()) {
            compactCard("还没有备份文件",
                    "可以先做一次备份、用「粘贴备份文本」恢复备份串，或「导入文件」选手机里任意位置的备份",
                    null, "刷新", this::refreshRestoreFiles,
                    new String[]{"返回", "导入文件"}, new Runnable[]{null,
                            () -> backupImportPicker.launch(new String[]{"*/*"})});
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
                sv, "刷新", this::refreshRestoreFiles,
                new String[]{"返回", "导入文件"}, new Runnable[]{null,
                        () -> {
                            dismiss(ref);
                            backupImportPicker.launch(new String[]{"*/*"});
                        }});
    }

    /**
     * 恢复列表左下角「刷新」：默认只重扫。
     * 只有「目录里确实有备份文件、应用却列不出来/读不到」时才进入旧备份修复；
     * 目录本来就空时只重扫，不弹任何授权卡。
     */
    private void refreshRestoreFiles() {
        JinApp.IO.execute(() -> {
            List<File> files = collectBackupFiles();
            File dir = backupDir();
            String[] names = dir.list();
            boolean hasBackup = names == null; // 看不到目录：无法确认是空还是旧备份读不到
            if (names != null) {
                for (String n : names) {
                    String low = n.toLowerCase(Locale.US);
                    if (n.startsWith(BACKUP_FILE_PREFIX)
                            && (low.endsWith(".json") || low.endsWith(".txt"))) {
                        hasBackup = true;
                        break;
                    }
                }
            }
            if (!files.isEmpty() || !hasBackup) {
                final boolean ready = !files.isEmpty();
                main.post(() -> {
                    toast(ready ? "无需修复" : "已刷新");
                    showRestoreFilesNow();
                });
                return;
            }
            // 确实有旧备份但读不到：实时探测当前可用通道（不读历史授权标记）
            PrivShell.Mode mode = PrivShell.mode(true);
            main.post(() -> repairOldBackups(mode));
        });
    }

    /** 旧备份读不到时的修复：root 静默修属主；Shizuku 只读列出；都没有再给授权入口。 */
    private void repairOldBackups(PrivShell.Mode mode) {
        if (mode == PrivShell.Mode.ROOT) {
            // 有 root：不弹任何提示，静默修属主 + 重扫
            JinApp.IO.execute(() -> {
                String dir = backupDir().getAbsolutePath();
                PrivShell.exec("exec >/dev/null 2>&1; "
                        + "chown -R 1023:1023 '" + dir + "'; "
                        + "chmod 0775 '" + dir + "'; "
                        + "find '" + dir + "' -name '*.json' -exec chmod 0664 {} \\;; "
                        + "exit 0", 30);
                List<File> files = collectBackupFiles();
                main.post(() -> {
                    if (!files.isEmpty()) {
                        toast("已修复 " + files.size() + " 个旧备份");
                        showRestoreFilesNow();
                    } else {
                        toast("修复未生效，可改用「导入文件」");
                        showAllFilesAccessCard();
                    }
                });
            });
            return;
        }
        if (mode == PrivShell.Mode.SHIZUKU) {
            // Shizuku 对 Download 可读可列，但 chown 会 Operation not permitted：跳过修属主
            toast("已刷新");
            showRestoreFilesNow();
            return;
        }
        // Shizuku 装着且在运行：先补一次授权请求，不弹 root 卡
        if (ShizukuGate.available(this)) {
            ShizukuGate.requestAnyway(() -> JinApp.IO.execute(() -> {
                PrivShell.Mode now = PrivShell.mode();
                main.post(() -> {
                    if (now == PrivShell.Mode.SHIZUKU) {
                        toast("已刷新");
                        showRestoreFilesNow();
                    } else {
                        showAllFilesAccessCard();
                    }
                });
            }));
            return;
        }
        // 装了 root 但没授权：弹凚默认的未授权提示
        if (rootInstalled()) {
            showPrivilegeMissingDialog();
            return;
        }
        // 只激活了 LSPosed：说明它代替不了 root / Shizuku，仍给凚默认提示
        detectLsposed(() -> {
            if (Boolean.TRUE.equals(lsposedAlive)) {
                showPrivilegeMissingDialog("修复旧备份需要 root 或 Shizuku，LSPosed 授权不能代替。");
            } else {
                showAllFilesAccessCard();
            }
        });
    }

    /** 设备上是否装了 root（只查常见 su 路径，不触发授权弹窗）。 */
    private boolean rootInstalled() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/debug_ramdisk/su", "/system/sbin/su"};
        for (String p : paths) {
            try {
                if (new File(p).exists()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 实时探测 LSPosed 是否在线：发 ping，800ms 没回 pong 就当没激活。 */
    private void detectLsposed(Runnable onDone) {
        lsposedAlive = null;
        sendBroadcast(new Intent(SwipeFreeze.ACTION_STATUS_PING).setPackage("android"));
        main.postDelayed(onDone, 800);
    }

    /** 备份文件来源：当前备份目录 + 旧版本/用户手工放置的路径，按绝对路径去重。 */
    private List<File> collectBackupFiles() {
        List<File> files = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        File external = android.os.Environment.getExternalStorageDirectory();
        File[] dirs = {
                backupDir(),
                new File("/storage/emulated/0/Download/凚/备份"),
                new File("/storage/emulated/0/Download/凚"),
                new File("/storage/emulated/0/凚/备份"),
                new File(external, "Download/凚/备份"),
                new File(external, "凚/备份")
        };
        for (File dir : dirs) {
            File[] arr = dir.listFiles((d, n) -> {
                // 只认凚自己生成的备份（JIN-yyyyMMdd-HHmmss.json），别把同目录其他 json/txt 列出来
                String low = n.toLowerCase(Locale.US);
                return n.startsWith(BACKUP_FILE_PREFIX)
                        && (low.endsWith(".json") || low.endsWith(".txt"));
            });
            if (arr != null) {
                for (File f : arr) {
                    if (f.isFile() && seen.add(f.getAbsolutePath())) {
                        files.add(f);
                    }
                }
            }
            if (arr == null || arr.length == 0) {
                // 应用列不出内容：无权时可能是 null，也可能被分区存储过滤成空目录
                // （目录看得见、里面的文件全不显示），两种都要走 root/Shizuku 再列一次
                addRootListedFiles(files, seen, dir);
            }
        }
        return files;
    }

    /** 用 root/Shizuku 列目录里的备份文件，绕开分区存储限制。 */
    private void addRootListedFiles(List<File> files, java.util.Set<String> seen, File dir) {
        PrivShell.Result r = PrivShell.exec(
                "ls -1p '" + dir.getAbsolutePath() + "' 2>/dev/null", 10);
        if (r == null || r.out == null) {
            return;
        }
        for (String line : r.out.split("\\r?\\n")) {
            String n = line.trim();
            if (n.isEmpty() || n.endsWith("/")) {
                continue;
            }
            String low = n.toLowerCase(Locale.US);
            if (!n.startsWith(BACKUP_FILE_PREFIX)
                    || !(low.endsWith(".json") || low.endsWith(".txt"))) {
                continue;
            }
            File f = new File(dir, n);
            if (seen.add(f.getAbsolutePath())) {
                files.add(f);
            }
        }
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
            if (text == null) {
                // 旧备份的属主可能是重装前的 uid，普通读一定失败：先按 media_rw 修一次权限再读
                fixBackupPerms(f.getParentFile(), f);
                try {
                    FileInputStream in2 = new FileInputStream(f);
                    ByteArrayOutputStream bo2 = new ByteArrayOutputStream();
                    byte[] buf2 = new byte[4096];
                    int n2;
                    while ((n2 = in2.read(buf2)) > 0) {
                        bo2.write(buf2, 0, n2);
                    }
                    in2.close();
                    text = new String(bo2.toByteArray(), "UTF-8");
                    err = null;
                } catch (Exception ignored) {
                }
            }
            if (text == null) {
                // 仍未读到：改用 root/Shizuku 直接读（分区存储下应用读不到 Download 时）
                PrivShell.Result r = PrivShell.exec(
                        "cat '" + f.getAbsolutePath() + "' 2>/dev/null", 20);
                if (r != null && r.code == 0 && r.out != null && !r.out.isEmpty()) {
                    text = r.out;
                    err = null;
                }
            }
            finishReadBackup(text, err, f.getName());
        });
    }

    /** 导入的文件：读 URI 内容后走与目录备份相同的解析。 */
    private void importBackupUri(final Uri uri) {
        JinApp.IO.execute(() -> {
            String text = null;
            String err = null;
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
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
            finishReadBackup(text, err, displayNameOf(uri));
        });
    }

    private String displayNameOf(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String n = c.getString(0);
                        if (n != null && !n.isEmpty()) {
                            return n;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        String seg = uri.getLastPathSegment();
        return seg == null ? "导入的备份" : seg;
    }

    private void finishReadBackup(final String text, final String err, final String name) {
        main.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            final String raw = text;
            final String msg = err;
            if (raw == null) {
                compactCard("读取失败", "这个备份文件读不出来：" + msg, null,
                        new String[]{"知道了"}, new Runnable[]{null});
                return;
            }
            if (parseAnyBackup(decodeIfText(raw)) == null) {
                compactCard("无法恢复",
                        "这个文件不是凚的备份（新格式 " + BACKUP_TEXT_PREFIX
                                + " 串、旧版名单或旧 JSON 都能识别），未改动任何配置",
                        null, new String[]{"知道了"}, new Runnable[]{null});
                return;
            }
            startRestore(raw, name);
        });
    }

    /** 文本来源：只认新备份串，不符合格式直接提示，不做旧版兼容。 */
    private void showRestorePaste() {
        EditText input = new EditText(this);
        input.setHint("粘贴 " + BACKUP_TEXT_PREFIX + " 开头的备份串；旧版备份文本（[用户应用]…）也能直接粘");
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
                new String[]{"返回", "解析"}, new Runnable[]{null, () -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) {
                        toast("请先粘贴备份内容");
                        return;
                    }
                    if (parseAnyBackup(decodeIfText(raw)) == null) {
                        compactCard("无法恢复",
                                "这段内容不是凚的备份（新格式 " + BACKUP_TEXT_PREFIX
                                        + " 串、旧版名单或旧 JSON 都能识别），未改动任何配置",
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
        JSONObject data = parseAnyBackup(decodeIfText(raw));
        if (data == null) {
            toast("格式不对：需要凚的备份文本（新格式或旧版名单）");
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
        JSONObject shortcuts = data.optJSONObject("shortcuts");
        int shortcutCount = 0;
        if (shortcuts != null) {
            JSONArray arr = shortcuts.optJSONArray("pkgs");
            shortcutCount = arr == null ? shortcuts.optInt("count", 0) : arr.length();
        }
        JSONObject whitelist = data.optJSONObject("whitelist");
        int whiteCount = 0;
        if (whitelist != null) {
            JSONArray arr = whitelist.optJSONArray("keys");
            whiteCount = arr == null ? whitelist.optInt("count", 0) : arr.length();
        }
        JSONObject pairsObj = data.optJSONObject("pairs");
        int pairCount = 0;
        if (pairsObj != null) {
            JSONArray arr = pairsObj.optJSONArray("items");
            pairCount = arr == null ? pairsObj.optInt("count", 0) : arr.length();
        }
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
        LinearLayout onlyWhite = optionRowCompact("只恢复列表白名单",
                whiteCount == 0 ? "这份备份里没有白名单记录"
                        : whiteCount + " 个应用，覆盖当前白名单，冻结列表与设置不动");
        onlyWhite.setOnClickListener(v -> {
            dismiss(ref);
            applyRestore(data, RESTORE_WHITELIST);
        });
        content.addView(onlyWhite, topMargin(6));
        LinearLayout onlyPairs = optionRowCompact("只恢复关联应用",
                pairCount == 0 ? "这份备份里没有主副应用关联记录"
                        : pairCount + " 组主副应用，覆盖当前关联，冻结列表与设置不动");
        onlyPairs.setOnClickListener(v -> {
            dismiss(ref);
            applyRestore(data, RESTORE_PAIRS);
        });
        content.addView(onlyPairs, topMargin(6));
        LinearLayout onlyShortcuts = optionRowCompact("只恢复快捷方式",
                shortcutCount == 0 ? "这份备份里没有桌面快捷方式记录"
                        : shortcutCount + " 个桌面图标，只补当前桌面缺少的，每个需在系统弹框确认");
        onlyShortcuts.setOnClickListener(v -> {
            dismiss(ref);
            applyRestore(data, RESTORE_SHORTCUTS);
        });
        content.addView(onlyShortcuts, topMargin(6));
        LinearLayout all = optionRowCompact("全部恢复",
                "配置 " + setCount + " 项 + 冻结列表 " + listCount + " 个 + 白名单 "
                        + whiteCount + " 个 + 关联应用 " + pairCount + " 组 + 快捷方式 "
                        + shortcutCount + " 个");
        all.setOnClickListener(v -> {
            dismiss(ref);
            applyRestore(data, RESTORE_ALL);
        });
        content.addView(all, topMargin(6));
        ref[0] = compactCard("选择恢复内容", desc, content,
                new String[]{"返回"}, new Runnable[]{null});
    }

    /**
     * 恢复：只写凚自己的 prefs，不碰 /data/system/jin_freeze_list.json，
     * 也不主动调 SwipeFreeze 同步；插件名单由原有路径在下次进列表页时自然同步。
     */
    private void applyRestore(JSONObject data, int mode) {
        boolean doSettings = (mode & RESTORE_SETTINGS) != 0;
        boolean doFreeze = (mode & RESTORE_FREEZE) != 0;
        boolean doShortcuts = (mode & RESTORE_SHORTCUTS) != 0;
        boolean doWhitelist = (mode & RESTORE_WHITELIST) != 0;
        boolean doPairs = (mode & RESTORE_PAIRS) != 0;
        int setCount = 0;
        int listCount = 0;
        int missing = 0;
        int shortcutTotal = 0;
        int shortcutRequested = 0;
        int whiteCount = -1;
        int pairCount = -1;
        if (doSettings) {
            setCount = writeBackupSettings(data.optJSONObject("settings"));
        }
        if (doFreeze) {
            int[] r = writeBackupFreeze(data.optJSONObject("freeze"));
            listCount = r[0];
            missing = r[1];
        }
        if (doWhitelist) {
            JSONObject obj = data.optJSONObject("whitelist");
            // 旧备份没有独立白名单段时不去动当前白名单
            if (obj != null) {
                whiteCount = writeBackupWhitelist(obj);
            }
        }
        if (doPairs) {
            JSONObject obj = data.optJSONObject("pairs");
            // 旧备份没有独立关联段时不去动当前关联
            if (obj != null) {
                pairCount = writeBackupPairs(obj);
            }
        }
        if (doShortcuts) {
            JSONObject sc = data.optJSONObject("shortcuts");
            JSONArray arr = sc == null ? null : sc.optJSONArray("pkgs");
            shortcutTotal = arr == null ? 0 : arr.length();
            shortcutRequested = restoreShortcutPkgs(arr);
        }
        StringBuilder msg = new StringBuilder();
        if (doSettings) {
            msg.append("配置 ").append(setCount).append(" 项");
        }
        if (doFreeze) {
            if (msg.length() > 0) {
                msg.append('\n');
            }
            msg.append("冻结列表 ").append(listCount).append(" 个");
            if (missing > 0) {
                msg.append("（跳过未安装 ").append(missing).append(" 个）");
            }
        }
        if (doWhitelist) {
            if (msg.length() > 0) {
                msg.append('\n');
            }
            if (whiteCount < 0) {
                msg.append("备份里没有白名单记录，当前白名单未改动");
            } else {
                msg.append("列表白名单 ").append(whiteCount).append(" 个");
            }
        }
        if (doPairs) {
            if (msg.length() > 0) {
                msg.append('\n');
            }
            if (pairCount < 0) {
                msg.append("备份里没有关联应用记录，当前关联未改动");
            } else {
                msg.append("关联应用 ").append(pairCount).append(" 组");
            }
        }
        if (doShortcuts) {
            if (msg.length() > 0) {
                msg.append('\n');
            }
            if (shortcutTotal == 0) {
                msg.append("备份里没有桌面快捷方式记录");
            } else if (shortcutRequested == 0) {
                msg.append("桌面快捷方式已存在（").append(shortcutTotal).append(" 个），无需重加");
            } else {
                msg.append("桌面快捷方式：已请求添加 ").append(shortcutRequested)
                        .append(" 个，请在桌面弹出的确认框里逐个确认");
            }
        }
        final boolean settingsApplied = doSettings;
        compactCard("恢复完成", msg.toString(), null, new String[]{"完成"}, new Runnable[]{() -> {
            if (settingsApplied) {
                // 布局参数在 onCreate 读取，重建一次让恢复的设置立即生效
                recreate();
            } else {
                reload();
            }
        }});
    }

    /**
     * 恢复桌面快捷方式：只补当前桌面缺少的，已固定或未安装的跳过。
     * 冻结应用处于 disable 状态，查安装信息必须带 MATCH_DISABLED_COMPONENTS，
     * 否则会被当成未安装而跳过（这正是「只有解冻的能恢复图标」的原因）。
     * 固定动作由桌面弹确认框、无法静默批量，因此交给 ShortcutPin 串行队列逐个请求。
     */
    private int restoreShortcutPkgs(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return 0;
        }
        Set<String> pinned = ShortcutPin.pinnedPackages(this);
        android.content.pm.PackageManager pm = getPackageManager();
        List<String> todo = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String pkg = arr.optString(i, "").trim();
            if (pkg.isEmpty() || pinned.contains(pkg)) {
                continue;
            }
            // 实例键（pkg@999）不能直接喂给 PackageManager，先取纯包名再查
            String pure = packagePart(pkg);
            if (pure.isEmpty()) {
                continue;
            }
            int userId = userIdPart(pkg);
            if (userId != 0) {
                // 分身装在自己的用户空间里，PackageManager 查不到，按 user 判断；查不到也照常排队
                if (Boolean.FALSE.equals(cloneInstalled(pure, userId))) {
                    continue;
                }
            } else {
                try {
                    pm.getApplicationInfo(pure,
                            android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                } catch (Exception e) {
                    continue;
                }
            }
            todo.add(pkg);
        }
        if (!todo.isEmpty()) {
            ShortcutPin.pinAll(this, todo, null);
        }
        return todo.size();
    }

    /**
     * 分身用户下这个包是否存在：TRUE/FALSE 为确定结果，null 表示查询失败。
     * 查询失败时调用方应「宁可直接写入」，不要因为查不到就丢备份数据。
     */
    private Boolean cloneInstalled(String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return Boolean.FALSE;
        }
        PrivShell.Result r = PrivShell.exec("pm list packages --user " + userId + " " + pkg, 20);
        if (r == null || r.timeout || r.out == null) {
            return null;
        }
        String out = r.out.trim();
        if (out.contains("package:" + pkg)) {
            return Boolean.TRUE;
        }
        String low = out.toLowerCase(Locale.ROOT);
        if (low.contains("error") || low.contains("exception") || low.contains("permission")) {
            return null;
        }
        return Boolean.FALSE;
    }

    /** 备份 JSON：与搞机助手同一套字段，freeze 段 + settings 段 + shortcuts 段。 */
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

            // 列表白名单：单独成段，恢复时可以只恢复它
            List<String> whiteKeys = new ArrayList<>(whitelistSet());
            Collections.sort(whiteKeys);
            JSONArray whiteArr = new JSONArray();
            for (String k : whiteKeys) {
                whiteArr.put(k);
            }
            JSONObject whitelist = new JSONObject();
            whitelist.put("count", whiteArr.length());
            whitelist.put("keys", whiteArr);
            root.put("whitelist", whitelist);

            // 主副应用关联：单独成段，恢复时可以只恢复它
            JSONArray pairArr = new JSONArray();
            for (Pair p : allPairs()) {
                JSONObject one = new JSONObject();
                one.put("main", p.main);
                one.put("sub", p.sub);
                one.put("enabled", p.enabled);
                pairArr.put(one);
            }
            JSONObject pairs = new JSONObject();
            pairs.put("count", pairArr.length());
            pairs.put("items", pairArr);
            root.put("pairs", pairs);

            // 桌面快捷方式：只记录备份时确实固定在桌面的包名，恢复时补桌面缺失的
            JSONArray scArr = new JSONArray();
            List<String> pinned = new ArrayList<>(ShortcutPin.pinnedPackages(this));
            Collections.sort(pinned);
            for (String p : pinned) {
                scArr.put(p);
            }
            JSONObject shortcuts = new JSONObject();
            shortcuts.put("count", scArr.length());
            shortcuts.put("pkgs", scArr);
            root.put("shortcuts", shortcuts);

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

    /** 文本解码：新格式 JIN:、旧压缩串 FWB1: / DJLB: 都解压，其余按明文处理。 */
    private String decodeIfText(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.startsWith(BACKUP_TEXT_PREFIX)) {
            return decodeBackupText(t);
        }
        if (t.startsWith("FWB1:") || t.startsWith("DJLB:")) {
            return decodeDeflated(t.substring(5));
        }
        return t;
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

    /** 文本解码：JIN: 前缀，解压后交给 parseBackup 校验。 */
    private String decodeBackupText(String raw) {
        if (raw == null || !raw.startsWith(BACKUP_TEXT_PREFIX)) {
            return null;
        }
        return decodeDeflated(raw.substring(BACKUP_TEXT_PREFIX.length()));
    }

    /** Base64 → Deflate 解压成 UTF-8 文本，旧备份串共用。 */
    private String decodeDeflated(String b64) {
        try {
            byte[] data = Base64.decode(b64, Base64.NO_WRAP);
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

    /** 备份解析：新格式 → 旧 JSON（无 format 字段）→ 旧版纯文本名单，逐级兼容。 */
    private JSONObject parseAnyBackup(String text) {
        JSONObject o = parseBackup(text);
        if (o != null) {
            return o;
        }
        if (text == null) {
            return null;
        }
        try {
            JSONObject loose = new JSONObject(text.trim());
            if (loose.optJSONObject("freeze") == null
                    && loose.optJSONObject("settings") == null) {
                return null;
            }
            loose.put("format", BACKUP_FORMAT);
            if (!loose.has("version")) {
                loose.put("version", BACKUP_VERSION);
            }
            return loose;
        } catch (Exception ignored) {
        }
        return parseLegacyText(text);
    }

    /** 旧版纯文本名单：[用户应用] / [系统应用] / [设置] 分区，或每行一个包名。 */
    private JSONObject parseLegacyText(String text) {
        if (text == null) {
            return null;
        }
        JSONArray pkgs = new JSONArray();
        JSONObject settings = new JSONObject();
        boolean hasHeader = false;
        boolean inSettings = false;
        for (String line0 : text.split("\\r?\\n")) {
            String line = line0.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                hasHeader = true;
                inSettings = line.contains("设置");
                continue;
            }
            if (inSettings) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    putLegacySetting(settings, line.substring(0, eq).trim(),
                            line.substring(eq + 1).trim());
                }
                continue;
            }
            if (isPackageName(line)) {
                pkgs.put(line);
            } else if (!hasHeader) {
                return null;
            }
        }
        if (pkgs.length() == 0 && settings.length() == 0) {
            return null;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("format", BACKUP_FORMAT);
            root.put("version", BACKUP_VERSION);
            root.put("appVersion", "旧版备份");
            JSONObject freeze = new JSONObject();
            freeze.put("pkgs", pkgs);
            freeze.put("count", pkgs.length());
            root.put("freeze", freeze);
            if (settings.length() > 0) {
                root.put("settings", settings);
            }
            return root;
        } catch (Exception e) {
            return null;
        }
    }

    /** 旧版设置行 key=value，按 true/false、数字、文本三类还原类型。 */
    private void putLegacySetting(JSONObject settings, String key, String value) {
        if (key.isEmpty() || !ownSettingKey(key)) {
            return;
        }
        try {
            JSONObject one = new JSONObject();
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                one.put("type", "boolean");
                one.put("value", Boolean.parseBoolean(value));
            } else {
                try {
                    one.put("type", "int");
                    one.put("value", Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    one.put("type", "string");
                    one.put("value", value);
                }
            }
            settings.put(key, one);
        } catch (Exception ignored) {
        }
    }

    private boolean isPackageName(String s) {
        String[] parts = s.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty()) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                boolean ok = Character.isLetterOrDigit(c) || c == '_';
                if (!ok) {
                    return false;
                }
            }
        }
        return Character.isLetter(parts[0].charAt(0));
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
            // 新备份把白名单/主副关联单独成段；旧备份里它们还在 settings 段，这里照样兼容写回
            if (!ownSettingKey(key)
                    && !KEY_WHITELIST.equals(key) && !KEY_SUB_PAIRS.equals(key)) {
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
                int userId = userIdPart(p);
                if (userId != 0) {
                    // 双开/分身：PackageManager 只认主用户包名，按对应 user 判断是否存在；
                    // 查询不到时宁可直接写入，交给 reload() 的 packagePart 校验兜底
                    if (!Boolean.FALSE.equals(cloneInstalled(packagePart(p), userId))) {
                        next.add(p);
                    }
                    continue;
                }
                try {
                    pm.getApplicationInfo(packagePart(p), 0);
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

    /** 写回列表白名单：整体覆盖，只保留冻结列表里还在的应用，返回写入个数。 */
    private int writeBackupWhitelist(JSONObject whitelist) {
        // 名单是唯一依据：备份里有、但当前清单里没有的应用不再恢复
        Set<String> live = pkgs();
        Set<String> next = new HashSet<>();
        JSONArray arr = whitelist == null ? null : whitelist.optJSONArray("keys");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String k = arr.optString(i, "");
                if (!k.isEmpty() && live.contains(k)) {
                    next.add(k);
                }
            }
        }
        prefs().edit().putStringSet(KEY_WHITELIST, next).apply();
        SwipeFreeze.syncAsync(this);
        refreshWhitelistEntry();
        rebindCells();
        return next.size();
    }

    /** 写回主副应用关联：整体覆盖，只保留主副两端都在冻结列表里的，格式同 allPairs。 */
    private int writeBackupPairs(JSONObject pairs) {
        // 名单是唯一依据：主或副不在当前清单里的关联不再恢复
        Set<String> live = pkgs();
        List<Pair> list = new ArrayList<>();
        JSONArray arr = pairs == null ? null : pairs.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject one = arr.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String main = one.optString("main", "");
                String sub = one.optString("sub", "");
                if (main.isEmpty() || sub.isEmpty() || main.equals(sub)) {
                    continue;
                }
                if (!live.contains(main) || !live.contains(sub)) {
                    continue;
                }
                list.add(new Pair(main, sub, one.optBoolean("enabled", true)));
            }
        }
        persistPairs(list);
        return list.size();
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
        // 备份路径固定为下载目录：/storage/emulated/0/Download/凚/备份
        File dir = new File("/storage/emulated/0/Download/凚/备份");
        if (!dir.exists()) dir.mkdirs();
        if (dir.exists()) {
            // 应用无权写入（Android 11+ 分区存储）时，读写都走 root/Shizuku 兜底
            return dir;
        }
        // 兜底：目录建不出来时退回应用内部存储
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File fallback = new File(base, BACKUP_DIR);
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    /** 紧凑卡片弹窗：宽度 76%、内边距与字号整体收一档，与搞机助手备份/恢复弹框同一档。 */
    private AlertDialog compactCard(String title, CharSequence desc, View content,
            String[] labels, final Runnable[] actions) {
        return compactCard(title, desc, content, null, null, labels, actions);
    }

    /** 带左下角附加按钮的紧凑卡片：刷新这类次要操作靠左，主操作仍收在右下角。 */
    private AlertDialog compactCard(String title, CharSequence desc, View content,
            String leftLabel, Runnable leftAction, String[] labels, final Runnable[] actions) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.applyDialogBg(card);
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
        if (leftLabel != null) {
            TextView lb = cardButton(leftLabel);
            row.addView(lb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 30)));
            // 中间留一段弹簧，把附加按钮顶在左下角
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            lb.setOnClickListener(v -> {
                dialog.dismiss();
                if (leftAction != null) {
                    leftAction.run();
                }
            });
        }
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
        disableDialogWindowAnimation(dialog);
        dialog.show();
        applySettingsSurfaceAlpha(dialog);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            // 与设置面板 /「（查看说明）」同宽，并保持居中
            applySettingsWidthCentered(dialog);
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
            String tail = "（当前权限：" + privilegeValue() + "）";
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
        if (DhizukuGate.installed(this) && DhizukuGate.authorized(this)) {
            return "Dhizuku";
        }
        return "未激活";
    }

    private void handlePrivilegeClick(Runnable refresh) {
        PrivShell.Mode mode = PrivShell.peek();
        if (mode == PrivShell.Mode.ROOT) {
            toast("已通过 root 执行");
            return;
        }
        if (mode == PrivShell.Mode.SHIZUKU) {
            toast("已通过 Shizuku 执行");
            return;
        }
        if (DhizukuGate.installed(this) && DhizukuGate.authorized(this)) {
            toast("已通过 Dhizuku 执行（仅暂停）");
            return;
        }
        if (!ShizukuGate.installed(this)) {
            if (DhizukuGate.installed(this)) {
                DhizukuGate.requestAnyway(() -> {
                    PrivShell.reset();
                    IO.execute(() -> {
                        PrivShell.mode();
                        main.post(refresh);
                    });
                });
                return;
            }
            showPrivilegeMissingDialog();
            return;
        }
        ShizukuGate.requestAnyway(() -> {
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
        showPrivilegeMissingDialog(null);
    }

    /** extra 非空时在正文后补一行说明（例如仅 LSPosed 时说明它代替不了 root/Shizuku）。 */
    private void showPrivilegeMissingDialog(String extra) {
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
        message.setText("请在Shizuku、Dhizuku或root授权凚后，强制停止凚，再打开凚，即可激活，如未激活成功，请再次检查授权状态。"
                + (extra == null ? "" : "\n" + extra));
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
            Runnable recheck = () -> recheckPrivilegeAsync(true, true, () -> {
                retry.setEnabled(true);
                retry.setText("重新检测");
                if (!isFinishing() && privDialog != null && privDialog.isShowing()) {
                    toast("仍未激活：" + PrivShell.failureHint());
                }
            });
            // Shizuku 装着且在运行时，先补一次授权请求（弹「要允许凚使用 Shizuku 吗？」），
            // 否则光重测永远不会通过；Shizuku 不可用时改试 Dhizuku
            if (ShizukuGate.available(this)) {
                ShizukuGate.requestAnyway(recheck);
            } else if (DhizukuGate.available(this)) {
                DhizukuGate.requestAnyway(recheck);
            } else {
                recheck.run();
            }
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
        applySettingsSurfaceAlpha(dialog);
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
        checkPrivilegeOnStart(0);
    }

    /**
     * 启动检查，通道顺序：root → Shizuku → Dhizuku。
     * 1. 有 root 直接静默通过；
     * 2. 没有 root 时优先 Shizuku：Shizuku 已激活就主动请求一次授权
     *    （已在 Shizuku 应用里授权过的直接回调、不弹窗）；
     * 3. Shizuku 拿不到（未激活、或之前拒绝过且 Dhizuku 已授权）才走 Dhizuku；
     *    用户拒绝过 Shizuku、但 Dhizuku 还没授权时，下次启动仍可继续弹 Shizuku 授权；
     * 4. 都拿不到才弹未授权提示。
     * attempt 用于 Shizuku 刚开机还没连上 binder 时再等一轮。
     */
    private void checkPrivilegeOnStart(int attempt) {
        IO.execute(() -> {
            PrivShell.Mode mode = PrivShell.mode();
            if (mode == PrivShell.Mode.ROOT || mode == PrivShell.Mode.SHIZUKU) {
                return;
            }
            boolean dhizukuAuthorized = DhizukuGate.authorized(this);
            // 拒绝过 Shizuku 且 Dhizuku 已经授权：不再弹 Shizuku 授权
            boolean shizukuBlocked = dhizukuAuthorized && ShizukuGate.declined();
            if (!shizukuBlocked && !shizukuAutoAsked) {
                // Shizuku 被冻结（暂停）时先解冻，否则请求授权会把 Shizuku 拉起来，
                // 被系统的暂停策略拦成「已被工作政策禁用」
                if (SuspendSupport.cached(this).contains(ShizukuGate.PKG)
                        || Boolean.TRUE.equals(SuspendSupport.suspendedOne(ShizukuGate.PKG))) {
                    SuspendSupport.unsuspend(ShizukuGate.PKG);
                }
                if (ShizukuGate.available(this)) {
                    shizukuAutoAsked = true;
                    main.post(() -> ShizukuGate.requestAnyway(() -> {
                        if (ShizukuGate.authorized()) {
                            recheckPrivilegeAsync(true, false, this::showPrivilegeMissingDialog);
                        } else {
                            // 拒绝了 Shizuku：按顺序转 Dhizuku
                            checkPrivilegeOnStart(attempt);
                        }
                    }));
                    return;
                }
            }
            // Shizuku 没拿到：到 Dhizuku
            if (dhizukuAuthorized) {
                return;
            }
            if (DhizukuGate.available(this) && !dhizukuAutoAsked) {
                dhizukuAutoAsked = true;
                main.post(() -> DhizukuGate.requestAnyway(() ->
                        recheckPrivilegeAsync(true, false, this::showPrivilegeMissingDialog)));
                return;
            }
            if (mode == PrivShell.Mode.NONE) {
                if (ShizukuGate.installed(this) && attempt < 1) {
                    main.postDelayed(() -> checkPrivilegeOnStart(attempt + 1), 1500);
                    return;
                }
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
            // Dhizuku 不提供 shell，mode 仍是 NONE，但授权后照样可用
            boolean dhizuku = mode == PrivShell.Mode.NONE && DhizukuGate.only(this);
            main.post(() -> {
                if (mode != PrivShell.Mode.NONE || dhizuku) {
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

    private void runBatch(boolean freeze, List<Item> shown) {
        if (working) {
            return;
        }
        final List<Item> targets = new ArrayList<>();
        int skippedCount = 0;
        int whiteSkipped = 0;
        for (Item it : shown) {
            // 已经到位的直接跳过，不发起 pm 调用，也不算失败
            if (freeze == it.frozen) {
                skippedCount++;
                continue;
            }
            // 冻结白名单：任何冻结入口都不动它
            if (freeze && isWhitelisted(it.pkg)) {
                whiteSkipped++;
                continue;
            }
            targets.add(it);
        }
        if (freeze) {
            // 主副联动：把配了映射的副应用一并加入本轮冻结（已在目标里或已冻结的跳过）
            Set<String> have = new HashSet<>();
            for (Item it : targets) {
                have.add(it.pkg);
            }
            List<Item> extra = new ArrayList<>();
            for (Item it : shown) {
                String subKey = subOf(it.pkg);
                if (subKey == null || have.contains(subKey) || isWhitelisted(subKey)) {
                    continue;
                }
                have.add(subKey);
                boolean alreadyFrozen = false;
                for (Item x : items) {
                    if (x.pkg.equals(subKey) && x.frozen) {
                        alreadyFrozen = true;
                        break;
                    }
                }
                if (!alreadyFrozen) {
                    extra.add(itemForKey(subKey));
                }
            }
            targets.addAll(extra);
        }
        final int skippedFinal = skippedCount;
        if (targets.isEmpty()) {
            if (freeze) {
                // 一键冻结没有可处理的目标（名单为空 / 全部已冻结 / 全部在冻结白名单）：
                // 用和「已冻结」完全同一套自绘提示，只换文案
                FrozenTip.show(this, whiteSkipped > 0 && skippedCount == 0
                        ? "白名单应用不冻结" : "无需冻结");
            } else {
                // 一键解冻没有可处理的目标：同样用自绘提示，尺寸与「已解冻」一致
                FrozenTip.show(this, "无需解冻");
            }
            // 「冻结后收起列表」的自动收起保持原有判断，不受提示影响
            if (skippedCount > 0 && panelDismissAfterFreeze) {
                closeSelf();
            }
            return;
        }
        working = true;
        // 一键解冻同样保持当前顺序，解冻完成后图标不在列表里跳动
        if (!freeze) {
            keepOrderBeforeUnfreeze();
        }
        if (freeze) {
            startFreezeSpin();
        }
        render();
        if (count != null) {
            count.setText((freeze ? "正在冻结 " : "正在解冻 ") + targets.size() + " 个应用…");
        }
        IO.execute(() -> {
            final boolean dhizuku = DhizukuGate.only(this);
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
                if (freeze && (keepLauncherIcon || dhizuku)) {
                    // 勾选「留桌面图标」或 Dhizuku-only：整批改走暂停通道，不执行 pm disable-user，图标才不会被摘掉
                    outputs.putAll(execSuspendBatch(zeroKeys));
                } else if (!freeze && dhizuku) {
                    // Dhizuku-only 解冻：逐包走 DPM 恢复暂停（DO 只作用 user 0）
                    for (String key : zeroKeys) {
                        PrivShell.Result u = SuspendSupport.unsuspend(key);
                        outputs.put(key, u == null || u.out == null ? "" : u.out);
                    }
                    recordDiagOp("dhizuku:setPackagesSuspended",
                            "setPackagesSuspended(*, false) × " + zeroKeys.size(), null);
                } else {
                    outputs.putAll(execPmBatch(zeroKeys, freeze));
                    if (freeze) {
                        // 只对被 ROM 拦住停用的包（两类固定文案）降级暂停，其余包行为不变
                        for (String key : zeroKeys) {
                            if (SuspendSupport.romBlocked(outputs.get(key)) || dhizuku) {
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
                } else if (truth != null || dhizuku) {
                    String name = packagePart(key);
                    boolean dis = truth != null && truth.contains(name);
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
                    Ui.smallDialog(this, freeze ? "冻结失败" : "解冻失败", sb.toString(), "知道了");
                }
                // 所有短提示统一受「去掉 toast 提示」控制
                if (fails.isEmpty()) {
                    if (freeze) {
                        if (!hideToasts) {
                            FrozenTip.show(this);
                        }
                    } else if (!hideToasts) {
                        FrozenTip.show(this, "已解冻");
                    }
                }
                // 冻结且没有失败项时，按设置在本轮刷新完成后收起列表
                if (freeze && fails.isEmpty()) {
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
        if (DhizukuGate.only(this)) {
            // Dhizuku-only：逐包走 DPM 暂停（DO 只作用 user 0），无 shell 输出，成败按真值复核
            for (String pkg : pkgs) {
                PrivShell.Result r = SuspendSupport.suspend(this, packagePart(pkg), labelOf(pkg));
                result.put(pkg, r == null || r.out == null ? "" : r.out);
            }
            recordDiagOp("dhizuku:setPackagesSuspended",
                    "setPackagesSuspended(*, true) × " + pkgs.size(), null);
            return result;
        }
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
        PrivShell.Result r = PrivShell.exec(script.toString(),
                Math.max(30, 10 + pending.size() * 5));
        result.putAll(splitMarked(r, pending));
        // 批量暂停只记一条汇总，避免 50 条流水被一次批量操作冲掉
        recordDiagOp("批量暂停", "pm suspend × " + pending.size() + "\n" + script, r);
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
        // 主应用配了副应用：正常打开主应用，同时在后台顺带解冻副应用
        final String subKey = subOf(it.pkg);
        working = true;
        // 走 OPEN 独立线程：IO 里排着列表刷新/图标加载，点击不能排在它们后面
        com.jin.freeze.JinApp.OPEN.execute(() -> {
            PrivShell.Result r = execPm(it.pkg, it.userId, false);
            // 点开应用走快速判定：pm 返回成功即认为解冻完成，不再跑 pm list / dumpsys 复核
            //（这两次查询要 200ms 以上），真值由随后的 reload 在后台校正
            boolean ok = isPmOk(r);
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
                    Ui.smallDialog(this, "解冻失败，无法打开",
                            it.label + "：" + (failReason != null
                                    ? failReason : Ui.pmReason(r, false)), "知道了");
                    reload();
                    return;
                }
                it.frozen = false;
                it.suspended = false;
                keepOrderBeforeUnfreeze();
                rebindCells();
                launch(it);
                // 副应用顺带解冻放到主应用拉起之后，别挡在打开的路径上
                if (subKey != null) {
                    unfreezeSubQuiet(subKey);
                }
                reload();
            });
        });
    }

    private void launch(Item it) {
        String name = packagePart(it.pkg);
        // 分身不能用主用户的 getLaunchIntentForPackage：主应用被冻结（disabled）时
        // PackageManager 会返回 null，表现为「该应用没有可启动的界面」，而桌面能正常打开。
        // 分身一律在它自己的用户下解析启动组件。
        if (it.userId != 0) {
            String comp = resolveCloneLauncher(name, it.userId);
            if (comp == null) {
                toast("该应用没有可启动的界面");
                return;
            }
            PrivShell.Result r = PrivShell.exec(
                    "am start --user " + it.userId + " -n " + comp, 15);
            if (r == null || r.timeout || (r.out != null && r.out.contains("Error"))) {
                // 组件方式失败时退回 action/category，仍明确指定分身用户
                PrivShell.exec("am start --user " + it.userId
                        + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                        + name, 15);
            }
            return;
        }
        Intent i = getPackageManager().getLaunchIntentForPackage(name);
        if (i == null) {
            toast("该应用没有可启动的界面");
            return;
        }
        // 系统里存在同名双开时，直接用 startActivity 会让 HyperOS 弹「主应用还是双开」：
        // 统一改为在后台线程用 --user 0 明确启动，拿不到权限时才退回 startActivity。
        // （PrivShell 探测会阻塞，不能放在主线程）
        final Intent launch = i;
        com.jin.freeze.JinApp.OPEN.execute(() -> {
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
                        toast("打开失败：" + e.getClass().getSimpleName());
                    }
                });
            }
        });
    }

    /**
     * 在指定用户（分身）下解析启动组件，返回「包/类」短名；解析不到返回 null。
     * 走 shell 的 resolve-activity，不受主用户冻结影响。
     */
    private String resolveCloneLauncher(String pkg, int userId) {
        // 注意必须带 -a/-c/-p：resolve-activity 的位置参数是 URI，不是包名
        PrivShell.Result r = PrivShell.exec("cmd package resolve-activity --brief --user "
                + userId + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                + pkg + " | tail -n 1", 10);
        String out = r.out == null ? "" : r.out.trim();
        if (out.contains("/")) {
            return out;
        }
        // 兜底：主用户可用时直接拿系统给的启动组件
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null && i.getComponent() != null) {
            return i.getComponent().flattenToShortString();
        }
        return null;
    }

    /** 诊断通道名：实际走 Dhizuku 暂停通道时记 "dhizuku:setPackagesSuspended"。 */
    private String suspendDiagChannel(String normal) {
        return DhizukuGate.only(this) ? "dhizuku:setPackagesSuspended" : normal;
    }

    /** 执行 pm，返回完整结果（含退出码与是否超时），成败不在这里判。 */
    private PrivShell.Result execPm(String pkg, boolean freeze) {
        return execPm(pkg, 0, freeze);
    }

    private PrivShell.Result execPm(String pkg, int userId, boolean freeze) {
        // Dhizuku-only 解冻：走 DPM 恢复暂停（DO 只作用 user 0）；冻结走降级暂停链路，不进这里
        if (!freeze && userId == 0 && DhizukuGate.only(this)) {
            PrivShell.Result r = SuspendSupport.unsuspend(pkg);
            recordDiagOp("dhizuku:setPackagesSuspended",
                    "setPackagesSuspended(" + packagePart(pkg) + ", false)", r);
            return r;
        }
        // 主用户与分身各自独立：冻结主应用不会连带分身，双开仍可单独打开/冻结
        String cmd = pmCommand(pkg, userId, freeze);
        PrivShell.Result r = PrivShell.exec(cmd, 30);
        recordDiagOp("单次pm", cmd, r);
        return r;
    }

    /** 批量冻结/解冻脚本：同样留下最近一次命令与返回，供诊断包排查原文。 */
    private PrivShell.Result execPmScript(String script, int timeoutSeconds) {
        PrivShell.Result r = PrivShell.exec(script, timeoutSeconds);
        recordDiagOp("批量脚本", script, r);
        return r;
    }

    /**
     * 记一条「冻结/解冻/暂停」操作流水：保留最近 50 条（总量不超过 48KB），
     * 同时覆盖「最近一次」三个字段。通道用来区分入口，退出码/超时用来区分「没触发」和「触发了但失败」。
     */
    private void recordDiagOp(String channel, String cmd, PrivShell.Result r) {
        String c = cutDiagText(cmd, 600);
        String out;
        if (r == null) {
            out = "（没有返回）";
        } else {
            out = "退出码 " + r.code + (r.timeout ? "，执行超时" : "") + "\n"
                    + (r.out == null ? "" : r.out.trim());
        }
        out = cutDiagText(out, 800);
        try {
            JSONArray old = new JSONArray(prefs().getString(KEY_DIAG_HIST, "[]"));
            JSONArray next = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("t", System.currentTimeMillis());
            entry.put("ch", channel);
            entry.put("code", r == null ? -1 : r.code);
            entry.put("to", r != null && r.timeout);
            entry.put("cmd", c);
            entry.put("out", out);
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < 50; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null) {
                    next.put(item);
                }
            }
            // 总量上限：条数够但单条过大时，从最旧的开始丢
            String json = next.toString();
            while (json.length() > 48 * 1024 && next.length() > 1) {
                next.remove(next.length() - 1);
                json = next.toString();
            }
            prefs().edit()
                    .putString(KEY_DIAG_HIST, json)
                    .putString(KEY_DIAG_CMD, c)
                    .putString(KEY_DIAG_OUT, out)
                    .putLong(KEY_DIAG_AT, System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            // JSON 损坏时清掉流水重新开始，不影响主流程
            prefs().edit().putString(KEY_DIAG_HIST, "[]").apply();
        }
    }

    private static String cutDiagText(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "\n…（已截断）" : s;
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
            if (DhizukuGate.only(this)) {
                // Dhizuku-only：冻结语义是暂停，真值看 FLAG_SUSPENDED
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
