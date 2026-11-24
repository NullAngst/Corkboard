/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 *
 * The content-bounds measuring for legacy icons follows the idea of AOSP Launcher3's
 * IconNormalizer (Copyright (C) 2016 The Android Open Source Project, Apache License 2.0),
 * rewritten for this framework-only stack.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.util.DisplayMetrics
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Every icon goes through here: shape, fill or fit, scale, background, work badge, memory cache. */
object IconRenderer {
    val shapes = listOf("system", "circle", "squircle", "rounded", "square", "teardrop")

    fun shapeName(ctx: Context, id: String): String = ctx.getString(
        when (id) {
            "circle" -> R.string.shape_circle
            "squircle" -> R.string.shape_squircle
            "rounded" -> R.string.shape_rounded
            "square" -> R.string.shape_square
            "teardrop" -> R.string.shape_teardrop
            "none" -> R.string.shape_none
            "global" -> R.string.shape_global
            else -> R.string.shape_system
        },
    )

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Memory only. The disk cache keys carry every setting, so stale files are just never asked for again. */
    fun clear() = cache.evictAll()

    fun clearPackage(pkg: String) {
        for (k in cache.snapshot().keys) if (k.contains(pkg)) cache.remove(k)
        DiskIcons.clearPackage(pkg)
    }

    /** Home and drawer icon size in pixels for the current settings. */
    fun sizePx(ctx: Context): Int =
        (ctx.resources.displayMetrics.density * 54f * Store.layout.settings.iconSize / 100f).roundToInt()

    // ---- Shapes, defined on a 100 x 100 square ----

    private fun unitPath(name: String): Path? {
        val p = Path()
        when (name) {
            "none" -> return null
            "circle" -> p.addCircle(50f, 50f, 50f, Path.Direction.CW)
            "squircle" -> {
                // Superellipse |x|^4 + |y|^4 = 1
                val n = 96
                for (i in 0..n) {
                    val t = 2.0 * Math.PI * i / n
                    val c = cos(t)
                    val s = sin(t)
                    val x = 50 + 50 * Math.signum(c) * abs(c).pow(0.5)
                    val y = 50 + 50 * Math.signum(s) * abs(s).pow(0.5)
                    if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
                }
                p.close()
            }
            "rounded" -> p.addRoundRect(0f, 0f, 100f, 100f, 25f, 25f, Path.Direction.CW)
            "square" -> p.addRoundRect(0f, 0f, 100f, 100f, 8f, 8f, Path.Direction.CW)
            "teardrop" -> p.addRoundRect(
                RectF(0f, 0f, 100f, 100f),
                floatArrayOf(50f, 50f, 0f, 0f, 50f, 50f, 50f, 50f),
                Path.Direction.CW,
            )
            else -> {
                val d = AdaptiveIconDrawable(ColorDrawable(Color.BLACK), ColorDrawable(Color.BLACK))
                d.setBounds(0, 0, 100, 100)
                p.set(d.iconMask)
            }
        }
        return p
    }

    fun shapePath(name: String, size: Int): Path? {
        val p = unitPath(name) ?: return null
        p.transform(Matrix().apply { setScale(size / 100f, size / 100f) })
        return p
    }

    // ---- Sources ----

    fun customBitmap(s: IconSettings?): Bitmap? {
        if (s?.source != "custom") return null
        val f = s.file ?: return null
        return BitmapFactory.decodeFile(File(Store.filesDir, f).path)
    }

    private fun appSource(ctx: Context, app: AppInfo): Drawable? = try {
        app.info.getIcon(max(ctx.resources.displayMetrics.densityDpi, DisplayMetrics.DENSITY_XXXHIGH))
    } catch (_: Exception) {
        null
    }

    fun app(ctx: Context, key: AppKey, size: Int = sizePx(ctx), override: IconSettings? = null): Bitmap? {
        val app = Apps.apps[key] ?: return null
        val s = override ?: Store.layout.icons["app:$key"]
        val themed = themedColors(ctx)
        val k = "a|$key|${app.version}|${s?.look()}|$size|${Store.layout.settings.shape}|${themed?.let { "${it.first}.${it.second}" }}"
        if (override == null) {
            cache.get(k)?.let { return it }
            DiskIcons.get(ctx, k, key.pkg)?.let {
                cache.put(k, it)
                return it
            }
        }
        val src = appSource(ctx, app)
        val bmp = themed(ctx, src, s, size, app.user, themed) ?: render(ctx, src, s, size, app.user)
        if (override == null) {
            cache.put(k, bmp)
            DiskIcons.put(ctx, k, key.pkg, bmp)
        }
        return bmp
    }

