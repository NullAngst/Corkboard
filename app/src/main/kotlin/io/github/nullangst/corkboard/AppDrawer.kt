/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.content.res.ColorStateList
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
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Swipe-up drawer: search on top, Personal and Work tabs, every non-hidden app alphabetically. */
@SuppressLint("ViewConstructor")
class AppDrawer(private val act: HomeActivity) : LinearLayout(act) {
    private val d = resources.displayMetrics.density
    private val search = EditText(act)
    private val tabs = LinearLayout(act)
    private val tabPersonal = TextView(act)
    private val tabWork = TextView(act)
    private val pause = TextView(act)
    private val grid = GridView(act)
    private var work = false
    private var items: List<AppInfo> = emptyList()
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private val fling = 600 * d

    var isOpen = false
        private set

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val app = items[position]
            val v = (convertView as? IconView) ?: IconView(act).apply {
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight())
            }
            v.iconSize = IconRenderer.sizePx(act)
            v.showLabel = true
            v.panelStyle(act.pal.text)
            v.bitmap = IconRenderer.app(act, app.key)
            v.label = app.label
            v.greyed = Apps.isQuiet(app.key.user)
            v.setOnClickListener { act.launchApp(v, app.key) }
            v.setOnLongClickListener {
                act.startDrawerDrag(v, app)
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
                items.firstOrNull()?.let { act.launchApp(this, it.key) }
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
        for (t in listOf(tabPersonal, tabWork)) {
            t.gravity = Gravity.CENTER
            t.textSize = 15f
            val pad = (10 * d).roundToInt()
            t.setPadding(pad, pad, pad, pad)
            tabs.addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        tabPersonal.text = context.getString(R.string.tab_personal)
        tabWork.text = context.getString(R.string.tab_work)
        tabPersonal.setOnClickListener { setTab(false) }
        tabWork.setOnClickListener { setTab(true) }
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

        grid.adapter = adapter
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.verticalSpacing = (4 * d).roundToInt()
        grid.isVerticalScrollBarEnabled = false
        grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun cellHeight() = (IconRenderer.sizePx(act) + 34 * d).roundToInt()

    fun setInsets(bars: Rect, ime: Int) {
        setPadding(bars.left, bars.top, bars.right, 0)
        grid.setPadding(0, 0, 0, maxOf(bars.bottom, ime) + (8 * d).roundToInt())
    }

    fun applyTheme() {
        val pal = act.pal
        setBackgroundColor(pal.bg or (0xFF shl 24))
        search.setTextColor(pal.text)
        search.setHintTextColor(pal.textDim)
        search.background = pal.card(act, pal.surface, 24f)
        search.setPadding((18 * d).roundToInt(), 0, (18 * d).roundToInt(), 0)
        search.backgroundTintList = null
        search.compoundDrawableTintList = ColorStateList.valueOf(pal.textDim)
        grid.numColumns = Store.layout.settings.columns
        refresh()
    }

    private fun setTab(w: Boolean) {
        work = w
        refresh()
    }

    fun refresh() {
        val pal = act.pal
        val hasWork = Apps.workUser() != null
        if (!hasWork) work = false
        tabs.visibility = if (hasWork) VISIBLE else GONE
        tabPersonal.setTextColor(if (!work) pal.accent else pal.textDim)
        tabWork.setTextColor(if (work) pal.accent else pal.textDim)
        tabPersonal.background = if (!work) pal.card(act, pal.surface, 20f) else null
        tabWork.background = if (work) pal.card(act, pal.surface, 20f) else null
        val wu = Apps.workUser()
        val canPause = work && wu != null && Shortcuts.available()
        pause.visibility = if (canPause) VISIBLE else GONE
        if (wu != null) pause.text = context.getString(if (Apps.isQuiet(Apps.serial(wu))) R.string.work_resume else R.string.work_pause)
        pause.setTextColor(pal.accent)
        filter()
    }

    private fun filter() {
        val q = fold(search.text.toString().trim())
        items = Apps.drawerApps(work).filter { q.isEmpty() || fold(it.label).contains(q) }
        adapter.notifyDataSetChanged()
    }

    private val marks = Regex("\\p{Mn}+")

    /** Lowercase with accents dropped, so "musica" finds "Música" and "uber" finds "Über". */
    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()

    // ---- Open and close ----

    fun beginOpen() {
        if (visibility != VISIBLE) {
            search.setText("")
            work = false
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
    private var vt: android.view.VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                closing = false
                vt?.recycle()
                vt = android.view.VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                if (!closing && dy > slop && dy > abs(dx) && !grid.canScrollVertically(-1)) closing = true
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
