/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Drawer search order. The lists are in the drawer's alphabetical order, as the drawer passes them. */
class SearchTest {
    private fun search(names: List<String>, q: String) = Search.filter(names, q) { it }

    @Test
    fun nameStartBeatsWordStartBeatsInside() {
        val drawer = listOf("Gubbins", "Insget", "Instagram", "Universal Installer")
        assertEquals(listOf("Insget", "Instagram", "Universal Installer", "Gubbins"), search(drawer, "ins"))
    }

    @Test
    fun appWhoseNameStartsWithItComesFirst() {
        val drawer = listOf("Thank You", "Wall You", "YouTube ReVanced")
        assertEquals(listOf("YouTube ReVanced", "Thank You", "Wall You"), search(drawer, "you"))
    }

    @Test
    fun caseAccentsAndSpacesAroundAreIgnored() {
        val drawer = listOf("Ärzte", "Música", "Über")
        assertEquals(listOf("Música"), search(drawer, " MUSI "))
        assertEquals(listOf("Ärzte"), search(drawer, "arz"))
    }

    @Test
    fun wordsSplitOnDashesAndDots() {
        val drawer = listOf("A-Train", "Rain", "x.rain")
        assertEquals(listOf("Rain", "x.rain", "A-Train"), search(drawer, "rain"))
        assertEquals(1, Search.rank("a-train", "train"))
        assertEquals(1, Search.rank("x.rain", "rain"))
        assertEquals(2, Search.rank("a-train", "rain"))
    }

    @Test
    fun aLaterWordStartCountsEvenAfterAnInsideMatch() {
        // "ins" is inside "Gubbins", then starts the word "Installer".
        assertEquals(1, Search.rank("gubbins installer", "ins"))
    }

    @Test
    fun noMatchIsLeftOutAndEmptyQueryKeepsEverything() {
        val drawer = listOf("Calendar", "Clock")
        assertEquals(emptyList<String>(), search(drawer, "zz"))
        assertEquals(drawer, search(drawer, "  "))
    }
}
