/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.animation.ValueAnimator
import android.app.role.RoleManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ShortcutInfo
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ContextThemeWrapper
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

const val REQ_ROLE = 21

/** Height of the empty band above the grid where the Remove and Uninstall bar appears. */
const val TOP_BAND_DP = 48f

/** Longest the coming-home icons wait for the system's close transition before they start anyway. */
const val ENTER_WAIT_MS = 220L

fun isDefaultHome(ctx: Context): Boolean = try {
    ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
} catch (_: Exception) {
    false
}

/** Asks for the home role through RoleManager, or opens the system's home app setting. */
fun requestHomeRole(act: Activity) {
    val rm = act.getSystemService(RoleManager::class.java)
    try {
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            act.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
            return
        }
    } catch (_: Exception) {
    }
    try {
        act.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    } catch (_: Exception) {
    }
}

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

class MenuEntry(val label: String, val icon: Drawable? = null, val onLongPress: ((View) -> Unit)? = null, val action: () -> Unit)

/** Shadow whose touch point is where the finger went down, not the middle of the view. */
private class TouchShadow(v: View, private val tx: Int, private val ty: Int) : View.DragShadowBuilder(v) {
    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint)
        outShadowTouchPoint.set(tx.coerceIn(0, outShadowSize.x), ty.coerceIn(0, outShadowSize.y))
    }
}

class HomeActivity : Activity() {
    enum class Src { PAGE, DOCK, FOLDER, DRAWER, SHORTCUT, PICKER }

    /** Everything about one drag, passed as the system drag's local state. */
    class DragState(val source: Src, val item: Item?, val view: View?, val w: Int, val h: Int) {
        var appKey: AppKey? = null
        var folder: FolderItem? = null
        var shortcut: ShortcutInfo? = null
        var widgetInfo: AppWidgetProviderInfo? = null
        var startX = Float.NaN
        var startY = 0f
        var lastX = 0f
        var lastY = 0f
        var dirX = 0f
        var dirY = 0f
        var moved = false
        var grid: CellGrid? = null
        var page = -2
        var cellX = -1
        var cellY = -1
        var moves: Map<Item, Point> = emptyMap()
        var placeOk = false
        var refused = false
        var folderCandidate: Item? = null
        var folderTarget: Item? = null
        var edgeDir = 0
        var pendingMenu = false

        /** 0 none, 1 over Remove, 2 over Uninstall. */
        var dropHover = 0

        /** Where the finger holds the shadow, and the shadow's size: the item is placed where it looks. */
        var touchX = 0
        var touchY = 0
        var shadowW = 0
        var shadowH = 0

        val isApp get() = item is AppItem || ((source == Src.DRAWER || source == Src.FOLDER) && appKey != null)
        val isWidget get() = item is WidgetItem || source == Src.PICKER
        val key: AppKey? get() = appKey ?: (item as? AppItem)?.key
    }

