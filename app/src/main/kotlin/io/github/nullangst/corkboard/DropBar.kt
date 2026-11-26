/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The Remove and Uninstall targets across the top while something is dragged, like Pixel Launcher.
 * Remove for anything on the home screen, dock or in a folder; Uninstall for apps that can be.
 */
class DropBar(private val act: HomeActivity) {
    private lateinit var bar: LinearLayout
    private lateinit var remove: TextView
    private lateinit var uninstall: TextView

    /** Which target is under the finger. */
    enum class Target { NONE, REMOVE, UNINSTALL }

    fun build() {
        val d = act.d
        bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000))
        }
        fun target(label: Int) = TextView(act).apply {
            text = act.getString(label)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(4 * d, 0f, d, 0xAA000000.toInt())
            val p = (14 * d).roundToInt()
            setPadding(p * 2, p / 2, p * 2, p / 2)
        }
        remove = target(R.string.remove)
        uninstall = target(R.string.uninstall)
        val lp = { LinearLayout.LayoutParams(0, (44 * d).roundToInt(), 1f).apply { setMargins((8 * d).roundToInt(), 0, (8 * d).roundToInt(), 0) } }
        bar.addView(remove, lp())
        bar.addView(uninstall, lp())
        act.root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (TOP_BAND_DP * d).roundToInt(), Gravity.TOP))
    }

    fun setInsets(bars: Rect) {
        bar.setPadding(bars.left, bars.top, bars.right, 0)
        bar.layoutParams.height = bars.top + (TOP_BAND_DP * act.d).roundToInt()
    }

    val shown get() = bar.visibility == View.VISIBLE

    fun show(st: DragState) {
        if (st.source == Src.SHORTCUT || st.source == Src.PICKER) return
        val canRemove = (st.source == Src.PAGE || st.source == Src.DOCK || st.source == Src.FOLDER) && st.item !is DrawerButtonItem
        val app = st.key?.let { Apps.apps[it] }
        val canUninstall = app != null && !app.isSystem && st.item !is FolderItem
        if (!canRemove && !canUninstall) return
        remove.visibility = if (canRemove) View.VISIBLE else View.GONE
        uninstall.visibility = if (canUninstall) View.VISIBLE else View.GONE
        highlight(Target.NONE)
        bar.alpha = 0f
        bar.visibility = View.VISIBLE
        bar.animate().alpha(1f).setDuration(120).start()
    }

    fun hide() {
        bar.animate().cancel()
        bar.visibility = View.GONE
    }

    fun highlight(t: Target) {
        remove.isActivated = t == Target.REMOVE
        uninstall.isActivated = t == Target.UNINSTALL
        for (v in listOf(remove, uninstall)) {
            v.background = if (v.isActivated) act.pal.card(act, 0xCCD32F2F.toInt(), 22f) else null
        }
    }

    /** The target at a point in root coordinates. With both showing, the bar splits down the middle. */
    fun targetAt(x: Float, y: Float): Target {
        if (!shown) return Target.NONE
        val r = act.rectInRoot(bar)
        if (!r.contains(x, y)) return Target.NONE
        val both = remove.visibility == View.VISIBLE && uninstall.visibility == View.VISIBLE
        return when {
            both -> if (x < r.centerX()) Target.REMOVE else Target.UNINSTALL
            remove.visibility == View.VISIBLE -> Target.REMOVE
            else -> Target.UNINSTALL
        }
    }
}
