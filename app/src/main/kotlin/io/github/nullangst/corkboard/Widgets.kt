/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 *
 * Widget span math follows AOSP Launcher3's WidgetSizes / LauncherAppWidgetProviderInfo
 * (Copyright (C) 2021 The Android Open Source Project, Apache License 2.0), rewritten.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** AppWidgetHostView with our own "Can't load widget" box. */
class HostView(ctx: Context) : AppWidgetHostView(ctx) {
    var onRemove: (() -> Unit)? = null

    override fun getErrorView(): View = Widgets.errorBox(context, context.getString(R.string.cant_load_widget)) { onRemove?.invoke() }
}

/** Wraps a widget so a long-press anywhere on it lifts it, whatever the widget does with touches. */
@SuppressLint("ViewConstructor")
class WidgetFrame(ctx: Context, var item: WidgetItem) : FrameLayout(ctx) {
    var onLongPress: ((WidgetFrame) -> Unit)? = null
    var onSize: ((Int, Int) -> Unit)? = null
    var downX = 0f
    var downY = 0f
    private var fired = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPress = Runnable {
        fired = true
        onLongPress?.invoke(this)
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = ev.x
                downY = ev.y
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return fired
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && !fired && hypot(ev.x - downX, ev.y - downY) < slop) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onSize?.invoke(w, h)
    }
}

