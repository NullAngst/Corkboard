/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator

class AppInfo(val key: AppKey, val info: LauncherActivityInfo) {
    val user: UserHandle get() = info.user
    val baseLabel: String = info.label?.toString() ?: key.pkg
    val label: String get() = Store.layout.icons["app:$key"]?.label ?: baseLabel
    val isSystem: Boolean get() = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
    val version: String get() = info.applicationInfo.sourceDir ?: ""
}

/** LauncherApps queries, profiles, the package callback and the start-up reconcile. */
object Apps {
    const val WIDGET_HOST_ID = 1024

    lateinit var la: LauncherApps
    lateinit var um: UserManager
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    val apps = LinkedHashMap<AppKey, AppInfo>()
    private val users = HashMap<Long, UserHandle>()

    /** Called on the main thread after anything changed. HomeActivity sets it. */
    var listener: (() -> Unit)? = null
    var shortcutsListener: (() -> Unit)? = null

    val me: Long get() = serial(Process.myUserHandle())

    fun init(ctx: Context) {
        if (::la.isInitialized) return
        app = ctx.applicationContext
        la = app.getSystemService(LauncherApps::class.java)
        um = app.getSystemService(UserManager::class.java)
        la.registerCallback(callback, main)
        registerProfileReceiver()
    }

    fun serial(user: UserHandle): Long = um.getSerialNumberForUser(user)

    fun user(serial: Long): UserHandle? = users[serial] ?: um.getUserForSerialNumber(serial)

    fun isWork(serial: Long) = serial != me

    fun workUser(): UserHandle? = la.profiles.firstOrNull { it != Process.myUserHandle() }

    fun isQuiet(serial: Long): Boolean {
        if (serial == me) return false
        val u = user(serial) ?: return false
        return try {
            um.isQuietModeEnabled(u)
        } catch (_: Exception) {
            false
        }
    }

    fun setQuiet(user: UserHandle, quiet: Boolean): Boolean = try {
        um.requestQuietModeEnabled(quiet, user)
    } catch (_: Exception) {
        false
    }

    /**
     * Re-reads every launchable activity in every profile. Returns the profiles whose list can be
     * trusted for removals: a paused or locked work profile can come back empty, and that must not
     * wipe its apps off the home screen.
     */
    fun reload(): Set<Long> {
        apps.clear()
        users.clear()
        val trusted = HashSet<Long>()
        for (user in la.profiles) {
            val s = serial(user)
            users[s] = user
            val list = try {
                la.getActivityList(null, user)
            } catch (_: Exception) {
                null
            } ?: continue
            for (info in list) {
                if (info.componentName.packageName == app.packageName) continue
                val key = AppKey(info.componentName, s)
                apps[key] = AppInfo(key, info)
            }
            val unlocked = try {
                um.isUserUnlocked(user)
            } catch (_: Exception) {
                false
            }
            if (unlocked && !isQuiet(s)) trusted.add(s)
        }
        return trusted
    }

    private fun userTrusted(serial: Long, trusted: Set<Long>) = serial in trusted || user(serial) == null