    // ---- Themed (monochrome) icons ----

    /** Themed icons can be turned on: Android 13+, where apps can ship a monochrome layer. */
    val themedSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Background and foreground colors for themed icons, from the wallpaper like Pixel Launcher, or
     * null when themed icons are off. If the system colors can't be read, the launcher theme's
     * colors stand in.
     */
    fun themedColors(ctx: Context): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !Store.layout.settings.themedIcons) return null
        val pal = Themes.palette(ctx)
        return try {
            val r = ctx.resources
            if (pal.isDark) {
                r.getColor(android.R.color.system_neutral1_800, null) to r.getColor(android.R.color.system_accent1_100, null)
            } else {
                r.getColor(android.R.color.system_accent1_100, null) to r.getColor(android.R.color.system_neutral2_700, null)
            }
        } catch (_: Throwable) {
            if (pal.isDark) pal.surface to pal.accent else pal.accent to pal.onAccent
        }
    }

    /**
     * Draws the app's monochrome layer tinted on a wallpaper-colored shape. Returns null, so the
     * normal icon is drawn instead, whenever anything is off: themed icons disabled, a custom image
     * set, no monochrome layer, a layer that draws nothing, or any throw at all.
     */
    private fun themed(ctx: Context, src: Drawable?, s: IconSettings?, size: Int, user: UserHandle?, colors: Pair<Int, Int>?): Bitmap? {
        if (colors == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || s?.source == "custom") return null
        return try {
            val mono = (src as? AdaptiveIconDrawable)?.monochrome?.mutate() ?: return null
            mono.colorFilter = BlendModeColorFilter(colors.second, BlendMode.SRC_IN)
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawLayer(Canvas(layer), mono, (size * 0.25f).roundToInt(), size, (s?.scale ?: 100) / 100f)
            if (pixels(layer).none { it ushr 24 > 16 }) return null
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val shape = if (s == null || s.shape == "global") Store.layout.settings.shape else s.shape
            shapePath(shape, size)?.let { c.clipPath(it) }
            c.drawColor(colors.first)
            c.drawBitmap(layer, 0f, 0f, paint)
            badge(ctx, out, user)
        } catch (_: Throwable) {
            null
        }
    }

    fun shortcut(ctx: Context, item: ShortcutItem, size: Int = sizePx(ctx), override: IconSettings? = null): Bitmap {
        val s = override ?: Store.layout.icons["item:${item.id}"]
        val k = "s|${item.pkg}|${item.shortcutId}|${item.user}|${s?.look()}|$size|${Store.layout.settings.shape}"
        if (override == null) cache.get(k)?.let { return it }
        val src = Shortcuts.icon(ctx, item)
        val bmp = render(ctx, src, s, size, Apps.user(item.user))
        if (override == null) cache.put(k, bmp)
        return bmp
    }

    fun folder(ctx: Context, f: FolderItem, size: Int = sizePx(ctx), override: IconSettings? = null): Bitmap {
        val s = override ?: Store.layout.icons["item:${f.id}"]
        val pal = Themes.palette(ctx)
        val k = "f|${f.id}|${f.apps.take(4).joinToString()}|${s?.look()}|$size|${Store.layout.settings.shape}|${pal.panel}"
        if (override == null) cache.get(k)?.let { return it }
        val bmp = render(ctx, null, s, size, null) { c, sz ->
            c.drawColor(pal.panel)
            val mini = (sz * 0.30f).roundToInt()
            val gap = sz * 0.07f
            val start = (sz - 2 * mini - gap) / 2f
            f.apps.take(4).forEachIndexed { i, key ->
                val b = app(ctx, key, mini) ?: return@forEachIndexed
                c.drawBitmap(b, start + (i % 2) * (mini + gap), start + (i / 2) * (mini + gap), paint)
            }
        }
        if (override == null) cache.put(k, bmp)
        return bmp
    }

    /** Renders whatever the edit screen or a cell points at: "app:<key>" or "item:<id>". */
    fun forTarget(ctx: Context, target: String, size: Int, override: IconSettings?): Bitmap? {
        if (target.startsWith("app:")) return AppKey.parse(target.removePrefix("app:"))?.let { app(ctx, it, size, override) }
        val id = target.removePrefix("item:").toLongOrNull() ?: return null
        return when (val item = Store.layout.findItem(id)) {
            is FolderItem -> folder(ctx, item, size, override)
            is ShortcutItem -> shortcut(ctx, item, size, override)
            else -> null
        }
    }

    // ---- Pipeline ----

    fun render(
        ctx: Context,
        source: Drawable?,
        settings: IconSettings?,
        size: Int,
        user: UserHandle?,
        folderPreview: ((Canvas, Int) -> Unit)? = null,
    ): Bitmap {
        val s = settings ?: IconSettings()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shape = if (s.shape == "global") Store.layout.settings.shape else s.shape
        shapePath(shape, size)?.let { c.clipPath(it) }
        val scale = s.scale / 100f
        val custom = customBitmap(s)
        val src: Drawable? = if (custom != null) BitmapDrawable(ctx.resources, custom) else source

        when {
            src == null -> {
                if (folderPreview != null) {
                    c.save()
                    c.scale(scale, scale, size / 2f, size / 2f)
                    folderPreview(c, size)
                    c.restore()
                }
            }
            src is AdaptiveIconDrawable && s.treatment != "fit" -> {
                // Layers at 150% so the 72dp safe area fills the shape, same as the framework.
                val ext = (size * 0.25f).roundToInt()
                src.background?.let { drawLayer(c, it, ext, size, max(scale, 1f)) }
                src.foreground?.let { drawLayer(c, it, ext, size, scale) }
            }
            else -> {
                val bmp = toBitmap(src, size)
                val fill = when (s.treatment) {
                    "fill" -> true
                    "fit" -> false
                    else -> isFullBleed(bmp)
                }
                if (fill) {
                    if (scale < 1f) background(s.background, bmp, Color.TRANSPARENT).let { if (it != 0) c.drawColor(it) }
                    c.save()
                    c.scale(scale, scale, size / 2f, size / 2f)
                    c.drawBitmap(bmp, null, Rect(0, 0, size, size), paint)
                    c.restore()
                } else {
                    val bg = background(s.background, bmp, Color.WHITE)
                    if (bg != 0) c.drawColor(bg)
                    val content = contentBounds(bmp)
                    val target = size * 0.68f * scale
                    val k = target / max(content.width(), content.height()).coerceAtLeast(1)
                    val w = content.width() * k
                    val h = content.height() * k
                    val dst = RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
                    c.drawBitmap(bmp, content, dst, paint)
                }
            }
        }
        return badge(ctx, out, user)
    }

    private fun drawLayer(c: Canvas, d: Drawable, ext: Int, size: Int, scale: Float) {
        c.save()
        c.scale(scale, scale, size / 2f, size / 2f)
        d.setBounds(-ext, -ext, size + ext, size + ext)
        d.draw(c)
        c.restore()
    }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: size
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: size
        val k = min(size.toFloat() / iw, size.toFloat() / ih)
        val w = (iw * k).roundToInt()
        val h = (ih * k).roundToInt()
        d.setBounds((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
        d.draw(c)
        return b
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** A full-bleed square: the outer 2 pixels are at least 95% opaque. */
    private fun isFullBleed(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        val px = pixels(b)
        var total = 0
        var opaque = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x in 2 until w - 2 && y in 2 until h - 2) continue
            total++
            if (px[y * w + x] ushr 24 >= 242) opaque++
        }
        return total > 0 && opaque >= total * 0.95
    }

    private fun contentBounds(b: Bitmap): Rect {
        val w = b.width
        val px = pixels(b)
        var l = w
        var t = b.height
        var r = -1
        var btm = -1
        for (y in 0 until b.height) for (x in 0 until w) {
            if (px[y * w + x] ushr 24 > 16) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > btm) btm = y
            }
        }
        return if (r < 0) Rect(0, 0, w, b.height) else Rect(l, t, r + 1, btm + 1)
    }

    /**
     * The Fit background. "auto" samples the icon's outermost opaque pixels along eight rays from
     * the edges; if they agree the icon sits on its own color, if not it gets [fallback].
     */
    fun background(choice: String, b: Bitmap, fallback: Int): Int {
        when {
            choice == "white" -> return Color.WHITE
            choice == "black" -> return Color.BLACK
            choice == "transparent" -> return Color.TRANSPARENT
            choice.startsWith("#") -> return try {
                Color.parseColor(choice)
            } catch (_: Exception) {
                fallback
            }
        }
        val w = b.width
        val h = b.height
        val rays = listOf(
            Triple(0, h / 2, 1 to 0), Triple(w - 1, h / 2, -1 to 0), Triple(w / 2, 0, 0 to 1), Triple(w / 2, h - 1, 0 to -1),
            Triple(0, 0, 1 to 1), Triple(w - 1, 0, -1 to 1), Triple(0, h - 1, 1 to -1), Triple(w - 1, h - 1, -1 to -1),
        )
        val found = ArrayList<Int>()
        for ((sx, sy, d) in rays) {
            var x = sx
            var y = sy
            while (x in 0 until w && y in 0 until h) {
                val px = b.getPixel(x, y)
                if (Color.alpha(px) >= 200) {
                    found.add(px)
                    break
                }
                x += d.first
                y += d.second
            }
        }
        if (found.size < 6) return fallback
        val r = found.map { Color.red(it) }
        val g = found.map { Color.green(it) }
        val bl = found.map { Color.blue(it) }
        val agree = listOf(r, g, bl).all { ch -> (ch.maxOrNull() ?: 0) - (ch.minOrNull() ?: 0) <= 28 }
        return if (agree) Color.rgb(r.average().toInt(), g.average().toInt(), bl.average().toInt()) else fallback
    }

    /** The system briefcase badge, drawn on top after the shape is applied. */
    private fun badge(ctx: Context, b: Bitmap, user: UserHandle?): Bitmap {
        if (user == null || user == Process.myUserHandle()) return b
        val d = ctx.packageManager.getUserBadgedIcon(BitmapDrawable(ctx.resources, b), user)
        if (d is BitmapDrawable && d.bitmap === b) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, b.width, b.height)
        d.draw(Canvas(out))
        return out
    }

    /** Downscales a picked image to 432 x 432 at most and stores it as a PNG. Returns "icons/<name>". */
    fun saveCustom(src: Bitmap, name: String): String {
        val k = min(1f, 432f / max(src.width, src.height))
        val scaled = if (k < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * k).roundToInt().coerceAtLeast(1), (src.height * k).roundToInt().coerceAtLeast(1), true)
        } else {
            src
        }
        val f = File(Store.iconsDir, name)
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "icons/$name"
    }
}

