package com.filemanager.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Cached file metadata so the UI never touches the file system while scrolling. */
class Entry(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val created: Long) {
    val path: String = file.absolutePath
}

object FileOps {
    val imgExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    val vidExts = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
    val audExts = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
    val docExts = setOf(
        "csv", "xlsx", "xls", "doc", "docx", "html", "htm", "txt", "json", "md",
        "pdf", "ppt", "pptx", "rtf", "xml", "odt"
    )
    val textExts = setOf(
        "txt", "md", "json", "csv", "html", "htm", "xml", "log", "kt", "java", "js",
        "css", "yml", "yaml", "ini", "conf", "sh", "py", "properties", "gradle"
    )

    val chips = listOf("ALL", "csv", "xlsx", "doc", "html", "txt", "json", "md", "pdf", "zip", "OTHER")
    private val chipMap: Map<String, Set<String>> = mapOf(
        "csv" to setOf("csv"),
        "xlsx" to setOf("xlsx", "xls"),
        "doc" to setOf("doc", "docx"),
        "html" to setOf("html", "htm"),
        "txt" to setOf("txt", "log"),
        "json" to setOf("json"),
        "md" to setOf("md"),
        "pdf" to setOf("pdf"),
        "zip" to setOf("zip")
    )
    private val chipAll: Set<String> = chipMap.values.flatten().toSet()

    fun ext(f: File): String = f.extension.lowercase()

    fun matchesChip(f: File, chip: String): Boolean {
        val e = ext(f)
        return if (chip == "OTHER") e !in chipAll else e in (chipMap[chip] ?: emptySet())
    }

