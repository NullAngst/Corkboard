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
import java.io.File
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
    private var picked: String? = null
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
        pal = Themes.palette(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        setContentView(scroll)
        Ui.edgeToEdge(this, scroll, pal)
        scroll.setBackgroundColor(pal.bg)
        build()
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

        add(Ui.title(this, pal, "Edit icon"))
        preview = ImageView(this)
        val size = Ui.dp(this, 128f)
        list.addView(preview, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = Ui.dp(this@IconEditActivity, 8f)
        })
        updatePreview()

        add(Ui.header(this, pal, "Source"))
        val sources = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 16f), 0, Ui.dp(context, 16f), 0)
        }
        fun chip(text: String, onClick: () -> Unit) = Ui.button(this, pal, text, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Ui.dp(context, 8f)
            }
        }
        sources.addView(chip(if (isFolder) "Folder preview" else "Original") {
            s.source = "original"
            build()
        })
        sources.addView(chip("Photo") { pick(true) })
        sources.addView(chip("File") { pick(false) })
        sources.addView(chip("Reset") {
            s = IconSettings(label = s.label)
            build()
        })
        add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(sources)
        })

        add(Ui.header(this, pal, "Shape"))
        add(radios((listOf("global") + IconRenderer.shapes + "none").map { it to IconRenderer.shapeName(it) }, s.shape, true) {
            s.shape = it
            updatePreview()
        })

        add(Ui.header(this, pal, "Treatment"))
        add(radios(listOf("auto" to "Auto", "fill" to "Fill", "fit" to "Fit"), s.treatment, false) {
            s.treatment = it
            build()
        })

        add(Ui.header(this, pal, "Scale"))
        val scaleLabel = TextView(this).apply {
            text = "${s.scale}%"
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
                    scaleLabel.text = "${s.scale}%"
                    updatePreview()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        add(Ui.header(this, pal, "Background (Fit only)"))
        val custom = s.background.startsWith("#")
        val bgEnabled = s.treatment != "fill"
        add(radios(
            listOf("auto" to "Auto", "white" to "White", "black" to "Black", "transparent" to "Transparent", "custom" to "Custom"),
            if (custom) "custom" else s.background, true,
        ) {
            s.background = if (it == "custom") (if (custom) s.background else "#3E8C7E") else it
            build()
        }.apply {
            alpha = if (bgEnabled) 1f else 0.5f
        })
        if (custom) {
            val et = EditText(this).apply {
                setText(s.background)
                setTextColor(pal.text)
                setHintTextColor(pal.textDim)
                hint = "#RRGGBB"
                isSingleLine = true
                backgroundTintList = pal.accentList
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: Editable?) {
                        val v = e.toString().trim()
                        val ok = try {
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

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(Ui.dp(context, 20f), Ui.dp(context, 24f), Ui.dp(context, 20f), Ui.dp(context, 24f))
        }
        buttons.addView(TextView(this).apply {
            text = "Cancel"
            textSize = 15f
            setTextColor(pal.accent)
            setPadding(Ui.dp(context, 18f), Ui.dp(context, 10f), Ui.dp(context, 18f), Ui.dp(context, 10f))
            setOnClickListener { cancel() }
        })
        buttons.addView(Ui.button(this, pal, "Save") { save() })
        add(buttons)
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        preview.setImageBitmap(IconRenderer.forTarget(this, target, Ui.dp(this, 128f), s))
    }

    private fun pick(photo: Boolean) {
        val i = if (photo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        try {
            startActivityForResult(i, if (photo) reqPhoto else reqFile)
        } catch (_: Exception) {
            Toast.makeText(this, "No picker available", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != reqPhoto && requestCode != reqFile) return
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        // ImageDecoder must not run on the main thread.
        Thread {
            val path = try {
                val src = ImageDecoder.createSource(contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val m = max(info.size.width, info.size.height)
                    val k = min(1f, 432f / m)
                    dec.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                IconRenderer.saveCustom(bmp, "custom-${System.currentTimeMillis()}.png")
            } catch (_: Exception) {
                null
            }
            runOnUiThread { usePicked(path) }
        }.start()
    }

    private fun usePicked(path: String?) {
        if (path == null) {
            Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
            return
        }
        dropPicked()
        picked = path
        s.source = "custom"
        s.file = path
        build()
    }

    /** A picked image that was replaced or never saved is deleted; the original file is never touched. */
    private fun dropPicked() {
        val p = picked ?: return
        if (p != original?.file) File(Store.filesDir, p).delete()
        picked = null
    }

    private fun save() {
        if (s.source != "custom") s.file = null
        val icons = Store.layout.icons
        if (s.isDefault()) icons.remove(target) else icons[target] = s
        picked = null
        IconRenderer.clear()
        Store.save()
        Store.cleanIcons()
        finish()
    }

    private fun cancel() {
        dropPicked()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving by back or swipe without saving: drop the unsaved picked image.
        if (isFinishing) dropPicked()
    }
}
