/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.animation.ValueAnimator
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator

/** Longest the coming-home icons wait for the system's close transition before they start anyway. */
const val ENTER_WAIT_MS = 220L

/**
 * Coming back from an app: icons, widgets and the dock rise a little, grow from 88% and fade in,
 * bottom rows first. Skipped when Android's "Remove animations" is on.
 */
class EnterAnimation(private val act: HomeActivity) {
    private var pending = false
    private var go: Runnable? = null

    fun onStop() {
        settle()
        // An app covered the home screen while the screen was on: play it when coming back.
        // Screen-off stops (lock, then unlock) are left to Android's unlock animation.
        pending = act.getSystemService(PowerManager::class.java)?.isInteractive == true
    }

    fun onResume() {
        if (!pending) return
        pending = false
        play()
    }

    /** The system's close transition is done: start the icons now if they're still waiting. */
    fun onSystemTransitionDone() {
        go?.let { act.root.removeCallbacks(it); it.run() }
    }

    private fun targets(): List<Pair<View, Long>> {
        val out = ArrayList<Pair<View, Long>>()
        val dock = act.dock
        for (i in 0 until dock.childCount) out.add(dock.getChildAt(i) to 0L)
        val page = act.workspace.pages.getOrNull(act.workspace.current) ?: return out
        val rows = Store.layout.settings.rows
        for (i in 0 until page.childCount) {
            val v = page.getChildAt(i)
            val lp = v.layoutParams as? CellGrid.LP ?: continue
            // Bottom rows first, rising toward the top, right behind the dock.
            val fromBottom = (rows - (lp.y + lp.h)).coerceAtLeast(0)
            out.add(v to 30L + fromBottom * 22L)
        }
        return out
    }

    /**
     * The system plays its own transition as the app closes, and the app's window covers most of
     * the home screen for the first part of it. Starting right away spends the motion under that
     * window, so the icons wait, hidden, until the transition reports done or [ENTER_WAIT_MS] has
     * passed since the first frame, whichever comes first.
     */
    private fun play() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (act.folderPanel != null || act.overlay != null || act.drawer.isOpen) return
        val rise = 24 * act.d
        val list = targets().filter { it.first.visibility == View.VISIBLE }
        if (list.isEmpty()) return
        for ((v, _) in list) {
            v.animate().cancel()
            v.alpha = 0f
            v.scaleX = 0.88f
            v.scaleY = 0.88f
            v.translationY = rise
        }
        val run = object : Runnable {
            override fun run() {
                if (go !== this) return
                go = null
                for ((v, delay) in list) {
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setStartDelay(delay).setDuration(300).setInterpolator(DecelerateInterpolator(1.8f))
                        .start()
                }
            }
        }
        go = run
        // The fallback timer counts from the first frame drawn, which can come a moment after
        // onResume when an app was on top.
        val root = act.root
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                if (go === run) root.postDelayed(run, ENTER_WAIT_MS)
                return true
            }
        })
    }

    /** Puts everything back at rest, so a half-played entry never leaves icons faded or shifted. */
    private fun settle() {
        go?.let { act.root.removeCallbacks(it) }
        go = null
        for ((v, _) in targets()) {
            v.animate().cancel()
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationY = 0f
        }
    }
}
