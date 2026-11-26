/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.min

/**
 * The letter strip down the side of the app drawer, like Pixel Launcher's: touch or drag along it
 * to jump to the first app under a letter. A bubble beside the finger shows the letter, with a
 * light tick on each new one. Touch only; TalkBack users scroll the list itself.
 */
@SuppressLint("ViewConstructor")
class LetterScroller(ctx: Context, private val bubble: TextView, private val onJump: (Int) -> Unit) : View(ctx) {
    private var letters: List<String> = emptyList()
    private var starts: List<Int> = emptyList()
    private var current = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val d = resources.displayMetrics.density

    var color = 0
    var activeColor = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** At least this many letters, or the strip isn't worth showing. */
    val useful get() = letters.size >= 3

    /**
     * One entry per letter, at the first app under it. [keyOf] gives an app's letter; the list is
     * already in the drawer's order, so a letter seen again later (a sort quirk) keeps its first spot.
     */
    fun <T> setItems(items: List<T>, keyOf: (T) -> String) {
        val l = ArrayList<String>()
        val s = ArrayList<Int>()
        items.forEachIndexed { i, it ->
            val k = keyOf(it)
            if (k !in l) {
                l.add(k)
                s.add(i)
            }
        }
        letters = l
        starts = s
        current = -1
        invalidate()
    }

    /** Height per letter: the strip's height shared out, but never more than 24dp, so a short list stays compact. */
    private fun slot() = if (letters.isEmpty()) 0f else min((height - paddingTop - paddingBottom) / letters.size.toFloat(), 24 * d)

    /** Where the first letter starts: the letters sit centered in the strip. */
    private fun start() = paddingTop + ((height - paddingTop - paddingBottom) - slot() * letters.size) / 2f

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val slot = slot()
        val y0 = start()
        paint.textSize = min(slot * 0.8f, 12 * d)
        val cx = width / 2f
        for ((i, letter) in letters.withIndex()) {
            paint.color = if (i == current) activeColor else color
            paint.isFakeBoldText = i == current
            val cy = y0 + slot * i + slot / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(letter, cx, cy, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // Above or below the letters counts as the first or last one.
                val i = ((ev.y - start()) / slot()).toInt().coerceIn(0, letters.size - 1)
                if (i != current) {
                    current = i
                    onJump(starts[i])
                    Haptics.play(this, Haptics.SCROLL, Haptics.Fx.TICK)
                    invalidate()
                }
                bubble.text = letters[i]
                bubble.visibility = VISIBLE
                bubble.translationY = (top + ev.y - bubble.height / 2f).coerceAtLeast(0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = -1
                bubble.visibility = GONE
                invalidate()
            }
        }
        return true
    }
}
