/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** An app is its launchable activity plus the profile (user) serial number it lives in. */
data class AppKey(val component: ComponentName, val user: Long) {
    // Built once: this string is the key for icons, hidden apps and the seen set, looked up per icon.
    private val text = component.flattenToShortString() + "#" + user
    override fun toString() = text
    val pkg: String get() = component.packageName

    companion object {
        fun parse(s: String): AppKey? {
            val i = s.lastIndexOf('#')
            if (i < 0) return null
            val cn = ComponentName.unflattenFromString(s.substring(0, i)) ?: return null
            val u = s.substring(i + 1).toLongOrNull() ?: return null
            return AppKey(cn, u)
        }
    }
}

/** Largest cell coordinate or span read from a file. Anything past it is damage, and refit() moves it. */
private const val MAX_CELL = 32

/** Ids stay well below Long.MAX_VALUE so counting up can never wrap around to a negative id. */
private const val MAX_ID = 1L shl 40

sealed class Item(id: Long) {
    /** Only changed when loading a file whose ids clash or are out of range. */
    var id = id
        internal set
    var x = 0
    var y = 0
    var w = 1
    var h = 1
}

class AppItem(id: Long, val key: AppKey) : Item(id)
class FolderItem(id: Long, var name: String, val apps: MutableList<AppKey>) : Item(id)
class ShortcutItem(id: Long, val pkg: String, val shortcutId: String, val user: Long, var label: String) : Item(id)

/** The app drawer button, when the drawer opens from a button instead of a swipe. */
class DrawerButtonItem(id: Long) : Item(id)

/** widgetId is -1 for a restored widget that still needs to be set up again. */
class WidgetItem(id: Long, var widgetId: Int, var provider: ComponentName?, var user: Long) : Item(id)

class IconSettings(
    var source: String = "original",
    var file: String? = null,
    var shape: String = "global",
    var treatment: String = "auto",
    var scale: Int = 100,
    var background: String = "auto",
    var label: String? = null,
    /** Themed look: "auto", "generate", "image" (from monoFile) or "off". */
    var mono: String = "auto",
    var monoFile: String? = null,
) {
    fun copy() = IconSettings(source, file, shape, treatment, scale, background, label, mono, monoFile)

    fun isDefault() = source == "original" && shape == "global" && treatment == "auto" &&
        scale == 100 && background == "auto" && label == null && mono == "auto" && monoFile == null

    fun look() = "$source|$file|$shape|$treatment|$scale|$background|$mono|$monoFile"
}

class Settings {
    var columns = 4
    var rows = 6
    var shape = "system"
    var iconSize = 100
    var labels = true
    var autoAdd = true
    var locked = false
    var drawerKeyboard = false
    var theme = "system"
    var swipeDown = true
    var themedIcons = false

    /** With themed icons on, apps without a monochrome layer get one generated, like Pixel Launcher. */
    var themedGenerate = true

    /** The drawer opens from a button item instead of swiping up. */
    var drawerButton = false

    /** Disk icon cache size in MB. 0 turns it off. */
    var diskCacheMb = 25

    var allowRotation = false

    /** Master haptics switch, and the triggers turned off one by one (so new triggers start on). */
    var haptics = true
    val hapticOff: MutableSet<String> = linkedSetOf()

    /** Automatic backup: on/off, a folder tree URI the user granted, how often, and how many to keep. */
    var autoBackupOn = false
    var autoBackupUri: String? = null
    var autoBackupFreq = "daily"
    var autoBackupKeep = 3
    var lastAutoBackup = 0L
    var autoBackupError: String? = null

    /** "fingerprint|versionCode" of the build where the swipe-down hidden API failed. */
    var shadeBroken: String? = null

    /**
     * Privacy switches, see Settings > Privacy. Each is off until turned on, and each belongs to
     * this phone: restoring a backup never turns one on.
     */
    var dots = false
    var privateSpace = false
    var mostUsed = false

    /** "icon" takes the dot color from the app's icon, "accent" uses the theme accent. */
    var dotColor = "icon"
}

class Layout {
    val settings = Settings()
    val pages: MutableList<MutableList<Item>> = mutableListOf(mutableListOf())
    val dock: MutableList<Item> = mutableListOf()
    val seen: MutableSet<String> = linkedSetOf()
    val hidden: MutableSet<String> = linkedSetOf()
    val icons: MutableMap<String, IconSettings> = linkedMapOf()
    var nextId = 1L

