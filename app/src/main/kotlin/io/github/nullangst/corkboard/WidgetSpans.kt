/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import kotlin.math.ceil

/**
 * Widget sizes, which apps give in pixels, turned into grid cells. Plain Kotlin, so it is unit
 * tested. Widgets.kt feeds it the numbers from each widget's AppWidgetProviderInfo.
 */
object WidgetSpans {
    /** Cells needed to hold [px] pixels: at least 1, at most [max]. */
    fun cells(px: Int, cell: Float, max: Int): Int =
        if (cell <= 0f) 1 else ceil(px / cell).toInt().coerceIn(1, max)

    /** The size a widget is placed at: the cells it asks for (Android 12+), or what its minimum size needs. */
    fun default(targetCells: Int, minPx: Int, cell: Float, max: Int): Int =
        if (targetCells > 0) targetCells.coerceAtMost(max) else cells(minPx, cell, max)

    /**
     * The smallest it can be resized to.
     *
     * Android ignores a widget's minimum resize size when it is larger than its minimum size.
     * And, like Pixel Launcher (Launcher3), it is never more than the widget's [default] size: a
     * widget placed at its default size can always be resized back to it, even when its minimum
     * size in pixels needs more cells on this grid than the app expected.
     */
    fun min(minPx: Int, minResizePx: Int, default: Int, cell: Float, max: Int): Int {
        val px = if (minResizePx in 1..minPx) minResizePx else minPx
        return cells(px, cell, max).coerceAtMost(default)
    }

    /** The largest it can be resized to: its maximum resize size if it gives one (Android 12+), or the whole grid. */
    fun max(maxResizePx: Int, cell: Float, max: Int): Int =
        if (maxResizePx > 0) cells(maxResizePx, cell, max) else max
}
