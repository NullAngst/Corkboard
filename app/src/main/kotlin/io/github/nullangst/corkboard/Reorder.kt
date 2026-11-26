/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 *
 * The reorder solver below is a trimmed-down port of the reorder logic in AOSP Launcher3's
 * CellLayout (pushViewsToTempLocation / rearrangementExists),
 * Copyright (C) 2008 The Android Open Source Project, licensed under the Apache License 2.0.
 * Rewritten in Kotlin for a framework-only stack; only the push-then-relocate idea is kept.
 */
package io.github.nullangst.corkboard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A grid cell, column and row. Plain Kotlin, so the solver can be unit tested without Android. */
data class Cell(val x: Int, val y: Int)

/** Push-then-relocate solver for dropping on occupied cells. */
object Reorder {
    private class Box(val item: Item) {
        val x get() = item.x
        val y get() = item.y
        val w get() = item.w
        val h get() = item.h
    }

    private fun overlap(ax: Int, ay: Int, aw: Int, ah: Int, bx: Int, by: Int, bw: Int, bh: Int) =
        ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah

    /**
     * Finds new cells for the items in the way of a w x h drop at (x, y). Returns an empty map when
     * nothing is in the way, the moves when room can be made, or null when it cannot (cells go red).
     */
    fun solve(cols: Int, rows: Int, others: List<Item>, x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float): Map<Item, Cell>? {
        if (x < 0 || y < 0 || x + w > cols || y + h > rows) return null
        val boxes = others.map { Box(it) }
        val blockers = boxes.filter { overlap(it.x, it.y, it.w, it.h, x, y, w, h) }
        if (blockers.isEmpty()) return emptyMap()
        for (d in directions(dx, dy)) {
            push(cols, rows, boxes, blockers, x, y, w, h, d)?.let { return it }
        }
        return relocate(cols, rows, boxes, blockers, x, y, w, h)
    }

    private fun directions(dx: Float, dy: Float): List<Cell> {
        val sx = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val sy = if (dy > 0) 1 else if (dy < 0) -1 else 0
        val out = ArrayList<Cell>()
        val horizontalFirst = abs(dx) >= abs(dy)
        val primary = if (horizontalFirst) listOf(Cell(sx, 0), Cell(0, sy)) else listOf(Cell(0, sy), Cell(sx, 0))
        for (p in primary) if (p.x != 0 || p.y != 0) out.add(p)
        for (p in listOf(Cell(-sx, 0), Cell(0, -sy), Cell(1, 0), Cell(-1, 0), Cell(0, 1), Cell(0, -1))) {
            if ((p.x != 0 || p.y != 0) && out.none { it.x == p.x && it.y == p.y }) out.add(p)
        }
        return out
    }

    /** Step 1: push the blockers along [d] one cell at a time, carrying anything they bump into. */
    private fun push(cols: Int, rows: Int, all: List<Box>, blockers: List<Box>, x: Int, y: Int, w: Int, h: Int, d: Cell): Map<Item, Cell>? {
        val cluster = LinkedHashSet(blockers)
        val limit = max(cols, rows)
        restart@ while (true) {
            for (dist in 1..limit) {
                val moved = cluster.associateWith { Cell(it.x + d.x * dist, it.y + d.y * dist) }
                for ((b, p) in moved) {
                    if (p.x < 0 || p.y < 0 || p.x + b.w > cols || p.y + b.h > rows) return null
                }
                val hit = all.filter { o ->
                    o !in cluster && moved.any { (b, p) -> overlap(p.x, p.y, b.w, b.h, o.x, o.y, o.w, o.h) }
                }
                if (hit.isNotEmpty()) {
                    cluster.addAll(hit)
                    continue@restart
                }
                if (moved.none { (b, p) -> overlap(p.x, p.y, b.w, b.h, x, y, w, h) }) {
                    return moved.entries.associate { (b, p) -> b.item to p }
                }
            }
            return null
        }
    }

    /** Step 2: move each blocker to the nearest free spot that fits its span. */
    private fun relocate(cols: Int, rows: Int, all: List<Box>, blockers: List<Box>, x: Int, y: Int, w: Int, h: Int): Map<Item, Cell>? {
        val g = Array(rows) { BooleanArray(cols) }
        fun mark(bx: Int, by: Int, bw: Int, bh: Int) {
            for (yy in by until min(rows, by + bh)) for (xx in bx until min(cols, bx + bw)) if (yy >= 0 && xx >= 0) g[yy][xx] = true
        }
        for (b in all) if (b !in blockers) mark(b.x, b.y, b.w, b.h)
        mark(x, y, w, h)
        val out = HashMap<Item, Cell>()
        for (b in blockers.sortedByDescending { it.w * it.h }) {
            var best: Cell? = null
            var bestD = Int.MAX_VALUE
            for (yy in 0..rows - b.h) for (xx in 0..cols - b.w) {
                if (!Layout.fits(g, xx, yy, b.w, b.h)) continue
                val dd = (xx - b.x) * (xx - b.x) + (yy - b.y) * (yy - b.y)
                if (dd < bestD) {
                    bestD = dd
                    best = Cell(xx, yy)
                }
            }
            val p = best ?: return null
            mark(p.x, p.y, b.w, b.h)
            out[b.item] = p
        }
        return out
    }
}
