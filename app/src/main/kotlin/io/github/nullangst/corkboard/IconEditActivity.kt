/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Edit icon: large live preview on top, source, shape, treatment, scale and background under it. */
class IconEditActivity : Activity() {
    private val reqPhoto = 41
    private val reqFile = 42
    private lateinit var pal: Palette
    private lateinit var target: String
    private var original: IconSettings? = null
    private lateinit var s: IconSettings
    /** True while the picker is choosing the themed-look image instead of the icon image. */
    private var pickingMono = false

    /** Images picked in this editor, kept safe from cleanups until it closes. */
    private val picked = ArrayList<String>()
    private lateinit var preview: ImageView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Apps.init(this)
        target = intent.getStringExtra("target") ?: run {
            finish()
            return
        }
        original = Store.layout.icons[target]
        s = original?.copy() ?: IconSettings()
        // The photo picker can push this screen out of memory, and rotating rebuilds it: keep the edits.
        savedInstanceState?.let { b ->
            s = IconSettings(
                b.getString("source") ?: s.source, b.getString("file"), b.getString("shape") ?: s.shape,
                b.getString("treatment") ?: s.treatment, b.getInt("scale", s.scale), b.getString("background") ?: s.background,
                b.getString("label"), b.getString("mono") ?: s.mono, b.getString("monoFile"),
            )
            pickingMono = b.getBoolean("pickingMono")
            b.getStringArrayList("picked")?.let {
                picked.addAll(it)
                Store.pendingIcons.addAll(it)
            }
        }
        pal = Themes.palette(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        setContentView(scroll)
        Ui.edgeToEdge(this, scroll, pal)
        scroll.setBackgroundColor(pal.bg)
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("source", s.source)
        outState.putString("file", s.file)
        outState.putString("shape", s.shape)
        outState.putString("treatment", s.treatment)
        outState.putInt("scale", s.scale)
        outState.putString("background", s.background)
        outState.putString("label", s.label)
        outState.putString("mono", s.mono)
        outState.putString("monoFile", s.monoFile)
        outState.putBoolean("pickingMono", pickingMono)
        outState.putStringArrayList("picked", picked)
    }