/**
 * Rendered app icons as PNGs in the app's cache directory, so a cold start skips pulling every icon
 * out of its APK. Used by the home screen, dock, folders and drawer alike. Size is set in settings;
 * the oldest files go first when it is over. Android may clear this folder when storage is low, and
 * the only cost is one slower start.
 */
object DiskIcons {
    private val io = Executors.newSingleThreadExecutor()
    private val trimQueued = AtomicBoolean(false)
    @Volatile private var dir: File? = null

    private fun dir(ctx: Context): File =
        dir ?: File(ctx.applicationContext.cacheDir, "icons").also {
            it.mkdirs()
            dir = it
        }

    private fun capBytes(): Long = Store.layout.settings.diskCacheMb * 1024L * 1024L

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun file(ctx: Context, key: String, pkg: String) = File(dir(ctx), "$pkg-${hash(key)}.png")

    fun get(ctx: Context, key: String, pkg: String): Bitmap? {
        if (capBytes() <= 0) return null
        return try {
            val f = file(ctx, key, pkg)
            if (!f.exists()) return null
            val b = BitmapFactory.decodeFile(f.path)
            if (b == null) {
                f.delete()
                return null
            }
            io.execute { f.setLastModified(System.currentTimeMillis()) }
            b
        } catch (_: Throwable) {
            null
        }
    }