    private fun packageInstalled(pkg: String, serial: Long): Boolean {
        val u = user(serial) ?: return false
        return try {
            la.getApplicationInfo(pkg, 0, u)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Compares installed apps against the seen set. New apps go to the drawer, and onto the home
     * screen if auto-add is on. Apps that are gone are removed everywhere.
     */
    fun reconcile(): Boolean {
        val trusted = reload()
        val l = Store.layout
        var changed = false

        // Anything referenced that is no longer installed.
        val referenced = HashSet<String>()
        referenced.addAll(l.seen)
        referenced.addAll(l.hidden)
        for (it in l.allItems()) {
            when (it) {
                is AppItem -> referenced.add(it.key.toString())
                is FolderItem -> it.apps.forEach { k -> referenced.add(k.toString()) }
                else -> {}
            }
        }
        l.icons.keys.filter { it.startsWith("app:") }.forEach { referenced.add(it.removePrefix("app:")) }
        for (s in referenced) {
            val key = AppKey.parse(s)
            if (key == null) {
                l.seen.remove(s)
                l.hidden.remove(s)
                l.icons.remove("app:$s")
                changed = true
                continue
            }
            if (apps.containsKey(key) || !userTrusted(key.user, trusted)) continue
            l.seen.remove(s)
            l.hidden.remove(s)
            l.icons.remove("app:$s")
            l.removeApp(key)
            changed = true
        }

        // Pinned shortcuts and widgets whose app is gone.
        val awm = AppWidgetManager.getInstance(app)
        // A restored widget spot is checked against the widget list itself: an app that only provides
        // widgets has no launcher icon, so package visibility hides it from LauncherApps.
        val providers = HashMap<Long, Set<android.content.ComponentName>>()
        fun providerExists(p: android.content.ComponentName, serial: Long): Boolean = p in providers.getOrPut(serial) {
            val u = user(serial)
            try {
                if (u == null) emptySet() else awm.getInstalledProvidersForProfile(u).map { it.provider }.toHashSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
        for (list in l.containers()) {
            val dead = list.filter {
                when (it) {
                    is ShortcutItem -> userTrusted(it.user, trusted) && !packageInstalled(it.pkg, it.user)
                    is WidgetItem -> userTrusted(it.user, trusted) && if (it.widgetId >= 0) {
                        awm.getAppWidgetInfo(it.widgetId) == null
                    } else {
                        it.provider?.let { p -> !providerExists(p, it.user) } ?: true
                    }
                    else -> false
                }
            }
            for (it in dead) {
                list.remove(it)
                l.icons.remove("item:${it.id}")
                if (it is WidgetItem && it.widgetId >= 0) {
                    try {
                        AppWidgetHost(app, WIDGET_HOST_ID).deleteAppWidgetId(it.widgetId)
                    } catch (_: Exception) {
                    }
                }
                changed = true
            }
        }

        // First start: everything already installed counts as seen. The home screen starts empty
        // and the drawer holds every app, instead of 150 icons spilling across a dozen pages.
        if (Store.fresh) {
            Store.fresh = false
            for (key in apps.keys) l.seen.add(key.toString())
            changed = true
        }

        // New apps.
        for (key in apps.keys) {
            val s = key.toString()
            if (s in l.seen) continue
            l.seen.add(s)
            changed = true
            if (l.settings.autoAdd && s !in l.hidden) l.placeFirstFree(AppItem(l.newId(), key))
        }

        if (changed) {
            Store.save()
            Store.cleanIcons()
        }
        return changed
    }

    /** Apps for the drawer: installed, not hidden, sorted by label. */
    fun drawerApps(work: Boolean): List<AppInfo> {
        val hidden = Store.layout.hidden
        // Sorted the way the phone's language sorts: "Ärzte" next to "Apps", not after "Zoom".
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return apps.values
            .filter { isWork(it.key.user) == work && it.key.toString() !in hidden }
            .map { it to collator.getCollationKey(it.label) }
            .sortedWith { a, b -> a.second.compareTo(b.second) }
            .map { it.first }
    }

    private fun changed(pkg: String?) {
        if (pkg != null) IconRenderer.clearPackage(pkg)
        reconcile()
        listener?.invoke()
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            packageNames.forEach { IconRenderer.clearPackage(it) }
            changed(null)
        }

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            listener?.invoke()
        }

        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            IconRenderer.clearPackage(packageName)
            shortcutsListener?.invoke()
        }
    }

    @SuppressLint("InlinedApi", "UnspecifiedRegisterReceiverFlag")
    private fun registerProfileReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                IconRenderer.clear()
                changed(null)
            }
        }
        // Protected system broadcasts, so exported is correct here.
        app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }
}
