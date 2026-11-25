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
        val k = "a|$key|${app.version}|${s?.look()}|$size|${Store.layout.settings.shape}|${themedKey(themed)}"
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

    private fun themedKey(c: Pair<Int, Int>?) = c?.let { "${it.first}.${it.second}.${Store.layout.settings.themedGenerate}" }

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

    fun monoBitmap(s: IconSettings?): Bitmap? {
        val f = s?.monoFile ?: return null
        return BitmapFactory.decodeFile(File(Store.filesDir, f).path)
    }

    /**
     * The mask a themed icon is drawn from, white on transparent, or null to draw the normal icon.
     * Per app: "auto" uses the app's own monochrome layer, else a generated one if "Theme apps
     * without a monochrome icon" is on; "generate" always generates; "image" generates from the
     * user's picked image; "off" never themes. A custom icon image keeps its own look under "auto".
     */
    private fun monoMask(ctx: Context, src: Drawable?, s: IconSettings?, size: Int): Bitmap? {
        val mode = s?.mono ?: "auto"
        val scale = (s?.scale ?: 100) / 100f
        when (mode) {
            "off" -> return null
            "image" -> return monoBitmap(s)?.let { generateMono(BitmapDrawable(ctx.resources, it), size, scale) }
            "generate" -> {
                val base = customBitmap(s)?.let { BitmapDrawable(ctx.resources, it) } ?: src ?: return null
                return generateMono(base, size, scale)
            }
        }
        if (s?.source == "custom") return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (src as? AdaptiveIconDrawable)?.monochrome?.mutate()?.let { mono ->
                val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                drawLayer(Canvas(layer), mono, (size * 0.25f).roundToInt(), size, scale)
                if (visible(layer)) return layer
            }
        }
        if (Store.layout.settings.themedGenerate && src != null) return generateMono(src, size, scale)
        return null
    }

    private fun visible(b: Bitmap) = pixels(b).any { it ushr 24 > 16 }

    /**
     * Makes a monochrome mask from a full-color icon, the way AOSP Launcher3's MonochromeIconFactory
     * does (Copyright (C) 2022 The Android Open Source Project, Apache License 2.0), rewritten:
     * flatten the icon over black, take the average of R, G and B as brightness, stretch the
     * contrast to the full range, and invert if the edges came out bright, so the icon's background
     * ends up transparent and its logo opaque.
     */
    private fun generateMono(d: Drawable, size: Int, scale: Float): Bitmap? {
        val flat = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(flat)
        c.drawColor(Color.BLACK)
        if (d is AdaptiveIconDrawable) {
            val ext = (size * 0.25f).roundToInt()
            d.background?.let { drawLayer(c, it, ext, size, 1f) }
            d.foreground?.let { drawLayer(c, it, ext, size, 1f) }
        } else {
            val b = toBitmap(d, size)
            val content = contentBounds(b)
            val t = size * 0.8f
            val k = t / max(content.width(), content.height()).coerceAtLeast(1)
            val w = content.width() * k
            val h = content.height() * k
            c.drawBitmap(b, content, RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f), paint)
        }
        val px = pixels(flat)
        val lum = IntArray(px.size)
        var lo = 255
        var hi = 0
        for (i in px.indices) {
            val p = px[i]
            val v = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
            lum[i] = v
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (hi <= lo) return null
        val range = hi - lo
        for (i in lum.indices) lum[i] = (lum[i] - lo) * 255 / range
        // Edges stand for the background: top and bottom rows plus left and right columns.
        var edge = 0L
        var count = 0
        for (x in 0 until size) {
            edge += lum[x] + lum[(size - 1) * size + x]
            count += 2
        }
        for (y in 0 until size) {
            edge += lum[y * size] + lum[y * size + size - 1]
            count += 2
        }
        val invert = edge / count.toFloat() > 127f
        val out = IntArray(lum.size)
        for (i in lum.indices) {
            val a = if (invert) 255 - lum[i] else lum[i]
            out[i] = (a shl 24) or 0xFFFFFF
        }
        val mask = Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
        if (!visible(mask)) return null
        if (scale == 1f) return mask
        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sc = Canvas(scaled)
        sc.scale(scale, scale, size / 2f, size / 2f)
        sc.drawBitmap(mask, 0f, 0f, paint)
        return scaled
    }

    /**
     * Draws the monochrome mask tinted on a wallpaper-colored shape. Returns null, so the normal
     * icon is drawn instead, whenever anything is off: themed icons disabled, no usable mask, or
     * any throw at all.
     */
    private fun themed(ctx: Context, src: Drawable?, s: IconSettings?, size: Int, user: UserHandle?, colors: Pair<Int, Int>?): Bitmap? {
        if (colors == null) return null
        return try {
            val mask = monoMask(ctx, src, s, size) ?: return null
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val shape = if (s == null || s.shape == "global") Store.layout.settings.shape else s.shape
            shapePath(shape, size)?.let { c.clipPath(it) }
            c.drawColor(colors.first)
            val tint = Paint(paint).apply { colorFilter = BlendModeColorFilter(colors.second, BlendMode.SRC_IN) }
            c.drawBitmap(mask, 0f, 0f, tint)
            badge(ctx, out, user)
        } catch (_: Throwable) {
            null
        }
    }

    /** The drawer button: a dots grid in the global shape. */
    fun drawerButton(ctx: Context, size: Int = sizePx(ctx)): Bitmap {
        val pal = Themes.palette(ctx)
        val k = "drawer|$size|${Store.layout.settings.shape}|${pal.surface}|${pal.text}"
        cache.get(k)?.let { return it }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        shapePath(Store.layout.settings.shape, size)?.let { c.clipPath(it) }
        c.drawColor(pal.surface)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text }
        val gap = size * 0.18f
        val r = size * 0.055f
        for (i in -1..1) for (j in -1..1) c.drawCircle(size / 2f + i * gap, size / 2f + j * gap, r, dot)
        cache.put(k, out)
        return out
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
        // Each app's own look and version are in the key, so editing an app's icon updates the preview.
        val apps = f.apps.take(4).joinToString { "$it/${Apps.apps[it]?.version}/${Store.layout.icons["app:$it"]?.look()}" }
        val k = "f|${f.id}|$apps|${s?.look()}|$size|${Store.layout.settings.shape}|${pal.panel}|${themedKey(themedColors(ctx))}"
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

    /** A fixed background choice as a color, or null for "auto". */
    private fun solid(choice: String): Int? = when {
        choice == "white" -> Color.WHITE
        choice == "black" -> Color.BLACK
        choice == "transparent" -> Color.TRANSPARENT
        choice.startsWith("#") -> try {
            Color.parseColor(choice)
        } catch (_: Exception) {
            null
        }
        else -> null
    }

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
        val chosenBg = solid(s.background)

        when {
            src == null -> {
                if (folderPreview != null) {
                    c.save()
                    c.scale(scale, scale, size / 2f, size / 2f)
                    folderPreview(c, size)
                    c.restore()
                }
            }
            src is AdaptiveIconDrawable -> {
                // Background: the app's own layer, or the color picked in Edit icon in its place.
                // Layers at 150% so the 72dp safe area fills the shape, same as the framework; Fit
                // draws the foreground at 100% instead, so the logo sits smaller.
                val ext = (size * 0.25f).roundToInt()
                if (chosenBg == null) {
                    src.background?.let { drawLayer(c, it, ext, size, max(scale, 1f)) }
                } else if (chosenBg != Color.TRANSPARENT) {
                    c.drawColor(chosenBg)
                }
                src.foreground?.let { drawLayer(c, it, if (s.treatment == "fit") 0 else ext, size, scale) }
            }
            else -> {
                val bmp = toBitmap(src, size)
                val px = pixels(bmp)
                val content = contentBounds(bmp)
                val fill = when (s.treatment) {
                    "fill" -> true
                    "fit" -> false
                    else -> isFullBleed(px, size) || isSquarish(px, size, content)
                }
                if (fill) {
                    // Old square icons: crop the transparent margin and stretch to the shape. The
                    // background only shows where the icon's own rounded corners don't reach.
                    val bg = chosenBg ?: sampledBackground(bmp) ?: Color.TRANSPARENT
                    if (bg != Color.TRANSPARENT) c.drawColor(bg)
                    c.save()
                    c.scale(scale, scale, size / 2f, size / 2f)
                    c.drawBitmap(bmp, content, Rect(0, 0, size, size), paint)
                    c.restore()
                } else {
                    val bg = chosenBg ?: sampledBackground(bmp) ?: Color.WHITE
                    if (bg != Color.TRANSPARENT) c.drawColor(bg)
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
    private fun isFullBleed(px: IntArray, size: Int): Boolean {
        var total = 0
        var opaque = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (x in 2 until size - 2 && y in 2 until size - 2) continue
            total++
            if (px[y * size + x] ushr 24 >= 242) opaque++
        }
        return total > 0 && opaque >= total * 0.95
    }

    /**
     * A rounded-square icon with transparent corners (My Boy, Note Pad and most pre-2017 icons):
     * its content is close to square, spans most of the canvas, and is solid inside. A circle
     * covers about 79% of its square, so circles stay on the Fit path.
     */
    private fun isSquarish(px: IntArray, size: Int, content: Rect): Boolean {
        val w = content.width()
        val h = content.height()
        if (w < size * 0.6f || h < size * 0.6f || kotlin.math.abs(w - h) > size * 0.08f) return false
        var solid = 0
        for (y in content.top until content.bottom) for (x in content.left until content.right) {
            if (px[y * size + x] ushr 24 >= 200) solid++
        }
        return solid >= w * h * 0.86f
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
     * "Auto" background: walks in from the edges along eight rays, steps a few pixels past the first
     * opaque one to skip anti-aliasing, and returns the average if at least six samples sit close to
     * their median. Null when the icon has no single edge color.
     */
    private fun sampledBackground(b: Bitmap): Int? {
        val w = b.width
        val h = b.height
        val inset = (w / 48).coerceAtLeast(2)
        val rays = listOf(
            Triple(0, h / 2, 1 to 0), Triple(w - 1, h / 2, -1 to 0), Triple(w / 2, 0, 0 to 1), Triple(w / 2, h - 1, 0 to -1),
            Triple(0, 0, 1 to 1), Triple(w - 1, 0, -1 to 1), Triple(0, h - 1, 1 to -1), Triple(w - 1, h - 1, -1 to -1),
        )
        val found = ArrayList<Int>()
        for ((sx, sy, d) in rays) {
            var x = sx
            var y = sy
            while (x in 0 until w && y in 0 until h) {
                if (Color.alpha(b.getPixel(x, y)) >= 200) {
                    val ix = (x + d.first * inset).coerceIn(0, w - 1)
                    val iy = (y + d.second * inset).coerceIn(0, h - 1)
                    found.add(b.getPixel(ix, iy))
                    break
                }
                x += d.first
                y += d.second
            }
        }
        if (found.size < 6) return null
        fun med(f: (Int) -> Int) = found.map(f).sorted()[found.size / 2]
        val mr = med { Color.red(it) }
        val mg = med { Color.green(it) }
        val mb = med { Color.blue(it) }
        val close = found.filter {
            kotlin.math.abs(Color.red(it) - mr) + kotlin.math.abs(Color.green(it) - mg) + kotlin.math.abs(Color.blue(it) - mb) <= 60
        }
        if (close.size < 6) return null
        return Color.rgb(close.map { Color.red(it) }.average().toInt(), close.map { Color.green(it) }.average().toInt(), close.map { Color.blue(it) }.average().toInt())
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
