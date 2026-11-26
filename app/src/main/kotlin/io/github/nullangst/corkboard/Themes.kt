/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
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
    /** Material You needs Android 12's wallpaper color palettes, so it's only offered from there. */
    val ids: List<String>
        get() = listOf("system") +
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf("material") else emptyList()) +
            listOf("light", "dark", "verdigris", "kawaii_light", "kawaii_dark")

    fun name(ctx: Context, id: String): String = ctx.getString(
        when (id) {
            "light" -> R.string.theme_light
            "dark" -> R.string.theme_dark
            "verdigris" -> R.string.theme_verdigris
            "kawaii_light" -> R.string.theme_kawaii_light
            "kawaii_dark" -> R.string.theme_kawaii_dark
            "material" -> R.string.theme_material
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
        "material" -> material(ctx) ?: if (systemDark(ctx)) dark else light
        else -> if (systemDark(ctx)) dark else light
    }

    private fun systemDark(ctx: Context) =
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * Material You: the colors Android derives from the wallpaper, the same ones Pixel Launcher and
     * the system use, light or dark with the system. Mapped onto the Material 3 color roles:
     * background and surface from the neutral palette, text and dimmed text from the neutrals,
     * accent from the primary palette. Read fresh each time, so a new wallpaper's colors show up
     * at the next redraw.
     */
    private fun material(ctx: Context): Palette? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val r = ctx.resources
        fun col(id: Int) = r.getColor(id, null)
        return try {
            if (systemDark(ctx)) {
                Palette(
                    true,
                    bg = col(android.R.color.system_neutral1_900),
                    surface = col(android.R.color.system_neutral1_800),
                    text = col(android.R.color.system_neutral1_100),
                    textDim = col(android.R.color.system_neutral2_200),
                    accent = col(android.R.color.system_accent1_200),
                    onAccent = col(android.R.color.system_accent1_800),
                    panel = col(android.R.color.system_neutral1_800) and 0x00FFFFFF or c(0xCC000000),
                    scrim = c(0x88000000),
                    danger = c(0xFFFFB4AB),
                )
            } else {
                Palette(
                    false,
                    bg = col(android.R.color.system_neutral1_50),
                    surface = col(android.R.color.system_neutral1_10),
                    text = col(android.R.color.system_neutral1_900),
                    textDim = col(android.R.color.system_neutral2_700),
                    accent = col(android.R.color.system_accent1_600),
                    onAccent = col(android.R.color.system_accent1_0),
                    panel = col(android.R.color.system_neutral1_50) and 0x00FFFFFF or c(0xD9000000),
                    scrim = c(0x66000000),
                    danger = c(0xFFBA1A1A),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun dialogTheme(p: Palette) =
        if (p.isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
}
