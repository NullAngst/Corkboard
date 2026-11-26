/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings > Privacy: a switch for each feature that needs a permission or special access, each
 * off until turned on and each saying why, plus every permission the app asks Android for, read
 * from the installed app itself so the list can't drift from the truth.
 */
class PrivacyActivity : Activity() {
    private lateinit var pal: Palette
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Apps.init(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Android's Notification access screen: show what changed there.
        build()
    }

    private fun str(id: Int) = getString(id)

    private fun add(v: View) = list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(pal.textDim)
        setPadding(Ui.dp(context, 20f), Ui.dp(context, 4f), Ui.dp(context, 20f), Ui.dp(context, 8f))
    }

    private fun indent(v: View) = v.apply { setPadding(Ui.dp(context, 40f), paddingTop, paddingRight, paddingBottom) }

    private fun build() {
        pal = Themes.palette(this)
        Ui.edgeToEdge(this, scroll, pal)
        scroll.setBackgroundColor(pal.bg)
        list.removeAllViews()
        val s = Store.layout.settings

        add(Ui.title(this, pal, str(R.string.p_title)))
        add(note(str(R.string.p_intro)))

        add(Ui.header(this, pal, str(R.string.p_off_until)))

        // ---- Notification dots ----
        add(Ui.switchRow(this, pal, str(R.string.p_dots), s.dots, str(R.string.p_dots_sub)) { on ->
            s.dots = on
            Store.save()
            Dots.sync(this)
            if (on && !Dots.hasAccess(this)) askAccess()
            build()
        })
        val access = Dots.hasAccess(this)
        if (s.dots || access) {
            // Shown while access is granted even with dots off, so it's easy to take back.
            add(indent(Ui.row(this, pal, str(R.string.p_access), str(if (access) R.string.p_access_on else R.string.p_access_off)) {
                openAccess()
            }))
        }
        if (s.dots) {
            val names = listOf(str(R.string.p_dot_icon), str(R.string.p_dot_accent))
            val values = listOf("icon", "accent")
            add(indent(Ui.row(this, pal, str(R.string.p_dot_color), names[values.indexOf(s.dotColor).coerceAtLeast(0)]) {
                AlertDialog.Builder(this, Themes.dialogTheme(pal))
                    .setTitle(R.string.p_dot_color)
                    .setSingleChoiceItems(names.toTypedArray(), values.indexOf(s.dotColor)) { dlg, which ->
                        dlg.dismiss()
                        s.dotColor = values[which]
                        Store.save()
                        build()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }))
        }

        // ---- Private space ----
        add(Ui.switchRow(
            this, pal, str(R.string.p_private), s.privateSpace && PrivateSpace.supported,
            str(if (PrivateSpace.supported) R.string.p_private_sub else R.string.p_private_unsupported),
            PrivateSpace.supported,
        ) { on ->
            s.privateSpace = on
            Store.save()
            // Re-read the profiles, and drop private icons from memory when switched off.
            IconRenderer.clear()
            Apps.reconcile()
            Apps.listener?.invoke()
            build()
        })
        if (s.privateSpace && PrivateSpace.supported && Apps.privateSerial == null) {
            add(indent(note(str(R.string.p_private_none))))
        }

        // ---- What the app can do ----
        add(Ui.header(this, pal, str(R.string.p_permissions)))
        add(note(str(R.string.p_permissions_sub)))
        for (p in requested()) {
            val (title, sub) = describe(p)
            add(Ui.row(this, pal, title, sub, null))
        }
        add(note(str(R.string.p_no_internet)))

        add(Ui.row(this, pal, str(R.string.p_read_more), "github.com/NullAngst/Corkboard/blob/main/PRIVACY.md") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NullAngst/Corkboard/blob/main/PRIVACY.md")))
            } catch (_: Exception) {
            }
        })
        list.addView(View(this), LinearLayout.LayoutParams(1, Ui.dp(this, 24f)))
    }

    /** Explains, then opens Android's Notification access screen. Android itself asks to confirm. */
    private fun askAccess() {
        AlertDialog.Builder(this, Themes.dialogTheme(pal))
            .setTitle(R.string.p_access_title)
            .setMessage(R.string.p_access_msg)
            .setPositiveButton(R.string.p_open_settings) { _, _ -> openAccess() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAccess() {
        try {
            startActivity(Dots.accessIntent(this))
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    /** Every permission in the installed app's manifest. */
    private fun requested(): List<String> = try {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun describe(p: String): Pair<String, String> = when (p) {
        "android.permission.REQUEST_DELETE_PACKAGES" -> str(R.string.perm_delete) to str(R.string.perm_delete_sub)
        "android.permission.VIBRATE" -> str(R.string.perm_vibrate) to str(R.string.perm_vibrate_sub)
        "android.permission.EXPAND_STATUS_BAR" -> str(R.string.perm_shade) to str(R.string.perm_shade_sub)
        "android.permission.ACCESS_HIDDEN_PROFILES" -> str(R.string.perm_hidden) to str(R.string.perm_hidden_sub)
        else -> p.substringAfterLast('.') to getString(R.string.perm_unknown, p)
    }
}
