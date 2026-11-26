/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

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
