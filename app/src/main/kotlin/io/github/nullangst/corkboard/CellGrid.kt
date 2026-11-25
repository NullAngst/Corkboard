/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 *
 * The reorder solver below is a trimmed-down port of the reorder logic in AOSP Launcher3's
 * CellLayout (pushViewsToTempLocation / rearrangementExists),
 * Copyright (C) 2008 The Android Open Source Project, licensed under the Apache License 2.0.
 * Rewritten in Kotlin for a framework-only stack; only the push-then-relocate idea is kept.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One page of cells. Children are placed by column, row and span. Also used for the dock and folder panels. */
class CellGrid(ctx: Context, var cols: Int, var rows: Int) : ViewGroup(ctx) {

    class LP(val item: Item?, var x: Int, var y: Int, var w: Int, var h: Int) :
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    /** Fixed cell height in pixels, or 0 to split the available height evenly. */
    var fixedCellHeight = 0

    private var outline: RectF? = null
    private var outlineBad = false
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    /** Old positions of children, captured before a reorder so the next layout slides them over. */
    private val slideFrom = HashMap<View, Point>()

    fun animateNextLayout() {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            slideFrom[v] = Point(v.left + v.translationX.roundToInt(), v.top + v.translationY.roundToInt())
        }
        requestLayout()
    }

    val cellW: Float get() = (width - paddingLeft - paddingRight) / cols.toFloat()
    val cellH: Float
        get() = if (fixedCellHeight > 0) fixedCellHeight.toFloat() else (height - paddingTop - paddingBottom) / rows.toFloat()

    fun add(view: View, item: Item?, x: Int = item?.x ?: 0, y: Int = item?.y ?: 0, w: Int = item?.w ?: 1, h: Int = item?.h ?: 1) {
        addView(view, LP(item, x, y, w, h))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (fixedCellHeight > 0) {
            fixedCellHeight * rows + paddingTop + paddingBottom
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        setMeasuredDimension(w, h)
        val cw = (w - paddingLeft - paddingRight) / cols.toFloat()
        val ch = if (fixedCellHeight > 0) fixedCellHeight.toFloat() else (h - paddingTop - paddingBottom) / rows.toFloat()
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as LP
            v.measure(
                MeasureSpec.makeMeasureSpec((cw * lp.w).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((ch * lp.h).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as LP
            val left = (paddingLeft + lp.x * cellW).roundToInt()
            val top = (paddingTop + lp.y * cellH).roundToInt()
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
            val from = slideFrom[v] ?: continue
            if (from.x != left || from.y != top) {
                v.animate().cancel()
                v.translationX = (from.x - left).toFloat()
                v.translationY = (from.y - top).toFloat()
                v.animate().translationX(0f).translationY(0f).setDuration(160).start()
            }
        }
        slideFrom.clear()
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LP

    override fun onDraw(canvas: Canvas) {
        val o = outline ?: return
        val color = if (outlineBad) 0xFFE53935.toInt() else 0xFFFFFFFF.toInt()
        val r = resources.displayMetrics.density * 16
        fillPaint.color = (color and 0x00FFFFFF) or 0x33000000
        outlinePaint.color = color
        canvas.drawRoundRect(o, r, r, fillPaint)
        canvas.drawRoundRect(o, r, r, outlinePaint)
    }

    fun cellRect(x: Int, y: Int, w: Int, h: Int): RectF {
        val inset = resources.displayMetrics.density * 4
        return RectF(
            paddingLeft + x * cellW + inset, paddingTop + y * cellH + inset,
            paddingLeft + (x + w) * cellW - inset, paddingTop + (y + h) * cellH - inset,
        )
    }

    fun showOutline(x: Int, y: Int, w: Int, h: Int, bad: Boolean) {
        outline = cellRect(x, y, w, h)
        outlineBad = bad
        invalidate()
    }

    fun clearOutline() {
        if (outline == null) return
        outline = null
        invalidate()
    }

    /** The cell under a point, as an integer column and row. */
    fun cellUnder(px: Float, py: Float): Point = Point(
        ((px - paddingLeft) / cellW).toInt().coerceIn(0, cols - 1),
        ((py - paddingTop) / cellH).toInt().coerceIn(0, rows - 1),
    )

    /** Top-left cell for a w x h span centered under a point. */
    fun spanAt(px: Float, py: Float, w: Int, h: Int): Point = Point(
        ((px - paddingLeft) / cellW - w / 2f).roundToInt().coerceIn(0, max(0, cols - w)),
        ((py - paddingTop) / cellH - h / 2f).roundToInt().coerceIn(0, max(0, rows - h)),
    )

    /** True when the point is in the inner half of the given cell. */
    fun inInnerHalf(px: Float, py: Float, x: Int, y: Int): Boolean {
        val cx = paddingLeft + (x + 0.5f) * cellW
        val cy = paddingTop + (y + 0.5f) * cellH
        return abs(px - cx) < cellW / 4 && abs(py - cy) < cellH / 4
    }

    fun viewFor(item: Item): View? {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if ((v.layoutParams as LP).item === item) return v
        }
        return null
    }

    fun childAt(px: Float, py: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val v = getChildAt(i)
            if (v.visibility == VISIBLE && px >= v.left && px < v.right && py >= v.top && py < v.bottom) return v
        }
        return null
    }

    /** Live reorder preview: moves the given items' views to new cells. Anything not listed goes home. */
    fun preview(moves: Map<Item, Point>) {
        if (childCount == 0) return
        var dirty = false
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as LP
            val item = lp.item ?: continue
            val p = moves[item]
            val nx = p?.x ?: item.x
            val ny = p?.y ?: item.y
            if (lp.x != nx || lp.y != ny) {
                lp.x = nx
                lp.y = ny
                dirty = true
            }
        }
        if (dirty) animateNextLayout()
    }
}