    fun newId() = nextId++

    fun containers(): List<MutableList<Item>> = pages + listOf(dock)

    fun allItems(): Sequence<Item> = containers().asSequence().flatten()

    fun containerOf(item: Item): MutableList<Item>? = containers().firstOrNull { list -> list.any { it === item } }

    fun pageOf(item: Item): Int = pages.indexOfFirst { list -> list.any { it === item } }

    fun findItem(id: Long): Item? = allItems().firstOrNull { it.id == id }

    fun remove(item: Item) {
        containerOf(item)?.removeAll { it === item }
    }

    /** Free cells of one page, ignoring [exclude]. */
    fun occupancy(items: List<Item>, cols: Int, rows: Int, exclude: Item? = null): Array<BooleanArray> {
        val g = Array(rows) { BooleanArray(cols) }
        for (it in items) {
            if (it === exclude) continue
            for (yy in it.y until it.y + it.h) for (xx in it.x until it.x + it.w) {
                if (yy in 0 until rows && xx in 0 until cols) g[yy][xx] = true
            }
        }
        return g
    }

    /** First free spot that fits w x h on [page], scanning left to right, top to bottom. */
    fun freeSpot(page: Int, w: Int, h: Int): Cell? {
        val cols = settings.columns
        val rows = settings.rows
        if (page !in pages.indices || w > cols || h > rows) return null
        val g = occupancy(pages[page], cols, rows)
        for (y in 0..rows - h) for (x in 0..cols - w) {
            if (fits(g, x, y, w, h)) return Cell(x, y)
        }
        return null
    }

    /** Places [item] in the first free spot from page [start] on, adding a page at the end if all are full. */
    fun placeFirstFree(item: Item, start: Int = 0) {
        for (p in start until pages.size) {
            val spot = freeSpot(p, item.w, item.h) ?: continue
            item.x = spot.x
            item.y = spot.y
            pages[p].add(item)
            return
        }
        item.x = 0
        item.y = 0
        pages.add(mutableListOf(item))
    }

    fun firstFreeDockSlot(): Int? {
        val used = dock.map { it.x }.toSet()
        return (0 until settings.columns).firstOrNull { it !in used }
    }

    /** Removes every trace of an app from home, dock and folders. Folders left with one app collapse. */
    fun removeApp(key: AppKey) {
        for (list in containers()) {
            list.removeAll { it is AppItem && it.key == key }
            for (f in list.filterIsInstance<FolderItem>()) f.apps.remove(key)
        }
        fixFolders()
    }

    /** A folder left with one app turns back into that app. A folder left with none is deleted. */
    fun fixFolders() {
        for (list in containers()) {
            for (i in list.indices.reversed()) {
                val f = list[i] as? FolderItem ?: continue
                when (f.apps.size) {
                    0 -> {
                        list.removeAt(i)
                        icons.remove("item:${f.id}")
                    }
                    1 -> {
                        val a = AppItem(newId(), f.apps[0])
                        a.x = f.x
                        a.y = f.y
                        list[i] = a
                        icons.remove("item:${f.id}")
                    }
                }
            }
        }
    }

    /** Moves anything that no longer fits after a grid size change. */
    fun refit() {
        val cols = settings.columns
        val rows = settings.rows
        val misfits = mutableListOf<Item>()
        for (page in pages) {
            val keep = mutableListOf<Item>()
            for (it in page.sortedWith(compareBy({ it.y }, { it.x }))) {
                if (it.w > cols) it.w = cols
                if (it.h > rows) it.h = rows
                val fitsGrid = it.x + it.w <= cols && it.y + it.h <= rows
                val g = occupancy(keep, cols, rows)
                if (fitsGrid && fits(g, it.x, it.y, it.w, it.h)) keep.add(it) else misfits.add(it)
            }
            // retainAll keeps the stored order, so a page with nothing wrong comes out unchanged.
            val kept = keep.toHashSet()
            page.retainAll { it in kept }
        }
        // The dock is one row: each slot once, inside the grid.
        val taken = HashSet<Int>()
        for (it in dock.toList()) {
            if (it.x !in 0 until cols || !taken.add(it.x)) {
                dock.remove(it)
                misfits.add(it)
            }
        }
        for (it in misfits) placeFirstFree(it)
    }

