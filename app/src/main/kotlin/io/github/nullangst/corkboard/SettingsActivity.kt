/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Small builders for the plain framework-view screens, colored from the launcher theme. */
object Ui {
    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun edgeToEdge(act: Activity, root: View, pal: Palette) {
        act.window.decorView.setBackgroundColor(pal.bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) act.window.setDecorFitsSystemWindows(false)
            act.window.insetsController?.setSystemBarsAppearance(
                if (pal.isDark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            act.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (pal.isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
        root.setOnApplyWindowInsetsListener { v, wi ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(wi.systemWindowInsetLeft, wi.systemWindowInsetTop, wi.systemWindowInsetRight, wi.systemWindowInsetBottom)
            }
            wi
        }
    }

    fun title(ctx: Context, pal: Palette, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 26f
        setTextColor(pal.text)
        setPadding(dp(ctx, 20f), dp(ctx, 20f), dp(ctx, 20f), dp(ctx, 12f))
    }

    fun header(ctx: Context, pal: Palette, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(pal.accent)
        setPadding(dp(ctx, 20f), dp(ctx, 20f), dp(ctx, 20f), dp(ctx, 6f))
    }

    private fun texts(ctx: Context, pal: Palette, title: String, sub: String?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(pal.text)
        })
        if (sub != null) addView(TextView(ctx).apply {
            text = sub
            textSize = 13f
            setTextColor(pal.textDim)
        })
    }

    private fun rowFrame(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 56f)
        setPadding(dp(ctx, 20f), dp(ctx, 8f), dp(ctx, 20f), dp(ctx, 8f))
    }

    fun row(ctx: Context, pal: Palette, title: String, sub: String? = null, onClick: (() -> Unit)?) = rowFrame(ctx).apply {
        addView(texts(ctx, pal, title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onClick != null) setOnClickListener { onClick() }
    }

    fun tint(sw: Switch, pal: Palette) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(pal.accent, pal.textDim))
        sw.trackTintList = ColorStateList(states, intArrayOf((pal.accent and 0x00FFFFFF) or 0x80000000.toInt(), (pal.textDim and 0x00FFFFFF) or 0x50000000))
    }

    fun switchRow(
        ctx: Context, pal: Palette, title: String, checked: Boolean, sub: String? = null, enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ) = rowFrame(ctx).apply {
        addView(texts(ctx, pal, title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            isEnabled = enabled
            tint(this, pal)
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        addView(sw)
        if (enabled) setOnClickListener { sw.toggle() } else alpha = 0.5f
    }

    fun button(ctx: Context, pal: Palette, text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(pal.onAccent)
        background = pal.card(ctx, pal.accent, 20f)
        setPadding(dp(ctx, 18f), dp(ctx, 10f), dp(ctx, 18f), dp(ctx, 10f))
        setOnClickListener { onClick() }
    }

    fun tint(sb: SeekBar, pal: Palette) {
        sb.progressTintList = pal.accentList
        sb.thumbTintList = pal.accentList
    }
}

class SettingsActivity : Activity() {
    private val reqExport = 31
    private val reqImport = 32
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

        add(Ui.header(this, pal, str(R.string.s_drawer)))
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
        })
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
            reqExport -> try {
                contentResolver.openOutputStream(uri)?.use { Store.exportZip(it) }
                Toast.makeText(this, R.string.s_exported, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.s_export_failed, Toast.LENGTH_LONG).show()
            }
            reqImport -> try {
                val restored = contentResolver.openInputStream(uri)?.use { Store.importZip(it) } ?: throw IllegalStateException()
                val host = AppWidgetHost(this, Apps.WIDGET_HOST_ID)
                for (it in Store.layout.allItems()) {
                    if (it is WidgetItem && it.widgetId >= 0) try {
                        host.deleteAppWidgetId(it.widgetId)
                    } catch (_: Exception) {
                    }
                }
                Store.replace(restored)
                Apps.reload()
                // Apps installed here but not in the backup are not "new"; don't scatter them on the home screen.
                restored.seen.addAll(Apps.apps.keys.map { it.toString() })
                Apps.reconcile()
                IconRenderer.clear()
                DiskIcons.clearAll(this)
                Store.save()
                Store.cleanIcons()
                Toast.makeText(this, R.string.s_restored, Toast.LENGTH_SHORT).show()
                build()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.s_not_backup, Toast.LENGTH_LONG).show()
            }
        }
    }
}
