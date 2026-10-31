package com.filemanager.app

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class Msg(val text: String)
data class ExtStat(val ext: String, val count: Int, val size: Long)

class FileViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val root: File = Environment.getExternalStorageDirectory()

    var path by mutableStateOf(root)
    var tab by mutableStateOf("folders")
    var extFilter by mutableStateOf("ALL")
    var sortKey by mutableStateOf("name")
    var query by mutableStateOf("")
    var items by mutableStateOf<List<Entry>>(emptyList())
    var organizer by mutableStateOf<List<ExtStat>>(emptyList())
    var loading by mutableStateOf(false)
    var message by mutableStateOf<Msg?>(null)
    var highlight by mutableStateOf<String?>(null)
    var viewer by mutableStateOf<File?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var selecting by mutableStateOf(false)

    var bookmarks by mutableStateOf(store.get("bookmarks"))
    var hidden by mutableStateOf(store.get("hidden"))
    var locked by mutableStateOf(store.get("locked"))
    var unlocked by mutableStateOf<Set<String>>(emptySet())
    var showHidden by mutableStateOf(false)

    val basket = mutableStateListOf<File>()
    var basketMove by mutableStateOf(false)

    var usedBytes by mutableStateOf(0L)
    var totalBytes by mutableStateOf(1L)

    private var job: Job? = null
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Entry>>()

    fun refresh() { cache.clear(); reload() }

    // ---------- helpers ----------
    private fun under(p: String, base: String) = p == base || p.startsWith("$base/")
    private fun under(f: File, base: String) = under(f.absolutePath, base)
    fun isHidden(f: File) = hidden.any { under(f, it) }
    fun blocked(f: File) = locked.any { under(f, it) && it !in unlocked }
    fun say(t: String) { message = Msg(t) }

    // ---------- loading ----------
    fun reload() {
        job?.cancel()
        val t = tab
        val p = path
        val ext = extFilter
        val sk = sortKey
        val q = query.trim()
        val hid = hidden
        val lk = locked
        val ul = unlocked
        val sh = showHidden
        val bm = bookmarks
        job = viewModelScope.launch {
            loading = true
            val storage = withContext(Dispatchers.IO) {
                try {
                    val st = StatFs(root.path)
                    val total = st.blockCountLong * st.blockSizeLong
                    Pair(total - st.availableBlocksLong * st.blockSizeLong, total)
                } catch (e: Exception) {
                    Pair(0L, 1L)
                }
            }
            usedBytes = storage.first
            totalBytes = storage.second.coerceAtLeast(1L)
            if (t == "organizer") {
                organizer = withContext(Dispatchers.IO) { computeOrganizer(p, hid, lk, ul) }
            } else if (t != "apps") {
                items = withContext(Dispatchers.IO) { compute(t, p, ext, sk, q, hid, lk, ul, sh, bm) { isActive } }
            }
            loading = false
        }
    }

    private fun scan(t: String): List<Entry> {
        val exts = when (t) {
            "images" -> FileOps.imgExts
            "videos" -> FileOps.vidExts + FileOps.audExts
            else -> FileOps.docExts
        }
        return FileOps.walkFiles(root).filter { FileOps.ext(it) in exts }.take(4000)
            .map { FileOps.entry(it) }.toList()
    }

    private fun compute(
        t: String, p: File, ext: String, sk: String, q: String,
        hid: Set<String>, lk: Set<String>, ul: Set<String>, sh: Boolean, bm: Set<String>,
        active: () -> Boolean = { true }
    ): List<Entry> {
        var list: List<Entry> = when (t) {
            "folders" ->
                if (q.isNotEmpty())
                    FileOps.searchTree(
                        p, q,
                        skipDir = { d -> (!sh && hid.any { under(d, it) }) || lk.any { under(d, it) && it !in ul } },
                        active = active
                    ).map { FileOps.entry(it) }
                else FileOps.list(p)
            "images", "videos", "documents" -> cache.getOrPut(t) { scan(t) }
            "bookmarks" -> bm.map { File(it) }.filter { it.exists() }.map { FileOps.entry(it) }
            else -> emptyList()
        }
        list = list.filter { e ->
            (sh || hid.none { under(e.path, it) }) && lk.none { under(e.path, it) && it !in ul }
        }
        if (ext != "ALL") {
            list = list.filter { e ->
                if (e.isDir) (t == "folders" || t == "bookmarks") else FileOps.matchesChip(e.file, ext)
            }
        }
        if (q.isNotEmpty()) list = list.filter { it.file.name.contains(q, ignoreCase = true) }
        return when (sk) {
            "size" -> list.sortedWith(compareBy<Entry> { !it.isDir }.thenByDescending { it.size })
            "modified" -> list.sortedWith(compareBy<Entry> { !it.isDir }.thenByDescending { it.modified })
            else -> list.sortedWith(
                compareBy<Entry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            )
        }
    }

    private fun extKey(f: File) = f.extension.lowercase().ifEmpty { "no_ext" }

    private fun computeOrganizer(p: File, hid: Set<String>, lk: Set<String>, ul: Set<String>): List<ExtStat> {
        val counts = HashMap<String, Int>()
        val sizes = HashMap<String, Long>()
        FileOps.walkFiles(p) { d ->
            hid.any { under(d, it) } || lk.any { under(d, it) && it !in ul }
        }.forEach { f ->
            val k = extKey(f)
            counts[k] = (counts[k] ?: 0) + 1
            sizes[k] = (sizes[k] ?: 0L) + f.length()
        }
        return counts.keys.map { ExtStat(it, counts[it] ?: 0, sizes[it] ?: 0L) }.sortedByDescending { it.size }
    }

    // ---------- navigation ----------
    fun go(dir: File) {
        selected = emptySet()
        path = dir
        tab = "folders"
        query = ""
        highlight = null
        reload()
    }

    @JvmName("changePath")
    fun setPath(dir: File) {
        selected = emptySet()
        path = dir
        reload()
    }

    @JvmName("changeTab")
    fun setTab(t: String) {
        cache.remove(t)
        selected = emptySet()
        tab = t
        query = ""
        highlight = null
        reload()
    }

    fun setExt(e: String) { extFilter = e; reload() }
    fun setSort(k: String) { sortKey = k; reload() }
    fun updateQuery(q: String) { query = q; reload() }

    fun canGoUp() = tab == "folders" && path.absolutePath != root.absolutePath
    fun up() { path.parentFile?.let { go(it) } }

    fun openLocation(f: File) {
        val parent = f.parentFile ?: return
        go(parent)
        highlight = f.absolutePath
    }

    /** Used for home-screen shortcuts. */
    fun openPath(p: String) {
        val f = File(p)
        if (!f.exists()) { say("That item no longer exists"); return }
        if (blocked(f)) { say("Locked — unlock the folder inside the app first"); return }
        if (f.isDirectory) go(f) else { go(f.parentFile ?: root); highlight = f.absolutePath }
    }

    fun relock() { unlocked = emptySet() }

    fun enforceLock() {
        if (blocked(path)) { path = root; tab = "folders" }
        val v = viewer
        if (v != null && blocked(v)) viewer = null
    }

    // ---------- file operations ----------
    private fun launchOp(ok: String, work: () -> Unit, after: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                after()
                say(ok)
            } catch (e: Exception) {
                say("Failed: ${e.message ?: e.javaClass.simpleName}")
            }
            refresh()
        }
    }

    private fun remap(old: String, new: String) {
        fun m(s: Set<String>) = s.map {
            if (it == old) new else if (it.startsWith("$old/")) new + it.removePrefix(old) else it
        }.toSet()
        bookmarks = m(bookmarks); store.put("bookmarks", bookmarks)
        hidden = m(hidden); store.put("hidden", hidden)
        locked = m(locked); store.put("locked", locked)
    }

    fun copyTo(f: File, dest: File) = launchOp("Copied to ${dest.name}", { FileOps.copyInto(f, dest) })

    fun moveTo(f: File, dest: File) {
        var result: File? = null
        launchOp("Moved to ${dest.name}", { result = FileOps.moveInto(f, dest) }, {
            result?.let { remap(f.absolutePath, it.absolutePath) }
        })
    }

    fun rename(f: File, newName: String) {
        val n = newName.trim()
        if (n.isEmpty() || n.contains('/')) { say("Invalid name"); return }
        val target = File(f.parentFile, n)
        if (target.exists()) { say("An item with that name already exists"); return }
        launchOp("Renamed", {
            if (!f.renameTo(target)) throw IOException("Rename failed")
        }, { remap(f.absolutePath, target.absolutePath) })
    }

    fun delete(f: File) = launchOp("Deleted", {
        if (!f.deleteRecursively()) throw IOException("Could not delete everything")
    }, { basket.removeAll { !it.exists() } })

    fun create(name: String, folder: Boolean) {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/')) { say("Invalid name"); return }
        val target = File(path, if (folder || n.contains('.')) n else "$n.txt")
        if (target.exists()) { say("Already exists"); return }
        launchOp(if (folder) "Folder created" else "File created", {
            val ok = if (folder) target.mkdirs() else target.createNewFile()
            if (!ok) throw IOException("Could not create it here")
        })
    }

    fun compress(f: File) = launchOp("Compressed to zip", { FileOps.zip(f) })
    fun uncompress(f: File) = launchOp("Uncompressed", { FileOps.unzip(f) })
    fun exportDir(dir: File) = launchOp("Directory tree (with sub-folders) saved in ${dir.name}", { FileOps.dirToText(dir) })

    fun toggleBookmark(f: File) {
        val k = f.absolutePath
        val adding = k !in bookmarks
        bookmarks = if (adding) bookmarks + k else bookmarks - k
        store.put("bookmarks", bookmarks)
        say(if (adding) "Added to bookmarks" else "Removed from bookmarks")
        if (tab == "bookmarks") reload()
    }

    // ---------- hide / lock ----------
    fun toggleHide(d: File) {
        val k = d.absolutePath
        val nowHidden = k !in hidden
        hidden = if (nowHidden) hidden + k else hidden - k
        store.put("hidden", hidden)
        viewModelScope.launch(Dispatchers.IO) {
            val nm = File(d, ".nomedia")
            try {
                if (nowHidden) { if (!nm.exists()) nm.createNewFile() }
                else if (nm.exists() && nm.length() == 0L) nm.delete()
            } catch (e: Exception) { /* best effort */ }
        }
        say(if (nowHidden) "Folder hidden. Menu → Show hidden folders to see it" else "Folder unhidden")
        reload()
    }

    fun toggleShowHidden() { showHidden = !showHidden; reload() }

    fun lock(d: File) {
        locked = locked + d.absolutePath
        unlocked = unlocked - d.absolutePath
        store.put("locked", locked)
        say("Folder locked")
        enforceLock()
        reload()
    }

    fun removeLock(d: File) {
        locked = locked - d.absolutePath
        store.put("locked", locked)
        say("Lock removed")
        reload()
    }

    fun unlockFor(f: File) {
        unlocked = unlocked + locked.filter { under(f, it) }
        reload()
    }

    fun verifyPin(p: String) = store.checkPin(p)
    fun savePin(p: String) = store.setPin(p)

    // ---------- multi-select ----------
    fun toggleSelect(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun selectAll() {
        selected = if (selected.size >= items.size) emptySet() else items.map { it.path }.toSet()
    }

    fun clearSelection() { selected = emptySet() }

    fun toggleSelecting() {
        selecting = !selecting
        if (!selecting) selected = emptySet()
    }

    fun stopSelecting() { selecting = false; selected = emptySet() }

    fun selectedHasZip(): Boolean =
        items.any { it.path in selected && !it.isDir && FileOps.ext(it.file) == "zip" }

    /** All selected items go into ONE zip file. */
    fun zipSelected(name: String) {
        val files = selectedFiles()
        if (files.isEmpty()) { say("Select items first"); return }
        val n = name.trim().removeSuffix(".zip").trim().ifEmpty { "Archive" }
        if (n.contains('/')) { say("Invalid name"); return }
        val dest = files.first().parentFile ?: path
        launchOp(
            "Created $n.zip with ${files.size} item${if (files.size == 1) "" else "s"}",
            { FileOps.zipMany(files, dest, "$n.zip") },
            { selected = emptySet(); selecting = false }
        )
    }

    /** Each selected zip is extracted into a folder named after the zip file. */
    fun uncompressSelected() {
        val zips = selectedFiles().filter { it.isFile && FileOps.ext(it) == "zip" }
        bulk("Uncompressed", zips, { FileOps.unzip(it); null })
    }

    fun goHome() {
        path = root
        tab = "folders"
        query = ""
        extFilter = "ALL"
        selected = emptySet()
        selecting = false
        viewer = null
        highlight = null
        reload()
    }

    private fun selectedFiles(): List<File> = items.filter { it.path in selected }.map { it.file }

    private fun bulk(
        verb: String, files: List<File>,
        work: (File) -> File?, onResult: (File, File) -> Unit = { _, _ -> }
    ) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val results = ArrayList<Pair<File, File>>()
            var ok = 0
            var fail = 0
            withContext(Dispatchers.IO) {
                files.forEach { f ->
                    try {
                        val r = work(f)
                        if (r != null) synchronized(results) { results.add(Pair(f, r)) }
                        ok++
                    } catch (e: Exception) {
                        fail++
                    }
                }
            }
            results.forEach { onResult(it.first, it.second) }
            say("$verb $ok item${if (ok == 1) "" else "s"}" + if (fail > 0) " ($fail failed)" else "")
            selected = emptySet()
            selecting = false
            basket.removeAll { !it.exists() }
            refresh()
        }
    }

    fun copySelected(dest: File) = bulk("Copied", selectedFiles(), { FileOps.copyInto(it, dest) })

    fun moveSelected(dest: File) = bulk("Moved", selectedFiles(), { FileOps.moveInto(it, dest) },
        { old, new -> if (old.absolutePath != new.absolutePath) remap(old.absolutePath, new.absolutePath) })

    fun deleteSelected() = bulk("Deleted", selectedFiles(), {
        if (!it.deleteRecursively()) throw IOException("Could not delete ${it.name}")
        null
    })

    fun compressSelected() = bulk("Compressed", selectedFiles(), { FileOps.zip(it); null })

    fun pickSelected() {
        val files = selectedFiles()
        files.forEach { f -> if (basket.none { it.absolutePath == f.absolutePath }) basket.add(f) }
        selected = emptySet()
        selecting = false
        say("${files.size} added to Pick & Drop")
    }

    // ---------- pick & drop ----------
    fun pick(f: File) {
        if (basket.any { it.absolutePath == f.absolutePath }) { say("Already picked"); return }
        basket.add(f)
        say("Picked ${f.name}")
    }

    fun unpick(f: File) { basket.remove(f) }
    fun clearBasket() { basket.clear() }
    fun dropAll() = dropInto(basket.toList())
    fun dropOne(f: File) = dropInto(listOf(f))

    fun dropAllTo(dest: File) = dropInto(basket.toList(), dest)

    private fun dropInto(list: List<File>, dest: File = path) {
        val move = basketMove
        viewModelScope.launch {
            val done = ArrayList<File>()
            val moved = ArrayList<Pair<File, File>>()
            var fail = 0
            withContext(Dispatchers.IO) {
                list.forEach { f ->
                    try {
                        if (move) moved.add(Pair(f, FileOps.moveInto(f, dest))) else FileOps.copyInto(f, dest)
                        done.add(f)
                    } catch (e: Exception) {
                        fail++
                    }
                }
            }
            moved.forEach { remap(it.first.absolutePath, it.second.absolutePath) }
            basket.removeAll(done)
            say("${if (move) "Moved" else "Copied"} ${done.size} to ${dest.name}" + if (fail > 0) " ($fail failed)" else "")
            refresh()
        }
    }

    // ---------- organizer ----------
    fun organize(ext: String, move: Boolean) {
        val base = path
        viewModelScope.launch {
            var count = 0
            var failed = 0
            try {
                withContext(Dispatchers.IO) {
                    val dest = File(base, ext.uppercase() + "_Files")
                    val files = FileOps.walkFiles(base)
                        .filter { extKey(it) == ext && it.parentFile?.absolutePath != dest.absolutePath }
                        .toList()
                    if (files.isNotEmpty()) dest.mkdirs()
                    files.forEach { f ->
                        try {
                            if (move) FileOps.moveInto(f, dest) else FileOps.copyInto(f, dest)
                            count++
                        } catch (e: Exception) {
                            failed++
                        }
                    }
                }
                say("${if (move) "Moved" else "Copied"} $count .$ext files to ${ext.uppercase()}_Files" +
                    if (failed > 0) " ($failed failed)" else "")
            } catch (e: Exception) {
                say("Failed: ${e.message}")
            }
            refresh()
        }
    }
}