    /**
     * Makes a freshly read layout safe to use: unique ids with room to count up, folders without
     * repeats, and every item inside the grid without overlaps. A layout saved by Corkboard itself
     * comes through unchanged.
     */
    fun sanitize(storedNextId: Long) {
        val used = HashSet<Long>()
        val clash = ArrayList<Item>()
        for (it in allItems()) if (it.id !in 0..MAX_ID || !used.add(it.id)) clash.add(it)
        nextId = maxOf(storedNextId.takeIf { it in 1..MAX_ID } ?: 1L, (used.maxOrNull() ?: 0L) + 1)
        for (it in clash) it.id = newId()
        for (f in allItems().filterIsInstance<FolderItem>()) {
            val unique = f.apps.distinct()
            if (unique.size != f.apps.size) {
                f.apps.clear()
                f.apps.addAll(unique)
            }
        }
        fixFolders()
        refit()
    }

    /** Puts the drawer button in the dock (middle slot if free) or on the home screen, or removes it. */
    fun setDrawerButton(on: Boolean) {
        settings.drawerButton = on
        val existing = allItems().filterIsInstance<DrawerButtonItem>().toList()
        if (!on) {
            existing.forEach { remove(it) }
            return
        }
        if (existing.isNotEmpty()) return
        val item = DrawerButtonItem(newId())
        val used = dock.map { it.x }.toSet()
        val mid = settings.columns / 2
        val slot = if (mid !in used) mid else firstFreeDockSlot()
        if (slot != null) {
            item.x = slot
            item.y = 0
            dock.add(item)
        } else {
            placeFirstFree(item)
        }
    }

    fun pruneEmptyPages(keep: Int): Int {
        var current = keep
        for (i in pages.indices.reversed()) {
            if (i != 0 && i != current && pages[i].isEmpty()) {
                pages.removeAt(i)
                if (i < current) current--
            }
        }
        return current
    }

    companion object {
        fun fits(g: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int): Boolean {
            if (y < 0 || x < 0 || y + h > g.size || x + w > (g.firstOrNull()?.size ?: 0)) return false
            for (yy in y until y + h) for (xx in x until x + w) if (g[yy][xx]) return false
            return true
        }
    }
}

/** One JSON file, files/layout.json, plus custom icon PNGs in files/icons/. */
object Store {
    lateinit var layout: Layout
        private set
    lateinit var filesDir: File
        private set
    private lateinit var file: AtomicFile

    /** True on the very first start, before any layout.json existed. */
    var fresh = false

    /** Bumped on every save, so screens know to rebind. */
    var revision = 0
        private set

    val iconsDir: File get() = File(filesDir, "icons").apply { mkdirs() }

    private var app: Context? = null

    /** Where an import is unpacked and checked before anything in use changes. */
    private val stagingDir: File get() = File(filesDir, "import-staging")

    /**
     * Icon files picked in Edit icon but not saved yet. A package change can run [cleanIcons] while
     * the editor is open, and without this it would delete the image the user just picked.
     */
    val pendingIcons: MutableSet<String> = HashSet()

    fun init(ctx: Context) {
        if (::layout.isInitialized) return
        app = ctx.applicationContext
        filesDir = ctx.applicationContext.filesDir
        // Left over only if the process died mid-import.
        stagingDir.deleteRecursively()
        file = AtomicFile(File(filesDir, "layout.json"))
        fresh = !file.baseFile.exists()
        layout = try {
            fromJson(JSONObject(String(file.readFully(), Charsets.UTF_8)))
        } catch (_: Throwable) {
            // An unreadable layout is kept as layout.json.bad instead of being overwritten by the
            // next save, and the start counts as a first start so apps don't spill onto the home screen.
            if (file.baseFile.exists()) {
                try {
                    file.baseFile.copyTo(File(filesDir, "layout.json.bad"), overwrite = true)
                } catch (_: Exception) {
                }
                fresh = true
            }
            Layout()
        }
    }

    private val safeName = Regex("[A-Za-z0-9._-]{1,128}")

    /** A plain file name we'd write ourselves, never a path: no slashes, no "." or "..". */
    private fun iconName(name: String): String? = name.takeIf { safeName.matches(it) && it != "." && it != ".." }

