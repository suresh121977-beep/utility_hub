package com.filemanager.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun isViewable(f: File): Boolean {
    val e = FileOps.ext(f)
    return e in FileOps.imgExts || e in FileOps.vidExts || e in FileOps.audExts ||
        e == "pdf" || e in FileOps.textExts
}

@Composable
fun ViewerScreen(vm: FileViewModel, f: File) {
    val ctx = LocalContext.current
    val e = FileOps.ext(f)
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.viewer = null }) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(
                    f.name, Modifier.weight(1f), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { shareFile(ctx, f) }) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = {
                    if (!openExternal(ctx, f)) vm.say("No app can open this file")
                }) { Icon(Icons.Default.OpenInNew, "Open with") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    e in FileOps.imgExts -> ImageViewer(f)
                    e == "pdf" -> PdfViewer(f)
                    e in FileOps.vidExts || e in FileOps.audExts -> MediaViewer(f)
                    else -> TextViewer(f)
                }
            }
        }
    }
}

@Composable
fun ImageViewer(f: File) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) { FileOps.decode(f, 2048) }
    }
    var scale by remember { mutableStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                off = if (scale == 1f) Offset.Zero else off + pan
            }
        },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), f.name,
                Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y
                ),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
fun TextViewer(f: File) {
    val text by produceState("Loading…", f) {
        value = withContext(Dispatchers.IO) {
            try {
                val limit = 200 * 1024L
                val buf = ByteArray(minOf(f.length(), limit).toInt())
                var n = 0
                f.inputStream().use { s ->
                    while (n < buf.size) {
                        val r = s.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                }
                String(buf, 0, n) + if (f.length() > limit) "\n\n… (file truncated, showing first 200 KB)" else ""
            } catch (e: Exception) {
                "Cannot read this file: ${e.message}"
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SelectionContainer {
            Text(
                text, Modifier.padding(14.dp), fontFamily = FontFamily.Monospace,
                fontSize = 12.sp, color = TextMain
            )
        }
    }
}

class PdfDoc(f: File) {
    private val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    val count: Int = renderer.pageCount

    @Synchronized
    fun render(i: Int, width: Int): Bitmap {
        val page = renderer.openPage(i)
        try {
            val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.WHITE)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return b
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun close() {
        try {
            renderer.close()
            pfd.close()
        } catch (e: Exception) { /* ignore */ }
    }
}

@Composable
fun PdfViewer(f: File) {
    val doc = remember(f) { runCatching { PdfDoc(f) }.getOrNull() }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Cannot open this PDF") }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Color(0xFF3C4043)),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(doc.count) { i ->
            val page by produceState<Bitmap?>(null, i) {
                value = withContext(Dispatchers.IO) { runCatching { doc.render(i, 1080) }.getOrNull() }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                val b = page
                if (b != null) {
                    Image(
                        b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
fun MediaViewer(f: File) {
    val holder = remember { arrayOfNulls<VideoView>(1) }
    DisposableEffect(f) { onDispose { holder[0]?.stopPlayback() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val frame = FrameLayout(ctx)
            frame.setBackgroundColor(android.graphics.Color.BLACK)
            val vv = VideoView(ctx)
            holder[0] = vv
            frame.addView(
                vv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
            val mc = MediaController(ctx)
            mc.setAnchorView(vv)
            vv.setMediaController(mc)
            vv.setVideoURI(Uri.fromFile(f))
            vv.setOnPreparedListener { it.start() }
            frame
        }
    )
}
