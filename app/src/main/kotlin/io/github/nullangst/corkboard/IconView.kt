/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** An icon with an optional label under it. */
class IconView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var label: String = ""
        set(value) {
            field = value
            contentDescription = value
            invalidate()
        }
    var showLabel = true
    var iconSize = 0
    var greyed = false
        set(value) {
            field = value
            iconPaint.colorFilter = if (value) greyFilter else null
            iconPaint.alpha = if (value) 150 else 255
            invalidate()
        }

    /**
     * On the home screen and dock, only the icon and its label take touches. The rest of the cell
     * belongs to the home screen, so a long-press between icons opens the home menu, like Pixel.
     */
    var tightTouch = false

    /** Apps behind this icon, as "package#user", for notification dots. Empty: never a dot. */
    var dotKeys: List<String> = emptyList()

    /** Draws the notification dot. Set by [refreshDot]. */
    var dot = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Fixed dot color, or 0 to take one from the icon. */
    var dotColor = 0

    /** For icons with no vivid color to take (black and white logos). */
    var dotFallback = 0xFF888888.toInt()

    /** Highlight while something is held over it to make or add to a folder. */
    var hot = false
        set(value) {
            if (field == value) return
            field = value
            animate().scaleX(if (value) 1.12f else 1f).scaleY(if (value) 1.12f else 1f).setDuration(120).start()
        }

    init {
        // Corkboard plays its own lift haptic; the framework's long-press buzz would cut it off.
        isHapticFeedbackEnabled = false
    }

    /** Where the last touch went down, for the drag shadow's touch point. */
    var downX = 0f
    var downY = 0f

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    fun homeStyle() {
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.setShadowLayer(resources.displayMetrics.density * 2, 0f, resources.displayMetrics.density, 0xAA000000.toInt())
    }

    fun panelStyle(color: Int) {
        textPaint.color = color
        textPaint.clearShadowLayer()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Not ours: the home screen behind gets the whole gesture.
            if (!hits(event.x, event.y)) return false
            downX = event.x
            downY = event.y
        }
        return super.onTouchEvent(event)
    }

    /** True when a point (view coordinates) is on the icon or its label, with a small margin. */
    fun hits(x: Float, y: Float): Boolean {
        if (!tightTouch) return true
        val m = 6 * resources.displayMetrics.density
        val size = drawSize()
        val top = iconTop()
        val labelW = if (showLabel && label.isNotEmpty()) min(width.toFloat(), textPaint.measureText(label)) else 0f
        val halfW = max(size, labelW) / 2f + m
        val bottom = top + size + textSpace()
        return x >= width / 2f - halfW && x <= width / 2f + halfW && y >= top - m && y <= bottom + m
    }

    /** Shows or hides the dot from the current notification counts. */
    fun refreshDot() {
        dot = dotKeys.isNotEmpty() && Dots.any(dotKeys)
    }

    private fun textSpace() = if (showLabel) textPaint.textSize * 1.4f else 0f

    /** Drawn icon size: the wanted size, shrunk if the cell is too small. */
    fun drawSize(): Float = max(1f, min(iconSize.toFloat(), min(width * 0.86f, height - textSpace() - 4 * resources.displayMetrics.density)))

    fun iconTop(): Float = max(0f, (height - drawSize() - textSpace()) / 2f)

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        val size = drawSize()
        val top = iconTop()
        if (b != null) {
            val l = (width - size) / 2f
            dst.set(l, top, l + size, top + size)
            canvas.drawBitmap(b, null, dst, iconPaint)
        }
        if (dot && b != null) drawDot(canvas, (width - size) / 2f + size, top, size, b)
        if (showLabel && label.isNotEmpty()) {
            val pad = resources.displayMetrics.density * 4
            val text = TextUtils.ellipsize(label, textPaint, width - 2 * pad, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, width / 2f, top + size + textPaint.textSize * 1.15f, textPaint)
        }
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Pixel's dot: about a fifth of the icon, at its top-right, with a thin dark rim to stand out. */
    private fun drawDot(canvas: Canvas, right: Float, top: Float, size: Float, b: Bitmap) {
        val r = size * 0.11f
        val cx = right - r * 0.9f
        val cy = top + r * 0.9f
        dotPaint.color = 0x40000000
        canvas.drawCircle(cx, cy, r + resources.displayMetrics.density, dotPaint)
        dotPaint.color = when {
            dotColor != 0 -> dotColor
            else -> Dots.colorOf(b).takeIf { it != 0 } ?: dotFallback
        }
        canvas.drawCircle(cx, cy, r, dotPaint)
    }

    private companion object {
        val greyFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
}
