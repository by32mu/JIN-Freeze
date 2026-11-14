package com.jin.freeze.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.DisplayMetrics;

import java.io.InputStream;

/**
 * 自定义壁纸的读取与模糊：进程内缓存解码/模糊结果。
 * 面板、全屏、长按选项框共用同一份位图，反复打开不用重新解码，首帧就能直接挂上去。
 */
final class WallpaperSource {

    static final String PREF = "freeze_list";
    /** 长按选项框是否跟随自定义壁纸做底。 */
    static final String KEY_SHEET_WALLPAPER = "sheet_wallpaper_bg";

    /** 半屏与全屏各自一套壁纸/模糊缓存，互不顶替、互不串用。 */
    static final int MODE_PANEL = 0;
    static final int MODE_FULL = 1;
    private static final int MODES = 2;

    /** 对应形态的壁纸配置键（全屏的键没有 panel_ 前缀）。 */
    private static final String[] KEY_URI = {"panel_wallpaper_uri", "wallpaper_uri"};
    private static final String[] KEY_BLUR = {"panel_wallpaper_blur", "wallpaper_blur"};

    /** 快速模糊的降采样上限（像素）：首帧先挂这张，几十毫秒内算完，绝不露清晰原图。 */
    private static final int QUICK_MAX = 420;

    private static final String[] srcUri = new String[MODES];
    private static final Bitmap[] srcBitmap = new Bitmap[MODES];
    private static final String[] blurKey = new String[MODES];
    private static final Bitmap[] blurBitmap = new Bitmap[MODES];
    /** 降采样快速模糊结果，同样按形态分槽，不与全分辨率结果混用。 */
    private static final String[] quickKey = new String[MODES];
    private static final Bitmap[] quickBitmap = new Bitmap[MODES];
    /** 降采样后的原图（拖动滑块时反复用，避免每次都重新缩放）。 */
    private static final String[] quickSrcKey = new String[MODES];
    private static final Bitmap[] quickSrcBitmap = new Bitmap[MODES];

    private WallpaperSource() {
    }

    /** 按目标视图尺寸解码（屏幕宽高）：只按 2 的幂降采样到不小于目标，不做额外缩放或模糊。 */
    static synchronized Bitmap decode(Context c, String uriString, int mode) {
        if (uriString == null || uriString.isEmpty()) {
            return null;
        }
        int m = mode == MODE_FULL ? MODE_FULL : MODE_PANEL;
        if (uriString.equals(srcUri[m]) && srcBitmap[m] != null && !srcBitmap[m].isRecycled()) {
            return srcBitmap[m];
        }
        Bitmap bmp = decodeFile(c, Uri.parse(uriString));
        if (bmp == null) {
            return null;
        }
        // 不在这里 recycle 旧图：面板/全屏的 WallpaperDrawable 可能还持有同一个 Bitmap，
        // 提前回收会让那边画出空白。只换引用，旧图交由 GC 回收。
        srcUri[m] = uriString;
        srcBitmap[m] = bmp;
        blurKey[m] = null;
        blurBitmap[m] = null;
        quickKey[m] = null;
        quickBitmap[m] = null;
        quickSrcKey[m] = null;
        quickSrcBitmap[m] = null;
        return bmp;
    }

    /** 模糊结果同样按形态缓存：同一张图同一个档位只算一次，两个模式各算各的。 */
    static synchronized Bitmap blurred(Context c, String uriString, Bitmap src, int pct, int mode) {
        if (src == null) {
            return null;
        }
        int p = Math.max(0, Math.min(100, pct));
        if (p <= 0) {
            return src;
        }
        int m = mode == MODE_FULL ? MODE_FULL : MODE_PANEL;
        String key = m + "#" + uriString + "#" + p;
        if (key.equals(blurKey[m]) && blurBitmap[m] != null && !blurBitmap[m].isRecycled()) {
            return blurBitmap[m];
        }
        Bitmap out = softBlur(src, p);
        blurKey[m] = key;
        blurBitmap[m] = out;
        return out;
    }

    /**
     * 长按选项框要用的底图：按形态取各自那张（半屏取半屏壁纸/模糊度，全屏取全屏壁纸/模糊度）。
     */
    static Bitmap sheetBitmap(Context c, int mode) {
        int m = mode == MODE_FULL ? MODE_FULL : MODE_PANEL;
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String uri = sp.getString(KEY_URI[m], null);
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        Bitmap src = decode(c, uri, m);
        if (src == null) {
            return null;
        }
        return blurred(c, uri, src,
                Math.max(0, Math.min(100, sp.getInt(KEY_BLUR[m], 0))), m);
    }