    private fun radios(options: List<Pair<String, String>>, selected: String, horizontalScroll: Boolean, onPick: (String) -> Unit): View {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((id, name) in options) {
            group.addView(RadioButton(this).apply {
                text = name
                setTextColor(pal.text)
                buttonTintList = pal.accentList
                isChecked = id == selected
                setPadding(0, 0, Ui.dp(context, 12f), 0)
                setOnClickListener { onPick(id) }
            })
        }
        val pad = Ui.dp(this, 16f)
        return if (horizontalScroll) {
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(pad, 0, pad, 0)
                clipToPadding = false
                addView(group)
            }
        } else {
            group.setPadding(pad, 0, pad, 0)
            group
        }
    }

    private fun build() {
        list.removeAllViews()
        val add = { v: View -> list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val isFolder = (Store.layout.findItem(target.removePrefix("item:").toLongOrNull() ?: -1)) is FolderItem

        add(Ui.title(this, pal, getString(R.string.edit_icon)))
        preview = ImageView(this)
        val size = Ui.dp(this, 128f)
        list.addView(preview, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = Ui.dp(this@IconEditActivity, 8f)
        })
        updatePreview()

        add(Ui.header(this, pal, getString(R.string.e_source)))
        val sources = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 16f), 0, Ui.dp(context, 16f), 0)
        }
        fun chip(text: String, onClick: () -> Unit) = Ui.button(this, pal, text, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Ui.dp(context, 8f)
            }
        }
        sources.addView(chip(getString(if (isFolder) R.string.e_folder_preview else R.string.e_original)) {
            s.source = "original"
            build()
        })
        sources.addView(chip(getString(R.string.e_photo)) { pick(true, mono = false) })
        sources.addView(chip(getString(R.string.e_file)) { pick(false, mono = false) })
        sources.addView(chip(getString(R.string.reset)) {
            s = IconSettings(label = s.label)
            build()
        })
        add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(sources)
        })

        add(Ui.header(this, pal, getString(R.string.e_shape)))
        add(radios((listOf("global") + IconRenderer.shapes + "none").map { it to IconRenderer.shapeName(this, it) }, s.shape, true) {
            s.shape = it
            updatePreview()
        })

        add(Ui.header(this, pal, getString(R.string.e_treatment)))
        add(radios(listOf("auto" to getString(R.string.t_auto), "fill" to getString(R.string.t_fill), "fit" to getString(R.string.t_fit)), s.treatment, false) {
            s.treatment = it
            build()
        })

        add(Ui.header(this, pal, getString(R.string.e_scale)))
        val scaleLabel = TextView(this).apply {
            text = getString(R.string.percent, s.scale)
            setTextColor(pal.text)
            setPadding(Ui.dp(context, 20f), 0, Ui.dp(context, 20f), 0)
        }
        add(scaleLabel)
        add(SeekBar(this).apply {
            max = 20
            progress = (s.scale - 50) / 5
            Ui.tint(this, pal)
            setPadding(Ui.dp(context, 24f), Ui.dp(context, 8f), Ui.dp(context, 24f), Ui.dp(context, 8f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    s.scale = 50 + p * 5
                    scaleLabel.text = getString(R.string.percent, s.scale)
                    updatePreview()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        add(Ui.header(this, pal, getString(R.string.e_background)))
        val custom = s.background.startsWith("#")
        add(radios(
            listOf(
                "auto" to getString(R.string.t_auto), "white" to getString(R.string.bg_white), "black" to getString(R.string.bg_black),
                "transparent" to getString(R.string.bg_transparent), "custom" to getString(R.string.bg_custom),
            ),
            if (custom) "custom" else s.background, true,
        ) {
            s.background = if (it == "custom") (if (custom) s.background else "#3E8C7E") else it
            build()
        })
        if (custom) {
            val et = EditText(this).apply {
                setText(s.background)
                setTextColor(pal.text)
                setHintTextColor(pal.textDim)
                hint = getString(R.string.hex_hint)
                isSingleLine = true
                backgroundTintList = pal.accentList
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: Editable?) {
                        val v = e.toString().trim()
                        // Only #RRGGBB or #AARRGGBB: color names would parse here but not when drawing.
                        val ok = v.startsWith("#") && try {
                            Color.parseColor(v)
                            true
                        } catch (_: Exception) {
                            false
                        }
                        if (ok) {
                            s.background = v
                            updatePreview()
                        }
                    }
                })
            }
            add(LinearLayout(this).apply {
                setPadding(Ui.dp(context, 20f), 0, Ui.dp(context, 20f), 0)
                addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }

        if (target.startsWith("app:") && IconRenderer.themedSupported) {
            add(Ui.header(this, pal, getString(R.string.e_themed)))
            if (!Store.layout.settings.themedIcons) {
                add(TextView(this).apply {
                    text = getString(R.string.e_themed_off)
                    setTextColor(pal.textDim)
                    setPadding(Ui.dp(context, 20f), 0, Ui.dp(context, 20f), Ui.dp(context, 8f))
                })
            }
            add(radios(
                listOf(
                    "auto" to getString(R.string.t_auto), "generate" to getString(R.string.e_mono_generate),
                    "image" to getString(R.string.e_mono_image), "off" to getString(R.string.s_cache_off),
                ),
                s.mono, true,
            ) {
                s.mono = it
                if (it == "image" && s.monoFile == null) pick(true, mono = true) else build()
            })
            if (s.mono == "image") {
                add(LinearLayout(this).apply {
                    setPadding(Ui.dp(context, 16f), Ui.dp(context, 8f), Ui.dp(context, 16f), 0)
                    addView(chip(getString(R.string.e_mono_choose)) { pick(true, mono = true) })
                })
            }
            add(TextView(this).apply {
                text = getString(R.string.e_mono_help)
                setTextColor(pal.textDim)
                textSize = 13f
                setPadding(Ui.dp(context, 20f), Ui.dp(context, 8f), Ui.dp(context, 20f), 0)
            })
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(Ui.dp(context, 20f), Ui.dp(context, 24f), Ui.dp(context, 20f), Ui.dp(context, 24f))
        }
        buttons.addView(TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 15f
            setTextColor(pal.accent)
            setPadding(Ui.dp(context, 18f), Ui.dp(context, 10f), Ui.dp(context, 18f), Ui.dp(context, 10f))
            setOnClickListener { cancel() }
        })
        buttons.addView(Ui.button(this, pal, getString(R.string.save)) { save() })
        add(buttons)
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        preview.setImageBitmap(IconRenderer.forTarget(this, target, Ui.dp(this, 128f), s))
    }

    private fun pick(photo: Boolean, mono: Boolean) {
        pickingMono = mono
        val i = if (photo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        try {
            startActivityForResult(i, if (photo) reqPhoto else reqFile)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != reqPhoto && requestCode != reqFile) return
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        // Claimed before the file exists, so a cleanup running meanwhile leaves it alone.
        val name = "custom-${System.currentTimeMillis()}.png"
        picked.add(name)
        Store.pendingIcons.add(name)
        // ImageDecoder must not run on the main thread.
        Thread {
            val path = try {
                val src = ImageDecoder.createSource(contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val m = max(info.size.width, info.size.height)
                    val k = min(1f, IconRenderer.CUSTOM_MAX_PX.toFloat() / m)
                    dec.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                IconRenderer.saveCustom(bmp, name)
            } catch (_: Exception) {
                null
            }
            runOnUiThread { usePicked(path) }
        }.start()
    }

    private fun usePicked(path: String?) {
        // Closed while the image was decoding: the next cleanup removes the unused file.
        if (isDestroyed) return
        if (path == null) {
            Toast.makeText(this, R.string.cant_read_image, Toast.LENGTH_SHORT).show()
            return
        }
        if (pickingMono) {
            s.mono = "image"
            s.monoFile = path
        } else {
            s.source = "custom"
            s.file = path
        }
        build()
    }

    private fun save() {
        if (s.source != "custom") s.file = null
        if (s.mono != "image") s.monoFile = null
        val icons = Store.layout.icons
        if (s.isDefault()) icons.remove(target) else icons[target] = s
        AppKey.parse(target.removePrefix("app:"))?.let { DiskIcons.clearPackage(it.pkg) }
        IconRenderer.clear()
        Store.save()
        Store.cleanIcons()
        finish()
    }

    private fun cancel() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Images picked but never saved aren't in the layout, so this deletes them. Saved ones stay.
        if (isFinishing) {
            Store.pendingIcons.removeAll(picked.toSet())
            Store.cleanIcons()
        }
    }
}
