/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Pages side by side, swiped horizontally. */
class Workspace(ctx: Context) : ViewGroup(ctx) {
    val pages = ArrayList<CellGrid>()
    var current = 0
    var onPageChanged: ((Int) -> Unit)? = null
    private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
    private var dragStart = 0
    private val fling = 500 * resources.displayMetrics.density

    init {
        clipChildren = false
    }

    fun setPageCount(n: Int, cols: Int, rows: Int) {
        while (pages.size < n) {
            val g = CellGrid(context, cols, rows)
            val p = (8 * resources.displayMetrics.density).roundToInt()
            // An empty band on top, like Pixel's At a Glance row: the Remove bar lives there while dragging.
            g.setPadding(p, (TOP_BAND_DP * resources.displayMetrics.density).roundToInt(), p, 0)
            pages.add(g)
            addView(g)
        }
        while (pages.size > n) removeView(pages.removeAt(pages.size - 1))
        current = current.coerceIn(0, n - 1)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (p in pages) p.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for ((i, p) in pages.withIndex()) p.layout(i * w, 0, (i + 1) * w, b - t)
        if (scroller.isFinished) scrollTo(current * w, 0)
    }

    fun beginDrag() {
        scroller.forceFinished(true)
        dragStart = scrollX
    }

    fun dragTo(dx: Float) {
        val max = ((pages.size - 1) * width).toFloat()
        var sx = dragStart - dx
        if (sx < 0) sx /= 3f
        if (sx > max) sx = max + (sx - max) / 3f
        scrollTo(sx.roundToInt(), 0)
    }

    fun release(vx: Float) {
        val w = width.coerceAtLeast(1)
        var target = ((scrollX + w / 2f) / w).toInt()
        if (abs(vx) > fling) target = if (vx < 0) floor(scrollX / w.toFloat()).toInt() + 1 else floor(scrollX / w.toFloat()).toInt()
        snapTo(target.coerceIn(0, pages.size - 1))
    }

    fun snapTo(i: Int, animate: Boolean = true) {
        val old = current
        current = i.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val dx = current * width - scrollX
        if (animate && width > 0) {
            scroller.startScroll(scrollX, 0, dx, 0, 280)
            postInvalidateOnAnimation()
        } else {
            scroller.forceFinished(true)
            scrollTo(current * width, 0)
        }
        if (old != current) onPageChanged?.invoke(current)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }
}
