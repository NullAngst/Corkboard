/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.roundToInt

const val REQ_ROLE = 21

/** Height of the empty band above the grid where the Remove and Uninstall bar appears. */
const val TOP_BAND_DP = 48f

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
 * The home screen: its views, lifecycle, launching, and the folder and overlay layers.
 * Each feature lives in its own file and gets this activity to work with:
 * gestures in HomeRoot.kt, drag and drop in DragDrop.kt, the Remove/Uninstall bar in DropBar.kt,
 * long-press menus in Menus.kt, the Undo bar in Undo.kt, the coming-home animation in EnterAnimation.kt.
 */
class HomeActivity : Activity() {
    lateinit var pal: Palette
    lateinit var root: HomeRoot
        private set
    lateinit var workspace: Workspace
        private set
    lateinit var dock: CellGrid
        private set
    lateinit var dots: PageDots
        private set
    lateinit var banner: LinearLayout
        private set
    lateinit var drawer: AppDrawer
        private set
    lateinit var widgets: Widgets
        private set
    private lateinit var homeCol: LinearLayout

    var folderPanel: FolderPanel? = null
        private set
    var overlay: View? = null
        private set

    /** System bar and cutout insets, for anything laid over the whole screen. */
    var bars = Rect()
        private set

    val handler = Handler(Looper.getMainLooper())
    val d get() = resources.displayMetrics.density

    val dnd = DragDrop(this)
    val drops = DropBar(this)
    val menus = Menus(this)
    val undo = Undo(this)
    private val enter = EnterAnimation(this)

    private var boundRevision = -1
    private var backCallback: Any? = null
    private val appsListener: () -> Unit = { if (dnd.active) dnd.rebindPending = true else rebind() }
    private val shortcutsListener: () -> Unit = { if (!dnd.active) rebind() }
    private val dotsListener: () -> Unit = { Dots.refresh(root) }

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        Store.init(this)
        Apps.init(this)
        Shade.init(this)
        Haptics.init(this)
        Dots.sync(this)
        pal = Themes.palette(this)
        applyRotation()
        edgeToEdge()
        widgets = Widgets(this)
        buildViews()
        setContentView(root)
        Apps.listener = appsListener
        Apps.shortcutsListener = shortcutsListener
        Dots.listener = dotsListener
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
        // Leaving the home screen makes a removal final.
        undo.commit()
        AutoBackup.flush()
        enter.onStop()
        try {
            widgets.host.stopListening()
        } catch (_: Exception) {
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        enter.onSystemTransitionDone()
    }

