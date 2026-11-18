package com.jin.freeze.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * 冻结图标「一半灰度一半彩色」专用 ImageView。
 *
 * 复用同一份缓存的彩色 Bitmap，只在绘制时把左半边临时套上灰度滤镜、右半边保持原色，
 * 不额外解码、不额外占内存，也不生成第二张灰色图片。
 */
public class HalfGrayImageView extends ImageView {

    /** 左半边用的灰度滤镜（含透明度），由调用方统一传入，避免每个格子各建一份。 */
    private ColorFilter grayFilter;
    /** 当前是否按半灰半彩绘制；false 时走 ImageView 默认绘制。 */
    private boolean halfGray;

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

    public void setHalfGray(boolean on) {
        if (halfGray == on) {
            return;
        }
        halfGray = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = getDrawable();
        int w = getWidth();
        int h = getHeight();
        if (!halfGray || grayFilter == null || d == null || w <= 0 || h <= 0) {
            super.onDraw(canvas);
            return;
        }
        int mid = w / 2;
        // 左半：只在这块裁剪区域里临时套灰度滤镜，画完立刻撤掉，避免影响右半和复用
        canvas.save();
        canvas.clipRect(0, 0, mid, h);
        d.setColorFilter(grayFilter);
        super.onDraw(canvas);
        d.setColorFilter(null);
        canvas.restore();
        // 右半：保持原色
        canvas.save();
        canvas.clipRect(mid, 0, w, h);
        super.onDraw(canvas);
        canvas.restore();
    }
}
