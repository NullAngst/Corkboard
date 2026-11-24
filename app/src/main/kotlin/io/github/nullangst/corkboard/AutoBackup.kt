/*
 * Corkboard, a minimal home screen launcher for Android.
 * Copyright (C) 2026 Corkboard contributors
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE.
 */
package io.github.nullangst.corkboard

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Writes the same zip as Export layout into a folder the user picked once, through the storage
 * access framework, so no storage permission is needed. "change" backs up a minute after the last
 * layout change; "daily", "weekly" and "monthly" back up when the home screen comes back and the
 * last backup is that old. Only the newest N automatic backups are kept (N set in settings); manual
 * exports in the same folder are never touched.
 */
object AutoBackup {
    val frequencies = listOf("change", "daily", "weekly", "monthly")
    val keepChoices = listOf(1, 3, 5, 10, 30)
    private const val PREFIX = "corkboard-auto-"
    private const val DELAY_MS = 60_000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var pending = false
    private val run = Runnable {
        pending = false
        app?.let { backupNow(it) }
    }

    private val s get() = Store.layout.settings

    private fun active() = s.autoBackupOn && s.autoBackupUri != null

    fun freqName(f: String) = when (f) {
        "change" -> R.string.b_freq_change
        "weekly" -> R.string.b_freq_weekly
        "monthly" -> R.string.b_freq_monthly
        else -> R.string.b_freq_daily
    }

    private fun interval(): Long = when (s.autoBackupFreq) {
        "weekly" -> 7 * DAY_MS
        "monthly" -> 30 * DAY_MS
        else -> DAY_MS
    }

    /** Called on every layout save. */
    fun changed(ctx: Context) {
        if (!active() || s.autoBackupFreq != "change") return
        app = ctx.applicationContext
        pending = true
        main.removeCallbacks(run)
        main.postDelayed(run, DELAY_MS)
    }

    /** Home screen going away: don't lose a pending backup if Android kills the process. */
    fun flush() {
        if (!pending) return
        main.removeCallbacks(run)
        run.run()
    }

    /** Daily, weekly or monthly: run when the home screen comes back and the last one is that old. */
    fun periodicCheck(ctx: Context) {
        if (!active() || s.autoBackupFreq == "change") return
        if (System.currentTimeMillis() - s.lastAutoBackup < interval()) return
        backupNow(ctx)
    }

    fun backupNow(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val uri = s.autoBackupUri ?: run {
            done?.invoke(false)
            return
        }
        val appCtx = ctx.applicationContext
        val json = Store.backupJson()
        io.execute {
            val error = try {
                write(appCtx, Uri.parse(uri), json)
                null
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            main.post {
                if (error == null) s.lastAutoBackup = System.currentTimeMillis()
                s.autoBackupError = error
                Store.saveQuiet()
                done?.invoke(error == null)
            }
        }
    }

    private fun write(ctx: Context, tree: Uri, json: ByteArray) {
        val cr = ctx.contentResolver
        val dirId = DocumentsContract.getTreeDocumentId(tree)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val doc = DocumentsContract.createDocument(cr, dir, "application/zip", name)
            ?: throw IOException("Can't create a file in that folder")
        val out = cr.openOutputStream(doc, "w") ?: throw IOException("Can't write to that folder")
        out.use { Store.exportZip(it, json) }
        prune(ctx, tree, dirId)
    }

    /** Deletes all but the newest automatic backups, as many as the user chose to keep. Names sort by date. */
    private fun prune(ctx: Context, tree: Uri, dirId: String) {
        val cr = ctx.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val found = ArrayList<Pair<String, String>>()
        cr.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val n = c.getString(1) ?: continue
                if (n.startsWith(PREFIX) && n.endsWith(".zip")) found.add(id to n)
            }
        }
        for ((id, _) in found.sortedByDescending { it.second }.drop(s.autoBackupKeep.coerceAtLeast(1))) {
            try {
                DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            } catch (_: Exception) {
            }
        }
    }

    /** "primary:Documents/Corkboard" becomes "Documents/Corkboard". */
    fun folderLabel(uri: String?): String? {
        if (uri == null) return null
        return try {
            DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifEmpty { "/" }
        } catch (_: Exception) {
            uri
        }
    }
}