/** AppWidgetHost, the bind and setup flow, the picker and resizing. */
class Widgets(private val act: HomeActivity) {
    companion object {
        const val REQ_BIND = 11
        const val REQ_CONFIG = 12

        fun errorBox(ctx: Context, text: String, onRemove: () -> Unit): View {
            val pal = Themes.palette(ctx)
            val d = ctx.resources.displayMetrics.density
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = pal.card(ctx, pal.surface, 16f)
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                addView(TextView(ctx).apply {
                    this.text = text
                    setTextColor(pal.text)
                    gravity = Gravity.CENTER
                })
                addView(Button(ctx).apply {
                    this.text = ctx.getString(R.string.remove)
                    setTextColor(pal.accent)
                    setBackgroundColor(0)
                    setOnClickListener { onRemove() }
                })
            }
        }
    }

    val awm: AppWidgetManager = AppWidgetManager.getInstance(act)
    val host = object : AppWidgetHost(act.applicationContext, Apps.WIDGET_HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            HostView(context)
    }
    private val frames = HashMap<Int, WidgetFrame>()
    private val density = act.resources.displayMetrics.density

    /** The view for a widget item, reused across rebinds so the widget is not recreated each time. */
    fun view(item: WidgetItem): WidgetFrame {
        if (item.widgetId >= 0) frames[item.widgetId]?.let { f ->
            (f.parent as? ViewGroup)?.removeView(f)
            f.item = item
            return f
        }
        val frame = WidgetFrame(act, item)
        val info = if (item.widgetId >= 0) awm.getAppWidgetInfo(item.widgetId) else null
        when {
            item.widgetId < 0 -> {
                val label = item.provider?.let { p -> Apps.apps.values.firstOrNull { it.key.pkg == p.packageName }?.label } ?: act.getString(R.string.widget_generic)
                frame.addView(errorBox(act, act.getString(R.string.widget_set_up_again, label)) { act.removeItem(item) })
                frame.setOnClickListener { setUpAgain(item) }
            }
            info == null -> frame.addView(errorBox(act, act.getString(R.string.cant_load_widget)) { act.removeItem(item) })
            else -> {
                val hv = host.createView(act, item.widgetId, info)
                if (hv is HostView) hv.onRemove = { act.removeItem(item) }
                frame.addView(hv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                frame.onSize = { w, h -> reportSize(hv, w, h) }
                frames[item.widgetId] = frame
            }
        }
        return frame
    }

    /** Tells the widget its real size so it lays itself out correctly. */
    private fun reportSize(hv: AppWidgetHostView, wPx: Int, hPx: Int) {
        val w = wPx / density
        val h = hPx / density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hv.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
        } else {
            @Suppress("DEPRECATION")
            hv.updateAppWidgetSize(null, w.toInt(), h.toInt(), w.toInt(), h.toInt())
        }
    }

    fun delete(item: WidgetItem) {
        if (item.widgetId < 0) return
        frames.remove(item.widgetId)
        try {
            host.deleteAppWidgetId(item.widgetId)
        } catch (_: Exception) {
        }
    }

    // ---- Span math ----

    private fun cells(px: Int, cell: Float, max: Int) = ceil(px / cell).toInt().coerceIn(1, max)

    fun span(info: AppWidgetProviderInfo, cellW: Float, cellH: Float, cols: Int, rows: Int): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return Point(info.targetCellWidth.coerceAtMost(cols), info.targetCellHeight.coerceAtMost(rows))
        }
        return Point(cells(info.minWidth, cellW, cols), cells(info.minHeight, cellH, rows))
    }

    private fun minSpan(info: AppWidgetProviderInfo, cellW: Float, cellH: Float, cols: Int, rows: Int) = Point(
        cells(if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth, cellW, cols),
        cells(if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight, cellH, rows),
    )

    private fun maxSpan(info: AppWidgetProviderInfo, cellW: Float, cellH: Float, cols: Int, rows: Int): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val w = if (info.maxResizeWidth > 0) cells(info.maxResizeWidth, cellW, cols) else cols
            val h = if (info.maxResizeHeight > 0) cells(info.maxResizeHeight, cellH, rows) else rows
            return Point(w, h)
        }
        return Point(cols, rows)
    }

    // ---- Bind and setup ----

    private class Pending(val info: AppWidgetProviderInfo, val page: Int, val x: Int, val y: Int, val w: Int, val h: Int, val existing: WidgetItem?) {
        var id = -1
    }

    private var pending: Pending? = null

    /** Starts adding a widget at a spot. Android asks for bind permission the first time per app. */
    fun add(info: AppWidgetProviderInfo, page: Int, x: Int, y: Int, w: Int, h: Int, existing: WidgetItem? = null) {
        cancel()
        val p = Pending(info, page, x, y, w, h, existing)
        p.id = host.allocateAppWidgetId()
        pending = p
        val ok = try {
            awm.bindAppWidgetIdIfAllowed(p.id, info.profile, info.provider, null)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            configure(p)
            return
        }
        val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, p.id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        try {
            act.startActivityForResult(i, REQ_BIND)
        } catch (_: Exception) {
            cancel()
        }
    }

    /** Drops a widget into the first free spot that fits, from the picker. */
    fun addFirstFree(info: AppWidgetProviderInfo, cellW: Float, cellH: Float) {
        val l = Store.layout
        val s = span(info, cellW, cellH, l.settings.columns, l.settings.rows)
        for (p in l.pages.indices) {
            val spot = l.freeSpot(p, s.x, s.y) ?: continue
            add(info, p, spot.x, spot.y, s.x, s.y)
            return
        }
        add(info, l.pages.size, 0, 0, s.x, s.y)
    }

    private fun configure(p: Pending) {
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            p.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (p.info.configure != null && !optional) {
            try {
                host.startAppWidgetConfigureActivityForResult(act, p.id, 0, REQ_CONFIG, null)
                return
            } catch (_: Exception) {
            }
        }
        finish(p)
    }

    private fun finish(p: Pending) {
        pending = null
        val l = Store.layout
        val serial = Apps.serial(p.info.profile)
        val item = p.existing ?: WidgetItem(l.newId(), p.id, p.info.provider, serial)
        item.widgetId = p.id
        item.provider = p.info.provider
        item.user = serial
        if (p.existing == null) {
            item.w = p.w
            item.h = p.h
            while (l.pages.size <= p.page) l.pages.add(mutableListOf())
            val g = l.occupancy(l.pages[p.page], l.settings.columns, l.settings.rows)
            if (Layout.fits(g, p.x, p.y, p.w, p.h)) {
                item.x = p.x
                item.y = p.y
                l.pages[p.page].add(item)
            } else {
                l.placeFirstFree(item)
            }
        }
        Store.save()
        act.rebind()
    }

    fun cancel() {
        val p = pending ?: return
        pending = null
        try {
            host.deleteAppWidgetId(p.id)
        } catch (_: Exception) {
        }
    }

    fun onResult(requestCode: Int, resultCode: Int): Boolean {
        val p = pending ?: return false
        when (requestCode) {
            REQ_BIND -> if (resultCode == Activity.RESULT_OK) configure(p) else cancel()
            REQ_CONFIG -> if (resultCode == Activity.RESULT_OK) finish(p) else cancel()
            else -> return false
        }
        return true
    }

    /** Restored widget spots ask to be set up again with one tap each. */
    private fun setUpAgain(item: WidgetItem) {
        val provider = item.provider
        val user = Apps.user(item.user)
        val info = if (provider != null && user != null) {
            awm.getInstalledProvidersForProfile(user).firstOrNull { it.provider == provider }
        } else {
            null
        }
        if (info == null) {
            Toast.makeText(act, R.string.widget_app_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val page = Store.layout.pageOf(item)
        add(info, page, item.x, item.y, item.w, item.h, item)
    }

    // ---- Picker ----

    fun showPicker() {
        val pal = act.pal
        val d = density
        val providers = Apps.la.profiles.flatMap { u ->
            try {
                awm.getInstalledProvidersForProfile(u)
            } catch (_: Exception) {
                emptyList()
            }
        }.filter { it.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0 }
        val groups = providers.groupBy { it.provider.packageName to Apps.serial(it.profile) }
        fun appLabel(pkg: String, serial: Long) =
            Apps.apps.values.firstOrNull { it.key.pkg == pkg && it.key.user == serial }?.label ?: pkg

        val list = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (24 * d).toInt())
        }
        list.addView(TextView(act).apply {
            text = act.getString(R.string.widgets)
            textSize = 22f
            setTextColor(pal.text)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        })
        if (groups.isEmpty()) list.addView(TextView(act).apply {
            text = act.getString(R.string.no_widgets)
            setTextColor(pal.textDim)
        })
        val cellW = act.pageCellW()
        val cellH = act.pageCellH()
        val cols = Store.layout.settings.columns
        val rows = Store.layout.settings.rows
        for ((k, infos) in groups.entries.sortedBy { appLabel(it.key.first, it.key.second).lowercase() }) {
            val work = Apps.isWork(k.second)
            list.addView(TextView(act).apply {
                text = appLabel(k.first, k.second).let { if (work) act.getString(R.string.work_suffix, it) else it }
                textSize = 16f
                setTextColor(pal.accent)
                setPadding(0, (16 * d).toInt(), 0, (6 * d).toInt())
            })
            for (info in infos) {
                val s = span(info, cellW, cellH, cols, rows)
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = pal.card(act, pal.surface, 16f)
                    setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
                }
                val preview = ImageView(act).apply {
                    adjustViewBounds = true
                    maxHeight = (110 * d).toInt()
                    maxWidth = (150 * d).toInt()
                    setImageDrawable(
                        try {
                            info.loadPreviewImage(act, 0) ?: info.loadIcon(act, 0)
                        } catch (_: Exception) {
                            null
                        },
                    )
                }
                row.addView(preview, LinearLayout.LayoutParams((150 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(TextView(act).apply {
                    text = act.getString(R.string.widget_size, info.loadLabel(act.packageManager), s.x, s.y)
                    setTextColor(pal.text)
                    setPadding((12 * d).toInt(), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.setOnClickListener {
                    act.closeOverlay()
                    addFirstFree(info, cellW, cellH)
                }
                row.setOnLongClickListener { v ->
                    act.startNewWidgetDrag(v, info, s.x, s.y)
                    true
                }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (8 * d).toInt()
                })
            }
        }
        val scroll = ScrollView(act).apply {
            addView(list)
            setBackgroundColor(pal.bg)
        }
        act.showOverlay(scroll, full = true)
    }

    // ---- Resize ----

    fun startResize(item: WidgetItem, grid: CellGrid) {
        val info = if (item.widgetId >= 0) awm.getAppWidgetInfo(item.widgetId) else null
        if (info == null || info.resizeMode == AppWidgetProviderInfo.RESIZE_NONE) {
            Toast.makeText(act, R.string.cant_resize_widget, Toast.LENGTH_SHORT).show()
            return
        }
        val l = Store.layout
        val cols = l.settings.columns
        val rows = l.settings.rows
        val mn = minSpan(info, grid.cellW, grid.cellH, cols, rows)
        val mx = maxSpan(info, grid.cellW, grid.cellH, cols, rows).let { Point(maxOf(it.x, mn.x, item.w), maxOf(it.y, mn.y, item.h)) }
        mn.x = minOf(mn.x, item.w)
        mn.y = minOf(mn.y, item.h)
        val page = l.pageOf(item)
        if (page < 0) return
        val overlay = ResizeOverlay(
            act, grid, item, mn, mx,
            info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
            info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
        ) { x, y, w, h ->
            val g = l.occupancy(l.pages[page], cols, rows, exclude = item)
            if (Layout.fits(g, x, y, w, h)) {
                item.x = x
                item.y = y
                item.w = w
                item.h = h
                Store.save()
            }
            act.closeOverlay()
            act.rebind()
        }
        act.showOverlay(overlay, full = true, dim = false)
    }
}

/** Handles on the edges a widget allows, snapping to cells within its min and max spans. */
@SuppressLint("ViewConstructor")
class ResizeOverlay(
    private val act: HomeActivity,
    private val grid: CellGrid,
    item: WidgetItem,
    private val mn: Point,
    private val mx: Point,
    private val horizontal: Boolean,
    private val vertical: Boolean,
    private val done: (Int, Int, Int, Int) -> Unit,
) : View(act) {
    private var x = item.x
    private var y = item.y
    private var w = item.w
    private var h = item.h
    private val others = Store.layout.pages.getOrNull(Store.layout.pageOf(item)).orEmpty().filter { it !== item }
    private var handle = 0 // 1 left, 2 right, 3 top, 4 bottom
    private val d = act.resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
        color = act.pal.accent
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = act.pal.accent }

    private fun offset(): Pair<Float, Float> {
        val a = IntArray(2)
        val b = IntArray(2)
        grid.getLocationInWindow(a)
        getLocationInWindow(b)
        return (a[0] - b[0]).toFloat() to (a[1] - b[1]).toFloat()
    }

    private fun rect(): RectF {
        val (ox, oy) = offset()
        return grid.cellRect(x, y, w, h).apply { offset(ox, oy) }
    }

    private fun handles(): List<Pair<Int, Pair<Float, Float>>> {
        val r = rect()
        val out = ArrayList<Pair<Int, Pair<Float, Float>>>()
        if (horizontal) {
            out.add(1 to (r.left to r.centerY()))
            out.add(2 to (r.right to r.centerY()))
        }
        if (vertical) {
            out.add(3 to (r.centerX() to r.top))
            out.add(4 to (r.centerX() to r.bottom))
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        val r = rect()
        canvas.drawRoundRect(r, 16 * d, 16 * d, stroke)
        for ((_, p) in handles()) canvas.drawCircle(p.first, p.second, 8 * d, dot)
    }

    private fun free(nx: Int, ny: Int, nw: Int, nh: Int): Boolean {
        val g = Store.layout.occupancy(others, grid.cols, grid.rows)
        return Layout.fits(g, nx, ny, nw, nh)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val (ox, oy) = offset()
        val gx = ev.x - ox - grid.paddingLeft
        val gy = ev.y - oy - grid.paddingTop
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = handles().firstOrNull { (_, p) -> hypot(ev.x - p.first, ev.y - p.second) < 32 * d }?.first ?: 0
                if (handle == 0 && !rect().contains(ev.x, ev.y)) done(x, y, w, h)
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = (gx / grid.cellW).roundToInt()
                val cy = (gy / grid.cellH).roundToInt()
                var nx = x
                var ny = y
                var nw = w
                var nh = h
                when (handle) {
                    1 -> {
                        nx = cx.coerceIn(x + w - mx.x, x + w - mn.x).coerceAtLeast(0)
                        nw = x + w - nx
                    }
                    2 -> nw = (cx - x).coerceIn(mn.x, mx.x).coerceAtMost(grid.cols - x)
                    3 -> {
                        ny = cy.coerceIn(y + h - mx.y, y + h - mn.y).coerceAtLeast(0)
                        nh = y + h - ny
                    }
                    4 -> nh = (cy - y).coerceIn(mn.y, mx.y).coerceAtMost(grid.rows - y)
                }
                if (handle != 0 && (nx != x || ny != y || nw != w || nh != h) && nw >= 1 && nh >= 1 && free(nx, ny, nw, nh)) {
                    x = nx
                    y = ny
                    w = nw
                    h = nh
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handle = 0
        }
        return true
    }
}
