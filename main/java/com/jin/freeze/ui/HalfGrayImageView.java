package com.jin.freeze.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * 冻结图标「灰化」专用 ImageView。
 *
 * 复用同一份缓存的彩色 Bitmap：彩色半边按原色绘制，灰化半边只画灰度版本，
 * 两边各自裁剪、互不叠加，灰色区域不会透出下面的彩色。
 * 样式 1..9 对应不同方向的灰化半边与全灰，见 {@link #setSplitStyle(int)}。
 */
public class HalfGrayImageView extends ImageView {

    /** 左灰右彩（老版本默认样式）。 */
    public static final int SPLIT_LEFT = 1;
    public static final int SPLIT_RIGHT = 2;
    public static final int SPLIT_TOP = 3;
    public static final int SPLIT_BOTTOM = 4;
    /** 左上三角灰。 */
    public static final int SPLIT_TOP_LEFT = 5;
    /** 右上三角灰。 */
    public static final int SPLIT_TOP_RIGHT = 6;
    /** 左下三角灰。 */
    public static final int SPLIT_BOTTOM_LEFT = 7;
    /** 右下三角灰。 */
    public static final int SPLIT_BOTTOM_RIGHT = 8;
    /** 全灰。 */
    public static final int SPLIT_FULL = 9;

    /** 灰度滤镜（含透明度），由调用方统一传入，避免每个格子各建一份。 */
    private ColorFilter grayFilter;
    /** 灰化半边样式：0 表示不灰化，按普通图标整张绘制。 */
    private int splitStyle;
    private final Path grayClip = new Path();
    private final Path colorClip = new Path();

    public HalfGrayImageView(Context c) {
        super(c);
    }

    public HalfGrayImageView(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    public HalfGrayImageView(Context c, AttributeSet attrs, int defStyleAttr) {
        super(c, attrs, defStyleAttr);
    }

    public void setGrayFilter(ColorFilter filter) {
        grayFilter = filter;
        invalidate();
    }

    /** 0=不灰化，1..9=按方向灰化半边或全灰（见本类 SPLIT_* 常量）。 */
    public void setSplitStyle(int style) {
        if (splitStyle == style) {
            return;
        }
        splitStyle = style;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = getDrawable();
        int w = getWidth();
        int h = getHeight();
        if (splitStyle <= 0 || grayFilter == null || d == null || w <= 0 || h <= 0) {
            super.onDraw(canvas);
            return;
        }
        buildClips(w, h);
        // 彩色半边：只在这一半画原色
        canvas.save();
        canvas.clipPath(colorClip);
        d.setColorFilter(null);
        super.onDraw(canvas);
        canvas.restore();
        // 灰化半边：只画灰度，不与彩色叠加，灰色是干净的灰
        canvas.save();
        canvas.clipPath(grayClip);
        d.setColorFilter(grayFilter);
        super.onDraw(canvas);
        d.setColorFilter(null);
        canvas.restore();
    }

    /** 按样式拆出灰化半边和彩色半边的裁剪区域。 */
    private void buildClips(int w, int h) {
        grayClip.reset();
        colorClip.reset();
        float mx = w / 2f;
        float my = h / 2f;
        switch (splitStyle) {
            case SPLIT_LEFT:
                grayClip.addRect(0, 0, mx, h, Path.Direction.CW);
                colorClip.addRect(mx, 0, w, h, Path.Direction.CW);
                break;
            case SPLIT_RIGHT:
                grayClip.addRect(mx, 0, w, h, Path.Direction.CW);
                colorClip.addRect(0, 0, mx, h, Path.Direction.CW);
                break;
            case SPLIT_TOP:
                grayClip.addRect(0, 0, w, my, Path.Direction.CW);
                colorClip.addRect(0, my, w, h, Path.Direction.CW);
                break;
            case SPLIT_BOTTOM:
                grayClip.addRect(0, my, w, h, Path.Direction.CW);
                colorClip.addRect(0, 0, w, my, Path.Direction.CW);
                break;
            case SPLIT_TOP_LEFT:
                triangle(grayClip, 0, 0, w, 0, 0, h);
                triangle(colorClip, w, 0, w, h, 0, h);
                break;
            case SPLIT_TOP_RIGHT:
                triangle(grayClip, 0, 0, w, 0, w, h);
                triangle(colorClip, 0, 0, 0, h, w, h);
                break;
            case SPLIT_BOTTOM_LEFT:
                triangle(grayClip, 0, 0, 0, h, w, h);
                triangle(colorClip, 0, 0, w, 0, w, h);
                break;
            case SPLIT_BOTTOM_RIGHT:
                triangle(grayClip, w, 0, w, h, 0, h);
                triangle(colorClip, 0, 0, w, 0, 0, h);
                break;
            case SPLIT_FULL:
                grayClip.addRect(0, 0, w, h, Path.Direction.CW);
                break;
            default:
                break;
        }
    }

    private static void triangle(Path p, float x1, float y1, float x2, float y2,
                                 float x3, float y3) {
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        p.close();
    }
}
