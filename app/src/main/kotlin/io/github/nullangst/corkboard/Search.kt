/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import java.text.Normalizer

/**
 * Drawer search: which apps match, and in what order. Plain Kotlin, so it is unit tested.
 *
 * Best matches first, since Enter launches the top one:
 *  1. the name starts with what was typed ("ins" finds Instagram),
 *  2. a later word in the name starts with it ("ins" finds Universal Installer),
 *  3. it appears anywhere else in the name ("ins" finds Gubbins).
 * Within each group, more-opened apps come first when Most used apps is on, then the drawer's
 * alphabetical order. Accents and case are ignored.
 */
object Search {
    private val marks = Regex("\\p{Mn}+")

    /** Lowercase with accents dropped, so "musica" finds "Música" and "uber" finds "Über". */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()

    /** 0 the name starts with [query], 1 a later word does, 2 it's inside a word, -1 no match. Both already folded. */
    fun rank(name: String, query: String): Int {
        if (name.startsWith(query)) return 0
        var inside = false
        var i = name.indexOf(query, 1)
        while (i > 0) {
            // A word starts after anything that isn't a letter or digit: a space, a dash, a dot.
            if (!name[i - 1].isLetterOrDigit()) return 1
            inside = true
            i = name.indexOf(query, i + 1)
        }
        return if (inside) 2 else -1
    }

    /**
     * The items whose name matches [query], best first. Within a group, a higher [scoreOf] comes
     * first (how often an app is opened, when Most used apps is on), then the list's own order.
     */
    fun <T> filter(items: List<T>, query: String, scoreOf: (T) -> Double = { 0.0 }, nameOf: (T) -> String): List<T> {
        val q = fold(query.trim())
        if (q.isEmpty()) return items
        return items.map { Triple(it, rank(fold(nameOf(it)), q), scoreOf(it)) }
            .filter { it.second >= 0 }
            .sortedWith(compareBy<Triple<T, Int, Double>> { it.second }.thenByDescending { it.third })
            .map { it.first }
    }
}
