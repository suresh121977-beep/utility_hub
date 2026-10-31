package com.filemanager.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.File

fun shareFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = FileOps.mime(f)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        ctx.startActivity(Intent.createChooser(i, "Share ${f.name}"))
    } catch (e: ActivityNotFoundException) {
        // nothing can handle it
    }
}

fun openExternal(ctx: Context, f: File): Boolean {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, FileOps.mime(f))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        ctx.startActivity(Intent.createChooser(i, "Open ${f.name}"))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("path", text))
}

/** Asks the launcher to pin a home-screen shortcut that opens this folder/file inside the app. */
fun makeShortcut(ctx: Context, f: File): Boolean {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
    val drawable = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher) ?: return false
    val intent = Intent(ctx, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra("open_path", f.absolutePath)
    val info = ShortcutInfoCompat.Builder(ctx, "fm_" + f.absolutePath.hashCode())
        .setShortLabel(f.name.ifEmpty { "Files" })
        .setIntent(intent)
        .setIcon(IconCompat.createWithBitmap(drawable.toBitmap(192, 192)))
        .build()
    return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}
