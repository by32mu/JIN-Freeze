package com.jin.freeze.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用图标缓存：内存 Bitmap + 磁盘 PNG 两级，外加并行解码线程池。
 *
 * 进程被划掉后内存缓存会清空，但磁盘缓存还在：冷启动时直接从 cacheDir 读小图，
 * 比每次从 APK 里解析自适应图标（5~20ms/个）快一个数量级。
 */
public final class AppIconCache {

    /**
     * 内存缓存上限（单位 KB，与 sizeOf 的口径一致）。
     * 之前直接拿 192 当上限，实际只够存 1 个 72dp 图标，冷启动第二次渲染时
     * 图标全部落空、只能先显示占位图，看起来就是列表「先空一片、再慢慢补全」。
     */
    private static final int MAX_KB = (int) Math.min(48L * 1024L,
            Math.max(16L * 1024L, Runtime.getRuntime().maxMemory() / 1024L / 8L));
    // v3：图标改为优先取启动器（含 MIUI/HyperOS 主题图标），旧缓存里的默认图标不再适用
    private static final String DIR_NAME = "jin_icons3";
    /** 主题特征：主题图标包一换就作废两级缓存，避免一直显示旧图标。 */
    private static final String PREFS_THEME = "jin_icon_theme";
    private static final String KEY_THEME_SIG = "signature";
    private static final String KEY_MODE = "mode";
    /** 取图来源：0 = 系统默认（APK 自带图标），1 = 当前主题图标（启动器实际显示的图标）。 */
    public static final int MODE_SYSTEM = 0;
    public static final int MODE_THEME = 1;
    private static boolean themeChecked;

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(MAX_KB) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };

    /** 图标解码线程池：单个图标解码约 5~20ms，多个并发能明显缩短首屏等待。 */
    public static final ExecutorService EXEC = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "jin-icon");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private AppIconCache() {
    }

    /** 统一缓存尺寸：按 72dp 存一份，各页面按自己需要的尺寸缩放显示。 */
    private static int cachePx(Context c) {
        int dp = 72;
        return Math.max(48, Math.round(dp * c.getResources().getDisplayMetrics().density));
    }

    private static String key(String pkg, int px, int mode) {
        return mode + "|" + pkg + "@" + px;
    }

    private static File diskFile(Context c, String pkg, int px) {
        File dir = new File(c.getCacheDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        // 包名里的 / 等非法字符统一换掉，避免当成子目录；两种取图来源分开存，互不覆盖
        String name = pkg.replaceAll("[^a-zA-Z0-9._-]", "_");
        String prefix = getMode(c) == MODE_THEME ? "t_" : "s_";
        return new File(dir, prefix + name + "_" + px + ".png");
    }

    /** 当前取图来源：系统默认 / 当前主题图标。 */
    public static int getMode(Context c) {
        if (c == null) {
            return MODE_SYSTEM;
        }
        try {
            int m = c.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
                    .getInt(KEY_MODE, MODE_SYSTEM);
            return m == MODE_THEME ? MODE_THEME : MODE_SYSTEM;
        } catch (Throwable ignored) {
            return MODE_SYSTEM;
        }
    }

    /** 切换取图来源：立即作废两级缓存，下一次取图就按新来源重新解码。 */
    public static void setMode(Context c, int mode) {
        if (c == null) {
            return;
        }
        int m = mode == MODE_THEME ? MODE_THEME : MODE_SYSTEM;
        c.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MODE, m).apply();
        themeChecked = false;
        clear(c);
    }

    /** 只查内存缓存，不读磁盘也不解码；没有就返回 null。 */
    public static Drawable peek(Context c, String pkg) {
        if (c == null || pkg == null) {
            return null;
        }
        ensureThemeSignature(c);
        Bitmap bmp = CACHE.get(key(pkg, cachePx(c), getMode(c)));
        return bmp == null ? null : new BitmapDrawable(c.getResources(), bmp);
    }

    /** 内存 → 磁盘，不做 APK 解码；用于首帧快速出图。 */
    public static Drawable peekAny(Context c, String pkg) {
        Drawable hit = peek(c, pkg);
        if (hit != null) {
            return hit;
        }
        if (c == null || pkg == null) {
            return null;
        }
        ensureThemeSignature(c);
        int px = cachePx(c);
        Bitmap bmp = readDisk(c, pkg, px);
        if (bmp == null) {
            return null;
        }
        CACHE.put(key(pkg, px, getMode(c)), bmp);
        return new BitmapDrawable(c.getResources(), bmp);
    }

    /** 内存 → 磁盘 → 启动器/APK 资源，命中后回写两级缓存；失败返回 null。 */
    public static Drawable load(Context c, String pkg, ApplicationInfo ai) {
        if (c == null || pkg == null) {
            return null;
        }
        ensureThemeSignature(c);
        int px = cachePx(c);
        final int mode = getMode(c);
        String k = key(pkg, px, mode);
        Bitmap hit = CACHE.get(k);
        if (hit != null) {
            return new BitmapDrawable(c.getResources(), hit);
        }
        Bitmap fromDisk = readDisk(c, pkg, px);
        if (fromDisk != null) {
            CACHE.put(k, fromDisk);
            return new BitmapDrawable(c.getResources(), fromDisk);
        }
        if (ai == null) {
            return null;
        }
        try {
            // 主题模式：只有 LauncherApps 这条通道带主题图标，取不到再退回应用自身图标；
            // 系统默认模式：直接用 APK 里的图标，不去启动器取图
            Drawable d = mode == MODE_THEME ? themedIcon(c, pkg) : null;
            if (d == null) {
                d = ai.loadIcon(c.getPackageManager());
            }
            if (d == null) {
                return null;
            }
            Bitmap bmp = toBitmap(d, px);
            if (bmp != null) {
                CACHE.put(k, bmp);
                final Bitmap store = bmp;
                // 磁盘缓存放到图标线程池异步写：首次授权后一次要落几百个图标，
                // 同步压缩写文件会把列表拖慢好几秒
                EXEC.execute(() -> writeDisk(c, pkg, px, store));
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 LauncherApps 取「启动器实际显示的图标」。
     * 关键点：密度必须传 0。MIUI/HyperOS 的主题图标是按固定密度打包的，
     * 指定了具体 dpi 时系统会走普通资源、把主题图标丢掉；传 0 才回落到主题图标。
     */
    private static Drawable themedIcon(Context c, String pkg) {
        try {
            LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (la == null) {
                return null;
            }
            List<LauncherActivityInfo> list = la.getActivityList(pkg, Process.myUserHandle());
            if (list == null || list.isEmpty()) {
                return null;
            }
            for (LauncherActivityInfo info : list) {
                if (info == null) {
                    continue;
                }
                Drawable d;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    d = info.getIcon(0);
                } else {
                    d = info.getIcon(c.getResources().getDisplayMetrics().densityDpi);
                }
                if (d != null) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 主题图标包变化时清空缓存：图标是按包名缓存的，不主动作废就会一直显示旧主题。
     * 只在本进程第一次取图标时比对一次，后续零开销。
     */
    private static void ensureThemeSignature(Context c) {
        if (themeChecked) {
            return;
        }
        themeChecked = true;
        try {
            String sig = themeSignature(c);
            android.content.SharedPreferences sp =
                    c.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE);
            String old = sp.getString(KEY_THEME_SIG, null);
            if (old != null && !old.equals(sig)) {
                clear(c);
            }
            if (!sig.equals(old)) {
                sp.edit().putString(KEY_THEME_SIG, sig).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 当前主题图标包的特征值：换主题/换图标包后这些值会变。
     * 只取小米相关的三个键，读不到就当空串，不影响其它 ROM。
     */
    private static String themeSignature(Context c) {
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            String a = android.provider.Settings.System.getString(cr, "customize_icon_init_time");
            String b = android.provider.Settings.Secure.getString(
                    cr, "theme_customization_overlay_packages");
            String d = android.provider.Settings.System.getString(cr, "theme_icon_pack");
            return String.valueOf(a) + "|" + String.valueOf(b) + "|" + String.valueOf(d);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Bitmap toBitmap(Drawable d, int px) {
        try {
            Bitmap raw = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(raw);
            d.setBounds(0, 0, px, px);
            d.draw(canvas);
            return normalize(raw, px);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 统一图标观感尺寸：先裁掉四周透明留白，再等比放大到固定画布。
     * 部分设备/ROM 的主题图标、老式矩形图标自带不同大小的留白，直接铺满方框会显得忽大忽小；
     * 裁白边后按同一留边比例重新排布，所有图标的实际占位就一致了。
     */
    private static Bitmap normalize(Bitmap src, int px) {
        int w = src.getWidth();
        int h = src.getHeight();
        int left = w, top = h, right = -1, bottom = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 10) {
                    if (x < left) {
                        left = x;
                    }
                    if (x > right) {
                        right = x;
                    }
                    if (y < top) {
                        top = y;
                    }
                    if (y > bottom) {
                        bottom = y;
                    }
                }
            }
        }
        // 全透明（或取不到内容边界）时保持原图，避免把异常图标画成空白
        if (right < left || bottom < top) {
            return src;
        }
        int cw = right - left + 1;
        int ch = bottom - top + 1;
        int margin = Math.max(2, Math.round(px * 0.06f));
        int box = px - margin * 2;
        float scale = Math.min((float) box / cw, (float) box / ch);
        int dw = Math.max(1, Math.round(cw * scale));
        int dh = Math.max(1, Math.round(ch * scale));
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG
                        | android.graphics.Paint.ANTI_ALIAS_FLAG);
        int ox = (px - dw) / 2;
        int oy = (px - dh) / 2;
        canvas.drawBitmap(src,
                new android.graphics.Rect(left, top, right + 1, bottom + 1),
                new android.graphics.Rect(ox, oy, ox + dw, oy + dh), paint);
        src.recycle();
        return out;
    }

    private static Bitmap readDisk(Context c, String pkg, int px) {
        File f = diskFile(c, pkg, px);
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDisk(Context c, String pkg, int px, Bitmap bmp) {
        File f = diskFile(c, pkg, px);
        if (f == null) {
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 安装/卸载应用后清一遍，避免拿到旧图标。 */
    public static void clear(Context c) {
        CACHE.evictAll();
        File dir = new File(c.getCacheDir(), DIR_NAME);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
