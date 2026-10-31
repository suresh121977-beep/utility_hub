package com.apkstudio.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** APK Studio screen of Utility Hub: edit APK name/icon, install from ZIP without extracting. */
class MainActivity : AppCompatActivity() {
    private var zipUri: Uri? = null
    private val zipEntries = mutableListOf<String>()
    private lateinit var zipText: TextView
    private lateinit var listAdapter: ArrayAdapter<String>

    private var iconBytes: ByteArray? = null
    private var dlgIcon: ImageView? = null
    private var pendingSave: File? = null
    private var installAfterSave = false
    private lateinit var backCb: OnBackPressedCallback

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            val base = displayName(u).removeSuffix(".apk")
            showEditDialog { n, i -> runEdit(base, { copyUriToCache(u) }, n, i) }
        }
    }
    private val pickIcon = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) loadIcon(u)
    }
    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { zipUri = u; zipText.text = "ZIP: " + displayName(u); listZip(u) }
    }
    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { u ->
        val f = pendingSave
        if (u != null && f != null) Thread {
            try {
                contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                toast("APK saved")
                if (installAfterSave) installFile(f)
            } catch (e: Exception) { toast("Save failed: ${e.message}") }
            installAfterSave = false
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        backCb = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = showHome() }
        onBackPressedDispatcher.addCallback(this, backCb)
        showHome()
    }

    // ---------- screens ----------
    private fun screen(home: Boolean, build: LinearLayout.() -> Unit) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        root.build()
        setContentView(root)
        backCb.isEnabled = !home
    }

    private fun showHome() = screen(true) {
        addView(header("APK Studio"))
        addView(menu("APK Editor", "Change name & icon of any APK, then install or download it") { showEditor() })
        addView(menu("Install from ZIP", "List APKs inside a ZIP, optionally edit, then install") { showZip() })
    }

    private fun showEditor() = screen(false) {
        addView(header("APK Editor"))
        addView(text("Choose an APK, set a new name and/or icon, then install it or download the edited file."))
        addView(button("Choose APK to edit") { pickApk.launch(arrayOf("*/*")) })
    }

    private fun showZip() = screen(false) {
        addView(header("Install from ZIP"))
        addView(button("Choose ZIP") { pickZip.launch(arrayOf("application/zip", "*/*")) })
        zipText = text("Tap an APK below")
        addView(zipText)
        listAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, zipEntries)
        addView(ListView(this@MainActivity).apply {
            adapter = listAdapter
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ -> askEditOrInstall(zipEntries[pos]) }
        })
    }

    // ---------- ZIP flow ----------
    private fun askEditOrInstall(name: String) {
        val base = name.substringAfterLast('/').removeSuffix(".apk")
        AlertDialog.Builder(this)
            .setTitle(base)
            .setMessage("Edit name / icon before installing?")
            .setPositiveButton("Yes, edit") { _, _ ->
                showEditDialog { n, i -> runEdit(base, { extractEntry(name) }, n, i) }
            }
            .setNegativeButton("No, install") { _, _ -> installFromZip(name) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /** Lists entry names by streaming the zip - nothing is extracted. */
    private fun listZip(u: Uri) {
        Thread {
            val names = mutableListOf<String>()
            try {
                ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(".apk", true)) names.add(e.name)
                        e = z.nextEntry
                    }
                }
            } catch (ex: Exception) { toast("Can't read ZIP: ${ex.message}") }
            runOnUiThread {
                zipEntries.clear(); zipEntries.addAll(names); listAdapter.notifyDataSetChanged()
                if (names.isEmpty()) toast("No APK files found in this ZIP")
            }
        }.start()
    }

    private fun installFromZip(name: String) {
        val u = zipUri ?: return
        if (!canInstall()) return
        Thread {
            try {
                ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
                    var e = z.nextEntry
                    while (e != null && e.name != name) e = z.nextEntry
                    if (e != null) Installer.install(this, z, e.size)
                }
            } catch (ex: Exception) { toast("Install error: ${ex.message}") }
        }.start()
    }

    private fun extractEntry(name: String): File {
        val out = File(cacheDir, "input.apk")
        ZipInputStream(contentResolver.openInputStream(zipUri!!)!!).use { z ->
            var e = z.nextEntry
            while (e != null && e.name != name) e = z.nextEntry
            out.outputStream().use { z.copyTo(it) }
        }
        return out
    }

    // ---------- edit flow ----------
    private fun showEditDialog(onApply: (String?, ByteArray?) -> Unit) {
        iconBytes = null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val nameIn = EditText(this).apply { hint = "New app name (empty = keep)" }
        dlgIcon = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(160, 160) }
        box.addView(nameIn)
        box.addView(button("Choose new icon (optional)") { pickIcon.launch(arrayOf("image/*")) })
        box.addView(dlgIcon)
        AlertDialog.Builder(this).setTitle("Edit app").setView(box)
            .setPositiveButton("Apply") { _, _ ->
                onApply(nameIn.text.toString().trim().ifEmpty { null }, iconBytes)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runEdit(base: String, getSource: () -> File, name: String?, icon: ByteArray?) {
        if (name == null && icon == null) return toast("Enter a new name and/or choose an icon")
        toast("Editing...")
        Thread {
            try {
                val out = File(cacheDir, "edited.apk")
                ApkEditor.edit(this, getSource(), name, icon, out)
                runOnUiThread { chooseOutput(out, base) }
            } catch (t: Throwable) { toast("Edit failed: ${t.message}") }
        }.start()
    }

    private fun chooseOutput(out: File, base: String) {
        AlertDialog.Builder(this).setTitle("Edited APK ready")
            .setItems(arrayOf("Install only", "Download (save) APK only", "Download, then install")) { _, w ->
                if (w == 0) { if (canInstall()) installFile(out) }
                else {
                    pendingSave = out
                    installAfterSave = (w == 2) && canInstall()
                    saveLauncher.launch("$base-edited.apk")
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun installFile(f: File) {
        Thread {
            try { f.inputStream().use { Installer.install(this, it, f.length()) } }
            catch (e: Exception) { toast("Install error: ${e.message}") }
        }.start()
    }

    // ---------- helpers ----------
    private fun loadIcon(u: Uri) {
        val bmp = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) } ?: return
        val scaled = Bitmap.createScaledBitmap(bmp, 192, 192, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
        iconBytes = bos.toByteArray()
        dlgIcon?.setImageBitmap(scaled)
    }

    private fun copyUriToCache(u: Uri): File {
        val f = File(cacheDir, "input.apk")
        contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun canInstall(): Boolean {
        if (packageManager.canRequestPackageInstalls()) return true
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        toast("Allow installs from this app, then try again")
        return false
    }

    private fun displayName(u: Uri): String =
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: (u.lastPathSegment ?: "file")

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
    private fun header(t: String) = TextView(this).apply { text = t; textSize = 22f; setPadding(0, 16, 0, 16) }
    private fun text(t: String) = TextView(this).apply { text = t; setPadding(0, 8, 0, 8) }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
    private fun menu(title: String, sub: String, onClick: () -> Unit) = button("$title\n$sub", onClick)
}