    lateinit var pal: Palette
    private lateinit var root: HomeRoot
    private lateinit var homeCol: LinearLayout
    lateinit var workspace: Workspace
    private lateinit var dock: CellGrid
    private lateinit var dots: PageDots
    private lateinit var banner: LinearLayout
    lateinit var drawer: AppDrawer
    lateinit var widgets: Widgets
    private var folderPanel: FolderPanel? = null
    private var overlay: View? = null
    private var boundRevision = -1
    private var bars = Rect()
    private val handler = Handler(Looper.getMainLooper())
    private var drag: DragState? = null
    private var rebindPending = false
    private val d get() = resources.displayMetrics.density
    private var backCallback: Any? = null
    private var enterPending = false
    private var enterGo: Runnable? = null
    private lateinit var dropBar: LinearLayout
    private lateinit var dropRemove: TextView
    private lateinit var dropUninstall: TextView
    private val appsListener: () -> Unit = { if (drag == null) rebind() else rebindPending = true }
    private val shortcutsListener: () -> Unit = { if (drag == null) rebind() }

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        Store.init(this)
        Apps.init(this)
        Shade.init(this)
        Haptics.init(this)
        pal = Themes.palette(this)
        applyRotation()
        edgeToEdge()
        widgets = Widgets(this)
        buildViews()
        setContentView(root)
        Apps.listener = appsListener
        Apps.shortcutsListener = shortcutsListener
        Apps.reconcile()
        widgets.sweep()
        rebind()
        onOverlayChanged()
    }

    override fun onStart() {
        super.onStart()
        try {
            widgets.host.startListening()
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        super.onStop()
        AutoBackup.flush()
        settleEnter()
        // An app covered the home screen while the screen was on: play the entry animation when
        // coming back. Screen-off stops (lock, then unlock) are left to Android's unlock animation.
        enterPending = getSystemService(PowerManager::class.java)?.isInteractive == true
        try {
            widgets.host.stopListening()
        } catch (_: Exception) {
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        // The system's close transition is done: start the icons now if they're still waiting.
        enterGo?.let { root.removeCallbacks(it); it.run() }
    }

    override fun onResume() {
        super.onResume()
        // A drawer left half-closed by an interrupted animation must never sit invisible over the home screen.
        if (!drawer.isOpen && drawer.visibility == View.VISIBLE) drawer.close(animate = false)
        applyRotation()
        if (Store.revision != boundRevision) rebind()
        updateBanner()
        if (enterPending) {
            enterPending = false
            playEnter()
        }
        AutoBackup.periodicCheck(this)
    }

    /** Upright unless "Allow home screen rotation" is on. */
    private fun applyRotation() {
        val want = if (Store.layout.settings.allowRotation) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        }
        if (requestedOrientation != want) requestedOrientation = want
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only clear what this instance set: a recreated home screen may already have put its own.
        if (Apps.listener === appsListener) Apps.listener = null
        if (Apps.shortcutsListener === shortcutsListener) Apps.shortcutsListener = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        IconRenderer.clear()
        rebind()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_MAIN) return
        val alreadyOnHome = hasWindowFocus() && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        closeAll(alreadyOnHome)
        if (alreadyOnHome) workspace.snapTo(0)
    }

    /** Android 10 to 12: back arrives as a key. Android 13+ uses the OnBackInvokedCallback below. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (widgets.onResult(requestCode, resultCode)) return
        if (requestCode == REQ_ROLE) {
            updateBanner()
            rebind()
        }
    }

    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) return // edge-to-edge is enforced
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ---- Views ----

    private fun buildViews() {
        root = HomeRoot()
        homeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        workspace = Workspace()
        dots = PageDots(this)
        dock = CellGrid(this, Store.layout.settings.columns, 1)
        workspace.onPageChanged = { i ->
            dots.current = i
            if (drag == null) handler.postDelayed({ prunePages() }, 400)
        }
        val m = (8 * d).roundToInt()
        homeCol.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(m * 2, m, m * 2, 0)
        })
        homeCol.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        homeCol.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (20 * d).roundToInt()))
        homeCol.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (80 * d).roundToInt()))
        root.addView(homeCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        drawer = AppDrawer(this)
        root.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildDropBar()
        root.setOnApplyWindowInsetsListener { _, wi ->
            applyInsets(wi)
            wi
        }
        root.setOnDragListener { _, e -> onDragEvent(e) }
    }

    private fun applyInsets(wi: WindowInsets) {
        var ime = 0
        bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            ime = wi.getInsets(WindowInsets.Type.ime()).bottom
            Rect(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            Rect(wi.systemWindowInsetLeft, wi.systemWindowInsetTop, wi.systemWindowInsetRight, wi.systemWindowInsetBottom)
        }
        homeCol.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        drawer.setInsets(bars, ime)
        dropBar.setPadding(bars.left, bars.top, bars.right, 0)
        dropBar.layoutParams.height = bars.top + (TOP_BAND_DP * d).roundToInt()
        overlay?.let { if (it.tag == "full") it.setPadding(bars.left, bars.top, bars.right, bars.bottom) }
    }

    fun iconView(bmp: android.graphics.Bitmap?, label: String, showLabel: Boolean, home: Boolean): IconView =
        IconView(this).apply {
            bitmap = bmp
            this.label = label
            this.showLabel = showLabel
            iconSize = IconRenderer.sizePx(this@HomeActivity)
            if (home) homeStyle() else panelStyle(pal.text)
        }

    private fun viewFor(item: Item, src: Src): View {
        val s = Store.layout.settings
        val labels = s.labels && src != Src.DOCK
        return when (item) {
            is AppItem -> {
                val app = Apps.apps[item.key]
                iconView(IconRenderer.app(this, item.key), app?.label ?: "", labels, true).apply {
                    greyed = Apps.isQuiet(item.key.user)
                    setOnClickListener { launchApp(this, item.key) }
                    setOnLongClickListener { itemLongPress(this, item, src) }
                }
            }
            is FolderItem -> iconView(IconRenderer.folder(this, item), item.name, labels, true).apply {
                setOnClickListener { openFolder(item) }
                setOnLongClickListener { itemLongPress(this, item, src) }
            }
            is ShortcutItem -> {
                val label = Store.layout.icons["item:${item.id}"]?.label ?: item.label
                iconView(IconRenderer.shortcut(this, item), label, labels, true).apply {
                    setOnClickListener { Shortcuts.launch(this@HomeActivity, item.pkg, item.shortcutId, item.user, bounds(this), clipReveal(this)) }
                    setOnLongClickListener { itemLongPress(this, item, src) }
                }
            }
            is DrawerButtonItem -> iconView(IconRenderer.drawerButton(this), getString(R.string.s_apps), labels, true).apply {
                setOnClickListener { openDrawerFromButton() }
                setOnLongClickListener { itemLongPress(this, item, src) }
            }
            is WidgetItem -> widgets.view(item).apply {
                onLongPress = { f -> itemLongPress(f, item, src) }
            }
        }
    }

    fun rebind() {
        if (drag != null) {
            rebindPending = true
            return
        }
        rebindPending = false
        pal = Themes.palette(this)
        boundRevision = Store.revision
        val l = Store.layout
        val s = l.settings
        workspace.setPageCount(l.pages.size, s.columns, s.rows)
        for ((i, page) in l.pages.withIndex()) {
            val g = workspace.pages[i]
            g.cols = s.columns
            g.rows = s.rows
            g.removeAllViews()
            for (item in page) g.add(viewFor(item, Src.PAGE), item)
        }
        dock.cols = s.columns
        dock.removeAllViews()
        for (item in l.dock) {
            item.y = 0
            dock.add(viewFor(item, Src.DOCK), item, item.x, 0, 1, 1)
        }
        dock.layoutParams.height = (IconRenderer.sizePx(this) + 28 * d).roundToInt()
        dock.requestLayout()
        dots.count = l.pages.size
        dots.current = workspace.current
        drawer.applyTheme()
        widgets.retain(l.allItems().filterIsInstance<WidgetItem>().map { it.widgetId }.toHashSet())
        // An open folder can stop existing underneath (an app in it uninstalled, leaving one): close it
        // rather than keep showing, and saving into, a folder that's no longer on the home screen.
        folderPanel?.let { p -> if (l.allItems().any { it === p.folder }) p.build() else closeFolder() }
        updateBanner()
    }

    private fun updateBanner() {
        if (isDefaultHome(this)) {
            banner.visibility = View.GONE
            return
        }
        banner.removeAllViews()
        banner.background = pal.card(this, pal.surface, 18f)
        val p = (12 * d).roundToInt()
        banner.setPadding(p, p / 2, p / 2, p / 2)
        banner.addView(TextView(this).apply {
            text = getString(R.string.banner_not_default)
            setTextColor(pal.text)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        banner.addView(TextView(this).apply {
            text = getString(R.string.set_as_default)
            setTextColor(pal.accent)
            setPadding(p, p, p, p)
            setOnClickListener { requestHomeRole(this@HomeActivity) }
        })
        banner.visibility = View.VISIBLE
    }

    fun pageCellW(): Float = workspace.pages.getOrNull(workspace.current)?.cellW?.takeIf { it > 0 }
        ?: (resources.displayMetrics.widthPixels / Store.layout.settings.columns.toFloat())

    fun pageCellH(): Float = workspace.pages.getOrNull(workspace.current)?.cellH?.takeIf { it > 0 }
        ?: (resources.displayMetrics.heightPixels * 0.7f / Store.layout.settings.rows)

    private fun prunePages() {
        if (drag != null) return
        val l = Store.layout
        val before = l.pages.size
        val cur = l.pruneEmptyPages(workspace.current)
        if (l.pages.size != before) {
            Store.save()
            workspace.current = cur
            rebind()
            workspace.snapTo(cur, animate = false)
        }
    }

    // ---- Launching ----

    private fun bounds(v: View): Rect {
        val a = IntArray(2)
        v.getLocationOnScreen(a)
        return Rect(a[0], a[1], a[0] + v.width, a[1] + v.height)
    }

    private fun clipReveal(v: View): Bundle? = try {
        ActivityOptions.makeClipRevealAnimation(v, 0, 0, v.width, v.height).toBundle()
    } catch (_: Exception) {
        null
    }

    fun launchApp(v: View, key: AppKey) {
        if (Apps.isQuiet(key.user)) {
            askUnpause()
            return
        }
        val user = Apps.user(key.user) ?: return
        try {
            Apps.la.startMainActivity(key.component, user, bounds(v), clipReveal(v))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.cant_open_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun askUnpause() {
        val u = Apps.workUser() ?: return
        AlertDialog.Builder(this, Themes.dialogTheme(pal))
            .setTitle(R.string.work_paused_title)
            .setMessage(R.string.work_paused_msg)
            .setPositiveButton(R.string.turn_on) { _, _ -> Apps.setQuiet(u, false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Overlays: menus, picker, resize, folders ----

    fun showOverlay(v: View, full: Boolean, dim: Boolean = true) {
        closeOverlay()
        if (full) {
            v.tag = "full"
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        }
        if (dim && !full) v.setBackgroundColor(pal.scrim)
        overlay = v
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onOverlayChanged()
    }

    fun closeOverlay() {
        val v = overlay ?: return
        overlay = null
        root.removeView(v)
        onOverlayChanged()
    }

    fun openFolder(f: FolderItem) {
        closeOverlay()
        closeFolder()
        val p = FolderPanel(this, f)
        folderPanel = p
        root.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.alpha = 0f
        p.animate().alpha(1f).setDuration(150).start()
        onOverlayChanged()
    }

    fun closeFolder() {
        val p = folderPanel ?: return
        folderPanel = null
        root.removeView(p)
        onOverlayChanged()
    }

    private fun closeAll(animate: Boolean) {
        closeOverlay()
        closeFolder()
        if (drawer.isOpen || drawer.visibility == View.VISIBLE) drawer.close(animate)
    }

    private fun handleBack(): Boolean {
        when {
            overlay != null -> closeOverlay()
            folderPanel != null -> closeFolder()
            drawer.isOpen -> drawer.close()
            else -> return false
        }
        return true
    }

    /**
     * Android 13+: one back callback, always registered, so back on the home screen never runs the
     * system's back-to-home animation. It closes whatever is open, or does nothing.
     */
    fun onOverlayChanged() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backCallback != null) return
        val cb = OnBackInvokedCallback { handleBack() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        backCallback = cb
    }

    private fun rectInRoot(v: View): RectF {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val x = (a[0] - b[0]).toFloat()
        val y = (a[1] - b[1]).toFloat()
        return RectF(x, y, x + v.width, y + v.height)
    }

    /** A popup menu card near [anchor] (root coordinates), in the launcher's theme colors. */
    fun showMenu(anchor: RectF, entries: List<MenuEntry>) {
        if (entries.isEmpty()) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pal.card(this@HomeActivity, pal.surface, 18f)
            elevation = 8 * d
            val p = (6 * d).roundToInt()
            setPadding(0, p, 0, p)
        }
        var lastHadIcon = entries.first().icon != null
        for (e in entries) {
            if (lastHadIcon && e.icon == null) {
                card.addView(View(this).apply { setBackgroundColor(pal.textDim and 0x33FFFFFF) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).roundToInt().coerceAtLeast(1)))
            }
            lastHadIcon = e.icon != null
            card.addView(TextView(this).apply {
                text = e.label
                textSize = 15f
                setTextColor(pal.text)
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                val p = (16 * d).roundToInt()
                setPadding(p, (12 * d).roundToInt(), p * 2, (12 * d).roundToInt())
                e.icon?.let { ic ->
                    val s = (24 * d).roundToInt()
                    ic.setBounds(0, 0, s, s)
                    setCompoundDrawablesRelative(ic, null, null, null)
                    compoundDrawablePadding = (12 * d).roundToInt()
                }
                setOnClickListener {
                    closeOverlay()
                    e.action()
                }
                e.onLongPress?.let { lp ->
                    setOnLongClickListener {
                        lp(it)
                        true
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val frame = FrameLayout(this).apply { setOnClickListener { closeOverlay() } }
        val maxW = (280 * d).roundToInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val cw = card.measuredWidth
        val m = (8 * d).roundToInt()
        val screenW = root.width
        val screenH = root.height
        // A long menu (four shortcuts plus every action, in landscape) scrolls instead of running off screen.
        val avail = (screenH - bars.top - bars.bottom - 2 * m).coerceAtLeast(m * 6)
        val ch = minOf(card.measuredHeight, avail)
        val holder = ScrollView(this).apply {
            background = card.background
            elevation = card.elevation
            clipToOutline = true
            isVerticalScrollBarEnabled = card.measuredHeight > avail
        }
        card.background = null
        card.elevation = 0f
        holder.addView(card)
        val x = (anchor.centerX() - cw / 2f).roundToInt().coerceIn(m, (screenW - cw - m).coerceAtLeast(m))
        val above = anchor.top - ch - m
        val y = if (above > bars.top + m) {
            above.roundToInt()
        } else {
            (anchor.bottom + m).roundToInt().coerceAtMost((screenH - bars.bottom - ch - m).coerceAtLeast(bars.top + m))
        }
        frame.addView(holder, FrameLayout.LayoutParams(cw, ch).apply {
            leftMargin = x
            topMargin = y
        })
        showOverlay(frame, full = false, dim = false)
    }

    private fun emptySpaceMenu(x: Float, y: Float) {
        Haptics.play(root, Haptics.LIFT, Haptics.Fx.LIFT)
        showMenu(RectF(x, y, x, y), listOf(
            MenuEntry(getString(R.string.wallpaper)) {
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper)))
                } catch (_: Exception) {
                }
            },
            MenuEntry(getString(R.string.widgets)) { widgets.showPicker() },
            MenuEntry(getString(R.string.settings)) { startActivity(Intent(this, SettingsActivity::class.java)) },
        ))
    }

    private fun editIcon(target: String) {
        startActivity(Intent(this, IconEditActivity::class.java).putExtra("target", target))
    }

    private fun renameDialog(current: String, onDone: (String?) -> Unit) {
        // Built in the dialog's own theme: from the home screen's dark-based theme the text came out
        // white on a light dialog, so the name looked blank in light mode.
        val et = EditText(ContextThemeWrapper(this, Themes.dialogTheme(pal))).apply {
            setText(current)
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            val p = (20 * d).roundToInt()
            setPadding(p, p / 2, p, 0)
            addView(et)
        }
        AlertDialog.Builder(this, Themes.dialogTheme(pal))
            .setTitle(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> onDone(et.text.toString().trim().ifEmpty { null }) }
            .setNeutralButton(R.string.reset) { _, _ -> onDone(null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setLabel(target: String, label: String?) {
        val icons = Store.layout.icons
        val s = icons[target]?.copy() ?: IconSettings()
        s.label = label
        if (s.isDefault()) icons.remove(target) else icons[target] = s
        Store.save()
        rebind()
    }

    fun removeItem(item: Item) {
        val l = Store.layout
        l.remove(item)
        l.icons.remove("item:${item.id}")
        when (item) {
            is WidgetItem -> widgets.delete(item)
            is ShortcutItem -> Shortcuts.unpinIfUnused(item.pkg, item.shortcutId, item.user)
            else -> {}
        }
        Store.save()
        Store.cleanIcons()
        rebind()
    }

    private fun appMenu(anchor: RectF, key: AppKey, item: AppItem?, folder: FolderItem?, inDrawer: Boolean) {
        val app = Apps.apps[key] ?: return
        val l = Store.layout
        val entries = ArrayList<MenuEntry>()
        for (si in Shortcuts.forApp(app)) {
            entries.add(MenuEntry(
                Shortcuts.label(si),
                Shortcuts.icon(this, si),
                onLongPress = if (l.settings.locked) null else { v -> startShortcutDrag(v, si) },
            ) {
                Shortcuts.launch(this, si.`package`, si.id, key.user, null, null)
            })
        }
        entries.add(MenuEntry(getString(R.string.edit_icon)) { editIcon("app:$key") })
        entries.add(MenuEntry(getString(R.string.rename)) { renameDialog(app.label) { setLabel("app:$key", it) } })
        when {
            inDrawer -> entries.add(MenuEntry(getString(R.string.add_to_home)) {
                l.placeFirstFree(AppItem(l.newId(), key))
                Store.save()
                rebind()
                Toast.makeText(this, R.string.added_to_home, Toast.LENGTH_SHORT).show()
            })
            folder != null -> entries.add(MenuEntry(getString(R.string.remove_from_folder)) {
                folder.apps.remove(key)
                l.fixFolders()
                Store.save()
                closeFolder()
                rebind()
            })
            item != null -> entries.add(MenuEntry(getString(R.string.remove_from_home)) { removeItem(item) })
        }
        entries.add(MenuEntry(getString(R.string.hide)) {
            l.hidden.add(key.toString())
            l.removeApp(key)
            Store.save()
            closeFolder()
            rebind()
        })
        entries.add(MenuEntry(getString(R.string.app_info)) {
            try {
                Apps.la.startAppDetailsActivity(key.component, app.user, null, null)
            } catch (_: Exception) {
            }
        })
        if (!app.isSystem) entries.add(MenuEntry(getString(R.string.uninstall)) { uninstall(app) })
        showMenu(anchor, entries)
    }

    private fun itemMenu(st: DragState) {
        val v = st.view ?: return
        val anchor = rectInRoot(v)
        val item = st.item
        when {
            st.source == Src.DRAWER -> st.appKey?.let { appMenu(anchor, it, null, null, true) }
            st.source == Src.FOLDER -> st.appKey?.let { appMenu(anchor, it, null, st.folder, false) }
            item is AppItem -> appMenu(anchor, item.key, item, null, false)
            item is ShortcutItem -> showMenu(anchor, listOf(
                MenuEntry(getString(R.string.edit_icon)) { editIcon("item:${item.id}") },
                MenuEntry(getString(R.string.rename)) {
                    renameDialog(Store.layout.icons["item:${item.id}"]?.label ?: item.label) { setLabel("item:${item.id}", it) }
                },
                MenuEntry(getString(R.string.remove)) { removeItem(item) },
            ))
            item is FolderItem -> showMenu(anchor, listOf(
                MenuEntry(getString(R.string.edit_icon)) { editIcon("item:${item.id}") },
                MenuEntry(getString(R.string.rename)) {
                    renameDialog(item.name) {
                        item.name = it ?: getString(R.string.folder_default)
                        Store.save()
                        rebind()
                    }
                },
                MenuEntry(getString(R.string.remove)) { removeItem(item) },
            ))
            item is WidgetItem -> showMenu(anchor, listOf(
                MenuEntry(getString(R.string.resize)) {
                    val page = Store.layout.pageOf(item)
                    workspace.pages.getOrNull(page)?.let { widgets.startResize(item, it) }
                },
                MenuEntry(getString(R.string.remove)) { removeItem(item) },
                MenuEntry(getString(R.string.app_info)) {
                    val p = item.provider
                    val u = Apps.user(item.user)
                    if (p != null && u != null) try {
                        Apps.la.startAppDetailsActivity(p, u, null, null)
                    } catch (_: Exception) {
                    }
                },
            ))
        }
    }

    // ---- Starting drags ----

    private fun touchPoint(v: View): Pair<Int, Int> = when (v) {
        is IconView -> v.downX.roundToInt() to v.downY.roundToInt()
        is WidgetFrame -> v.downX.roundToInt() to v.downY.roundToInt()
        else -> v.width / 2 to v.height / 2
    }

    private fun startDrag(st: DragState, v: View): Boolean {
        val (tx, ty) = touchPoint(v)
        st.touchX = tx
        st.touchY = ty
        st.shadowW = v.width
        st.shadowH = v.height
        drag = st
        val ok = try {
            // Non-null clip data: an EditText under the finger would otherwise choke on a null clip.
            v.startDragAndDrop(ClipData.newPlainText("corkboard", ""), TouchShadow(v, tx, ty), st, View.DRAG_FLAG_OPAQUE)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            drag = null
            return false
        }
        Haptics.play(v, Haptics.LIFT, Haptics.Fx.LIFT)
        if (st.source == Src.PAGE || st.source == Src.DOCK || st.source == Src.FOLDER) v.visibility = View.INVISIBLE
        return true
    }

    /**
     * Long-press, the way Pixel Launcher does it: the item lifts and its menu opens right away,
     * while the finger is still down. Let go and the menu stays to tap; move past the touch slop
     * instead and the menu closes and the drag carries on (onDragMove).
     */
    private fun liftWithMenu(st: DragState, v: View) {
        if (Store.layout.settings.locked || !startDrag(st, v)) Haptics.play(v, Haptics.LIFT, Haptics.Fx.LIFT)
        itemMenu(st)
    }

    private fun itemLongPress(v: View, item: Item, src: Src): Boolean {
        liftWithMenu(DragState(src, item, v, if (src == Src.DOCK) 1 else item.w, if (src == Src.DOCK) 1 else item.h), v)
        return true
    }

    fun startDrawerDrag(v: View, app: AppInfo) {
        liftWithMenu(DragState(Src.DRAWER, null, v, 1, 1).apply { appKey = app.key }, v)
    }

    fun startFolderDrag(v: View, folder: FolderItem, key: AppKey) {
        liftWithMenu(DragState(Src.FOLDER, null, v, 1, 1).apply {
            appKey = key
            this.folder = folder
        }, v)
    }

    private fun startShortcutDrag(v: View, si: ShortcutInfo) {
        startDrag(DragState(Src.SHORTCUT, null, v, 1, 1).apply { shortcut = si }, v)
    }

    fun startNewWidgetDrag(v: View, info: AppWidgetProviderInfo, w: Int, h: Int) {
        startDrag(DragState(Src.PICKER, null, v, w, h).apply { widgetInfo = info }, v)
    }

    // ---- Drag and drop ----

    private val folderRunnable = Runnable {
        val st = drag ?: return@Runnable
        val t = st.folderCandidate ?: return@Runnable
        st.folderTarget = t
        (st.grid?.viewFor(t) as? IconView)?.hot = true
        Haptics.play(root, Haptics.FOLDER, Haptics.Fx.TICK)
    }

    private val reorderRunnable = Runnable {
        val st = drag ?: return@Runnable
        solveNow(st)
    }

    private val flipRunnable = object : Runnable {
        override fun run() {
            val st = drag ?: return
            if (st.edgeDir == 0) return
            val l = Store.layout
            val target = workspace.current + st.edgeDir
            if (target < 0) return
            clearTarget(st)
            if (target >= l.pages.size) {
                val last = l.pages.last()
                if (last.none { it !== st.item }) return
                l.pages.add(mutableListOf())
                workspace.setPageCount(l.pages.size, l.settings.columns, l.settings.rows)
                dots.count = l.pages.size
            }
            workspace.snapTo(target)
            Haptics.play(root, Haptics.PAGE, Haptics.Fx.TICK)
            handler.postDelayed(this, 900)
        }
    }

    private fun itemsOf(page: Int): List<Item> = if (page == -1) Store.layout.dock else Store.layout.pages.getOrNull(page).orEmpty()

    private fun clearTarget(st: DragState) {
        handler.removeCallbacks(folderRunnable)
        handler.removeCallbacks(reorderRunnable)
        st.grid?.let { g ->
            g.preview(emptyMap())
            g.clearOutline()
            st.folderTarget?.let { (g.viewFor(it) as? IconView)?.hot = false }
            st.folderCandidate?.let { (g.viewFor(it) as? IconView)?.hot = false }
        }
        st.grid = null
        st.page = -2
        st.cellX = -1
        st.cellY = -1
        st.moves = emptyMap()
        st.placeOk = false
        st.refused = false
        st.folderCandidate = null
        st.folderTarget = null
    }

    private fun solveNow(st: DragState) {
        val g = st.grid ?: return
        val dockMode = st.page == -1
        val w = if (dockMode) 1 else st.w
        val h = if (dockMode) 1 else st.h
        val others = itemsOf(st.page).filter { it !== st.item }
        val m = Reorder.solve(g.cols, g.rows, others, st.cellX, st.cellY, w, h, st.dirX, st.dirY)
        if (m == null) {
            st.refused = true
            st.placeOk = false
            g.showOutline(st.cellX, st.cellY, w, h, true)
        } else {
            st.moves = m
            st.placeOk = true
            st.refused = false
            if (m.isNotEmpty()) Haptics.play(root, Haptics.REORDER, Haptics.Fx.LIGHT)
            g.preview(m)
            g.showOutline(st.cellX, st.cellY, w, h, false)
        }
    }

    private fun onDragEvent(e: DragEvent): Boolean {
        val st = drag ?: (e.localState as? DragState) ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> onDragMove(st, e.x, e.y)
            DragEvent.ACTION_DROP -> return onDrop(st, e.x, e.y)
            DragEvent.ACTION_DRAG_ENDED -> onDragEnd(st, e.result)
            DragEvent.ACTION_DRAG_EXITED -> clearTarget(st)
        }
        return true
    }

    private fun onDragMove(st: DragState, x: Float, y: Float) {
        if (st.startX.isNaN()) {
            st.startX = x
            st.startY = y
            st.lastX = x
            st.lastY = y
        }
        if (!st.moved && hypot(x - st.startX, y - st.startY) > ViewConfiguration.get(this).scaledTouchSlop * 1.5f) {
            st.moved = true
            if (overlay != null) closeOverlay()
        }
        if (abs(x - st.lastX) + abs(y - st.lastY) > 4 * d) {
            st.dirX = x - st.lastX
            st.dirY = y - st.lastY
            st.lastX = x
            st.lastY = y
        }

        folderPanel?.let { p ->
            if (p.contains(x, y)) {
                val k = st.appKey
                if (st.source == Src.FOLDER && st.folder === p.folder && k != null) p.dragOver(x, y, k)
                return
            }
            if (!st.moved) return
            closeFolder()
        }
        // Lifting an app in the drawer and moving it drops you on the home screen to place it.
        if (drawer.isOpen) {
            if (st.source == Src.DRAWER && st.moved) {
                drawer.close()
                Haptics.play(root, Haptics.PLACE, Haptics.Fx.TICK)
            } else {
                return
            }
        }
        if (!st.moved) return

        if (dropBar.visibility != View.VISIBLE) showDropBar(st)
        val hover = dropTargetAt(x, y)
        if (hover != st.dropHover) {
            st.dropHover = hover
            dropRemove.isActivated = hover == 1
            dropUninstall.isActivated = hover == 2
            styleDropTargets()
            if (hover != 0) Haptics.play(root, Haptics.REMOVE, Haptics.Fx.TICK)
        }
        if (hover != 0) {
            setEdge(st, 0)
            clearTarget(st)
            return
        }

        // Cells are picked from the middle of the dragged item as drawn, not from the finger: a widget
        // grabbed near its top edge lands where it looks like it will.
        val vx = x - st.touchX + st.shadowW / 2f
        val vy = y - st.touchY + st.shadowH / 2f
        val dockR = rectInRoot(dock)
        val wsR = rectInRoot(workspace)
        when {
            dockR.contains(vx, vy) -> {
                setEdge(st, 0)
                hover(st, dock, -1, vx - dockR.left, vy - dockR.top)
            }
            wsR.contains(x, y) || wsR.contains(vx, vy) -> {
                val edge = 28 * d
                setEdge(st, if (x < wsR.left + edge) -1 else if (x > wsR.right - edge) 1 else 0)
                val g = workspace.pages.getOrNull(workspace.current) ?: return
                hover(st, g, workspace.current, vx - wsR.left, (vy - wsR.top).coerceIn(0f, wsR.height() - 1))
            }
            else -> {
                setEdge(st, 0)
                clearTarget(st)
            }
        }
    }

    private fun setEdge(st: DragState, dir: Int) {
        if (dir == st.edgeDir) return
        st.edgeDir = dir
        handler.removeCallbacks(flipRunnable)
        if (dir != 0) handler.postDelayed(flipRunnable, 550)
    }

    private fun hover(st: DragState, grid: CellGrid, page: Int, lx: Float, ly: Float) {
        if (grid !== st.grid || page != st.page) {
            clearTarget(st)
            st.grid = grid
            st.page = page
        }
        val dockMode = page == -1
        val w = if (dockMode) 1 else st.w
        val h = if (dockMode) 1 else st.h
        if (dockMode && st.isWidget) {
            val c = grid.spanAt(lx, ly, 1, 1)
            st.refused = true
            st.placeOk = false
            grid.showOutline(c.x, c.y, 1, 1, true)
            return
        }

        // Hold over the middle of an app or folder to make or add to a folder.
        val under = grid.cellUnder(lx, ly)
        val items = itemsOf(page)
        val occupant = if (st.moves.isEmpty()) {
            items.firstOrNull { it !== st.item && under.x in it.x until it.x + it.w && under.y in it.y until it.y + it.h }
        } else {
            null
        }
        // An app held over another copy of itself makes no folder: that would be a folder of one app twice.
        val folderable = st.isApp && w == 1 && h == 1 &&
            ((occupant is AppItem && occupant.key != st.key) || occupant is FolderItem) &&
            grid.inInnerHalf(lx, ly, occupant.x, occupant.y)
        if (folderable) {
            if (st.folderCandidate !== occupant) {
                handler.removeCallbacks(reorderRunnable)
                handler.removeCallbacks(folderRunnable)
                st.folderTarget?.let { (grid.viewFor(it) as? IconView)?.hot = false }
                st.folderTarget = null
                st.folderCandidate = occupant
                st.cellX = -1
                grid.clearOutline()
                handler.postDelayed(folderRunnable, 500)
            }
            return
        }
        if (st.folderCandidate != null) {
            handler.removeCallbacks(folderRunnable)
            st.folderCandidate?.let { (grid.viewFor(it) as? IconView)?.hot = false }
            st.folderTarget?.let { (grid.viewFor(it) as? IconView)?.hot = false }
            st.folderCandidate = null
            st.folderTarget = null
        }

        val cell = grid.spanAt(lx, ly, w, h)
        if (cell.x == st.cellX && cell.y == st.cellY) return
        st.cellX = cell.x
        st.cellY = cell.y
        handler.removeCallbacks(reorderRunnable)
        if (st.moves.isNotEmpty()) {
            st.moves = emptyMap()
            grid.preview(emptyMap())
        }
        val blocked = items.any {
            it !== st.item && it.x < cell.x + w && cell.x < it.x + it.w && it.y < cell.y + h && cell.y < it.y + it.h
        }
        st.refused = false
        if (!blocked) {
            st.placeOk = true
            grid.showOutline(cell.x, cell.y, w, h, false)
        } else {
            st.placeOk = false
            grid.showOutline(cell.x, cell.y, w, h, false)
            handler.postDelayed(reorderRunnable, 150)
        }
    }

    private fun onDrop(st: DragState, x: Float, y: Float): Boolean {
        handler.removeCallbacks(flipRunnable)
        handler.removeCallbacks(reorderRunnable)
        if (!st.moved) {
            st.pendingMenu = st.source != Src.SHORTCUT && st.source != Src.PICKER
            return false
        }
        when (st.dropHover) {
            1 -> {
                dropRemoveItem(st)
                Haptics.play(root, Haptics.REMOVE, Haptics.Fx.CONFIRM)
                return true
            }
            2 -> {
                st.key?.let { k -> Apps.apps[k]?.let { uninstall(it) } }
                Haptics.play(root, Haptics.REMOVE, Haptics.Fx.CONFIRM)
                return false
            }
        }
        folderPanel?.let { p ->
            if (p.contains(x, y) && st.source == Src.FOLDER && st.folder === p.folder) {
                p.commitOrder()
                return true
            }
        }
        st.folderTarget?.let { return dropIntoFolder(st, it) }
        if (st.grid == null) return false
        if (!st.placeOk && !st.refused) solveNow(st)
        if (!st.placeOk || st.refused) {
            Haptics.play(root, Haptics.REORDER, Haptics.Fx.REJECT)
            return false
        }
        val ok = commitPlace(st)
        if (ok) Haptics.play(root, Haptics.PLACE, Haptics.Fx.CONFIRM)
        return ok
    }

    private fun dropIntoFolder(st: DragState, target: Item): Boolean {
        val key = st.key ?: return false
        val l = Store.layout
        when (st.source) {
            Src.PAGE, Src.DOCK -> st.item?.let { l.remove(it) }
            Src.FOLDER -> st.folder?.apps?.remove(key)
            else -> {}
        }
        when (target) {
            is FolderItem -> if (key !in target.apps) target.apps.add(key)
            is AppItem -> {
                val list = l.containerOf(target) ?: return false
                val f = FolderItem(l.newId(), getString(R.string.folder_default), mutableListOf(target.key, key))
                f.x = target.x
                f.y = target.y
                list[list.indexOfFirst { it === target }] = f
            }
            else -> return false
        }
        l.fixFolders()
        Store.save()
        Haptics.play(root, Haptics.FOLDER, Haptics.Fx.CONFIRM)
        return true
    }

    private fun commitPlace(st: DragState): Boolean {
        val l = Store.layout
        val page = st.page
        if (page != -1 && page !in l.pages.indices) return false
        val target = if (page == -1) l.dock else l.pages[page]
        for ((it, p) in st.moves) {
            it.x = p.x
            it.y = p.y
        }
        val item: Item = when (st.source) {
            Src.PAGE, Src.DOCK -> st.item!!.also { l.remove(it) }
            Src.FOLDER -> {
                val k = st.appKey ?: return false
                st.folder?.apps?.remove(k)
                AppItem(l.newId(), k)
            }
            Src.DRAWER -> AppItem(l.newId(), st.appKey ?: return false)
            Src.SHORTCUT -> {
                val si = st.shortcut ?: return false
                if (!Shortcuts.pin(si)) {
                    Toast.makeText(this, R.string.cant_pin_shortcut, Toast.LENGTH_SHORT).show()
                    return false
                }
                ShortcutItem(l.newId(), si.`package`, si.id, Apps.serial(si.userHandle), Shortcuts.label(si))
            }
            Src.PICKER -> {
                Store.save()
                val info = st.widgetInfo ?: return false
                handler.post { widgets.add(info, page, st.cellX, st.cellY, st.w, st.h) }
                return true
            }
        }
        item.x = st.cellX
        item.y = if (page == -1) 0 else st.cellY
        if (page == -1) {
            item.w = 1
            item.h = 1
        }
        target.add(item)
        l.fixFolders()
        Store.save()
        return true
    }

    private fun onDragEnd(st: DragState, result: Boolean) {
        handler.removeCallbacks(flipRunnable)
        hideDropBar()
        st.dropHover = 0
        clearTarget(st)
        drag = null
        if (!result) st.view?.visibility = View.VISIBLE
        if (st.pendingMenu) {
            // Let go without moving: the menu opened at the long-press is still up. Only put it back
            // if something closed it in the meantime.
            if (overlay == null) itemMenu(st)
            if (rebindPending) rebind()
            return
        }
        val l = Store.layout
        val cur = l.pruneEmptyPages(workspace.current)
        if (cur != workspace.current || l.pages.size != workspace.pages.size) {
            workspace.current = cur
            Store.save()
        }
        rebind()
        workspace.snapTo(workspace.current, animate = false)
    }

    // ---- Coming home ----

    private fun enterTargets(): List<Pair<View, Long>> {
        val out = ArrayList<Pair<View, Long>>()
        for (i in 0 until dock.childCount) out.add(dock.getChildAt(i) to 0L)
        val page = workspace.pages.getOrNull(workspace.current) ?: return out
        val rows = Store.layout.settings.rows
        for (i in 0 until page.childCount) {
            val v = page.getChildAt(i)
            val lp = v.layoutParams as? CellGrid.LP ?: continue
            // Bottom rows first, rising toward the top, right behind the dock.
            val fromBottom = (rows - (lp.y + lp.h)).coerceAtLeast(0)
            out.add(v to 30L + fromBottom * 22L)
        }
        return out
    }

    /**
     * Icons, widgets and the dock rise a little, grow from 88% and fade in, bottom rows first, when
     * coming back from an app. Skipped when Android's "Remove animations" is on.
     *
     * The system plays its own transition as the app closes, and the app's window covers most of
     * the home screen for the first part of it. Starting right away spends the motion under that
     * window, so the icons wait, hidden, until the transition reports done or [ENTER_WAIT_MS] has
     * passed since the first frame, whichever comes first.
     */
    private fun playEnter() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (folderPanel != null || overlay != null || drawer.isOpen) return
        val rise = 24 * d
        val targets = enterTargets().filter { it.first.visibility == View.VISIBLE }
        if (targets.isEmpty()) return
        for ((v, _) in targets) {
            v.animate().cancel()
            v.alpha = 0f
            v.scaleX = 0.88f
            v.scaleY = 0.88f
            v.translationY = rise
        }
        val go = object : Runnable {
            override fun run() {
                if (enterGo !== this) return
                enterGo = null
                for ((v, delay) in targets) {
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setStartDelay(delay).setDuration(300).setInterpolator(DecelerateInterpolator(1.8f))
                        .start()
                }
            }
        }
        enterGo = go
        // The fallback timer counts from the first frame drawn, which can come a moment after
        // onResume when an app was on top.
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                if (enterGo === go) root.postDelayed(go, ENTER_WAIT_MS)
                return true
            }
        })
    }

    /** Puts everything back at rest, so a half-played entry never leaves icons faded or shifted. */
    private fun settleEnter() {
        enterGo?.let { root.removeCallbacks(it) }
        enterGo = null
        for ((v, _) in enterTargets()) {
            v.animate().cancel()
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationY = 0f
        }
    }

    private fun openDrawerFromButton() {
        closeOverlay()
        closeFolder()
        Haptics.play(root, Haptics.DRAWER, Haptics.Fx.THRESHOLD)
        drawer.open()
    }

    // ---- Drag-to-remove bar ----

    private fun buildDropBar() {
        dropBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), 0x00000000),
            )
        }
        fun target(label: Int) = TextView(this).apply {
            text = getString(label)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(4 * d, 0f, d, 0xAA000000.toInt())
            val p = (14 * d).roundToInt()
            setPadding(p * 2, p / 2, p * 2, p / 2)
        }
        dropRemove = target(R.string.remove)
        dropUninstall = target(R.string.uninstall)
        val lp = { LinearLayout.LayoutParams(0, (44 * d).roundToInt(), 1f).apply { setMargins((8 * d).roundToInt(), 0, (8 * d).roundToInt(), 0) } }
        dropBar.addView(dropRemove, lp())
        dropBar.addView(dropUninstall, lp())
        root.addView(dropBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (TOP_BAND_DP * d).roundToInt(), Gravity.TOP))
    }

    /** Remove for anything on the home screen, dock or in a folder; Uninstall for apps that can be. */
    private fun showDropBar(st: DragState) {
        if (st.source == Src.SHORTCUT || st.source == Src.PICKER) return
        val canRemove = (st.source == Src.PAGE || st.source == Src.DOCK || st.source == Src.FOLDER) && st.item !is DrawerButtonItem
        val app = st.key?.let { Apps.apps[it] }
        val canUninstall = app != null && !app.isSystem && st.item !is FolderItem
        if (!canRemove && !canUninstall) return
        dropRemove.visibility = if (canRemove) View.VISIBLE else View.GONE
        dropUninstall.visibility = if (canUninstall) View.VISIBLE else View.GONE
        dropRemove.isActivated = false
        dropUninstall.isActivated = false
        styleDropTargets()
        dropBar.alpha = 0f
        dropBar.visibility = View.VISIBLE
        dropBar.animate().alpha(1f).setDuration(120).start()
    }

    private fun hideDropBar() {
        dropBar.animate().cancel()
        dropBar.visibility = View.GONE
    }

    private fun styleDropTargets() {
        for (t in listOf(dropRemove, dropUninstall)) {
            t.background = if (t.isActivated) pal.card(this, 0xCCD32F2F.toInt(), 22f) else null
        }
    }

    private fun dropTargetAt(x: Float, y: Float): Int {
        if (dropBar.visibility != View.VISIBLE) return 0
        val bar = rectInRoot(dropBar)
        if (!bar.contains(x, y)) return 0
        val both = dropRemove.visibility == View.VISIBLE && dropUninstall.visibility == View.VISIBLE
        return when {
            both -> if (x < bar.centerX()) 1 else 2
            dropRemove.visibility == View.VISIBLE -> 1
            else -> 2
        }
    }

    private fun dropRemoveItem(st: DragState) {
        when (st.source) {
            Src.PAGE, Src.DOCK -> st.item?.let { removeItem(it) }
            Src.FOLDER -> {
                val k = st.appKey ?: return
                st.folder?.apps?.remove(k)
                Store.layout.fixFolders()
                Store.save()
            }
            else -> {}
        }
    }

    private fun uninstall(app: AppInfo) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.key.pkg, null)).putExtra(Intent.EXTRA_USER, app.user))
        } catch (_: Exception) {
        }
    }

    // ---- Pages ----

    /** Pages side by side, swiped horizontally. */
    inner class Workspace : ViewGroup(this@HomeActivity) {
        val pages = ArrayList<CellGrid>()
        var current = 0
        var onPageChanged: ((Int) -> Unit)? = null
        private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
        private var dragStart = 0
        private val fling = 500 * resources.displayMetrics.density

        init {
            clipChildren = false
        }

        fun setPageCount(n: Int, cols: Int, rows: Int) {
            while (pages.size < n) {
                val g = CellGrid(context, cols, rows)
                val p = (8 * resources.displayMetrics.density).roundToInt()
                // An empty band on top, like Pixel's At a Glance row: the Remove bar lives there while dragging.
                g.setPadding(p, (TOP_BAND_DP * resources.displayMetrics.density).roundToInt(), p, 0)
                pages.add(g)
                addView(g)
            }
            while (pages.size > n) removeView(pages.removeAt(pages.size - 1))
            current = current.coerceIn(0, n - 1)
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            for (p in pages) p.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            for ((i, p) in pages.withIndex()) p.layout(i * w, 0, (i + 1) * w, b - t)
            if (scroller.isFinished) scrollTo(current * w, 0)
        }

        fun beginDrag() {
            scroller.forceFinished(true)
            dragStart = scrollX
        }

        fun dragTo(dx: Float) {
            val max = ((pages.size - 1) * width).toFloat()
            var sx = dragStart - dx
            if (sx < 0) sx /= 3f
            if (sx > max) sx = max + (sx - max) / 3f
            scrollTo(sx.roundToInt(), 0)
        }

        fun release(vx: Float) {
            val w = width.coerceAtLeast(1)
            var target = ((scrollX + w / 2f) / w).toInt()
            if (abs(vx) > fling) target = if (vx < 0) floor(scrollX / w.toFloat()).toInt() + 1 else floor(scrollX / w.toFloat()).toInt()
            snapTo(target.coerceIn(0, pages.size - 1))
        }

        fun snapTo(i: Int, animate: Boolean = true) {
            val old = current
            current = i.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            val dx = current * width - scrollX
            if (animate && width > 0) {
                scroller.startScroll(scrollX, 0, dx, 0, 280)
                postInvalidateOnAnimation()
            } else {
                scroller.forceFinished(true)
                scrollTo(current * width, 0)
            }
            if (old != current) onPageChanged?.invoke(current)
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, 0)
                postInvalidateOnAnimation()
            }
        }
    }

    // ---- Gestures ----

    /** Root view: swipe left/right for pages, up for the drawer, down for the shade, long-press empty space. */
    inner class HomeRoot : FrameLayout(this@HomeActivity) {
        private var downX = 0f
        private var downY = 0f
        private var mode = 0 // 0 undecided, 1 pages, 2 drawer, 3 shade, -1 ignore
        private var vt: VelocityTracker? = null
        private var downOnWidget = false
        private var drawerTicked = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable {
            mode = -1
            emptySpaceMenu(downX, downY)
        }

        /**
         * Something else owns touches, unless this gesture is the one opening the drawer or flipping pages.
         * The activity's fields are named explicitly: inside a View, a bare "overlay" is View.getOverlay(),
         * which is never null, and that silently blocked every gesture on the home screen.
         */
        private fun blocked() = mode <= 0 && (
            this@HomeActivity.drag != null || this@HomeActivity.overlay != null ||
                this@HomeActivity.folderPanel != null || this@HomeActivity.drawer.isShown
            )

        private fun itemViewAt(x: Float, y: Float): View? {
            val g = workspace.pages.getOrNull(workspace.current)
            if (g != null) {
                val r = rectInRoot(g)
                if (r.contains(x, y)) g.childAt(x - r.left, y - r.top)?.let { return it }
            }
            val r = rectInRoot(dock)
            if (r.contains(x, y)) dock.childAt(x - r.left, y - r.top)?.let { return it }
            if (banner.visibility == View.VISIBLE && rectInRoot(banner).contains(x, y)) return banner
            return null
        }

        private fun track(ev: MotionEvent): Boolean {
            if (blocked()) {
                removeCallbacks(longPress)
                return false
            }
            if (vt == null) vt = VelocityTracker.obtain()
            vt?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    mode = 0
                    drawerTicked = false
                    vt?.clear()
                    vt?.addMovement(ev)
                    val hit = itemViewAt(ev.x, ev.y)
                    downOnWidget = hit is WidgetFrame
                    // DOWN arrives here twice (intercept, then onTouchEvent): post the long-press once.
                    removeCallbacks(longPress)
                    if (hit == null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (mode == 0 && hypot(dx, dy) > slop) {
                        removeCallbacks(longPress)
                        mode = when {
                            abs(dx) > abs(dy) -> 1
                            downOnWidget -> -1
                            dy < 0 && !Store.layout.settings.drawerButton -> 2
                            dy < 0 -> -1
                            Shade.available && Store.layout.settings.swipeDown -> 3
                            else -> -1
                        }
                        if (mode == 1) workspace.beginDrag()
                        if (mode == 2) drawer.beginOpen()
                    }
                    when (mode) {
                        1 -> workspace.dragTo(dx)
                        2 -> {
                            drawer.trackOpen(-dy)
                            if (!drawerTicked && -dy > height * 0.2f) {
                                drawerTicked = true
                                Haptics.play(this, Haptics.DRAWER, Haptics.Fx.THRESHOLD)
                            }
                        }
                        3 -> if (dy > 48 * d) {
                            mode = -1
                            Shade.expand(this@HomeActivity)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    val vy = vt?.yVelocity ?: 0f
                    when (mode) {
                        1 -> workspace.release(vx)
                        2 -> {
                            // A busy frame can swallow the last moves; the lift-off point is the truth.
                            drawer.trackOpen(downY - ev.y)
                            drawer.releaseOpen(vy)
                        }
                        3 -> if (vy > 600 * d) Shade.expand(this@HomeActivity)
                    }
                    val m = mode
                    mode = 0
                    return m > 0
                }
            }
            return mode > 0
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev)

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            vt?.recycle()
            vt = null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (blocked()) return super.onTouchEvent(ev)
            track(ev)
            return true
        }
    }
}
