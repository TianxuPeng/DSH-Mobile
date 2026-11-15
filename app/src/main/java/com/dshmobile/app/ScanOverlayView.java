package com.dshmobile.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Full-screen scanner mask: a dimmed camera preview with a clear square
 * cutout, four corner brackets and a moving scan line.
 */
public final class ScanOverlayView extends View {

    private static final int SCRIM_COLOR = 0xB8070D14;

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scrimPath = new Path();
    private final Path cornerPath = new Path();
    private final RectF frameRect = new RectF();
    private final float density;

    private ValueAnimator animator;
    private float scanProgress;
    private boolean scanning = true;

    public ScanOverlayView(Context context) {
        this(context, null);
    }

    public ScanOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScanOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;

        scrimPaint.setColor(SCRIM_COLOR);
        scrimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(0x33FFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));

        cornerPaint.setColor(0xFF5EEAD4);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(dp(3.5f));
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setStrokeJoin(Paint.Join.ROUND);

        scanLinePaint.setStyle(Paint.Style.FILL);
        scanGlowPaint.setStyle(Paint.Style.FILL);

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private float dp(float value) {
        return value * density;
    }

    public void setScanning(boolean scanning) {
        if (this.scanning == scanning) {
            return;
        }
        this.scanning = scanning;
        if (scanning) {
            startAnimationIfVisible();
        } else {
            stopAnimator();
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (scanning) {
            startAnimationIfVisible();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGeometry(w, h);
    }

    private void updateGeometry(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float size = Math.min(width - dp(72f), Math.min(dp(300f), height * 0.38f));
        size = Math.max(size, dp(180f));
        float centerX = width / 2f;
        float centerY = height * 0.455f;
        frameRect.set(
                centerX - size / 2f,
                centerY - size / 2f,
                centerX + size / 2f,
                centerY + size / 2f);

        LinearGradient shader = new LinearGradient(
                frameRect.left + dp(18f),
                0f,
                frameRect.right - dp(18f),
                0f,
                new int[]{0x005EEAD4, 0xFF5EEAD4, 0x005EEAD4},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        scanLinePaint.setShader(shader);
        scanGlowPaint.setShader(shader);
        scanGlowPaint.setAlpha(70);

        invalidate();
    }

    private void startAnimationIfVisible() {
        if (!isAttachedToWindow() || getVisibility() != VISIBLE || animator != null) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2200L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            scanProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frameRect.isEmpty()) {
            canvas.drawColor(SCRIM_COLOR);
            return;
        }

        float radius = dp(30f);
        scrimPath.reset();
        scrimPath.setFillType(Path.FillType.EVEN_ODD);
        scrimPath.addRect(0f, 0f, getWidth(), getHeight(), Path.Direction.CW);
        scrimPath.addRoundRect(frameRect, radius, radius, Path.Direction.CCW);
        canvas.drawPath(scrimPath, scrimPaint);

        canvas.drawRoundRect(frameRect, radius, radius, borderPaint);
        drawCorners(canvas);

        if (scanning) {
            float travelTop = frameRect.top + dp(20f);
            float travelBottom = frameRect.bottom - dp(20f);
            float y = travelTop + (travelBottom - travelTop) * scanProgress;
            float left = frameRect.left + dp(18f);
            float right = frameRect.right - dp(18f);

            canvas.drawRoundRect(
                    left, y - dp(4f), right, y + dp(4f),
                    dp(4f), dp(4f), scanGlowPaint);
            canvas.drawRoundRect(
                    left, y - dp(0.75f), right, y + dp(0.75f),
                    dp(1f), dp(1f), scanLinePaint);
        }
    }

    private void drawCorners(Canvas canvas) {
        float inset = dp(1.75f);
        float arm = dp(30f);
        float radius = dp(24f);
        float left = frameRect.left + inset;
        float top = frameRect.top + inset;
        float right = frameRect.right - inset;
        float bottom = frameRect.bottom - inset;

        cornerPath.reset();
        // Top-left.
        cornerPath.moveTo(left, top + arm);
        cornerPath.lineTo(left, top + radius);
        cornerPath.quadTo(left, top, left + radius, top);
        cornerPath.lineTo(left + arm, top);

        // Top-right.
        cornerPath.moveTo(right - arm, top);
        cornerPath.lineTo(right - radius, top);
        cornerPath.quadTo(right, top, right, top + radius);
        cornerPath.lineTo(right, top + arm);

        // Bottom-left.
        cornerPath.moveTo(left, bottom - arm);
        cornerPath.lineTo(left, bottom - radius);
        cornerPath.quadTo(left, bottom, left + radius, bottom);
        cornerPath.lineTo(left + arm, bottom);

        // Bottom-right.
        cornerPath.moveTo(right - arm, bottom);
        cornerPath.lineTo(right - radius, bottom);
        cornerPath.quadTo(right, bottom, right, bottom - radius);
        cornerPath.lineTo(right, bottom - arm);

        canvas.drawPath(cornerPath, cornerPaint);
    }
}
