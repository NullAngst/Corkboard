/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.app.ActivityOptions
import android.content.pm.LauncherUserInfo
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast

/**
 * Android 15's private space, off by default (Settings > Privacy).
 *
 * Android only shows a private space to the default home app, and only if it asks for
 * ACCESS_HIDDEN_PROFILES. With the switch off, Corkboard skips that profile entirely and never
 * lists its apps. With it on, they show in their own drawer tab and nowhere else: never on the home
 * screen, in folders, in the hidden list, in backups or in the icon cache on disk. Nothing about
 * them is written to storage.
 */
object PrivateSpace {
    /** Private space exists from Android 15. */
    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    fun enabled() = supported && Store.layout.settings.privateSpace

    fun isPrivateProfile(user: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        return try {
            Apps.la.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
        } catch (_: Exception) {
            false
        }
    }

    fun isPrivate(serial: Long) = serial == Apps.privateSerial

    /** The private profile, when the switch is on and one is set up. */
    fun user(): UserHandle? = if (enabled()) Apps.privateSerial?.let { Apps.user(it) } else null

    fun locked(): Boolean = Apps.privateSerial?.let { Apps.isQuiet(it) } ?: true

    /** Unlocking asks for the private space's own lock (PIN, pattern, fingerprint) through Android. */
    fun setLocked(lock: Boolean) {
        user()?.let { Apps.setQuiet(it, lock) }
    }

    /**
     * Android 16 lets the person hide the private space while it's locked (Settings > Private space
     * > Hide when locked). Launchers are told through the profile's config; earlier versions can't say.
     */
    private fun hiddenWhenLocked(user: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return try {
            Apps.la.getLauncherUserInfo(user)?.userConfig?.getBoolean(LauncherUserInfo.PRIVATE_SPACE_ENTRYPOINT_HIDDEN, false) == true
        } catch (_: Exception) {
            false
        }
    }

    /** Show the drawer's Private tab: switched on, set up, and not hidden while locked. */
    fun showTab(): Boolean {
        val u = user() ?: return false
        return !(locked() && hiddenWhenLocked(u))
    }

    /** Android 16 lets a launcher open the private space settings; Android 15 keeps them to itself. */
    val canOpenSettings get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /**
     * Android's private space settings. Android hands the launcher a sealed intent for it, and
     * since Android 14 starting such an intent counts as a background start unless the sender
     * says it allows one while it is on screen. Without that, Android drops the launch silently.
     */
    fun openSettings(act: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val opts = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        try {
            val sender = Apps.la.privateSpaceSettingsIntent ?: throw IllegalStateException("no settings intent")
            act.startIntentSender(sender, null, 0, 0, 0, opts.toBundle())
        } catch (_: Exception) {
            Toast.makeText(act, R.string.cant_open_app, Toast.LENGTH_SHORT).show()
        }
    }
}
