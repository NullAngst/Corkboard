/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.pm.ShortcutInfo
import android.graphics.Point
import android.view.DragEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Where a drag started. */
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
    var moves: Map<Item, Cell> = emptyMap()
    var placeOk = false
    var refused = false
    var folderCandidate: Item? = null
    var folderTarget: Item? = null
    var edgeDir = 0
    var pendingMenu = false
    var dropHover = DropBar.Target.NONE

    /** Where the finger holds the shadow, and the shadow's size: the item is placed where it looks. */
    var touchX = 0
    var touchY = 0
    var shadowW = 0
    var shadowH = 0

    val isApp get() = item is AppItem || ((source == Src.DRAWER || source == Src.FOLDER) && appKey != null)
    val isWidget get() = item is WidgetItem || source == Src.PICKER
    val key: AppKey? get() = appKey ?: (item as? AppItem)?.key
}

/** Shadow whose touch point is where the finger went down, not the middle of the view. */
private class TouchShadow(v: View, private val tx: Int, private val ty: Int) : View.DragShadowBuilder(v) {
    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint)
        outShadowTouchPoint.set(tx.coerceIn(0, outShadowSize.x), ty.coerceIn(0, outShadowSize.y))
    }
}

/**
 * Long-press to lift, and drag and drop: moving items, reordering, making folders, flipping pages
 * at the edges, and dropping on Remove or Uninstall. Uses Android's own drag and drop.
 */
class DragDrop(private val act: HomeActivity) {
    private var drag: DragState? = null

    /** Something changed during the drag; rebind once it ends. */
    var rebindPending = false

    val active get() = drag != null

    private val d get() = act.d
    private val handler get() = act.handler

    // ---- Starting ----

    private fun touchPoint(v: View): Pair<Int, Int> = when (v) {
        is IconView -> v.downX.roundToInt() to v.downY.roundToInt()
        is WidgetFrame -> v.downX.roundToInt() to v.downY.roundToInt()
        else -> v.width / 2 to v.height / 2
    }

    private fun start(st: DragState, v: View): Boolean {
        act.undo.commit()
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
     * instead and the menu closes and the drag carries on (onMove).
     */
    private fun liftWithMenu(st: DragState, v: View) {
        if (Store.layout.settings.locked || !start(st, v)) Haptics.play(v, Haptics.LIFT, Haptics.Fx.LIFT)
        act.menus.forDrag(st)
    }

    fun itemLongPress(v: View, item: Item, src: Src): Boolean {
        liftWithMenu(DragState(src, item, v, if (src == Src.DOCK) 1 else item.w, if (src == Src.DOCK) 1 else item.h), v)
        return true
    }

    fun startDrawerDrag(v: View, app: AppInfo) {
        val st = DragState(Src.DRAWER, null, v, 1, 1).apply { appKey = app.key }
        // Private space apps stay in the drawer: they never go on the home screen, so they never lift.
        if (PrivateSpace.isPrivate(app.key.user)) {
            Haptics.play(v, Haptics.LIFT, Haptics.Fx.LIFT)
            act.menus.forDrag(st)
            return
        }
        liftWithMenu(st, v)
    }

    fun startFolderDrag(v: View, folder: FolderItem, key: AppKey) {
        liftWithMenu(DragState(Src.FOLDER, null, v, 1, 1).apply {
            appKey = key
            this.folder = folder
        }, v)
    }

    fun startShortcutDrag(v: View, si: ShortcutInfo) {
        start(DragState(Src.SHORTCUT, null, v, 1, 1).apply { shortcut = si }, v)
    }

    fun startNewWidgetDrag(v: View, info: AppWidgetProviderInfo, w: Int, h: Int) {
        start(DragState(Src.PICKER, null, v, w, h).apply { widgetInfo = info }, v)
    }

    // ---- Timers: folder hover, reorder, page flip at the edges ----

    private val folderRunnable = Runnable {
        val st = drag ?: return@Runnable
        val t = st.folderCandidate ?: return@Runnable
        st.folderTarget = t
        (st.grid?.viewFor(t) as? IconView)?.hot = true
        Haptics.play(act.root, Haptics.FOLDER, Haptics.Fx.TICK)
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
            val ws = act.workspace
            val target = ws.current + st.edgeDir
            if (target < 0) return
            clearTarget(st)
            if (target >= l.pages.size) {
                val last = l.pages.last()
                if (last.none { it !== st.item }) return
                l.pages.add(mutableListOf())
                ws.setPageCount(l.pages.size, l.settings.columns, l.settings.rows)
                act.dots.count = l.pages.size
            }
            ws.snapTo(target)
            Haptics.play(act.root, Haptics.PAGE, Haptics.Fx.TICK)
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
            if (m.isNotEmpty()) Haptics.play(act.root, Haptics.REORDER, Haptics.Fx.LIGHT)
            g.preview(m)
            g.showOutline(st.cellX, st.cellY, w, h, false)
        }
    }

