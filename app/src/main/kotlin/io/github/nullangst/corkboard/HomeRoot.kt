/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The home screen's root view and its gestures: swipe left or right for pages, up for the drawer,
 * down for the notification shade, and long-press on empty space (or between icons) for the home menu.
 */
@SuppressLint("ViewConstructor")
class HomeRoot(private val act: HomeActivity) : FrameLayout(act) {
    private var downX = 0f
    private var downY = 0f
    private var mode = 0 // 0 undecided, 1 pages, 2 drawer, 3 shade, -1 ignore
    private var vt: VelocityTracker? = null
    private var downOnWidget = false
    private var drawerTicked = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val d = resources.displayMetrics.density
    private val longPress = Runnable {
        mode = -1
        act.menus.emptySpace(downX, downY)
    }

    /** Something else owns touches, unless this gesture is the one opening the drawer or flipping pages. */
    private fun blocked() = mode <= 0 && (act.dnd.active || act.overlay != null || act.folderPanel != null || act.drawer.isShown)

    /** The item, banner or bar under a point, or null for empty home screen. */
    private fun itemViewAt(x: Float, y: Float): View? {
        val ws = act.workspace
        val g = ws.pages.getOrNull(ws.current)
        if (g != null) {
            val r = act.rectInRoot(g)
            if (r.contains(x, y)) g.childAt(x - r.left, y - r.top)?.let { return it }
        }
        val r = act.rectInRoot(act.dock)
        if (r.contains(x, y)) act.dock.childAt(x - r.left, y - r.top)?.let { return it }
        for (v in listOf(act.banner, act.undo.view)) {
            if (v.visibility == View.VISIBLE && act.rectInRoot(v).contains(x, y)) return v
        }
        return null
    }

    private fun track(ev: MotionEvent): Boolean {
        if (blocked()) {
            removeCallbacks(longPress)
            return false
        }
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = 0
                drawerTicked = false
                vt?.clear()
                vt?.addMovement(ev)
                val hit = itemViewAt(ev.x, ev.y)
                downOnWidget = hit is WidgetFrame
                // DOWN arrives here twice (intercept, then onTouchEvent): post the long-press once.
                removeCallbacks(longPress)
                if (hit == null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (mode == 0 && hypot(dx, dy) > slop) {
                    removeCallbacks(longPress)
                    val s = Store.layout.settings
                    mode = when {
                        abs(dx) > abs(dy) -> 1
                        downOnWidget -> -1
                        dy < 0 && !s.drawerButton -> 2
                        dy < 0 -> -1
                        Shade.available && s.swipeDown -> 3
                        else -> -1
                    }
                    if (mode == 1) act.workspace.beginDrag()
                    if (mode == 2) act.drawer.beginOpen()
                }
                when (mode) {
                    1 -> act.workspace.dragTo(dx)
                    2 -> {
                        act.drawer.trackOpen(-dy)
                        if (!drawerTicked && -dy > height * 0.2f) {
                            drawerTicked = true
                            Haptics.play(this, Haptics.DRAWER, Haptics.Fx.THRESHOLD)
                        }
                    }
                    3 -> if (dy > 48 * d) {
                        mode = -1
                        Shade.expand(act)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                when (mode) {
                    1 -> act.workspace.release(vx)
                    2 -> {
                        // A busy frame can swallow the last moves; the lift-off point is the truth.
                        act.drawer.trackOpen(downY - ev.y)
                        act.drawer.releaseOpen(vy)
                    }
                    3 -> if (vy > 600 * d) Shade.expand(act)
                }
                val m = mode
                mode = 0
                return m > 0
            }
        }
        return mode > 0
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        vt?.recycle()
        vt = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (blocked()) return super.onTouchEvent(ev)
        track(ev)
        return true
    }
}
