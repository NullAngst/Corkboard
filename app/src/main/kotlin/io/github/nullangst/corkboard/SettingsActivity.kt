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

    private fun changed(clearIcons: Boolean = false) {
        if (clearIcons) IconRenderer.clear()
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
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, msg: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(this, Themes.dialogTheme(pal))
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun build() {
        pal = Themes.palette(this)
        Ui.edgeToEdge(this, scroll, pal)
        scroll.setBackgroundColor(pal.bg)
        list.removeAllViews()
        val s = Store.layout.settings
        val add = { v: View -> list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

        add(Ui.title(this, pal, "Launcher settings"))

        if (!isDefaultHome(this)) {
            add(Ui.header(this, pal, "Default home"))
            add(Ui.row(this, pal, "Set as default home app", "App shortcuts, pinned shortcuts and pausing work apps need this") {
                requestHomeRole(this)
            })
        }

        add(Ui.header(this, pal, "Layout"))
        add(stepper("Columns", s.columns, 3, 6) {
            s.columns = it
            Store.layout.refit()
            changed(true)
            build()
        })
        add(stepper("Rows", s.rows, 4, 8) {
            s.rows = it
            Store.layout.refit()
            changed()
            build()
        })
        add(Ui.switchRow(this, pal, "Lock layout", s.locked, "No dragging") {
            s.locked = it
            changed()
        })
        add(Ui.switchRow(this, pal, "Add new apps to home screen", s.autoAdd) {
            s.autoAdd = it
            changed()
        })

        add(Ui.header(this, pal, "Drawer"))
        add(Ui.switchRow(this, pal, "Open keyboard with drawer", s.drawerKeyboard) {
            s.drawerKeyboard = it
            changed()
        })

        add(Ui.header(this, pal, "Icons"))
        add(Ui.row(this, pal, "Shape", IconRenderer.shapeName(s.shape)) {
            choose("Icon shape", IconRenderer.shapes.map { IconRenderer.shapeName(it) }, IconRenderer.shapes.indexOf(s.shape)) {
                s.shape = IconRenderer.shapes[it]
                changed(true)
                build()
            }
        })
        val sizeLabel = TextView(this).apply {
            setTextColor(pal.text)
            text = "Size: ${s.iconSize}%"
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
                    sizeLabel.text = "Size: ${80 + p * 5}%"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    s.iconSize = 80 + (sb?.progress ?: 4) * 5
                    changed(true)
                }
            })
        })
        add(Ui.switchRow(this, pal, "Show labels", s.labels) {
            s.labels = it
            changed()
        })
        add(Ui.row(this, pal, "Reset all custom icons", "Shapes, images, scale and backgrounds. Renamed labels stay") {
            confirm("Reset all custom icons?", "Every icon goes back to its default look.", "Reset") {
                val icons = Store.layout.icons
                for (k in icons.keys.toList()) {
                    val label = icons[k]?.label
                    if (label == null) icons.remove(k) else icons[k] = IconSettings(label = label)
                }
                changed(true)
                Store.cleanIcons()
            }
        })

        add(Ui.header(this, pal, "Apps"))
        val hidden = Store.layout.hidden.toList()
        if (hidden.isEmpty()) add(Ui.row(this, pal, "Hidden apps", "None", null))
        for (h in hidden) {
            val key = AppKey.parse(h)
            val app = key?.let { Apps.apps[it] }
            val name = (app?.label ?: key?.pkg ?: h) + if (key != null && Apps.isWork(key.user)) " (Work)" else ""
            add(Ui.row(this, pal, name, "Hidden", null).apply {
                addView(Ui.button(context, pal, "Unhide") {
                    Store.layout.hidden.remove(h)
                    changed()
                    build()
                })
            })
        }

        add(Ui.header(this, pal, "Look"))
        add(Ui.row(this, pal, "Theme", Themes.name(s.theme)) {
            choose("Theme", Themes.ids.map { Themes.name(it) }, Themes.ids.indexOf(s.theme)) {
                s.theme = Themes.ids[it]
                changed(true)
                build()
            }
        })

        add(Ui.header(this, pal, "Gestures"))
        add(Ui.switchRow(
            this, pal, "Swipe down for notifications", s.swipeDown && Shade.available,
            if (Shade.available) "Swipe down anywhere on the home screen" else "Not supported on this Android version",
            Shade.available,
        ) {
            s.swipeDown = it
            changed()
        })

        add(Ui.header(this, pal, "Backup"))
        add(Ui.row(this, pal, "Export layout", "Layout and custom icons to one file") {
            val name = "corkboard-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".zip"
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, name)
            startActivityForResult(i, reqExport)
        })
        add(Ui.row(this, pal, "Import layout", "Replaces the current layout") {
            confirm("Import a layout?", "This replaces your current home screen and custom icons. Widgets ask to be set up again.", "Choose file") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                startActivityForResult(i, reqImport)
            }
        })

        add(Ui.header(this, pal, "About"))
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: "?"
        add(Ui.row(this, pal, "Corkboard $version", "GPL-3.0. Built on ideas and code from Lawnchair and AOSP Launcher3", null))
        add(Ui.row(this, pal, "Source code", "github.com/NullAngst/Corkboard") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NullAngst/Corkboard")))
            } catch (_: Exception) {
            }
        })
        list.addView(View(this), LinearLayout.LayoutParams(1, Ui.dp(this, 24f)))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            reqExport -> try {
                contentResolver.openOutputStream(uri)?.use { Store.exportZip(it) }
                Toast.makeText(this, "Layout exported", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't write the backup", Toast.LENGTH_LONG).show()
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
                Store.save()
                Store.cleanIcons()
                Toast.makeText(this, "Layout restored", Toast.LENGTH_SHORT).show()
                build()
            } catch (_: Exception) {
                Toast.makeText(this, "That file isn't a Corkboard backup", Toast.LENGTH_LONG).show()
            }
        }
    }
}
