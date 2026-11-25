/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Vibration feedback with the phone's predefined effects (click, heavy click, tick, double click),
 * played through the system vibrator with the "touch" usage, so Android's own Touch feedback and
 * vibration strength settings still apply. Each moment belongs to a trigger the user can switch off
 * on its own, under a master switch.
 *
 * Earlier builds used View.performHapticFeedback with Android 14's lighter "segment tick" and
 * "gesture threshold" effects; on a Pixel those can be too faint to notice, which read as haptics
 * working only some of the time.
 */
object Haptics {
    const val DRAWER = "drawer"
    const val LIFT = "lift"
    const val FOLDER = "folder"
    const val PLACE = "place"
    const val REORDER = "reorder"
    const val PAGE = "page"
    const val REMOVE = "remove"

    val triggers = listOf(DRAWER, LIFT, FOLDER, PLACE, REORDER, PAGE, REMOVE)

    enum class Fx { LIFT, TICK, LIGHT, CONFIRM, REJECT, THRESHOLD }

    private var vibrator: Vibrator? = null

    fun init(ctx: Context) {
        if (vibrator != null) return
        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Vibrator::class.java)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun name(trigger: String) = when (trigger) {
        DRAWER -> R.string.h_drawer
        LIFT -> R.string.h_lift
        FOLDER -> R.string.h_folder
        PLACE -> R.string.h_place
        REORDER -> R.string.h_reorder
        PAGE -> R.string.h_page
        else -> R.string.h_remove
    }

    fun enabled(trigger: String): Boolean {
        val s = Store.layout.settings
        return s.haptics && trigger !in s.hapticOff
    }

    private fun effect(fx: Fx): Int = when (fx) {
        Fx.LIFT -> VibrationEffect.EFFECT_HEAVY_CLICK
        Fx.CONFIRM, Fx.THRESHOLD -> VibrationEffect.EFFECT_CLICK
        Fx.TICK, Fx.LIGHT -> VibrationEffect.EFFECT_TICK
        Fx.REJECT -> VibrationEffect.EFFECT_DOUBLE_CLICK
    }

    fun play(v: View?, trigger: String, fx: Fx) {
        if (!enabled(trigger)) return
        if (vibrator == null) v?.context?.let { init(it) }
        val vib = vibrator
        try {
            if (vib == null || !vib.hasVibrator()) {
                v?.rootView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return
            }
            val e = VibrationEffect.createPredefined(effect(fx))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(e, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
        } catch (_: Throwable) {
        }
    }
}
