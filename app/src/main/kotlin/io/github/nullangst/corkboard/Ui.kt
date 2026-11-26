/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
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