    fun size(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    fun date(ms: Long): String = dateFmt.get()!!.format(Date(ms))

    /** One stat call gives size, modified and creation time (creation falls back to modified if unsupported). */
    fun entry(f: File): Entry {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val a = java.nio.file.Files.readAttributes(
                    f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java
                )
                val mod = a.lastModifiedTime().toMillis()
                val cr = a.creationTime().toMillis().let { if (it <= 0L) mod else it }
                return Entry(f, a.isDirectory, if (a.isDirectory) 0L else a.size(), mod, cr)
            } catch (e: Exception) {
                // fall through to the classic calls
            }
        }
        val dir = f.isDirectory
        val m = f.lastModified()
        return Entry(f, dir, if (dir) 0L else f.length(), m, m)
    }

    private fun isSymlink(f: File): Boolean = try {
        val p = f.parentFile
        if (p == null) false else f.canonicalPath != File(p.canonicalFile, f.name).path
    } catch (e: Exception) {
        true
    }

    fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f)) ?: "*/*"

    fun list(dir: File): List<Entry> = dir.listFiles()?.map { entry(it) } ?: emptyList()

    /** Walks files below root, skipping dot-folders, Android/data, Android/obb and anything skipDir() rejects. */
    fun walkFiles(root: File, skipDir: (File) -> Boolean = { false }): Sequence<File> =
        root.walkTopDown()
            .onEnter { d ->
                !d.name.startsWith(".") &&
                    !d.absolutePath.contains("/Android/data") &&
                    !d.absolutePath.contains("/Android/obb") &&
                    !skipDir(d)
            }
            .filter { it.isFile }

    /**
     * Recursive name search: walks [root] and ALL subfolders, returning files AND folders whose
     * name contains [q] (case-insensitive). Skips dot-folders, Android/data, Android/obb and
     * anything [skipDir] rejects. Stops early when [active] turns false or [limit] is reached.
     */
    fun searchTree(
        root: File, q: String, limit: Int = 5000,
        skipDir: (File) -> Boolean = { false }, active: () -> Boolean = { true }
    ): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < limit && active()) {
            val dir = stack.removeLast()
            val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (k in kids) {
                if (k.name.contains(q, ignoreCase = true)) out.add(k)
                if (k.isDirectory && !isSymlink(k) && !k.name.startsWith(".") &&
                    !k.absolutePath.contains("/Android/data") &&
                    !k.absolutePath.contains("/Android/obb") && !skipDir(k)
                ) stack.addLast(k)
            }
        }
        return out
    }

    fun uniqueTarget(dir: File, name: String): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        do {
            t = File(dir, "$base ($i)$ext")
            i++
        } while (t.exists())
        return t
    }

    private fun checkNotInside(src: File, destDir: File) {
        if (!src.isDirectory) return
        val s = src.canonicalPath
        val d = destDir.canonicalPath
        if (d == s || d.startsWith(s + File.separator)) {
            throw IOException("Cannot put a folder inside itself")
        }
    }

    fun copyInto(src: File, destDir: File): File {
        if (!destDir.isDirectory) throw IOException("Destination is not a folder")
        checkNotInside(src, destDir)
        val target = uniqueTarget(destDir, src.name)
        if (src.isDirectory) {
            if (!target.mkdirs()) throw IOException("Cannot create ${target.name}")
            src.listFiles()?.forEach { copyInto(it, target) }
        } else {
            src.copyTo(target)
        }
        return target
    }

    fun moveInto(src: File, destDir: File): File {
        if (!destDir.isDirectory) throw IOException("Destination is not a folder")
        if (src.parentFile?.canonicalPath == destDir.canonicalPath) return src
        checkNotInside(src, destDir)
        val target = uniqueTarget(destDir, src.name)
        if (src.renameTo(target)) return target
        val copy = copyInto(src, destDir)
        if (!src.deleteRecursively()) throw IOException("Copied, but could not remove the original")
        return copy
    }

    fun zip(src: File): File {
        val parent = src.parentFile ?: throw IOException("No parent folder")
        val name = (if (src.isDirectory) src.name else src.nameWithoutExtension.ifEmpty { src.name }) + ".zip"
        val out = uniqueTarget(parent, name)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            fun add(f: File, path: String) {
                if (f.isDirectory) {
                    val kids = f.listFiles() ?: emptyArray()
                    if (kids.isEmpty()) {
                        zos.putNextEntry(ZipEntry("$path/"))
                        zos.closeEntry()
                    }
                    kids.forEach { add(it, "$path/${it.name}") }
                } else if (f.absolutePath != out.absolutePath) {
                    zos.putNextEntry(ZipEntry(path))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            add(src, src.name)
        }
        return out
    }

    /** Puts every file/folder in `files` into ONE zip named zipName inside destDir. */
    fun zipMany(files: List<File>, destDir: File, zipName: String): File {
        val out = uniqueTarget(destDir, zipName)
        val used = HashSet<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            fun add(f: File, path: String) {
                if (f.isDirectory) {
                    val kids = f.listFiles() ?: emptyArray()
                    if (kids.isEmpty()) {
                        zos.putNextEntry(ZipEntry("$path/"))
                        zos.closeEntry()
                    }
                    kids.forEach { add(it, "$path/${it.name}") }
                } else if (f.absolutePath != out.absolutePath) {
                    zos.putNextEntry(ZipEntry(path))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            files.forEach { f ->
                var top = f.name
                var i = 1
                while (!used.add(top)) {
                    top = "${f.nameWithoutExtension} ($i)" + (if (f.extension.isEmpty()) "" else "." + f.extension)
                    i++
                }
                add(f, top)
            }
        }
        return out
    }

    fun unzip(zip: File): File {
        val parent = zip.parentFile ?: throw IOException("No parent folder")
        val dest = uniqueTarget(parent, zip.nameWithoutExtension.ifEmpty { "extracted" })
        dest.mkdirs()
        val base = dest.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(base + File.separator)) {
                    throw IOException("Blocked unsafe entry in archive")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return dest
    }

    /** Writes the whole tree (all sub-folders and their files) with size and creation date/time. */
    fun dirToText(dir: File): File {
        val out = uniqueTarget(dir, dir.name.ifEmpty { "storage" } + "_tree.txt")
        var folders = 0
        var files = 0
        var total = 0L
        out.bufferedWriter().use { w ->
            w.write("Directory tree: ${dir.absolutePath}\n")
            w.write("Exported: ${date(System.currentTimeMillis())}\n")
            w.write("Format: name  [size, created]\n\n")
            w.write("${dir.name.ifEmpty { "/" }}/\n")
            fun walk(d: File, prefix: String, depth: Int) {
                val kids = d.listFiles()
                    ?.filter { it.absolutePath != out.absolutePath }
                    ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: return
                kids.forEachIndexed { i, k ->
                    val last = i == kids.lastIndex
                    val branch = if (last) "└── " else "├── "
                    val e = entry(k)
                    if (e.isDir) {
                        folders++
                        w.write("$prefix$branch${k.name}/  [${date(e.created)}]\n")
                        if (depth < 40 && !isSymlink(k)) {
                            walk(k, prefix + if (last) "    " else "│   ", depth + 1)
                        }
                    } else {
                        files++
                        total += e.size
                        w.write("$prefix$branch${k.name}  [${size(e.size)}, ${date(e.created)}]\n")
                    }
                }
            }
            walk(dir, "", 0)
            w.write("\n$folders folders, $files files, ${size(total)} total\n")
        }
        return out
    }

    fun decode(f: File, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var s = 1
        while (bounds.outWidth / s > max || bounds.outHeight / s > max) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        return BitmapFactory.decodeFile(f.path, opts)
    }
}
