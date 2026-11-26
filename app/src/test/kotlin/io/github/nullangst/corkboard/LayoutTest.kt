/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout repair: refitting after a grid change, fixing ids, placing and pruning. */
class LayoutTest {
    private fun icon(id: Long, x: Int, y: Int) = ShortcutItem(id, "p", "s$id", 0, "x").apply {
        this.x = x
        this.y = y
    }

    private fun widget(id: Long, x: Int, y: Int, w: Int, h: Int) = WidgetItem(id, id.toInt(), null, 0).apply {
        this.x = x
        this.y = y
        this.w = w
        this.h = h
    }

    /** Every item inside its grid, nothing overlapping, the dock one row with each slot used once. */
    private fun assertSane(l: Layout) {
        val cols = l.settings.columns
        val rows = l.settings.rows
        for ((i, page) in l.pages.withIndex()) {
            val g = Array(rows) { BooleanArray(cols) }
            for (it in page) {
                assertTrue("page $i item ${it.id} inside", it.x >= 0 && it.y >= 0 && it.x + it.w <= cols && it.y + it.h <= rows)
                for (yy in it.y until it.y + it.h) for (xx in it.x until it.x + it.w) {
                    assertTrue("page $i overlap at $xx,$yy", !g[yy][xx])
                    g[yy][xx] = true
                }
            }
        }
        val slots = HashSet<Int>()
        for (it in l.dock) assertTrue("dock slot ${it.x}", it.x in 0 until cols && it.y == 0 && slots.add(it.x))
        val ids = l.allItems().map { it.id }.toList()
        assertEquals("unique ids", ids.size, ids.toSet().size)
        assertTrue("next id above every id", ids.all { it < l.nextId })
    }

    @Test
    fun refitAfterFewerColumnsKeepsEverything() {
        val l = Layout()
        l.settings.columns = 6
        l.settings.rows = 6
        l.pages[0].addAll(listOf(icon(1, 5, 0), icon(2, 4, 4), widget(3, 2, 2, 4, 2), icon(4, 0, 0)))
        l.nextId = 5
        l.settings.columns = 4
        l.refit()
        assertSane(l)
        assertEquals(4, l.allItems().count())
    }

    @Test
    fun refitShrinksTooWideWidgets() {
        val l = Layout()
        l.settings.columns = 4
        l.settings.rows = 4
        l.pages[0].add(widget(1, 0, 0, 6, 9))
        l.nextId = 2
        l.refit()
        assertSane(l)
        val w = l.allItems().single()
        assertEquals(4, w.w)
        assertEquals(4, w.h)
    }

    @Test
    fun refitMovesOverlapsAndDockClashes() {
        val l = Layout()
        l.pages[0].addAll(listOf(icon(1, 1, 1), icon(2, 1, 1)))
        l.dock.addAll(listOf(icon(3, 0, 0), icon(4, 0, 0), icon(5, 9, 0)))
        l.nextId = 6
        l.refit()
        assertSane(l)
        assertEquals(5, l.allItems().count())
    }

    @Test
    fun sanitizeGivesClashingAndBadIdsNewOnes() {
        val l = Layout()
        l.pages[0].addAll(listOf(icon(7, 0, 0), icon(7, 1, 0), icon(-4, 2, 0), icon(Long.MAX_VALUE, 3, 0)))
        l.sanitize(Long.MAX_VALUE)
        assertSane(l)
        assertTrue("ids stay small", l.allItems().all { it.id in 0..(1L shl 40) })
        assertTrue("room to count up", l.nextId < (1L shl 40))
    }

    @Test
    fun placeFirstFreeAddsAPageWhenFull() {
        val l = Layout()
        l.settings.columns = 3
        l.settings.rows = 4
        var id = 1L
        for (y in 0 until 4) for (x in 0 until 3) l.pages[0].add(icon(id++, x, y))
        l.nextId = id
        l.placeFirstFree(icon(l.newId(), 0, 0))
        assertEquals(2, l.pages.size)
        assertSane(l)
    }

    @Test
    fun pruneKeepsFirstAndCurrentPage() {
        val l = Layout()
        l.pages.add(mutableListOf())
        l.pages.add(mutableListOf(icon(1, 0, 0)))
        l.pages.add(mutableListOf())
        l.nextId = 2
        // On the empty page 3: it stays, page 1 (empty, not current) goes.
        val cur = l.pruneEmptyPages(3)
        assertEquals(3, l.pages.size)
        assertEquals(2, cur)
        assertTrue(l.pages[cur].isEmpty())
    }

    @Test
    fun emptyAndSingleFoldersResolve() {
        val l = Layout()
        l.pages[0].add(FolderItem(1, "Empty", mutableListOf()))
        l.nextId = 2
        l.fixFolders()
        assertTrue(l.pages[0].isEmpty())
    }
}
