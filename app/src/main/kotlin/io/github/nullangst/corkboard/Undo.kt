/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

/** How long the bar stays up, unless Android's "Time to take action" accessibility setting asks for longer. */
private const val UNDO_MS = 5000

/**
 * The "Item removed · Undo" bar after a removal.
 *
 * It keeps a copy of the layout from just before the change, in memory only. Undo puts that copy
 * back. Whatever can't be taken back (deleting a widget, unpinning a shortcut, deleting a custom
 * icon file) waits until the bar goes away without Undo. The bar goes away after a few seconds,
 * when another change starts (a menu, a drag, another removal), or when the home screen is left.
 */
class Undo(private val act: HomeActivity) {
    private var before: JSONObject? = null
    private val later = ArrayList<() -> Unit>()
    private lateinit var bar: LinearLayout
    private lateinit var text: TextView
    private val timeout = Runnable { commit() }

    val view: View get() = bar

    fun build() {
        val d = act.d
        bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            elevation = 6 * d
            isClickable = true
            // Read out by TalkBack when it appears.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val p = (16 * d).roundToInt()
        text = TextView(act).apply {
            textSize = 15f
            setPadding(p, p, p, p)
        }
        bar.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(act).apply {
            text = act.getString(R.string.undo)
            textSize = 15f
            setPadding(p, p, p, p)
            setOnClickListener { undo() }
            tag = "undo"
        })
        act.root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun setInsets(bars: Rect) {
        val m = (12 * act.d).roundToInt()
        (bar.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left + m, 0, bars.right + m, bars.bottom + m)
        bar.requestLayout()
    }

    /**
     * Makes a change with Undo: [edit] changes the layout, then it is saved and shown. [after] runs
     * once the change is final.
     */
    fun change(message: Int, after: (() -> Unit)? = null, edit: () -> Unit) {
        commit()
        before = Store.toJson(Store.layout)
        edit()
        after?.let { later.add(it) }
        Store.save()
        act.rebind()
        show(act.getString(message))
    }

    private fun show(message: String) {
        val pal = act.pal
        bar.background = pal.card(act, pal.surface, 14f)
        text.text = message
        text.setTextColor(pal.text)
        bar.findViewWithTag<TextView>("undo")?.setTextColor(pal.accent)
        bar.removeCallbacks(timeout)
        bar.animate().cancel()
        bar.alpha = 0f
        bar.translationY = 16 * act.d
        bar.visibility = View.VISIBLE
        bar.animate().alpha(1f).translationY(0f).setDuration(150).start()
        val wait = act.getSystemService(AccessibilityManager::class.java)?.getRecommendedTimeoutMillis(
            UNDO_MS, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
        ) ?: UNDO_MS
        bar.postDelayed(timeout, wait.toLong())
    }

    private fun hide() {
        bar.removeCallbacks(timeout)
        if (bar.visibility != View.VISIBLE) return
        bar.animate().cancel()
        bar.animate().alpha(0f).setDuration(120).withEndAction { bar.visibility = View.GONE }.start()
    }

    /** The change is final: forget the copy and finish what was held back. */
    fun commit() {
        if (before == null && later.isEmpty()) return
        hide()
        before = null
        val steps = later.toList()
        later.clear()
        steps.forEach { it() }
        Store.cleanIcons()
    }

    private fun undo() {
        val b = before ?: return
        hide()
        before = null
        later.clear()
        Store.replace(Store.fromJson(b))
        // Anything installed or removed in the meantime is put right again.
        Apps.reconcile()
        act.rebind()
        Haptics.play(bar, Haptics.PLACE, Haptics.Fx.CONFIRM)
    }
}
