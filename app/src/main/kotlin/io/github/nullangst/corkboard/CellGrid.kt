/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
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
            if (v.visibility != VISIBLE || px < v.left || px >= v.right || py < v.top || py >= v.bottom) continue
            // An icon only counts where it is drawn: the gaps around it are the home screen's.
            if (v is IconView && !v.hits(px - v.left, py - v.top)) continue
            return v
        }
        return null
    }

    /** Live reorder preview: moves the given items' views to new cells. Anything not listed goes home. */
    fun preview(moves: Map<Item, Cell>) {
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
