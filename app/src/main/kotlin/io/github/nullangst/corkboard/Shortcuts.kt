/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast

/** App shortcuts for the long-press menu, and pinned shortcuts on the home screen. */
object Shortcuts {
    /** Android only hands shortcuts to the current default home app. */
    fun available(): Boolean = try {
        Apps.la.hasShortcutHostPermission()
    } catch (_: Exception) {
        false
    }

    private fun query(q: LauncherApps.ShortcutQuery, serial: Long): List<ShortcutInfo> {
        val user = Apps.user(serial) ?: return emptyList()
        return try {
            Apps.la.getShortcuts(q, user) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Up to 4 of the app's own shortcuts, ranked by the app. */
    fun forApp(app: AppInfo): List<ShortcutInfo> {
        if (!available()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(app.key.pkg)
            .setActivity(app.key.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        return query(q, app.key.user)
            .filter { it.isEnabled }
            .sortedWith(compareBy({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
            .take(4)
    }

    fun pinnedInfo(pkg: String, id: String, serial: Long): ShortcutInfo? {
        if (!available()) return null
        val q = LauncherApps.ShortcutQuery()
            .setPackage(pkg)
            .setShortcutIds(listOf(id))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        return query(q, serial).firstOrNull()
    }

    fun icon(ctx: Context, item: ShortcutItem): Drawable? {
        val info = pinnedInfo(item.pkg, item.shortcutId, item.user) ?: return null
        return icon(ctx, info)
    }

    fun icon(ctx: Context, info: ShortcutInfo): Drawable? = try {
        Apps.la.getShortcutIconDrawable(info, maxOf(ctx.resources.displayMetrics.densityDpi, DisplayMetrics.DENSITY_XXXHIGH))
    } catch (_: Exception) {
        null
    }

    fun launch(ctx: Context, pkg: String, id: String, serial: Long, bounds: Rect?, opts: Bundle?): Boolean {
        val user = Apps.user(serial) ?: return false
        return try {
            Apps.la.startShortcut(pkg, id, bounds, opts, user)
            true
        } catch (_: Exception) {
            Toast.makeText(ctx, "Shortcut isn't available", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun pinnedIds(pkg: String, serial: Long): List<String> {
        val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        return query(q, serial).map { it.id }
    }

    /** Pins an app shortcut dragged out of the long-press menu. */
    fun pin(info: ShortcutInfo): Boolean {
        val serial = Apps.serial(info.userHandle)
        val ids = (pinnedIds(info.`package`, serial) + info.id).distinct()
        return try {
            Apps.la.pinShortcuts(info.`package`, ids, info.userHandle)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Unpins once no item on the home screen uses the shortcut any more. */
    fun unpinIfUnused(pkg: String, id: String, serial: Long) {
        val stillUsed = Store.layout.allItems().any { it is ShortcutItem && it.pkg == pkg && it.shortcutId == id && it.user == serial }
        if (stillUsed) return
        val user = Apps.user(serial) ?: return
        try {
            Apps.la.pinShortcuts(pkg, pinnedIds(pkg, serial) - id, user)
        } catch (_: Exception) {
        }
    }

    fun label(info: ShortcutInfo): String = (info.shortLabel ?: info.longLabel ?: info.id).toString()
}

/** Receives "Add to home screen" requests from Chrome and other apps. No UI of its own. */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Apps.init(this)
        try {
            val req = Apps.la.getPinItemRequest(intent)
            if (req != null && req.isValid && req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
                val info = req.shortcutInfo
                if (info != null && req.accept()) {
                    val l = Store.layout
                    val item = ShortcutItem(l.newId(), info.`package`, info.id, Apps.serial(info.userHandle), Shortcuts.label(info))
                    l.placeFirstFree(item)
                    Store.save()
                    Apps.listener?.invoke()
                    Toast.makeText(this, "Added to home screen", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {
        }
        finish()
    }
}
