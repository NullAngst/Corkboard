/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An open folder: a panel in the middle of the screen over a dimmed home screen. */
@SuppressLint("ViewConstructor")
class FolderPanel(private val act: HomeActivity, val folder: FolderItem) : FrameLayout(act) {
    private val pal = act.pal
    private val d = resources.displayMetrics.density
    private val cols = min(4, Store.layout.settings.columns)
    private val slotW = (84 * d).roundToInt()
    private val slotH = (IconRenderer.sizePx(act) + 40 * d).roundToInt()
    private val order = folder.apps.toMutableList()
    private val grid = CellGrid(act, cols, 1).apply { fixedCellHeight = slotH }
    private val scroll = ScrollView(act)
    private val card = LinearLayout(act)
    private val title = EditText(act)

    init {
        setBackgroundColor(pal.scrim)
        setOnClickListener { act.closeFolder() }

        card.orientation = LinearLayout.VERTICAL
        card.background = pal.card(act, pal.surface, 24f)
        card.elevation = 8 * d
        card.isClickable = true
        val pad = (16 * d).roundToInt()
        card.setPadding(pad, pad, pad, pad)

        title.apply {
            setText(folder.name)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(pal.text)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isFocusableInTouchMode = false
            setOnClickListener {
                isFocusableInTouchMode = true
                requestFocus()
                setSelection(text.length)
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
            }
            setOnEditorActionListener { _, _, _ ->
                commitName()
                true
            }
            setOnFocusChangeListener { _, has -> if (!has) commitName() }
        }
        card.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll.addView(grid, FrameLayout.LayoutParams(cols * slotW, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rows = ceil(order.size / cols.toFloat()).toInt().coerceAtLeast(1)
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).roundToInt()
        card.addView(scroll, LinearLayout.LayoutParams(cols * slotW, min(rows * slotH, max(slotH, maxH))))
        addView(card, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        build()
    }

    private fun commitName() {
        val name = title.text.toString().trim().ifEmpty { "Folder" }
        title.isFocusableInTouchMode = false
        title.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(title.windowToken, 0)
        if (name != folder.name) {
            folder.name = name
            Store.save()
            act.rebind()
        }
    }

    fun build() {
        grid.removeAllViews()
        order.retainAll(folder.apps)
        for (k in folder.apps) if (k !in order) order.add(k)
        grid.rows = ceil(order.size / cols.toFloat()).toInt().coerceAtLeast(1)
        order.forEachIndexed { i, key ->
            val app = Apps.apps[key] ?: return@forEachIndexed
            val v = act.iconView(IconRenderer.app(act, key), app.label, true, home = false)
            v.greyed = Apps.isQuiet(key.user)
            v.tag = key
            v.setOnClickListener { act.launchApp(v, key) }
            v.setOnLongClickListener {
                act.startFolderDrag(v, folder, key)
                true
            }
            grid.add(v, null, i % cols, i / cols)
        }
        grid.requestLayout()
    }

    private fun rectInRoot(v: android.view.View): RectF {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        getLocationInWindow(b)
        return RectF((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat(), (a[0] - b[0] + v.width).toFloat(), (a[1] - b[1] + v.height).toFloat())
    }

    fun contains(x: Float, y: Float) = rectInRoot(card).contains(x, y)

    /** Reorder inside the panel while dragging one of its apps. */
    fun dragOver(x: Float, y: Float, key: AppKey) {
        val r = rectInRoot(grid)
        val cx = ((x - r.left) / slotW).toInt().coerceIn(0, cols - 1)
        val cy = ((y - r.top) / slotH).toInt().coerceAtLeast(0)
        val idx = (cy * cols + cx).coerceIn(0, order.size - 1)
        val from = order.indexOf(key)
        if (from < 0 || from == idx) return
        order.removeAt(from)
        order.add(idx, key)
        for (i in 0 until grid.childCount) {
            val v = grid.getChildAt(i)
            val lp = v.layoutParams as CellGrid.LP
            val pos = order.indexOf(v.tag as AppKey)
            lp.x = pos % cols
            lp.y = pos / cols
        }
        grid.requestLayout()
    }

    fun commitOrder() {
        folder.apps.clear()
        folder.apps.addAll(order)
        Store.save()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (title.isFocusableInTouchMode) commitName()
    }
}
