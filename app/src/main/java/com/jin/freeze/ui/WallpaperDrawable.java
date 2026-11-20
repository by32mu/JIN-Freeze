package com.jin.freeze.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 壁纸底图：自适应铺满（cover，居中，超出部分裁掉，不变形），可选顶部圆角。
 * 不做任何模糊——清晰度只取决于原图与按目标尺寸解码的结果。
 * alpha 由外层背景滑块控制，和原来的底板透明度是同一套语义。
 */
class WallpaperDrawable extends Drawable {

    private final Bitmap bitmap;
    private final float topRadius;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix matrix = new android.graphics.Matrix();
    private final Path clip = new Path();
    private final Rect boundsRect = new Rect();
    private final RectF boundsF = new RectF();
    private int alpha = 255;

    WallpaperDrawable(Bitmap bitmap, float topRadius) {
        this.bitmap = bitmap;
        this.topRadius = topRadius;
    }

    @Override
    public void draw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        copyBounds(boundsRect);
        if (boundsRect.isEmpty()) {
            return;
        }
        paint.setAlpha(alpha);
        int save = canvas.save();
        if (topRadius > 0) {
            boundsF.set(boundsRect);
            clip.reset();
            clip.addRoundRect(boundsF,
                    new float[]{topRadius, topRadius, topRadius, topRadius, 0, 0, 0, 0},
                    Path.Direction.CW);
            canvas.clipPath(clip);
        }
        // cover：短边铺满、长边居中裁掉
        float scale = Math.max((float) boundsRect.width() / bitmap.getWidth(),
                (float) boundsRect.height() / bitmap.getHeight());
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        float left = boundsRect.left + (boundsRect.width() - w) / 2f;
        float top = boundsRect.top + (boundsRect.height() - h) / 2f;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(left, top);
        canvas.drawBitmap(bitmap, matrix, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void getOutline(Outline outline) {
        copyBounds(boundsRect);
        if (topRadius > 0) {
            outline.setRoundRect(boundsRect, topRadius);
        } else {
            outline.setRect(boundsRect);
        }
    }
}