/** Push-then-relocate solver for dropping on occupied cells. */
object Reorder {
    private class Box(val item: Item) {
        val x get() = item.x
        val y get() = item.y
        val w get() = item.w
        val h get() = item.h
    }

    private fun overlap(ax: Int, ay: Int, aw: Int, ah: Int, bx: Int, by: Int, bw: Int, bh: Int) =
        ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah

    /**
     * Finds new cells for the items in the way of a w x h drop at (x, y). Returns an empty map when
     * nothing is in the way, the moves when room can be made, or null when it cannot (cells go red).
     */
    fun solve(cols: Int, rows: Int, others: List<Item>, x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float): Map<Item, Point>? {
        if (x < 0 || y < 0 || x + w > cols || y + h > rows) return null
        val boxes = others.map { Box(it) }
        val blockers = boxes.filter { overlap(it.x, it.y, it.w, it.h, x, y, w, h) }
        if (blockers.isEmpty()) return emptyMap()
        for (d in directions(dx, dy)) {
            push(cols, rows, boxes, blockers, x, y, w, h, d)?.let { return it }
        }
        return relocate(cols, rows, boxes, blockers, x, y, w, h)
    }

    private fun directions(dx: Float, dy: Float): List<Point> {
        val sx = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val sy = if (dy > 0) 1 else if (dy < 0) -1 else 0
        val out = ArrayList<Point>()
        val horizontalFirst = abs(dx) >= abs(dy)
        val primary = if (horizontalFirst) listOf(Point(sx, 0), Point(0, sy)) else listOf(Point(0, sy), Point(sx, 0))
        for (p in primary) if (p.x != 0 || p.y != 0) out.add(p)
        for (p in listOf(Point(-sx, 0), Point(0, -sy), Point(1, 0), Point(-1, 0), Point(0, 1), Point(0, -1))) {
            if ((p.x != 0 || p.y != 0) && out.none { it.x == p.x && it.y == p.y }) out.add(p)
        }
        return out
    }

    /** Step 1: push the blockers along [d] one cell at a time, carrying anything they bump into. */
    private fun push(cols: Int, rows: Int, all: List<Box>, blockers: List<Box>, x: Int, y: Int, w: Int, h: Int, d: Point): Map<Item, Point>? {
        val cluster = LinkedHashSet(blockers)
        val limit = max(cols, rows)
        restart@ while (true) {
            for (dist in 1..limit) {
                val moved = cluster.associateWith { Point(it.x + d.x * dist, it.y + d.y * dist) }
                for ((b, p) in moved) {
                    if (p.x < 0 || p.y < 0 || p.x + b.w > cols || p.y + b.h > rows) return null
                }
                val hit = all.filter { o ->
                    o !in cluster && moved.any { (b, p) -> overlap(p.x, p.y, b.w, b.h, o.x, o.y, o.w, o.h) }
                }
                if (hit.isNotEmpty()) {
                    cluster.addAll(hit)
                    continue@restart
                }
                if (moved.none { (b, p) -> overlap(p.x, p.y, b.w, b.h, x, y, w, h) }) {
                    return moved.entries.associate { (b, p) -> b.item to p }
                }
            }
            return null
        }
    }

    /** Step 2: move each blocker to the nearest free spot that fits its span. */
    private fun relocate(cols: Int, rows: Int, all: List<Box>, blockers: List<Box>, x: Int, y: Int, w: Int, h: Int): Map<Item, Point>? {
        val g = Array(rows) { BooleanArray(cols) }
        fun mark(bx: Int, by: Int, bw: Int, bh: Int) {
            for (yy in by until min(rows, by + bh)) for (xx in bx until min(cols, bx + bw)) if (yy >= 0 && xx >= 0) g[yy][xx] = true
        }
        for (b in all) if (b !in blockers) mark(b.x, b.y, b.w, b.h)
        mark(x, y, w, h)
        val out = HashMap<Item, Point>()
        for (b in blockers.sortedByDescending { it.w * it.h }) {
            var best: Point? = null
            var bestD = Int.MAX_VALUE
            for (yy in 0..rows - b.h) for (xx in 0..cols - b.w) {
                if (!Layout.fits(g, xx, yy, b.w, b.h)) continue
                val dd = (xx - b.x) * (xx - b.x) + (yy - b.y) * (yy - b.y)
                if (dd < bestD) {
                    bestD = dd
                    best = Point(xx, yy)
                }
            }
            val p = best ?: return null
            mark(p.x, p.y, b.w, b.h)
            out[b.item] = p
        }
        return out
    }
}

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
            downX = event.x
            downY = event.y
        }
        return super.onTouchEvent(event)
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
        if (showLabel && label.isNotEmpty()) {
            val pad = resources.displayMetrics.density * 4
            val text = TextUtils.ellipsize(label, textPaint, width - 2 * pad, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, width / 2f, top + size + textPaint.textSize * 1.15f, textPaint)
        }
    }

    private companion object {
        val greyFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
}

/** Page dots under the pages. */
class PageDots(ctx: Context) : View(ctx) {
    var count = 1
        set(value) {
            field = value
            invalidate()
        }
    var current = 0
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val d = resources.displayMetrics.density
        val r = 3 * d
        val gap = 12 * d
        val start = width / 2f - (count - 1) * gap / 2f
        for (i in 0 until count) {
            paint.color = if (i == current) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
            canvas.drawCircle(start + i * gap, height / 2f, r, paint)
        }
    }
}
