/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt


/** Extra height of the most-used row, holding the line under it. */
private const val TOP_ROW_EXTRA_DP = 12f

/** Narrowest a drawer column gets on a wide screen. */
private const val WIDE_COLUMN_DP = 120

/** Most drawer columns, however wide the screen. */
private const val MAX_DRAWER_COLUMNS = 10

/**
 * Drawer columns for a window [widthDp] wide, like Pixel Launcher: wide screens (tablets,
 * landscape) get more columns, each at least [WIDE_COLUMN_DP] wide, instead of stretched cells.
 * Never fewer than the Columns [setting], so phones in portrait keep exactly the setting.
 */
fun drawerColumns(widthDp: Int, setting: Int): Int =
    (widthDp / WIDE_COLUMN_DP).coerceIn(setting, maxOf(setting, MAX_DRAWER_COLUMNS))

/**
 * Swipe-up drawer: search on top, Personal, Work and Private tabs, every non-hidden app
 * alphabetically, and the letter strip down the side.
 */
@SuppressLint("ViewConstructor")
class AppDrawer(private val act: HomeActivity) : LinearLayout(act) {
    private val d = resources.displayMetrics.density
    private val search = EditText(act)
    private val tabs = LinearLayout(act)
    private val tabPersonal = TextView(act)
    private val tabWork = TextView(act)
    private val tabPrivate = TextView(act)
    private val pause = TextView(act)
    private val privBar = LinearLayout(act)
    private val privLock = TextView(act)
    private val privSettings = TextView(act)
    private val lockedNote = TextView(act)
    private val body = android.widget.FrameLayout(act)
    /** The app grid. Draws a thin line under the most-used row while that row is on screen. */
    private val grid = object : GridView(act) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            val r = super.onInterceptTouchEvent(ev)
            if (ev.actionMasked != MotionEvent.ACTION_MOVE) android.util.Log.d("CBDBG", "grid intercept a=${ev.actionMasked} x=${ev.x} y=${ev.y} -> $r first=$firstVisiblePosition children=$childCount")
            return r
        }
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val r = super.onTouchEvent(ev)
            if (ev.actionMasked != MotionEvent.ACTION_MOVE) android.util.Log.d("CBDBG", "grid touch a=${ev.actionMasked} -> $r")
            return r
        }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (topCells == 0 || firstVisiblePosition != 0) return
            val row = getChildAt(0) ?: return
            val y = row.bottom - TOP_ROW_EXTRA_DP * d / 2f
            val inset = 16 * d
            canvas.drawRect(paddingLeft + inset, y, width - paddingRight - inset, y + d, linePaint)
        }
    }
    private val linePaint = Paint()
    private val bubble = TextView(act)
    private val scroller = LetterScroller(act, bubble) { grid.setSelection(it + topCells) }
    private var tab = Apps.Tab.PERSONAL

    /** The tab's apps, or search results, in order. */
    private var items: List<AppInfo> = emptyList()

    /** What the grid shows: the most-used row (padded with blanks to a full row), then [items]. */
    private var cells: List<AppInfo?> = emptyList()

    /** How many cells the most-used row takes: a full row, or 0 when it isn't shown. */
    private var topCells = 0
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private val fling = 600 * d

    var isOpen = false
        private set

    private val adapter = object : BaseAdapter() {
        override fun getCount() = cells.size
        override fun getItem(position: Int) = cells[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (cells[position] == null) 1 else 0
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = cells[position] != null
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // The most-used row is a little taller, leaving room for the line under it.
            val h = cellHeight() + if (position < topCells) (TOP_ROW_EXTRA_DP * d).roundToInt() else 0
            val app = cells[position] ?: return (convertView ?: View(act)).apply {
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val v = (convertView as? IconView) ?: IconView(act)
            v.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            v.iconSize = IconRenderer.sizePx(act)
            v.showLabel = true
            v.panelStyle(act.pal.text)
            v.bitmap = IconRenderer.app(act, app.key)
            v.label = app.label
            v.greyed = Apps.isQuiet(app.key.user)
            Dots.attach(v, listOf(Dots.keyOf(app.key)), act.pal)
            android.util.Log.d("CBDBG", "getView pos=$position view=${System.identityHashCode(v)} app=${app.label} parent=${v.parent != null}")
            v.setOnClickListener {
                android.util.Log.d("CBDBG", "click pos=$position view=${System.identityHashCode(v)} app=${app.label}")
                act.launchApp(v, app.key)
            }
            v.setOnLongClickListener {
                act.dnd.startDrawerDrag(v, app)
                true
            }
            return v
        }
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        isClickable = true

        search.apply {
            hint = context.getString(R.string.search_apps)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                cells.firstOrNull { it != null }?.let { act.launchApp(this, it.key) }
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = filter()
            })
        }
        addView(search, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).roundToInt()).apply {
            setMargins((16 * d).roundToInt(), (8 * d).roundToInt(), (16 * d).roundToInt(), (8 * d).roundToInt())
        })

        tabs.orientation = HORIZONTAL
        for (t in listOf(tabPersonal, tabWork, tabPrivate)) {
            t.gravity = Gravity.CENTER
            t.textSize = 15f
            val pad = (10 * d).roundToInt()
            t.setPadding(pad, pad, pad, pad)
            tabs.addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        tabPersonal.text = context.getString(R.string.tab_personal)
        tabWork.text = context.getString(R.string.tab_work)
        tabPrivate.text = context.getString(R.string.tab_private)
        tabPersonal.setOnClickListener { setTab(Apps.Tab.PERSONAL) }
        tabWork.setOnClickListener { setTab(Apps.Tab.WORK) }
        tabPrivate.setOnClickListener { setTab(Apps.Tab.PRIVATE) }
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins((16 * d).roundToInt(), 0, (16 * d).roundToInt(), 0)
        })

        pause.gravity = Gravity.CENTER
        pause.textSize = 14f
        pause.setPadding(0, (10 * d).roundToInt(), 0, (10 * d).roundToInt())
        pause.setOnClickListener {
            val u = Apps.workUser() ?: return@setOnClickListener
            Apps.setQuiet(u, !Apps.isQuiet(Apps.serial(u)))
        }
        addView(pause, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Private space: lock or unlock, and Android's private space settings.
        privBar.orientation = HORIZONTAL
        for (t in listOf(privLock, privSettings)) {
            t.gravity = Gravity.CENTER
            t.textSize = 14f
            t.setPadding(0, (10 * d).roundToInt(), 0, (10 * d).roundToInt())
            privBar.addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        privSettings.text = context.getString(R.string.private_settings)
        privLock.setOnClickListener { PrivateSpace.setLocked(!PrivateSpace.locked()) }
        privSettings.setOnClickListener { PrivateSpace.openSettings(act) }
        addView(privBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        grid.adapter = adapter
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.verticalSpacing = (4 * d).roundToInt()
        grid.isVerticalScrollBarEnabled = false
        grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        body.addView(grid, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        lockedNote.gravity = Gravity.CENTER
        lockedNote.textSize = 15f
        lockedNote.text = context.getString(R.string.private_locked)
        lockedNote.visibility = GONE
        body.addView(lockedNote, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val strip = (24 * d).roundToInt()
        scroller.setPadding(0, (8 * d).roundToInt(), 0, (8 * d).roundToInt())
        body.addView(scroller, android.widget.FrameLayout.LayoutParams(strip, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        bubble.gravity = Gravity.CENTER
        bubble.textSize = 28f
        bubble.visibility = GONE
        val bs = (64 * d).roundToInt()
        body.addView(bubble, android.widget.FrameLayout.LayoutParams(bs, bs, Gravity.END or Gravity.TOP).apply { marginEnd = strip + (8 * d).roundToInt() })
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun cellHeight() = (IconRenderer.sizePx(act) + 34 * d).roundToInt()

    /** Columns for this window's width: see [drawerColumns]. */
    private fun columns() = drawerColumns(resources.configuration.screenWidthDp, Store.layout.settings.columns)

    fun setInsets(bars: Rect, ime: Int) {
        setPadding(bars.left, bars.top, bars.right, 0)
        bottomPad = maxOf(bars.bottom, ime) + (8 * d).roundToInt()
        grid.setPadding(grid.paddingLeft, 0, grid.paddingRight, bottomPad)
        scroller.setPadding(0, scroller.paddingTop, 0, bottomPad)
    }

    private var bottomPad = 0

    fun applyTheme() {
        val pal = act.pal
        setBackgroundColor(pal.bg or (0xFF shl 24))
        search.setTextColor(pal.text)
        search.setHintTextColor(pal.textDim)
        search.background = pal.card(act, pal.surface, 24f)
        search.setPadding((18 * d).roundToInt(), 0, (18 * d).roundToInt(), 0)
        search.backgroundTintList = null
        search.compoundDrawableTintList = ColorStateList.valueOf(pal.textDim)
        grid.numColumns = columns()
        linePaint.color = pal.textDim and 0x55FFFFFF
        scroller.color = pal.textDim
        scroller.activeColor = pal.accent
        bubble.setTextColor(pal.onAccent)
        bubble.background = pal.card(act, pal.accent, 32f)
        lockedNote.setTextColor(pal.textDim)
        refresh()
    }

    private fun setTab(t: Apps.Tab) {
        tab = t
        grid.setSelection(0)
        refresh()
    }

    fun refresh() {
        val pal = act.pal
        val wu = Apps.workUser()
        val hasWork = wu != null
        val hasPrivate = PrivateSpace.showTab()
        if (tab == Apps.Tab.WORK && !hasWork) tab = Apps.Tab.PERSONAL
        if (tab == Apps.Tab.PRIVATE && !hasPrivate) tab = Apps.Tab.PERSONAL
        tabs.visibility = if (hasWork || hasPrivate) VISIBLE else GONE
        tabWork.visibility = if (hasWork) VISIBLE else GONE
        tabPrivate.visibility = if (hasPrivate) VISIBLE else GONE
        for ((t, v) in listOf(Apps.Tab.PERSONAL to tabPersonal, Apps.Tab.WORK to tabWork, Apps.Tab.PRIVATE to tabPrivate)) {
            v.setTextColor(if (tab == t) pal.accent else pal.textDim)
            v.background = if (tab == t) pal.card(act, pal.surface, 20f) else null
        }
        val canPause = tab == Apps.Tab.WORK && wu != null && Shortcuts.available()
        pause.visibility = if (canPause) VISIBLE else GONE
        if (wu != null) pause.text = context.getString(if (Apps.isQuiet(Apps.serial(wu))) R.string.work_resume else R.string.work_pause)
        pause.setTextColor(pal.accent)
        val priv = tab == Apps.Tab.PRIVATE
        val locked = priv && PrivateSpace.locked()
        privBar.visibility = if (priv) VISIBLE else GONE
        privLock.text = context.getString(if (locked) R.string.private_unlock else R.string.private_lock)
        privLock.setTextColor(pal.accent)
        privSettings.setTextColor(pal.accent)
        privSettings.visibility = if (PrivateSpace.canOpenSettings) VISIBLE else GONE
        lockedNote.visibility = if (locked) VISIBLE else GONE
        filter()
    }

    private fun filter() {
        val q = search.text.toString().trim()
        val locked = tab == Apps.Tab.PRIVATE && PrivateSpace.locked()
        val all = if (locked) emptyList() else Apps.drawerApps(tab)
        // Best matches first (Search.kt), since Enter launches the first one.
        items = Search.filter(all, q, scoreOf = { Usage.score(it.key) }) { it.label }
        // Most used apps (Usage.kt) get the first row, when on, not searching, and not in private space.
        val n = columns()
        val top = if (q.isEmpty() && tab != Apps.Tab.PRIVATE) Usage.top(all, n) else emptyList()
        topCells = if (top.isEmpty()) 0 else n
        cells = if (top.isEmpty()) items else top + List(n - top.size) { null } + items
        adapter.notifyDataSetChanged()
        // The letter strip is for browsing the whole list, so it steps aside while searching.
        scroller.setItems(items) { letterOf(it.label) }
        val showStrip = q.isEmpty() && scroller.useful
        scroller.visibility = if (showStrip) VISIBLE else GONE
        val strip = if (showStrip) (24 * d).roundToInt() else 0
        grid.setPadding(0, 0, strip, bottomPad)
    }

    /** The letter an app sorts under: its first letter without accents, or # for anything else. */
    private fun letterOf(label: String): String {
        val c = Search.fold(label).trimStart().firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    // ---- Open and close ----

    fun beginOpen() {
        if (visibility != VISIBLE) {
            search.setText("")
            tab = Apps.Tab.PERSONAL
            refresh()
            grid.setSelection(0)
        }
        visibility = VISIBLE
        translationY = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
    }

    fun trackOpen(dist: Float) {
        val h = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        translationY = (h - dist).coerceIn(0f, h)
    }

    /** Opens on an upward fling, or on any swipe past a fifth of the screen that isn't flung back down. */
    fun releaseOpen(vy: Float) {
        val h = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        val dist = h - translationY
        if (vy < -fling / 2 || (vy < fling && dist > h * 0.2f)) open() else close()
    }

    /** Dark status bar icons over a light drawer, light ones back over the wallpaper. */
    private fun lightBars(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        act.window.insetsController?.setSystemBarsAppearance(if (on) mask else 0, mask)
    }

    fun open() {
        if (visibility != VISIBLE) beginOpen()
        isOpen = true
        lightBars(!act.pal.isDark)
        animate().translationY(0f).setDuration(220).start()
        act.onOverlayChanged()
        if (Store.layout.settings.drawerKeyboard) {
            search.requestFocus()
            search.postDelayed({
                act.getSystemService(InputMethodManager::class.java)?.showSoftInput(search, 0)
            }, 120)
        }
    }

    fun close(animate: Boolean = true) {
        isOpen = false
        lightBars(false)
        act.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
        if (animate && visibility == VISIBLE) {
            animate().translationY(height.toFloat()).setDuration(200).withEndAction { if (!isOpen) visibility = GONE }.start()
        } else {
            animate().cancel()
            visibility = GONE
        }
        act.onOverlayChanged()
    }

    // ---- Swipe down at the top of the list closes it ----

    private var downX = 0f
    private var downY = 0f
    private var closing = false
    private var onStrip = false
    private var vt: android.view.VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val r = super.dispatchTouchEvent(ev)
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) {
            val c0 = grid.getChildAt(0)
            val loc = IntArray(2)
            c0?.getLocationOnScreen(loc)
            android.util.Log.d("CBDBG", "drawer dispatch a=${ev.actionMasked} raw=${ev.rawX},${ev.rawY} -> $r closing=$closing child0=${c0?.let { System.identityHashCode(it) }} at=${loc[0]},${loc[1]} ${c0?.width}x${c0?.height} enabled=${c0?.isEnabled} clickable=${c0?.isClickable} ty=$translationY")
        }
        return r
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                closing = false
                // Dragging down the letter strip scrolls, it never closes the drawer.
                onStrip = scroller.isShown && ev.x >= width - paddingRight - scroller.width
                vt?.recycle()
                vt = android.view.VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                if (!closing && !onStrip && dy > slop && dy > abs(dx) && !grid.canScrollVertically(-1)) closing = true
            }
        }
        vt?.addMovement(ev)
        return closing
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!closing) return super.onTouchEvent(ev)
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> translationY = (ev.rawY - downY).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                closing = false
                if (vy > fling || translationY > height * 0.25f) close() else open()
            }
        }
        return true
    }
}