    /**
     * The custom icon file a stored name points at ("icons/x.png"), or null if the name isn't one
     * Corkboard would write. Names come from layout.json and from backups, so they're never used as paths.
     */
    fun iconFile(stored: String?): File? = stored?.substringAfterLast('/')?.let { iconName(it) }?.let { File(iconsDir, it) }

    /** Saves, and lets automatic backup know something changed. */
    fun save() {
        saveQuiet()
        app?.let { AutoBackup.changed(it) }
    }

    /** Saves without counting as a layout change (backup status, bookkeeping). */
    fun saveQuiet() {
        revision++
        val out = try {
            file.startWrite()
        } catch (_: Exception) {
            return
        }
        try {
            out.write(toJson(layout).toString(1).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (_: Exception) {
            file.failWrite(out)
        }
    }

    fun replace(l: Layout) {
        layout = l
        save()
    }

    /** Deletes custom icon files nothing points at any more. */
    fun cleanIcons() {
        val used = layout.icons.values.flatMap { listOfNotNull(it.file, it.monoFile) }.map { it.substringAfterLast('/') }.toSet()
        iconsDir.listFiles()?.forEach { if (it.name !in used && it.name !in pendingIcons) it.delete() }
    }

    // ---- JSON ----

    private fun itemJson(it: Item): JSONObject = JSONObject().apply {
        put("id", it.id)
        put("x", it.x)
        put("y", it.y)
        put("w", it.w)
        put("h", it.h)
        when (it) {
            is AppItem -> {
                put("type", "app")
                put("component", it.key.component.flattenToShortString())
                put("user", it.key.user)
            }
            is FolderItem -> {
                put("type", "folder")
                put("name", it.name)
                put("apps", JSONArray().apply {
                    for (k in it.apps) put(JSONObject().put("component", k.component.flattenToShortString()).put("user", k.user))
                })
            }
            is ShortcutItem -> {
                put("type", "shortcut")
                put("package", it.pkg)
                put("shortcutId", it.shortcutId)
                put("user", it.user)
                put("label", it.label)
            }
            is DrawerButtonItem -> put("type", "drawer")
            is WidgetItem -> {
                put("type", "widget")
                put("widgetId", it.widgetId)
                it.provider?.let { p -> put("provider", p.flattenToShortString()) }
                put("user", it.user)
            }
        }
    }

    private fun keyOf(o: JSONObject): AppKey? {
        val cn = ComponentName.unflattenFromString(o.optString("component")) ?: return null
        return AppKey(cn, o.optLong("user", 0))
    }

    private fun itemFrom(o: JSONObject): Item? {
        val id = o.optLong("id", -1)
        if (id < 0) return null
        val item = when (o.optString("type")) {
            "app" -> AppItem(id, keyOf(o) ?: return null)
            "folder" -> {
                val arr = o.optJSONArray("apps") ?: JSONArray()
                val apps = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { keyOf(it) } }
                FolderItem(id, o.optString("name", "Folder"), apps.toMutableList())
            }
            "shortcut" -> ShortcutItem(
                id, o.optString("package"), o.optString("shortcutId"), o.optLong("user", 0), o.optString("label"),
            )
            "drawer" -> DrawerButtonItem(id)
            "widget" -> WidgetItem(
                id, o.optInt("widgetId", -1),
                ComponentName.unflattenFromString(o.optString("provider")), o.optLong("user", 0),
            )
            else -> return null
        }
        item.x = o.optInt("x", 0).coerceIn(0, MAX_CELL)
        item.y = o.optInt("y", 0).coerceIn(0, MAX_CELL)
        // Only widgets span cells; an app, folder or shortcut is always one cell.
        if (item is WidgetItem) {
            item.w = o.optInt("w", 1).coerceIn(1, MAX_CELL)
            item.h = o.optInt("h", 1).coerceIn(1, MAX_CELL)
        }
        return item
    }

    fun toJson(l: Layout): JSONObject {
        val s = l.settings
        return JSONObject().apply {
            put("version", 1)
            put("nextId", l.nextId)
            put("settings", JSONObject().apply {
                put("columns", s.columns)
                put("rows", s.rows)
                put("shape", s.shape)
                put("iconSize", s.iconSize)
                put("labels", s.labels)
                put("autoAdd", s.autoAdd)
                put("locked", s.locked)
                put("drawerKeyboard", s.drawerKeyboard)
                put("theme", s.theme)
                put("swipeDown", s.swipeDown)
                put("themedIcons", s.themedIcons)
                put("themedGenerate", s.themedGenerate)
                put("drawerButton", s.drawerButton)
                put("diskCacheMb", s.diskCacheMb)
                put("allowRotation", s.allowRotation)
                put("haptics", s.haptics)
                put("hapticOff", JSONArray(s.hapticOff.toList()))
                s.autoBackupUri?.let { put("autoBackupUri", it) }
                put("autoBackupOn", s.autoBackupOn)
                put("autoBackupFreq", s.autoBackupFreq)
                put("autoBackupKeep", s.autoBackupKeep)
                put("lastAutoBackup", s.lastAutoBackup)
                s.autoBackupError?.let { put("autoBackupError", it) }
                s.shadeBroken?.let { put("shadeBroken", it) }
                put("dots", s.dots)
                put("dotColor", s.dotColor)
                put("privateSpace", s.privateSpace)
                put("mostUsed", s.mostUsed)
            })
            put("pages", JSONArray().apply {
                for (page in l.pages) put(JSONArray().apply { for (it in page) put(itemJson(it)) })
            })
            put("dock", JSONArray().apply { for (it in l.dock) put(itemJson(it)) })
            put("seen", JSONArray(l.seen.toList()))
            put("hidden", JSONArray(l.hidden.toList()))
            put("icons", JSONObject().apply {
                for ((k, v) in l.icons) {
                    put(k, JSONObject().apply {
                        put("source", v.source)
                        v.file?.let { put("file", it) }
                        put("shape", v.shape)
                        put("treatment", v.treatment)
                        put("scale", v.scale)
                        put("background", v.background)
                        v.label?.let { put("label", it) }
                        put("mono", v.mono)
                        v.monoFile?.let { put("monoFile", it) }
                    })
                }
            })
        }
    }

    fun fromJson(o: JSONObject): Layout {
        // "version" is here so a future format change can migrate old files instead of wiping them.
        val l = Layout()
        o.optJSONObject("settings")?.let { j ->
            val s = l.settings
            s.columns = j.optInt("columns", 4).coerceIn(3, 6)
            s.rows = j.optInt("rows", 6).coerceIn(4, 8)
            s.shape = j.optString("shape", "system")
            s.iconSize = j.optInt("iconSize", 100).coerceIn(80, 120)
            s.labels = j.optBoolean("labels", true)
            s.autoAdd = j.optBoolean("autoAdd", true)
            s.locked = j.optBoolean("locked", false)
            s.drawerKeyboard = j.optBoolean("drawerKeyboard", false)
            s.theme = j.optString("theme", "system")
            s.swipeDown = j.optBoolean("swipeDown", true)
            s.themedIcons = j.optBoolean("themedIcons", false)
            s.themedGenerate = j.optBoolean("themedGenerate", true)
            s.drawerButton = j.optBoolean("drawerButton", false)
            s.diskCacheMb = j.optInt("diskCacheMb", 25).coerceIn(0, 2000)
            s.allowRotation = j.optBoolean("allowRotation", false)
            s.haptics = j.optBoolean("haptics", true)
            j.optJSONArray("hapticOff")?.let { a -> for (i in 0 until a.length()) s.hapticOff.add(a.optString(i)) }
            s.autoBackupUri = if (j.has("autoBackupUri")) j.optString("autoBackupUri") else null
            s.autoBackupOn = j.optBoolean("autoBackupOn", false)
            s.autoBackupFreq = j.optString("autoBackupFreq", "daily")
            s.autoBackupKeep = j.optInt("autoBackupKeep", 3).coerceIn(1, 100)
            s.lastAutoBackup = j.optLong("lastAutoBackup", 0)
            s.autoBackupError = if (j.has("autoBackupError")) j.optString("autoBackupError") else null
            s.shadeBroken = if (j.has("shadeBroken")) j.optString("shadeBroken") else null
            s.dots = j.optBoolean("dots", false)
            s.dotColor = if (j.optString("dotColor") == "accent") "accent" else "icon"
            s.privateSpace = j.optBoolean("privateSpace", false)
            s.mostUsed = j.optBoolean("mostUsed", false)
        }
        l.pages.clear()
        o.optJSONArray("pages")?.let { pages ->
            for (i in 0 until pages.length()) {
                val arr = pages.optJSONArray(i) ?: continue
                l.pages.add((0 until arr.length()).mapNotNull { j -> arr.optJSONObject(j)?.let { itemFrom(it) } }.toMutableList())
            }
        }
        if (l.pages.isEmpty()) l.pages.add(mutableListOf())
        o.optJSONArray("dock")?.let { arr ->
            for (j in 0 until arr.length()) {
                val it = arr.optJSONObject(j)?.let { x -> itemFrom(x) } ?: continue
                if (it !is WidgetItem) {
                    it.y = 0
                    it.w = 1
                    it.h = 1
                    l.dock.add(it)
                }
            }
        }
        o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) l.seen.add(a.optString(i)) }
        o.optJSONArray("hidden")?.let { a -> for (i in 0 until a.length()) l.hidden.add(a.optString(i)) }
        o.optJSONObject("icons")?.let { icons ->
            for (k in icons.keys()) {
                val j = icons.optJSONObject(k) ?: continue
                l.icons[k] = IconSettings(
                    j.optString("source", "original"),
                    if (j.has("file")) j.optString("file") else null,
                    j.optString("shape", "global"),
                    j.optString("treatment", "auto"),
                    j.optInt("scale", 100).coerceIn(50, 150),
                    j.optString("background", "auto"),
                    if (j.has("label")) j.optString("label") else null,
                    j.optString("mono", "auto"),
                    if (j.has("monoFile")) j.optString("monoFile") else null,
                )
            }
        }
        l.sanitize(o.optLong("nextId", 1))
        return l
    }

    // ---- Backup ----

    /** The layout as backup bytes. Call on the main thread; the zip itself can be written anywhere. */
    fun backupJson(): ByteArray = toJson(layout).toString(1).toByteArray(Charsets.UTF_8)

    /** Writes the backup zip. Safe off the main thread: an icon deleted meanwhile is just left out. */
    fun exportZip(out: OutputStream, json: ByteArray = backupJson()) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("layout.json"))
            zip.write(json)
            zip.closeEntry()
            for (f in iconsDir.listFiles().orEmpty()) {
                val data = try {
                    f.readBytes()
                } catch (_: IOException) {
                    continue
                }
                zip.putNextEntry(ZipEntry("icons/" + f.name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    // A real backup is a few hundred KB. These limits stop a damaged or hostile zip from filling the
    // disk or running the launcher out of memory.
    private const val MAX_JSON = 4L shl 20
    private const val MAX_IMAGE = 4L shl 20
    private const val MAX_TOTAL = 64L shl 20
    private const val MAX_ENTRIES = 4096

    /** A read backup: its layout, and its icons waiting in a scratch folder until [commitImport]. */
    class Imported(val layout: Layout, val staged: File)

    /**
     * Reads a backup zip. Safe off the main thread: nothing in use is touched, icons land in a scratch
     * folder, and a file that isn't a valid backup throws without leaving anything behind.
     */
    fun importZip(input: InputStream): Imported {
        val stage = stagingDir.apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            var json: ByteArray? = null
            var total = 0L
            var entries = 0
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw IOException("Too many files in backup")
                    val name = e.name
                    when {
                        name == "layout.json" -> json = readCapped(zip, MAX_JSON)
                        name.startsWith("icons/") -> {
                            val safe = iconName(name.removePrefix("icons/")) ?: continue
                            val data = readCapped(zip, MAX_IMAGE)
                            total += data.size
                            if (total > MAX_TOTAL) throw IOException("Backup too large")
                            File(stage, safe).writeBytes(data)
                        }
                    }
                }
            }
            val bytes = json ?: throw IllegalArgumentException("Not a Corkboard backup")
            val l = fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
            for (it in l.allItems()) if (it is WidgetItem) it.widgetId = -1
            return Imported(l, stage)
        } catch (t: Throwable) {
            stage.deleteRecursively()
            // Deeply nested JSON throws StackOverflowError: report it as a bad file, not a crash.
            throw t as? Exception ?: IOException(t)
        }
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            if (n > cap) throw IOException("Backup entry too large")
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    /** Moves an import's icons into place. Main thread, right before [replace] with its layout. */
    fun commitImport(im: Imported) {
        for (f in im.staged.listFiles().orEmpty()) {
            val dst = File(iconsDir, f.name)
            if (!f.renameTo(dst)) {
                try {
                    f.copyTo(dst, overwrite = true)
                } catch (_: IOException) {
                }
            }
        }
        im.staged.deleteRecursively()
    }
}
