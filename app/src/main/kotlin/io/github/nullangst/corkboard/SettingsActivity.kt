/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {
    private val reqExport = 31
    private val reqImport = 32
    private val reqTree = 33
    private lateinit var pal: Palette
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Apps.init(this)
        Shade.init(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    /** Global look changes re-render everything, so the old disk icons are dead weight: drop them too. */
    private fun changed(clearIcons: Boolean = false) {
        if (clearIcons) {
            IconRenderer.clear()
            DiskIcons.clearAll(this)
        }
        Store.save()
    }

    private fun stepper(title: String, value: Int, min: Int, max: Int, onSet: (Int) -> Unit) = Ui.row(this, pal, title, null, null).apply {
        val minus = TextView(context).apply {
            text = "−"
            textSize = 22f
            setTextColor(if (value > min) pal.accent else pal.textDim)
            setPadding(Ui.dp(context, 14f), 0, Ui.dp(context, 14f), 0)
            setOnClickListener { if (value > min) onSet(value - 1) }
        }
        val v = TextView(context).apply {
            text = value.toString()
            textSize = 17f
            setTextColor(pal.text)
            gravity = Gravity.CENTER
            minWidth = Ui.dp(context, 28f)
        }
        val plus = TextView(context).apply {
            text = "+"
            textSize = 22f
            setTextColor(if (value < max) pal.accent else pal.textDim)
            setPadding(Ui.dp(context, 14f), 0, Ui.dp(context, 14f), 0)
            setOnClickListener { if (value < max) onSet(value + 1) }
        }
        addView(minus)
        addView(v)
        addView(plus)
    }

    private fun choose(title: String, names: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, Themes.dialogTheme(pal))
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), current) { dlg, which ->
                dlg.dismiss()
                onPick(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirm(title: String, msg: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(this, Themes.dialogTheme(pal))
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun build() {
        pal = Themes.palette(this)
        Ui.edgeToEdge(this, scroll, pal)
        scroll.setBackgroundColor(pal.bg)
        list.removeAllViews()
        val s = Store.layout.settings
        val add = { v: View -> list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val str = { id: Int -> getString(id) }

        add(Ui.title(this, pal, str(R.string.settings)))

        if (!isDefaultHome(this)) {
            add(Ui.header(this, pal, str(R.string.s_default_home)))
            add(Ui.row(this, pal, str(R.string.s_set_default), str(R.string.s_set_default_sub)) {
                requestHomeRole(this)
            })
        }

        add(Ui.header(this, pal, str(R.string.s_layout)))
        add(stepper(str(R.string.s_columns), s.columns, 3, 6) {
            s.columns = it
            Store.layout.refit()
            changed(true)
            build()
        })
        add(stepper(str(R.string.s_rows), s.rows, 4, 8) {
            s.rows = it
            Store.layout.refit()
            changed()
            build()
        })
        add(Ui.switchRow(this, pal, str(R.string.s_lock), s.locked, str(R.string.s_lock_sub)) {
            s.locked = it
            changed()
        })
        add(Ui.switchRow(this, pal, str(R.string.s_auto_add), s.autoAdd) {
            s.autoAdd = it
            changed()
        })
        add(Ui.switchRow(this, pal, str(R.string.s_rotation), s.allowRotation, str(R.string.s_rotation_sub)) {
            s.allowRotation = it
            changed()
        })

        add(Ui.header(this, pal, str(R.string.s_drawer)))
        add(Ui.switchRow(this, pal, str(R.string.s_drawer_button), s.drawerButton, str(R.string.s_drawer_button_sub)) {
            Store.layout.setDrawerButton(it)
            changed()
        })
        add(Ui.switchRow(this, pal, str(R.string.s_drawer_keyboard), s.drawerKeyboard) {
            s.drawerKeyboard = it
            changed()
        })

        add(Ui.header(this, pal, str(R.string.s_icons)))
        add(Ui.row(this, pal, str(R.string.s_shape), IconRenderer.shapeName(this, s.shape)) {
            choose(str(R.string.s_icon_shape), IconRenderer.shapes.map { IconRenderer.shapeName(this, it) }, IconRenderer.shapes.indexOf(s.shape)) {
                s.shape = IconRenderer.shapes[it]
                changed(true)
                build()
            }
        })
        val sizeLabel = TextView(this).apply {
            setTextColor(pal.text)
            text = getString(R.string.s_size, s.iconSize)
            textSize = 16f
            setPadding(Ui.dp(context, 20f), Ui.dp(context, 8f), Ui.dp(context, 20f), 0)
        }
        add(sizeLabel)
        add(SeekBar(this).apply {
            max = 8
            progress = (s.iconSize - 80) / 5
            Ui.tint(this, pal)
            setPadding(Ui.dp(context, 24f), Ui.dp(context, 8f), Ui.dp(context, 24f), Ui.dp(context, 8f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    sizeLabel.text = getString(R.string.s_size, 80 + p * 5)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    s.iconSize = 80 + (sb?.progress ?: 4) * 5
                    changed(true)
                }
            })
        })
        add(Ui.switchRow(this, pal, str(R.string.s_labels), s.labels) {
            s.labels = it
            changed()
        })
        add(Ui.switchRow(
            this, pal, str(R.string.s_themed), s.themedIcons && IconRenderer.themedSupported,
            str(if (IconRenderer.themedSupported) R.string.s_themed_sub else R.string.s_themed_unsupported),
            IconRenderer.themedSupported,
        ) {
            s.themedIcons = it
            changed(true)
            build()
        })
        if (s.themedIcons && IconRenderer.themedSupported) {
            add(Ui.switchRow(this, pal, str(R.string.s_themed_generate), s.themedGenerate, str(R.string.s_themed_generate_sub)) {
                s.themedGenerate = it
                changed(true)
            }.apply { setPadding(Ui.dp(context, 40f), paddingTop, paddingRight, paddingBottom) })
        }
        add(Ui.row(this, pal, str(R.string.s_cache_size), cacheSummary()) {
            val sizes = listOf(0, 10, 25, 50, 100, 200, 500)
            val names = sizes.map { if (it == 0) str(R.string.s_cache_off) else getString(R.string.s_cache_mb, it) }
            choose(str(R.string.s_cache_size), names, sizes.indexOf(s.diskCacheMb)) {
                s.diskCacheMb = sizes[it]
                changed()
                if (s.diskCacheMb == 0) DiskIcons.clearAll(this) else DiskIcons.trimSoon()
                build()
            }
        })
        add(Ui.row(this, pal, str(R.string.s_cache_clear), str(R.string.s_cache_clear_sub)) {
            DiskIcons.clearAll(this)
            IconRenderer.clear()
            Toast.makeText(this, R.string.s_cache_cleared, Toast.LENGTH_SHORT).show()
            scroll.postDelayed({ build() }, 300)
        })
        add(Ui.row(this, pal, str(R.string.s_reset_icons), str(R.string.s_reset_icons_sub)) {
            confirm(str(R.string.s_reset_icons_title), str(R.string.s_reset_icons_msg), str(R.string.reset)) {
                val icons = Store.layout.icons
                for (k in icons.keys.toList()) {
                    val label = icons[k]?.label
                    if (label == null) icons.remove(k) else icons[k] = IconSettings(label = label)
                }
                changed(true)
                Store.cleanIcons()
            }
        })

        add(Ui.header(this, pal, str(R.string.s_apps)))
        val hidden = Store.layout.hidden.toList()
        if (hidden.isEmpty()) add(Ui.row(this, pal, str(R.string.s_hidden_apps), str(R.string.s_none), null))
        for (h in hidden) {
            val key = AppKey.parse(h)
            val app = key?.let { Apps.apps[it] }
            val base = app?.label ?: key?.pkg ?: h
            val name = if (key != null && Apps.isWork(key.user)) getString(R.string.work_suffix, base) else base
            add(Ui.row(this, pal, name, str(R.string.s_hidden), null).apply {
                addView(Ui.button(context, pal, str(R.string.s_unhide)) {
                    Store.layout.hidden.remove(h)
                    changed()
                    build()
                })
            })
        }

        add(Ui.header(this, pal, str(R.string.s_haptics)))
        add(Ui.switchRow(this, pal, str(R.string.s_haptics_all), s.haptics, str(R.string.s_haptics_all_sub)) {
            s.haptics = it
            changed()
            build()
        })
        if (s.haptics) {
            for (t in Haptics.triggers) {
                add(Ui.switchRow(this, pal, str(Haptics.name(t)), t !in s.hapticOff) {
                    if (it) s.hapticOff.remove(t) else s.hapticOff.add(t)
                    changed()
                }.apply { setPadding(Ui.dp(context, 40f), paddingTop, paddingRight, paddingBottom) })
            }
        }

        add(Ui.header(this, pal, str(R.string.s_look)))
        add(Ui.row(this, pal, str(R.string.s_theme), Themes.name(this, s.theme)) {
            choose(str(R.string.s_theme), Themes.ids.map { Themes.name(this, it) }, Themes.ids.indexOf(s.theme)) {
                s.theme = Themes.ids[it]
                changed(true)
                build()
            }
        })

        add(Ui.header(this, pal, str(R.string.s_gestures)))
        add(Ui.switchRow(
            this, pal, str(R.string.s_swipe_down), s.swipeDown && Shade.available,
            str(if (Shade.available) R.string.s_swipe_down_sub else R.string.s_unsupported),
            Shade.available,
        ) {
            s.swipeDown = it
            changed()
        })

        add(Ui.header(this, pal, str(R.string.p_title)))
        add(Ui.row(this, pal, str(R.string.s_privacy), str(R.string.s_privacy_sub)) {
            startActivity(Intent(this, PrivacyActivity::class.java))
        })

        add(Ui.header(this, pal, str(R.string.s_backup)))
        add(Ui.row(this, pal, str(R.string.s_export), str(R.string.s_export_sub)) {
            val name = "corkboard-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".zip"
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, name)
            startActivityForResult(i, reqExport)
        })
        add(Ui.row(this, pal, str(R.string.s_import), str(R.string.s_import_sub)) {
            confirm(str(R.string.s_import_title), str(R.string.s_import_msg), str(R.string.s_choose_file)) {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                startActivityForResult(i, reqImport)
            }
        })

        add(Ui.switchRow(this, pal, str(R.string.b_auto), s.autoBackupOn && s.autoBackupUri != null, backupStatus()) {
            if (it && s.autoBackupUri == null) {
                pickFolder()
            } else {
                s.autoBackupOn = it
                changed()
                build()
            }
        })
        if (s.autoBackupOn && s.autoBackupUri != null) {
            add(Ui.row(this, pal, str(R.string.b_folder), AutoBackup.folderLabel(s.autoBackupUri)) { pickFolder() })
            add(Ui.row(this, pal, str(R.string.b_freq), str(AutoBackup.freqName(s.autoBackupFreq))) {
                choose(str(R.string.b_freq), AutoBackup.frequencies.map { str(AutoBackup.freqName(it)) }, AutoBackup.frequencies.indexOf(s.autoBackupFreq)) {
                    s.autoBackupFreq = AutoBackup.frequencies[it]
                    changed()
                    build()
                }
            })
            add(Ui.row(this, pal, str(R.string.b_keep), getString(R.string.b_keep_sub, s.autoBackupKeep)) {
                choose(str(R.string.b_keep), AutoBackup.keepChoices.map { it.toString() }, AutoBackup.keepChoices.indexOf(s.autoBackupKeep)) {
                    s.autoBackupKeep = AutoBackup.keepChoices[it]
                    changed()
                    build()
                }
            })
            add(Ui.row(this, pal, str(R.string.b_now), null) {
                AutoBackup.backupNow(this) { ok ->
                    Toast.makeText(applicationContext, if (ok) R.string.b_saved else R.string.b_save_failed, Toast.LENGTH_SHORT).show()
                    if (!isDestroyed) build()
                }
            })
        }

        add(Ui.header(this, pal, str(R.string.s_about)))
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: "?"
        add(Ui.row(this, pal, getString(R.string.s_version, version), str(R.string.s_about_sub), null))
        add(Ui.row(this, pal, str(R.string.s_source), "github.com/NullAngst/Corkboard") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NullAngst/Corkboard")))
            } catch (_: Exception) {
            }
        })
        list.addView(View(this), LinearLayout.LayoutParams(1, Ui.dp(this, 24f)))
    }

    private fun backupStatus(): String {
        val s = Store.layout.settings
        if (!s.autoBackupOn || s.autoBackupUri == null) return getString(R.string.b_auto_sub_off)
        s.autoBackupError?.let { return getString(R.string.b_failed, it) }
        if (s.lastAutoBackup <= 0) return getString(R.string.b_never)
        val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(s.lastAutoBackup))
        return getString(R.string.b_last, at)
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        try {
            startActivityForResult(i, reqTree)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cacheSummary(): String {
        val mb = Store.layout.settings.diskCacheMb
        if (mb == 0) return getString(R.string.s_cache_sub_off)
        val used = getString(R.string.s_cache_mb_used, DiskIcons.usedBytes(this) / (1024.0 * 1024.0))
        return getString(R.string.s_cache_sub, getString(R.string.s_cache_mb, mb), used)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            reqTree -> {
                val s = Store.layout.settings
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                    s.autoBackupUri?.takeIf { it != uri.toString() }?.let { old ->
                        try {
                            contentResolver.releasePersistableUriPermission(Uri.parse(old), flags)
                        } catch (_: Exception) {
                        }
                    }
                    s.autoBackupUri = uri.toString()
                    s.autoBackupOn = true
                    s.autoBackupError = null
                    changed()
                    AutoBackup.backupNow(this) { if (!isDestroyed) build() }
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.b_save_failed, Toast.LENGTH_SHORT).show()
                }
                build()
            }
            reqExport -> {
                // The layout is read here on the main thread; the zip, with every icon, is written off it.
                val json = Store.backupJson()
                val app = applicationContext
                Thread {
                    val ok = try {
                        val out = app.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("No output")
                        out.use { Store.exportZip(it, json) }
                        true
                    } catch (_: Exception) {
                        false
                    }
                    runOnUiThread {
                        Toast.makeText(app, if (ok) R.string.s_exported else R.string.s_export_failed, Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            reqImport -> {
                val app = applicationContext
                Thread {
                    val im = try {
                        app.contentResolver.openInputStream(uri)?.use { Store.importZip(it) }
                    } catch (_: Exception) {
                        null
                    }
                    runOnUiThread {
                        if (im == null) {
                            Toast.makeText(app, R.string.s_not_backup, Toast.LENGTH_LONG).show()
                        } else {
                            applyImport(im)
                            Toast.makeText(app, R.string.s_restored, Toast.LENGTH_SHORT).show()
                            if (!isDestroyed) build()
                        }
                    }
                }.start()
            }
        }
    }

    /** Swaps in a read backup. Main thread. */
    private fun applyImport(im: Store.Imported) {
        val restored = im.layout
        val app = applicationContext
        val host = AppWidgetHost(app, Apps.WIDGET_HOST_ID)
        for (it in Store.layout.allItems()) {
            if (it is WidgetItem && it.widgetId >= 0) try {
                host.deleteAppWidgetId(it.widgetId)
            } catch (_: Exception) {
            }
        }
        // These belong to this phone, not the backup: the backup folder's permission doesn't travel
        // in a zip, and the swipe-down check is per Android build.
        val here = Store.layout.settings
        restored.settings.autoBackupOn = here.autoBackupOn
        restored.settings.autoBackupUri = here.autoBackupUri
        restored.settings.autoBackupFreq = here.autoBackupFreq
        restored.settings.autoBackupKeep = here.autoBackupKeep
        restored.settings.lastAutoBackup = here.lastAutoBackup
        restored.settings.autoBackupError = here.autoBackupError
        restored.settings.shadeBroken = here.shadeBroken
        // Privacy switches stay as they are on this phone. Off is the default, and a backup never turns one on.
        restored.settings.dots = here.dots
        restored.settings.privateSpace = here.privateSpace
        Store.commitImport(im)
        Store.replace(restored)
        Apps.reload()
        // Apps installed here but not in the backup are not "new"; don't scatter them on the home screen.
        restored.seen.addAll(Apps.apps.keys.map { it.toString() })
        Apps.reconcile()
        IconRenderer.clear()
        DiskIcons.clearAll(app)
        Store.save()
        Store.cleanIcons()
        Dots.sync(app)
    }
}
