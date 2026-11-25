/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

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
            Toast.makeText(ctx, R.string.shortcut_unavailable, Toast.LENGTH_SHORT).show()
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

/**
 * Receives "Add to home screen" requests from Chrome and other apps, and asks before adding, as
 * Android expects of a launcher: without that, any app in the foreground could quietly put icons
 * on the home screen with whatever name and picture it likes. The dialog names the app asking.
 */
class PinShortcutActivity : Activity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Apps.init(this)
        val req = try {
            Apps.la.getPinItemRequest(intent)
        } catch (_: Exception) {
            null
        }
        val info = req?.takeIf { it.isValid && it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }?.shortcutInfo
        if (req == null || info == null) {
            finish()
            return
        }
        val pal = Themes.palette(this)
        val d = resources.displayMetrics.density
        val from = Apps.apps.values.firstOrNull { it.key.pkg == info.`package` && it.user == info.userHandle }?.baseLabel ?: info.`package`
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (20 * d).roundToInt()
            setPadding(p, (12 * d).roundToInt(), p, 0)
            addView(ImageView(this@PinShortcutActivity).apply { setImageDrawable(Shortcuts.icon(this@PinShortcutActivity, info)) },
                LinearLayout.LayoutParams((48 * d).roundToInt(), (48 * d).roundToInt()))
            addView(LinearLayout(this@PinShortcutActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * d).roundToInt(), 0, 0, 0)
                addView(TextView(this@PinShortcutActivity).apply {
                    text = Shortcuts.label(info)
                    textSize = 17f
                    setTextColor(pal.text)
                })
                addView(TextView(this@PinShortcutActivity).apply {
                    text = getString(R.string.pin_from, from)
                    textSize = 14f
                    setTextColor(pal.textDim)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        dialog = AlertDialog.Builder(this, Themes.dialogTheme(pal))
            .setTitle(R.string.pin_title)
            .setView(body)
            .setPositiveButton(R.string.pin_add) { _, _ -> add(req, info) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
            // Another app drawing over the dialog can't trick a tap onto Add.
            .also { it.getButton(AlertDialog.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true }
    }

    private fun add(req: LauncherApps.PinItemRequest, info: ShortcutInfo) {
        val ok = try {
            req.accept()
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            Toast.makeText(applicationContext, R.string.cant_pin_shortcut, Toast.LENGTH_SHORT).show()
            return
        }
        val serial = Apps.serial(info.userHandle)
        val l = Store.layout
        l.placeFirstFree(ShortcutItem(l.newId(), info.`package`, info.id, serial, Shortcuts.label(info)))
        Store.save()
        Apps.listener?.invoke()
        Toast.makeText(applicationContext, R.string.added_to_home, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
