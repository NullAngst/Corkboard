/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Vibration feedback through View.performHapticFeedback: the phone's own tuned effects, no VIBRATE
 * permission, and it follows Android's "Touch feedback" setting. Each moment belongs to a trigger
 * the user can switch off on its own, under a master switch.
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

    fun play(v: View?, trigger: String, fx: Fx) {
        if (v == null || !enabled(trigger)) return
        try {
            v.performHapticFeedback(constant(fx))
        } catch (_: Throwable) {
        }
    }

    /** The richest effect this Android version has, falling back to older ones on Android 10 to 13. */
    private fun constant(fx: Fx): Int = when (fx) {
        Fx.LIFT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.DRAG_START
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        Fx.TICK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        Fx.LIGHT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        Fx.CONFIRM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        Fx.REJECT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        Fx.THRESHOLD -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
    }
}
