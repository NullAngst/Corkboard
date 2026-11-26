/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The push-then-relocate solver that makes room when something is dropped on an occupied cell. */
class ReorderTest {
    private var nextId = 1L

    private fun icon(x: Int, y: Int) = ShortcutItem(nextId++, "p", "s", 0, "x").apply {
        this.x = x
        this.y = y
    }

    /** Applies the moves and checks nothing overlaps the drop or anything else, and all is in the grid. */
    private fun assertValid(cols: Int, rows: Int, items: List<Item>, moves: Map<Item, Cell>, x: Int, y: Int, w: Int, h: Int) {
        val g = Array(rows) { BooleanArray(cols) }
        for (yy in y until y + h) for (xx in x until x + w) g[yy][xx] = true
        for (it in items) {
            val p = moves[it] ?: Cell(it.x, it.y)
            assertTrue("inside the grid", p.x >= 0 && p.y >= 0 && p.x + it.w <= cols && p.y + it.h <= rows)
            for (yy in p.y until p.y + it.h) for (xx in p.x until p.x + it.w) {
                assertTrue("no overlap at $xx,$yy", !g[yy][xx])
                g[yy][xx] = true
            }
        }
    }

    @Test
    fun freeCellNeedsNoMoves() {
        val items = listOf(icon(0, 0))
        assertEquals(emptyMap<Item, Cell>(), Reorder.solve(4, 4, items, 2, 2, 1, 1, 0f, 0f))
    }

    @Test
    fun blockerIsPushedTheWayTheFingerMoves() {
        val a = icon(1, 0)
        val m = Reorder.solve(4, 4, listOf(a), 1, 0, 1, 1, 10f, 0f)
        assertEquals(mapOf<Item, Cell>(a to Cell(2, 0)), m)
    }

    @Test
    fun pushCarriesNeighbours() {
        val a = icon(1, 0)
        val b = icon(2, 0)
        val items = listOf(a, b)
        val m = Reorder.solve(4, 1, items, 1, 0, 1, 1, 10f, 0f)
        assertNotNull(m)
        assertValid(4, 1, items, m!!, 1, 0, 1, 1)
    }

    @Test
    fun relocatesWhenPushingCannotWork() {
        // A full top row and a widget dropped across it: pushing sideways runs off the grid.
        val items = (0 until 3).map { icon(it, 0) }
        val m = Reorder.solve(3, 3, items, 0, 0, 3, 1, 0f, -10f)
        assertNotNull(m)
        assertValid(3, 3, items, m!!, 0, 0, 3, 1)
    }

    @Test
    fun fullGridRefuses() {
        val items = (0 until 4).map { icon(it % 2, it / 2) }
        assertNull(Reorder.solve(2, 2, items, 0, 0, 1, 1, 10f, 0f))
    }

    @Test
    fun dropOutsideTheGridRefuses() {
        assertNull(Reorder.solve(4, 4, emptyList(), 3, 3, 2, 1, 0f, 0f))
        assertNull(Reorder.solve(4, 4, emptyList(), -1, 0, 1, 1, 0f, 0f))
    }
}