    override fun onResume() {
        super.onResume()
        // A drawer left half-closed by an interrupted animation must never sit invisible over the home screen.
        if (!drawer.isOpen && drawer.visibility == View.VISIBLE) drawer.close(animate = false)
        applyRotation()
        if (Store.revision != boundRevision) rebind()
        updateBanner()
        enter.onResume()
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
        undo.commit()
        // Only clear what this instance set: a recreated home screen may already have put its own.
        if (Apps.listener === appsListener) Apps.listener = null
        if (Apps.shortcutsListener === shortcutsListener) Apps.shortcutsListener = null
        if (Dots.listener === dotsListener) Dots.listener = null
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
        root = HomeRoot(this)
        homeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        workspace = Workspace(this)
        dots = PageDots(this)
        dock = CellGrid(this, Store.layout.settings.columns, 1)
        workspace.onPageChanged = { i ->
            dots.current = i
            if (!dnd.active) handler.postDelayed({ prunePages() }, 400)
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
        drops.build()
        undo.build()
        root.setOnApplyWindowInsetsListener { _, wi ->
            applyInsets(wi)
            wi
        }
        root.setOnDragListener { _, e -> dnd.onDragEvent(e) }
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
        drops.setInsets(bars)
        undo.setInsets(bars)
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
        val v = when (item) {
            is AppItem -> {
                val app = Apps.apps[item.key]
                iconView(IconRenderer.app(this, item.key), app?.label ?: "", labels, true).apply {
                    greyed = Apps.isQuiet(item.key.user)
                    setOnClickListener { launchApp(this, item.key) }
                    Dots.attach(this, listOf(Dots.keyOf(item.key)), pal)
                }
            }
            is FolderItem -> iconView(IconRenderer.folder(this, item), item.name, labels, true).apply {
                setOnClickListener { openFolder(item) }
                Dots.attach(this, item.apps.map { Dots.keyOf(it) }, pal)
            }
            is ShortcutItem -> {
                val label = Store.layout.icons["item:${item.id}"]?.label ?: item.label
                iconView(IconRenderer.shortcut(this, item), label, labels, true).apply {
                    setOnClickListener { Shortcuts.launch(this@HomeActivity, item.pkg, item.shortcutId, item.user, bounds(this), clipReveal(this)) }
                    Dots.attach(this, listOf(Dots.keyOf(item.pkg, item.user)), pal)
                }
            }
            is DrawerButtonItem -> iconView(IconRenderer.drawerButton(this), getString(R.string.s_apps), labels, true).apply {
                setOnClickListener { openDrawerFromButton() }
            }
            is WidgetItem -> return widgets.view(item).apply {
                onLongPress = { f -> dnd.itemLongPress(f, item, src) }
            }
        }
        // Only the icon and its label take touches, so a long-press between icons opens the home menu.
        v.tightTouch = true
        v.setOnLongClickListener { dnd.itemLongPress(v, item, src) }
        return v
    }

    fun rebind() {
        if (dnd.active) {
            dnd.rebindPending = true
            return
        }
        dnd.rebindPending = false
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
        if (dnd.active) return
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

    /** A view's bounds in [root] coordinates. */
    fun rectInRoot(v: View): RectF {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val x = (a[0] - b[0]).toFloat()
        val y = (a[1] - b[1]).toFloat()
        return RectF(x, y, x + v.width, y + v.height)
    }

    // ---- Changing the layout ----

    /** Takes an item off the home screen or dock, with Undo. What can't be taken back waits for the bar to go. */
    fun removeItem(item: Item) {
        undo.change(R.string.undo_removed, after = {
            when (item) {
                is WidgetItem -> widgets.delete(item)
                is ShortcutItem -> Shortcuts.unpinIfUnused(item.pkg, item.shortcutId, item.user)
                else -> {}
            }
        }) {
            Store.layout.remove(item)
            Store.layout.icons.remove("item:${item.id}")
        }
    }

    /** Takes an app out of a folder, with Undo. A folder left with one app turns back into that app. */
    fun removeFromFolder(folder: FolderItem, key: AppKey) {
        undo.change(R.string.undo_removed) {
            folder.apps.remove(key)
            Store.layout.fixFolders()
        }
        closeFolder()
    }

    fun uninstall(app: AppInfo) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.key.pkg, null)).putExtra(Intent.EXTRA_USER, app.user))
        } catch (_: Exception) {
        }
    }

    // ---- Launching ----

    fun bounds(v: View): Rect {
        val a = IntArray(2)
        v.getLocationOnScreen(a)
        return Rect(a[0], a[1], a[0] + v.width, a[1] + v.height)
    }

    fun clipReveal(v: View): Bundle? = try {
        ActivityOptions.makeClipRevealAnimation(v, 0, 0, v.width, v.height).toBundle()
    } catch (_: Exception) {
        null
    }

    fun launchApp(v: View, key: AppKey) {
        if (Apps.isQuiet(key.user)) {
            if (!PrivateSpace.isPrivate(key.user)) askUnpause()
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

    private fun openDrawerFromButton() {
        closeOverlay()
        closeFolder()
        Haptics.play(root, Haptics.DRAWER, Haptics.Fx.THRESHOLD)
        drawer.open()
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
        // Below the Undo bar, so Undo can still be tapped while a menu is up.
        root.addView(v, root.indexOfChild(undo.view).coerceAtLeast(0), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        root.addView(p, root.indexOfChild(undo.view).coerceAtLeast(0), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
}