    /**
     * 降采样后的快速模糊：把原图缩到短边约 420px 再跑模糊，耗时几十毫秒，
     * 用来在首帧先顶住模糊态（全屏打开、拖动滑块时），之后再换成全分辨率结果。
     * 返回 null 以外的结果都已经是模糊图，pct 为 0 时才返回原图。
     */
    static synchronized Bitmap quickBlur(Context c, String uriString, Bitmap src, int pct, int mode) {
        if (src == null) {
            return null;
        }
        int p = Math.max(0, Math.min(100, pct));
        if (p <= 0) {
            return src;
        }
        int m = mode == MODE_FULL ? MODE_FULL : MODE_PANEL;
        String key = "q" + m + "#" + uriString + "#" + p;
        if (key.equals(quickKey[m]) && quickBitmap[m] != null && !quickBitmap[m].isRecycled()) {
            return quickBitmap[m];
        }
        int max = Math.max(src.getWidth(), src.getHeight());
        int sample = Math.max(1, max / QUICK_MAX);
        String srcKey = m + "#" + uriString;
        Bitmap small;
        if (sample <= 1) {
            small = src;
        } else if (srcKey.equals(quickSrcKey[m]) && quickSrcBitmap[m] != null
                && !quickSrcBitmap[m].isRecycled()) {
            small = quickSrcBitmap[m];
        } else {
            int w = Math.max(1, src.getWidth() / sample);
            int h = Math.max(1, src.getHeight() / sample);
            small = Bitmap.createScaledBitmap(src, w, h, true);
            quickSrcKey[m] = srcKey;
            quickSrcBitmap[m] = small;
        }
        Bitmap out = softBlur(small, p);
        quickKey[m] = key;
        quickBitmap[m] = out;
        return out;
    }

    /** 清除某个形态的自定义壁纸时，只放掉那一套缓存（只断引用，交给 GC）。 */
    static synchronized void drop(int mode) {
        int m = mode == MODE_FULL ? MODE_FULL : MODE_PANEL;
        srcUri[m] = null;
        srcBitmap[m] = null;
        blurKey[m] = null;
        blurBitmap[m] = null;
        quickKey[m] = null;
        quickBitmap[m] = null;
        quickSrcKey[m] = null;
        quickSrcBitmap[m] = null;
    }

    /** 全部放掉。 */
    static synchronized void drop() {
        for (int m = 0; m < MODES; m++) {
            drop(m);
        }
    }

    private static Bitmap decodeFile(Context c, Uri uri) {
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int targetW = Math.max(1, dm.widthPixels);
            int targetH = Math.max(1, dm.heightPixels);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            // 只降采样到「不小于目标」为止，避免解码出来再放大造成糊
            while (bounds.outWidth / (sample * 2) >= targetW
                    && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inScaled = false;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 真模糊：横向 + 纵向可分离盒式卷积做两轮，近似高斯，边缘平滑过渡、没有方块感。
     * 强度按短边换算成像素半径，最大档仍能认出图片。
     */
    private static Bitmap softBlur(Bitmap src, int pct) {
        int w = src.getWidth();
        int h = src.getHeight();
        int radius = Math.max(1, Math.round(Math.min(w, h) / 90f * pct / 100f * 2f));
        // 两块缓冲区来回倒（横 A→B、纵 B→A），结果再落到 Bitmap。
        // 原来每趟都 new int[w*h]，一张 1280×2772 的图一次模糊要临时分配 5 个 14MB 数组。
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < 2; i++) {
            boxPass(a, b, w, h, radius, true);
            boxPass(b, a, w, h, radius, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    /** 一次可分离盒式模糊（窗口和滚动累加，复杂度 O(w*h)）。 */
    private static void boxPass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int div = r * 2 + 1;
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int sr = 0;
                int sg = 0;
                int sb = 0;
                for (int k = -r; k <= r; k++) {
                    int c = in[row + Math.min(w - 1, Math.max(0, k))];
                    sr += (c >> 16) & 0xff;
                    sg += (c >> 8) & 0xff;
                    sb += c & 0xff;
                }
                for (int x = 0; x < w; x++) {
                    out[row + x] = 0xff000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                    int add = in[row + Math.min(w - 1, x + r + 1)];
                    int sub = in[row + Math.max(0, x - r)];
                    sr += ((add >> 16) & 0xff) - ((sub >> 16) & 0xff);
                    sg += ((add >> 8) & 0xff) - ((sub >> 8) & 0xff);
                    sb += (add & 0xff) - (sub & 0xff);
                }
            }
            return;
        }
        for (int x = 0; x < w; x++) {
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[Math.min(h - 1, Math.max(0, k)) * w + x];
                sr += (c >> 16) & 0xff;
                sg += (c >> 8) & 0xff;
                sb += c & 0xff;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xff000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[Math.min(h - 1, y + r + 1) * w + x];
                int sub = in[Math.max(0, y - r) * w + x];
                sr += ((add >> 16) & 0xff) - ((sub >> 16) & 0xff);
                sg += ((add >> 8) & 0xff) - ((sub >> 8) & 0xff);
                sb += (add & 0xff) - (sub & 0xff);
            }
        }
    }
}
