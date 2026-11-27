/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Most used apps: how counts fade, and which apps make the row. */
class UsageTest {
    @Test
    fun countsHalveEveryHalfLife() {
        val h = Usage.HALF_LIFE_DAYS.toLong()
        assertEquals(8.0, Usage.decay(8.0, 100, 100), 1e-9)
        assertEquals(4.0, Usage.decay(8.0, 100, 100 + h), 1e-9)
        assertEquals(2.0, Usage.decay(8.0, 100, 100 + 2 * h), 1e-9)
        // A clock set back never makes a count grow.
        assertEquals(8.0, Usage.decay(8.0, 100, 90), 1e-9)
    }

    @Test
    fun recentUseBeatsOldHeavyUse() {
        // 40 opens two months ago against 5 opens this week.
        val old = Usage.decay(40.0, 0, 60)
        val recent = Usage.decay(5.0, 55, 60)
        assertEquals(listOf("recent", "old"), Usage.pick(listOf("old", "recent"), 2) { if (it == "old") old else recent })
    }

    @Test
    fun rowTakesTheTopFewMostUsedFirst() {
        val scores = mapOf("Calendar" to 3.0, "Chrome" to 9.0, "Clock" to 0.0, "Files" to 3.0, "Gmail" to 5.0)
        val drawer = scores.keys.toList()
        assertEquals(listOf("Chrome", "Gmail", "Calendar"), Usage.pick(drawer, 3) { scores.getValue(it) })
        // Never opened (or faded out) means not in the row, even when the row has room.
        assertEquals(listOf("Chrome", "Gmail", "Calendar", "Files"), Usage.pick(drawer, 5) { scores.getValue(it) })
        assertEquals(emptyList<String>(), Usage.pick(drawer, 0) { scores.getValue(it) })
    }

    @Test
    fun searchTiesGoToTheMoreUsedApp() {
        val drawer = listOf("Gubbins", "Insget", "Instagram", "Universal Installer")
        val scores = mapOf("Instagram" to 7.0)
        assertEquals(
            listOf("Instagram", "Insget", "Universal Installer", "Gubbins"),
            Search.filter(drawer, "ins", scoreOf = { scores[it] ?: 0.0 }) { it },
        )
    }

    @Test
    fun drawerColumnsAdaptToWidth() {
        // Phones in portrait keep the setting.
        assertEquals(5, drawerColumns(412, 5))
        assertEquals(4, drawerColumns(360, 4))
        // Tablets and landscape get more, each column at least 120dp, up to 10.
        assertEquals(6, drawerColumns(800, 5))
        assertEquals(7, drawerColumns(915, 5))
        assertEquals(10, drawerColumns(1600, 5))
        // Never fewer than the setting.
        assertEquals(6, drawerColumns(600, 6))
    }
}
