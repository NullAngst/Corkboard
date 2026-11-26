/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Widget sizes in pixels to grid cells, with 100 px cells on a 5-wide grid. */
class WidgetSpansTest {
    private val cell = 100f
    private val cols = 5

    @Test
    fun cellsRoundUpAndStayOnTheGrid() {
        assertEquals(1, WidgetSpans.cells(0, cell, cols))
        assertEquals(1, WidgetSpans.cells(100, cell, cols))
        assertEquals(2, WidgetSpans.cells(101, cell, cols))
        assertEquals(cols, WidgetSpans.cells(10_000, cell, cols))
        assertEquals(1, WidgetSpans.cells(250, 0f, cols))
    }

    @Test
    fun defaultUsesTargetCellsWhenGiven() {
        assertEquals(4, WidgetSpans.default(4, 450, cell, cols))
        assertEquals(cols, WidgetSpans.default(9, 450, cell, cols))
        assertEquals(5, WidgetSpans.default(0, 450, cell, cols))
    }

    @Test
    fun minimumNeverAboveTheDefaultSize() {
        // The case from the report: placed at its 4-cell target, but its minimum width needs 5
        // cells here. Grown to 5, it must still go back to 4.
        assertEquals(4, WidgetSpans.min(minPx = 450, minResizePx = 0, default = 4, cell = cell, max = cols))
    }

    @Test
    fun minimumResizeSizeCountsOnlyWhenSmaller() {
        assertEquals(2, WidgetSpans.min(minPx = 350, minResizePx = 150, default = 4, cell = cell, max = cols))
        // Larger than the minimum size: Android says it has no effect.
        assertEquals(2, WidgetSpans.min(minPx = 150, minResizePx = 350, default = 4, cell = cell, max = cols))
    }

    @Test
    fun maximumIsTheWholeGridUnlessGiven() {
        assertEquals(cols, WidgetSpans.max(0, cell, cols))
        assertEquals(3, WidgetSpans.max(250, cell, cols))
    }
}
