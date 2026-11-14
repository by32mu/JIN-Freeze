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
    /** 长按选项框是否跟随半屏壁纸做底。 */
    static final String KEY_SHEET_WALLPAPER = "sheet_wallpaper_bg";

    private static String srcUri;
    private static Bitmap srcBitmap;
    private static String blurKey;
    private static Bitmap blurBitmap;

    private WallpaperSource() {
    }

    /** 按目标视图尺寸解码（屏幕宽高）：只按 2 的幂降采样到不小于目标，不做额外缩放或模糊。 */
    static synchronized Bitmap decode(Context c, String uriString) {
        if (uriString == null || uriString.isEmpty()) {
            return null;
        }
        if (uriString.equals(srcUri) && srcBitmap != null && !srcBitmap.isRecycled()) {
            return srcBitmap;
        }
        Bitmap bmp = decodeFile(c, Uri.parse(uriString));
        if (bmp == null) {
            return null;
        }
        recycleQuietly(srcBitmap);
        recycleQuietly(blurBitmap);
        srcUri = uriString;
        srcBitmap = bmp;
        blurKey = null;
        blurBitmap = null;
        return bmp;
    }

    /** 模糊结果同样缓存：同一张图同一个档位只算一次。 */
    static synchronized Bitmap blurred(Context c, String uriString, Bitmap src, int pct) {
        if (src == null) {
            return null;
        }
        int p = Math.max(0, Math.min(100, pct));
        if (p <= 0) {
            return src;
        }
        String key = uriString + "#" + p;
        if (key.equals(blurKey) && blurBitmap != null && !blurBitmap.isRecycled()) {
            return blurBitmap;
        }
        Bitmap out = softBlur(src, p);
        recycleQuietly(blurBitmap);
        blurKey = key;
        blurBitmap = out;
        return out;
    }

    /** 长按选项框要用的底图：固定取半屏那张（含它自己的模糊度）。 */
    static Bitmap panelSheetBitmap(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String uri = sp.getString("panel_wallpaper_uri", null);
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        Bitmap src = decode(c, uri);
        if (src == null) {
            return null;
        }
        return blurred(c, uri, src,
                Math.max(0, Math.min(100, sp.getInt("panel_wallpaper_blur", 0))));
    }

    /** 清除自定义壁纸时把缓存一起放掉。 */
    static synchronized void drop() {
        recycleQuietly(srcBitmap);
        if (blurBitmap != srcBitmap) {
            recycleQuietly(blurBitmap);
        }
        srcUri = null;
        srcBitmap = null;
        blurKey = null;
        blurBitmap = null;
    }

    private static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            b.recycle();
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
        int[] buf = new int[w * h];
        src.getPixels(buf, 0, w, 0, 0, w, h);
        int radius = Math.max(1, Math.round(Math.min(w, h) / 90f * pct / 100f * 2f));
        for (int i = 0; i < 2; i++) {
            buf = boxPass(buf, w, h, radius, true);
            buf = boxPass(buf, w, h, radius, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(buf, 0, w, 0, 0, w, h);
        return out;
    }

    /** 一次可分离盒式模糊（窗口和滚动累加，复杂度 O(w*h)）。 */
    private static int[] boxPass(int[] in, int w, int h, int r, boolean horizontal) {
        int[] out = new int[in.length];
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
            return out;
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
        return out;
    }
}