    // ---- Events ----

    fun onDragEvent(e: DragEvent): Boolean {
        val st = drag ?: (e.localState as? DragState) ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> onMove(st, e.x, e.y)
            DragEvent.ACTION_DROP -> return onDrop(st, e.x, e.y)
            DragEvent.ACTION_DRAG_ENDED -> onEnd(st, e.result)
            DragEvent.ACTION_DRAG_EXITED -> clearTarget(st)
        }
        return true
    }

    private fun onMove(st: DragState, x: Float, y: Float) {
        if (st.startX.isNaN()) {
            st.startX = x
            st.startY = y
            st.lastX = x
            st.lastY = y
        }
        if (!st.moved && hypot(x - st.startX, y - st.startY) > ViewConfiguration.get(act).scaledTouchSlop * 1.5f) {
            st.moved = true
            if (act.overlay != null) act.closeOverlay()
        }
        if (abs(x - st.lastX) + abs(y - st.lastY) > 4 * d) {
            st.dirX = x - st.lastX
            st.dirY = y - st.lastY
            st.lastX = x
            st.lastY = y
        }

        act.folderPanel?.let { p ->
            if (p.contains(x, y)) {
                val k = st.appKey
                if (st.source == Src.FOLDER && st.folder === p.folder && k != null) p.dragOver(x, y, k)
                return
            }
            if (!st.moved) return
            act.closeFolder()
        }
        // Lifting an app in the drawer and moving it drops you on the home screen to place it.
        if (act.drawer.isOpen) {
            if (st.source == Src.DRAWER && st.moved) {
                act.drawer.close()
                Haptics.play(act.root, Haptics.PLACE, Haptics.Fx.TICK)
            } else {
                return
            }
        }
        if (!st.moved) return

        val drops = act.drops
        if (!drops.shown) drops.show(st)
        val hover = drops.targetAt(x, y)
        if (hover != st.dropHover) {
            st.dropHover = hover
            drops.highlight(hover)
            if (hover != DropBar.Target.NONE) Haptics.play(act.root, Haptics.REMOVE, Haptics.Fx.TICK)
        }
        if (hover != DropBar.Target.NONE) {
            setEdge(st, 0)
            clearTarget(st)
            return
        }

        // Cells are picked from the middle of the dragged item as drawn, not from the finger: a widget
        // grabbed near its top edge lands where it looks like it will.
        val vx = x - st.touchX + st.shadowW / 2f
        val vy = y - st.touchY + st.shadowH / 2f
        val dockR = act.rectInRoot(act.dock)
        val wsR = act.rectInRoot(act.workspace)
        when {
            dockR.contains(vx, vy) -> {
                setEdge(st, 0)
                hover(st, act.dock, -1, vx - dockR.left, vy - dockR.top)
            }
            wsR.contains(x, y) || wsR.contains(vx, vy) -> {
                val edge = 28 * d
                setEdge(st, if (x < wsR.left + edge) -1 else if (x > wsR.right - edge) 1 else 0)
                val g = act.workspace.pages.getOrNull(act.workspace.current) ?: return
                hover(st, g, act.workspace.current, vx - wsR.left, (vy - wsR.top).coerceIn(0f, wsR.height() - 1))
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
        st.placeOk = !blocked
        grid.showOutline(cell.x, cell.y, w, h, false)
        if (blocked) handler.postDelayed(reorderRunnable, 150)
    }

    // ---- Dropping ----

    private fun onDrop(st: DragState, x: Float, y: Float): Boolean {
        handler.removeCallbacks(flipRunnable)
        handler.removeCallbacks(reorderRunnable)
        if (!st.moved) {
            st.pendingMenu = st.source != Src.SHORTCUT && st.source != Src.PICKER
            return false
        }
        when (st.dropHover) {
            DropBar.Target.REMOVE -> {
                dropRemove(st)
                Haptics.play(act.root, Haptics.REMOVE, Haptics.Fx.CONFIRM)
                return true
            }
            DropBar.Target.UNINSTALL -> {
                st.key?.let { k -> Apps.apps[k]?.let { act.uninstall(it) } }
                Haptics.play(act.root, Haptics.REMOVE, Haptics.Fx.CONFIRM)
                return false
            }
            DropBar.Target.NONE -> {}
        }
        act.folderPanel?.let { p ->
            if (p.contains(x, y) && st.source == Src.FOLDER && st.folder === p.folder) {
                p.commitOrder()
                return true
            }
        }
        st.folderTarget?.let { return dropIntoFolder(st, it) }
        if (st.grid == null) return false
        if (!st.placeOk && !st.refused) solveNow(st)
        if (!st.placeOk || st.refused) {
            Haptics.play(act.root, Haptics.REORDER, Haptics.Fx.REJECT)
            return false
        }
        val ok = commitPlace(st)
        if (ok) Haptics.play(act.root, Haptics.PLACE, Haptics.Fx.CONFIRM)
        return ok
    }

    private fun dropRemove(st: DragState) {
        when (st.source) {
            Src.PAGE, Src.DOCK -> st.item?.let { act.removeItem(it) }
            Src.FOLDER -> {
                val f = st.folder ?: return
                val k = st.appKey ?: return
                act.removeFromFolder(f, k)
            }
            else -> {}
        }
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
                val f = FolderItem(l.newId(), act.getString(R.string.folder_default), mutableListOf(target.key, key))
                f.x = target.x
                f.y = target.y
                list[list.indexOfFirst { it === target }] = f
            }
            else -> return false
        }
        l.fixFolders()
        Store.save()
        Haptics.play(act.root, Haptics.FOLDER, Haptics.Fx.CONFIRM)
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
                    Toast.makeText(act, R.string.cant_pin_shortcut, Toast.LENGTH_SHORT).show()
                    return false
                }
                ShortcutItem(l.newId(), si.`package`, si.id, Apps.serial(si.userHandle), Shortcuts.label(si))
            }
            Src.PICKER -> {
                Store.save()
                val info = st.widgetInfo ?: return false
                handler.post { act.widgets.add(info, page, st.cellX, st.cellY, st.w, st.h) }
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

    private fun onEnd(st: DragState, result: Boolean) {
        handler.removeCallbacks(flipRunnable)
        act.drops.hide()
        st.dropHover = DropBar.Target.NONE
        clearTarget(st)
        drag = null
        if (!result) st.view?.visibility = View.VISIBLE
        if (st.pendingMenu) {
            // Let go without moving: the menu opened at the long-press is still up. Only put it back
            // if something closed it in the meantime.
            if (act.overlay == null) act.menus.forDrag(st)
            if (rebindPending) act.rebind()
            return
        }
        val l = Store.layout
        val ws = act.workspace
        val cur = l.pruneEmptyPages(ws.current)
        if (cur != ws.current || l.pages.size != ws.pages.size) {
            ws.current = cur
            Store.save()
        }
        act.rebind()
        ws.snapTo(ws.current, animate = false)
    }
}
