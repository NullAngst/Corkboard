/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Notification dots, off by default.
 *
 * Android only shows notification counts to an app holding Notification access, and that access
 * can read every notification in full. Corkboard keeps its side as narrow as it can:
 *  - The listener ships disabled in the manifest. Until the Privacy switch is on, Android has
 *    nothing to connect to, even if the access were granted by mistake.
 *  - From each notification it reads only the app, the user, and whether the app allows dots.
 *    Never the title, text, people or pictures.
 *  - The counts live in memory and are never written anywhere. The app has no internet permission.
 */
object Dots {
    /** "package#user serial" for every app with at least one notification that may show a dot. */
    private var keys: Set<String> = emptySet()

    /** Called on the main thread when the counts change. HomeActivity sets it. */
    var listener: (() -> Unit)? = null

    fun on(): Boolean = Store.layout.settings.dots

    fun any(appKeys: List<String>): Boolean = on() && appKeys.any { it in keys }

    fun keyOf(pkg: String, serial: Long) = "$pkg#$serial"

    fun keyOf(app: AppKey) = keyOf(app.pkg, app.user)

    /** Sets up an icon's dot: which apps it stands for, and its color. */
    fun attach(v: IconView, appKeys: List<String>, pal: Palette) {
        v.dotKeys = appKeys
        v.dotColor = if (Store.layout.settings.dotColor == "accent") pal.accent else 0
        v.dotFallback = pal.accent
        v.refreshDot()
    }

    /** Re-checks every icon under [v] after the counts changed. */
    fun refresh(v: View) {
        if (v is IconView) v.refreshDot()
        if (v is ViewGroup) for (i in 0 until v.childCount) refresh(v.getChildAt(i))
    }

    internal fun update(new: Set<String>) {
        if (new == keys) return
        keys = new
        listener?.invoke()
    }

    private fun component(ctx: Context) = ComponentName(ctx, DotListener::class.java)

    /**
     * Matches the listener's enabled state to the switch. Off disables it, so Android disconnects
     * it at once and stops delivering anything, whatever the Notification access setting says.
     */
    fun sync(ctx: Context) {
        val on = on()
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            val pm = ctx.packageManager
            if (pm.getComponentEnabledSetting(component(ctx)) != state) {
                pm.setComponentEnabledSetting(component(ctx), state, PackageManager.DONT_KILL_APP)
            }
            if (on) NotificationListenerService.requestRebind(component(ctx))
        } catch (_: Exception) {
        }
        if (!on) update(emptySet())
    }

    fun hasAccess(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(ctx)) == true
    } catch (_: Exception) {
        false
    }

    /** Android's Notification access screen, for Corkboard's listener where Android allows that. */
    fun accessIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    // ---- Dot color taken from the icon, like Pixel Launcher ----

    private val colors = WeakHashMap<Bitmap, Int>()

    /**
     * The icon's dominant vivid color: samples a 20 x 20 grid, skips see-through, grey, very dark and
     * very light pixels, groups the rest by hue and takes the strongest group. 0 when there is none
     * (a black and white icon), and the caller falls back to the theme accent.
     */
    fun colorOf(b: Bitmap): Int = colors.getOrPut(b) {
        try {
            val step = max(1, b.width / 20)
            val score = FloatArray(36)
            val best = IntArray(36)
            val bestS = FloatArray(36)
            val hsv = FloatArray(3)
            var y = 0
            while (y < b.height) {
                var x = 0
                while (x < b.width) {
                    val c = b.getPixel(x, y)
                    x += step
                    if (Color.alpha(c) < 128) continue
                    Color.colorToHSV(c, hsv)
                    if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
                    val bucket = (hsv[0] / 10f).toInt().coerceIn(0, 35)
                    val s = hsv[1] * hsv[2]
                    score[bucket] += s
                    if (s > bestS[bucket]) {
                        bestS[bucket] = s
                        best[bucket] = c
                    }
                }
                y += step
            }
            val top = score.indices.maxByOrNull { score[it] } ?: return@getOrPut 0
            if (score[top] <= 0f) 0 else best[top] or 0xFF000000.toInt()
        } catch (_: Exception) {
            0
        }
    }
}

/** The listener itself. See [Dots] for what it reads and why. Disabled in the manifest. */
class DotListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Store.init(this)
        Apps.init(this)
        recount()
    }

    override fun onListenerDisconnected() = Dots.update(emptySet())

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = recount()

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = recount()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = recount()

    private fun recount() {
        if (!Dots.on()) {
            Dots.update(emptySet())
            return
        }
        val active = try {
            activeNotifications
        } catch (_: Exception) {
            return
        } ?: return
        val ranking = try {
            currentRanking
        } catch (_: Exception) {
            null
        }
        val r = Ranking()
        val out = HashSet<String>()
        for (sbn in active) {
            if (!Apps.visible(sbn.user)) continue
            val n = sbn.notification ?: continue
            // A group's summary repeats the notifications under it.
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (ranking != null && ranking.getRanking(sbn.key, r)) {
                // The app, or the person in Android's settings, turned dots off for this channel.
                if (!r.canShowBadge()) continue
                // Ongoing notifications in an app's catch-all channel (music, downloads) aren't news.
                if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 && r.channel?.id == NotificationChannel.DEFAULT_CHANNEL_ID) continue
            }
            out.add(Dots.keyOf(sbn.packageName, Apps.serial(sbn.user)))
        }
        Dots.update(out)
    }
}