    fun put(ctx: Context, key: String, pkg: String, bmp: Bitmap) {
        if (capBytes() <= 0) return
        val f = try {
            file(ctx, key, pkg)
        } catch (_: Throwable) {
            return
        }
        io.execute {
            try {
                val tmp = File(f.path + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(f)) tmp.delete()
            } catch (_: Throwable) {
            }
        }
        trimSoon()
    }

    /** Deletes the oldest files until the folder is under 90% of the cap. */
    fun trimSoon() {
        if (!trimQueued.compareAndSet(false, true)) return
        io.execute {
            trimQueued.set(false)
            try {
                val files = dir?.listFiles() ?: return@execute
                val cap = capBytes()
                var total = files.sumOf { it.length() }
                if (total <= cap) return@execute
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= cap * 0.9) break
                    total -= f.length()
                    f.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun clearPackage(pkg: String) {
        io.execute {
            try {
                dir?.listFiles()?.forEach { if (it.name.startsWith("$pkg-")) it.delete() }
            } catch (_: Throwable) {
            }
        }
    }

    fun clearAll(ctx: Context) {
        val d = dir(ctx)
        io.execute {
            try {
                d.listFiles()?.forEach { it.delete() }
            } catch (_: Throwable) {
            }
        }
    }

    fun usedBytes(ctx: Context): Long = try {
        dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
    } catch (_: Throwable) {
        0L
    }
}
