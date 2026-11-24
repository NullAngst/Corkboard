/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable

/** Colors for the launcher's own surfaces: drawer, folder panels, menus, picker, edit icon, settings. */
class Palette(
    val isDark: Boolean,
    val bg: Int,
    val surface: Int,
    val text: Int,
    val textDim: Int,
    val accent: Int,
    val onAccent: Int,
    val panel: Int,
    val scrim: Int,
    val danger: Int,
) {
    fun card(ctx: Context, color: Int = surface, radiusDp: Float = 20f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
    }

    val accentList: ColorStateList get() = ColorStateList.valueOf(accent)
}

object Themes {
    val ids = listOf("system", "light", "dark", "verdigris", "kawaii_light", "kawaii_dark")

    fun name(ctx: Context, id: String): String = ctx.getString(
        when (id) {
            "light" -> R.string.theme_light
            "dark" -> R.string.theme_dark
            "verdigris" -> R.string.theme_verdigris
            "kawaii_light" -> R.string.theme_kawaii_light
            "kawaii_dark" -> R.string.theme_kawaii_dark
            else -> R.string.theme_system
        },
    )

    private fun c(v: Long) = v.toInt()

    private val light = Palette(
        false, c(0xFFF4F5F3), c(0xFFFFFFFF), c(0xFF1B1C1C), c(0xFF5E6366), c(0xFF2F6CA8),
        c(0xFFFFFFFF), c(0xD9F0F1EF), c(0x66000000), c(0xFFC62828),
    )
    private val dark = Palette(
        true, c(0xFF121314), c(0xFF202224), c(0xFFECEDEE), c(0xFFA3A7AB), c(0xFF8AB4F8),
        c(0xFF0F1A2A), c(0xCC2A2C2E), c(0x88000000), c(0xFFEF7A7A),
    )
    private val verdigris = Palette(
        true, c(0xFF0F201D), c(0xFF19332F), c(0xFFE4F1EC), c(0xFF9CBDB4), c(0xFFC27A44),
        c(0xFF0F201D), c(0xCC1C3A35), c(0x88000000), c(0xFFE58A7A),
    )
    private val kawaiiLight = Palette(
        false, c(0xFFFFF3F8), c(0xFFFFFFFF), c(0xFF4A2B3D), c(0xFF9A7488), c(0xFFE76FA3),
        c(0xFFFFFFFF), c(0xD9FFE3EF), c(0x66402030), c(0xFFD84B6B),
    )
    private val kawaiiDark = Palette(
        true, c(0xFF231722), c(0xFF33222F), c(0xFFFBE4EF), c(0xFFC9A3B6), c(0xFFF7A8C8),
        c(0xFF231722), c(0xCC3A2736), c(0x88000000), c(0xFFFF8FA3),
    )

    fun palette(ctx: Context, id: String = Store.layout.settings.theme): Palette = when (id) {
        "light" -> light
        "dark" -> dark
        "verdigris" -> verdigris
        "kawaii_light" -> kawaiiLight
        "kawaii_dark" -> kawaiiDark
        else -> {
            val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) dark else light
        }
    }

    fun dialogTheme(p: Palette) =
        if (p.isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
}
