/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import java.lang.reflect.Method

/**
 * Swipe down for the notification shade. Android has no public API for this, so it calls
 * StatusBarManager.expandNotificationsPanel() through reflection and fails safe: any throw marks it
 * unavailable for this Android build and launcher version, and it tries again once after an update.
 */
object Shade {
    private var method: Method? = null
    private var tried = false

    var available = false
        private set

    private fun stamp(ctx: Context): String {
        val code = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        } catch (_: Throwable) {
            0L
        }
        return Build.FINGERPRINT + "|" + code
    }

    @SuppressLint("WrongConstant", "PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    fun init(ctx: Context) {
        if (tried) return
        tried = true
        try {
            val s = Store.layout.settings
            val now = stamp(ctx)
            if (s.shadeBroken == now) return
            val svc = ctx.getSystemService("statusbar") ?: throw IllegalStateException("no statusbar service")
            method = svc.javaClass.getMethod("expandNotificationsPanel")
            available = true
            if (s.shadeBroken != null) {
                s.shadeBroken = null
                Store.save()
            }
        } catch (_: Throwable) {
            markBroken(ctx)
        }
    }

    @SuppressLint("WrongConstant")
    fun expand(ctx: Context) {
        if (!available || !Store.layout.settings.swipeDown) return
        try {
            method!!.invoke(ctx.getSystemService("statusbar"))
        } catch (_: Throwable) {
            markBroken(ctx)
        }
    }

    private fun markBroken(ctx: Context) {
        available = false
        try {
            Store.layout.settings.shadeBroken = stamp(ctx)
            Store.save()
        } catch (_: Throwable) {
        }
    }
}
