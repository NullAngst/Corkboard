/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.AlertDialog
import android.content.Intent
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MenuEntry(val label: String, val icon: Drawable? = null, val onLongPress: ((View) -> Unit)? = null, val action: () -> Unit)

/** The long-press menus: on an item, in the drawer, in a folder, and on empty home screen space. */
class Menus(private val act: HomeActivity) {
    private val d get() = act.d
    private fun str(id: Int) = act.getString(id)

    /** A popup menu card near [anchor] (root coordinates), in the launcher's theme colors. */
    fun show(anchor: RectF, entries: List<MenuEntry>) {
        if (entries.isEmpty()) return
        // A new menu means a new action: an earlier removal is final now.
        act.undo.commit()
        val pal = act.pal
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = pal.card(act, pal.surface, 18f)
            elevation = 8 * d
            val p = (6 * d).roundToInt()
            setPadding(0, p, 0, p)
        }
        var lastHadIcon = entries.first().icon != null
        for (e in entries) {
            if (lastHadIcon && e.icon == null) {
                card.addView(View(act).apply { setBackgroundColor(pal.textDim and 0x33FFFFFF) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).roundToInt().coerceAtLeast(1)))
            }
            lastHadIcon = e.icon != null
            card.addView(TextView(act).apply {
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
                    act.closeOverlay()
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
        val frame = FrameLayout(act).apply { setOnClickListener { act.closeOverlay() } }
        val maxW = (280 * d).roundToInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val cw = card.measuredWidth
        val m = (8 * d).roundToInt()
        val bars = act.bars
        val screenW = act.root.width
        val screenH = act.root.height
        // A long menu (four shortcuts plus every action, in landscape) scrolls instead of running off screen.
        val avail = (screenH - bars.top - bars.bottom - 2 * m).coerceAtLeast(m * 6)
        val ch = minOf(card.measuredHeight, avail)
        val holder = ScrollView(act).apply {
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
        act.showOverlay(frame, full = false, dim = false)
    }

    /** Long-press on empty home screen space, including the gaps between icons. */
    fun emptySpace(x: Float, y: Float) {
        Haptics.play(act.root, Haptics.LIFT, Haptics.Fx.LIFT)
        show(RectF(x, y, x, y), listOf(
            MenuEntry(str(R.string.wallpaper)) {
                try {
                    act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), str(R.string.wallpaper)))
                } catch (_: Exception) {
                }
            },
            MenuEntry(str(R.string.widgets)) { act.widgets.showPicker() },
            MenuEntry(str(R.string.settings)) { act.startActivity(Intent(act, SettingsActivity::class.java)) },
        ))
    }

    /** The menu for whatever a long-press lifted. */
    fun forDrag(st: DragState) {
        val v = st.view ?: return
        val anchor = act.rectInRoot(v)
        val item = st.item
        when {
            st.source == Src.DRAWER -> st.appKey?.let { app(anchor, it, null, null, true) }
            st.source == Src.FOLDER -> st.appKey?.let { app(anchor, it, null, st.folder, false) }
            item is AppItem -> app(anchor, item.key, item, null, false)
            item is ShortcutItem -> show(anchor, listOf(
                MenuEntry(str(R.string.edit_icon)) { editIcon("item:${item.id}") },
                MenuEntry(str(R.string.rename)) {
                    renameDialog(Store.layout.icons["item:${item.id}"]?.label ?: item.label) { setLabel("item:${item.id}", it) }
                },
                MenuEntry(str(R.string.remove)) { act.removeItem(item) },
            ))
            item is FolderItem -> show(anchor, listOf(
                MenuEntry(str(R.string.edit_icon)) { editIcon("item:${item.id}") },
                MenuEntry(str(R.string.rename)) {
                    renameDialog(item.name) {
                        item.name = it ?: str(R.string.folder_default)
                        Store.save()
                        act.rebind()
                    }
                },
                MenuEntry(str(R.string.remove)) { act.removeItem(item) },
            ))
            item is WidgetItem -> widget(anchor, item)
        }
    }

    private fun widget(anchor: RectF, item: WidgetItem) {
        val entries = ArrayList<MenuEntry>()
        // Only for widgets whose app says they can be set up again once placed.
        if (act.widgets.canReconfigure(item)) {
            entries.add(MenuEntry(str(R.string.widget_settings)) { act.widgets.reconfigure(item) })
        }
        entries.add(MenuEntry(str(R.string.resize)) {
            val page = Store.layout.pageOf(item)
            act.workspace.pages.getOrNull(page)?.let { act.widgets.startResize(item, it) }
        })
        entries.add(MenuEntry(str(R.string.remove)) { act.removeItem(item) })
        entries.add(MenuEntry(str(R.string.app_info)) {
            val p = item.provider
            val u = Apps.user(item.user)
            if (p != null && u != null) try {
                Apps.la.startAppDetailsActivity(p, u, null, null)
            } catch (_: Exception) {
            }
        })
        show(anchor, entries)
    }

    private fun app(anchor: RectF, key: AppKey, item: AppItem?, folder: FolderItem?, inDrawer: Boolean) {
        val app = Apps.find(key) ?: return
        val l = Store.layout
        // Private space apps get no menu entry that writes to the layout: nothing about them is saved.
        val isPrivate = PrivateSpace.isPrivate(key.user)
        val entries = ArrayList<MenuEntry>()
        for (si in Shortcuts.forApp(app)) {
            entries.add(MenuEntry(
                Shortcuts.label(si),
                Shortcuts.icon(act, si),
                onLongPress = if (l.settings.locked || isPrivate) null else { v -> act.dnd.startShortcutDrag(v, si) },
            ) {
                Shortcuts.launch(act, si.`package`, si.id, key.user, null, null)
            })
        }
        if (!isPrivate) {
            entries.add(MenuEntry(str(R.string.edit_icon)) { editIcon("app:$key") })
            entries.add(MenuEntry(str(R.string.rename)) { renameDialog(app.label) { setLabel("app:$key", it) } })
            when {
                inDrawer -> entries.add(MenuEntry(str(R.string.add_to_home)) {
                    l.placeFirstFree(AppItem(l.newId(), key))
                    Store.save()
                    act.rebind()
                    Toast.makeText(act, R.string.added_to_home, Toast.LENGTH_SHORT).show()
                })
                folder != null -> entries.add(MenuEntry(str(R.string.remove_from_folder)) { act.removeFromFolder(folder, key) })
                item != null -> entries.add(MenuEntry(str(R.string.remove_from_home)) { act.removeItem(item) })
            }
            entries.add(MenuEntry(str(R.string.hide)) {
                act.closeFolder()
                act.undo.change(R.string.undo_hidden) {
                    l.hidden.add(key.toString())
                    l.removeApp(key)
                }
            })
        }
        entries.add(MenuEntry(str(R.string.app_info)) {
            try {
                Apps.la.startAppDetailsActivity(key.component, app.user, null, null)
            } catch (_: Exception) {
            }
        })
        if (!app.isSystem) entries.add(MenuEntry(str(R.string.uninstall)) { act.uninstall(app) })
        show(anchor, entries)
    }

    private fun editIcon(target: String) {
        act.startActivity(Intent(act, IconEditActivity::class.java).putExtra("target", target))
    }

    private fun renameDialog(current: String, onDone: (String?) -> Unit) {
        val pal = act.pal
        // Built in the dialog's own theme: from the home screen's dark-based theme the text came out
        // white on a light dialog, so the name looked blank in light mode.
        val et = EditText(ContextThemeWrapper(act, Themes.dialogTheme(pal))).apply {
            setText(current)
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(act).apply {
            val p = (20 * d).roundToInt()
            setPadding(p, p / 2, p, 0)
            addView(et)
        }
        AlertDialog.Builder(act, Themes.dialogTheme(pal))
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
        act.rebind()
    }
}
