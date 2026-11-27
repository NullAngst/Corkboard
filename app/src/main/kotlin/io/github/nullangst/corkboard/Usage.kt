/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/**
 * Most used apps, off by default (Settings > Privacy).
 *
 * Counts only what Corkboard itself opens: taps on the home screen, dock, folders and drawer, and
 * Enter in search. Opens from notifications, recents or other apps are never seen.
 *
 * Each app keeps one number that fades: an open adds 1, and every open's weight halves every
 * [HALF_LIFE_DAYS] days, so the most-used row follows what is used now. Stored per app is that
 * number and the day it last changed, nothing else: no list of opens, no times of day. An app
 * that fades below [FLOOR] (about four months after a single open) is dropped.
 *
 * The file is files/usage.json, apart from the layout, so it is never in a backup. Private space
 * apps are never counted. Turning the switch off deletes the file.
 */
object Usage {
    const val HALF_LIFE_DAYS = 14.0
    const val FLOOR = 0.003
    private const val FILE = "usage.json"
    private const val MAX_APPS = 2000

    private class Entry(var score: Double, var day: Long)

    private val entries = HashMap<String, Entry>()
    private var loaded = false
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    fun on(): Boolean = Store.layout.settings.mostUsed

    // ---- The math, plain Kotlin so it is unit tested ----

    /** [score] as of [from], faded to [to]. Days are whole days since 1970. */
    fun decay(score: Double, from: Long, to: Long): Double =
        if (to <= from) score else score * 0.5.pow((to - from) / HALF_LIFE_DAYS)

    /** The [n] highest-scoring items, highest first. Ties keep the list's order; faded-out items are left out. */
    fun <T> pick(items: List<T>, n: Int, scoreOf: (T) -> Double): List<T> =
        items.map { it to scoreOf(it) }
            .filter { it.second >= FLOOR }
            .sortedByDescending { it.second }
            .take(n.coerceAtLeast(0))
            .map { it.first }

    // ---- Counting ----

    /** Called after Corkboard opened an app. */
    fun opened(key: AppKey) {
        if (!on() || PrivateSpace.isPrivate(key.user) || !load()) return
        val today = today()
        val k = key.toString()
        val e = entries[k]
        if (e == null) {
            if (entries.size >= MAX_APPS) return
            entries[k] = Entry(1.0, today)
        } else {
            e.score = decay(e.score, e.day, today) + 1.0
            e.day = today
        }
        save()
    }

    /** How used an app is now; 0 when never opened or the switch is off. */
    fun score(key: AppKey): Double {
        if (!on() || !load()) return 0.0
        val e = entries[key.toString()] ?: return 0.0
        return decay(e.score, e.day, today())
    }

    /** The [n] most used of [apps], most used first. Apps never opened aren't in it. */
    fun top(apps: List<AppInfo>, n: Int): List<AppInfo> =
        if (!on()) emptyList() else pick(apps, n) { score(it.key) }

    /** Drops apps for which [keep] says no, such as ones no longer installed. */
    fun forget(keep: (String) -> Boolean) {
        if (!on() || !load()) return
        if (entries.keys.removeAll { !keep(it) }) save()
    }

    /** Forgets everything and deletes the file. */
    fun clear() {
        entries.clear()
        loaded = true
        try {
            file()?.delete()
        } catch (_: Exception) {
        }
    }

    // ---- The file ----

    private fun today(): Long = System.currentTimeMillis() / 86_400_000L

    private fun file(): AtomicFile? = app?.let { AtomicFile(File(it.filesDir, FILE)) }

    /** Reads the file once. False until [init] has given a place to keep it. */
    private fun load(): Boolean {
        if (loaded) return true
        val f = file() ?: return false
        loaded = true
        entries.clear()
        try {
            if (!f.baseFile.exists()) return true
            val o = JSONObject(String(f.readFully(), Charsets.UTF_8)).optJSONObject("apps") ?: return true
            val today = today()
            for (k in o.keys()) {
                if (entries.size >= MAX_APPS) break
                val a = o.optJSONArray(k) ?: continue
                val score = a.optDouble(0, 0.0)
                val day = a.optLong(1, today).coerceAtMost(today)
                if (score.isNaN() || score <= 0.0 || decay(score, day, today) < FLOOR) continue
                entries[k] = Entry(score.coerceAtMost(1e6), day)
            }
        } catch (_: Exception) {
            // Damaged: start counting again.
        }
        return true
    }

    private fun save() {
        val f = file() ?: return
        val apps = JSONObject()
        for ((k, e) in entries) apps.put(k, JSONArray().put(e.score).put(e.day))
        val bytes = JSONObject().put("version", 1).put("apps", apps).toString().toByteArray(Charsets.UTF_8)
        var out: java.io.FileOutputStream? = null
        try {
            out = f.startWrite()
            out.write(bytes)
            f.finishWrite(out)
        } catch (_: Exception) {
            out?.let { f.failWrite(it) }
        }
    }
}
