/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading layout.json and backups. A backup can come from anywhere, so a damaged or hostile file
 * must come out as a sane layout, never a crash or items off the grid.
 */
class LayoutJsonTest {
    private fun assertSane(l: Layout) {
        val cols = l.settings.columns
        val rows = l.settings.rows
        for (page in l.pages) {
            val g = Array(rows) { BooleanArray(cols) }
            for (it in page) {
                assertTrue("item ${it.id} inside", it.x >= 0 && it.y >= 0 && it.x + it.w <= cols && it.y + it.h <= rows)
                for (yy in it.y until it.y + it.h) for (xx in it.x until it.x + it.w) {
                    assertFalse("overlap at $xx,$yy", g[yy][xx])
                    g[yy][xx] = true
                }
            }
        }
        for (it in l.dock) assertTrue(it !is WidgetItem && it.x in 0 until cols && it.y == 0 && it.w == 1 && it.h == 1)
        val ids = l.allItems().map { it.id }.toList()
        assertEquals("unique ids", ids.size, ids.toSet().size)
        assertTrue("ids in range", ids.all { it in 0 until l.nextId })
    }

    @Test
    fun privacySwitchesAreOffByDefault() {
        val fresh = Layout().settings
        assertFalse(fresh.dots)
        assertFalse(fresh.privateSpace)
        assertEquals("icon", fresh.dotColor)
        val read = Store.fromJson(JSONObject("{}")).settings
        assertFalse(read.dots)
        assertFalse(read.privateSpace)
        assertEquals("icon", read.dotColor)
    }

    @Test
    fun hostileFileComesOutSane() {
        val json = JSONObject(
            """
            {"version": 1, "nextId": 9223372036854775807,
             "settings": {"columns": 99, "rows": -3, "iconSize": 5000, "diskCacheMb": -1, "autoBackupKeep": 0,
                          "dotColor": "<script>", "dots": "yes", "privateSpace": 1},
             "pages": [[{"type": "widget", "id": 1, "x": 999, "y": -5, "w": 1000, "h": 1000, "widgetId": 3},
                        {"type": "shortcut", "id": 1, "x": 0, "y": 0, "package": "a", "shortcutId": "b", "user": 0, "label": "c"},
                        {"type": "shortcut", "id": -9, "x": 1, "y": 0},
                        {"type": "mystery", "id": 5},
                        "not an object", 42],
                       "not a page"],
             "dock": [{"type": "widget", "id": 20, "x": 0, "y": 0, "w": 2, "h": 2, "widgetId": 4},
                      {"type": "drawer", "id": 21, "x": 77, "y": 5}],
             "seen": "nope",
             "icons": {"app:x": "nope", "item:1": {"scale": 99999}}}
            """.trimIndent(),
        )
        val l = Store.fromJson(json)
        val s = l.settings
        assertEquals(6, s.columns)
        assertEquals(4, s.rows)
        assertEquals(120, s.iconSize)
        assertEquals(0, s.diskCacheMb)
        assertEquals(1, s.autoBackupKeep)
        assertEquals("icon", s.dotColor)
        // Only a real true turns a privacy switch on.
        assertFalse(s.dots)
        assertFalse(s.privateSpace)
        assertSane(l)
        // The widget, the shortcut and the drawer button survive; the dock widget and the junk don't.
        assertEquals(3, l.allItems().count())
        assertTrue(l.dock.isEmpty())
        assertEquals(150, l.icons["item:1"]?.scale)
        assertTrue(l.seen.isEmpty())
    }

    @Test
    fun notAnObjectAnywhere() {
        val l = Store.fromJson(JSONObject("""{"pages": "x", "dock": 3, "settings": [], "icons": 1, "hidden": {}}"""))
        assertEquals(1, l.pages.size)
        assertTrue(l.allItems().none())
        assertSane(l)
    }

    @Test
    fun savedLayoutReadsBackUnchanged() {
        val l = Layout()
        l.settings.columns = 5
        l.settings.dots = true
        l.settings.dotColor = "accent"
        l.pages[0].add(ShortcutItem(l.newId(), "pkg", "id", 0, "Label").apply { x = 2; y = 3 })
        l.pages.add(mutableListOf(WidgetItem(l.newId(), 7, null, 0).apply { x = 1; y = 1; w = 3; h = 2 }))
        l.dock.add(DrawerButtonItem(l.newId()).apply { x = 2 })
        l.hidden.add("a/b#0")
        l.icons["item:1"] = IconSettings(label = "Mine")
        val first = Store.toJson(l)
        val again = Store.toJson(Store.fromJson(first))
        // Both come from the same toJson code, so the same keys in the same order.
        assertEquals(first.toString(), again.toString())
    }
}
