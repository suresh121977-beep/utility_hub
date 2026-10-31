package com.allmediastudio.app

import android.content.ContentValues
import android.content.Intent
import android.app.DownloadManager
import android.webkit.URLUtil
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.setPadding
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {
    private var selectedVideo: Uri? = null
    private var selectedAudio: Uri? = null
    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var videoName: TextView
    private lateinit var audioName: TextView
    private var darkMode = true
    private var selectedOutputTree: Uri? = null
    private var outputFolderLabel: String = "Downloads/AllMediaStudio"
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedVideo = it; contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION); videoName.text = "Video: ${displayName(it)}"; preparePlayer(it); setStatus("Video selected") }
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedAudio = it; runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; audioName.text = "Audio: ${displayName(it)}"; setStatus("Audio selected") }
    }
    private var pendingMulti: ((List<Uri>) -> Unit)? = null
    private val pickMulti = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) setStatus("Nothing selected") else pendingMulti?.invoke(uris)
    }
    private val pickOutputFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            selectedOutputTree = uri
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            outputFolderLabel = DocumentFile.fromTreeUri(this, uri)?.name ?: "Selected folder"
            setStatus("Export folder selected: $outputFolderLabel")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }
    private fun buildUi() {
        val bg = if (darkMode) Color.rgb(18,18,24) else Color.rgb(247,245,252)
        val fg = if (darkMode) Color.WHITE else Color.rgb(25,25,32)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18); setBackgroundColor(bg) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(2) }
        val header = TextView(this).apply { text = "🎬 All Media Studio"; textSize = 27f; setTextColor(fg); gravity = Gravity.CENTER_VERTICAL; setPadding(4, 16, 4, 4); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        content.addView(header)
        content.addView(TextView(this).apply { text = "Edit • Convert • Extract • Create"; textSize = 14f; setTextColor(if (darkMode) 0xFFBDB5D8.toInt() else 0xFF625B71.toInt()); setPadding(4, 4, 4, 12) })
        content.addView(button("Switch to ${if (darkMode) "light" else "dark"} mode") { darkMode = !darkMode; buildUi() })
        content.addView(sectionTitle("Media selection", fg))
        content.addView(button("Choose video / open player") { pickVideo.launch(arrayOf("video/*")) })
        videoName = TextView(this).apply { text = "No video selected"; setTextColor(fg); setPadding(8) }; content.addView(videoName)
        content.addView(button("Choose audio file") { pickAudio.launch(arrayOf("audio/*")) })
        audioName = TextView(this).apply { text = "No audio selected"; setTextColor(fg); setPadding(8) }; content.addView(audioName)
        content.addView(sectionTitle("Video tools", fg))
        content.addView(button("Cut / trim video by time") { showTrimDialog(false) })
        content.addView(button("Mute video (remove audio)") { exportVideo(removeAudio = true, removeVideo = false) })
        content.addView(button("Extract audio from video") { exportVideo(removeAudio = false, removeVideo = true) })
        content.addView(button("Create image from selected time frame") { showFrameDialog() })
        content.addView(button("Split video into time-based clips") { showClipDialog() })
        content.addView(sectionTitle("Audio, conversion & creative tools", fg))
        content.addView(button("Audio cutter (trim to M4A)") { showAudioCutDialog() })
        content.addView(button("Audio merger (join files in order)") { pendingMulti = { mergeAudio(it) }; setStatus("Select 2 or more audio files"); pickMulti.launch(arrayOf("audio/*")) })
        content.addView(button("Audio mixer (play files together)") { pendingMulti = { mixAudio(it) }; setStatus("Select 2 or more audio files"); pickMulti.launch(arrayOf("audio/*")) })
        content.addView(button("Video merger + filter") { showVideoMergeDialog() })
        content.addView(button("Add / mix audio into video") { showAddAudioDialog() })
        content.addView(button("Video to GIF maker") { showGifDialog() })
        content.addView(button("Video / audio format converter") { showConvertDialog() })
        content.addView(button("Compress video") { showCompressDialog() })
        content.addView(button("Media URL downloader") { showUrlDownloader() })
        content.addView(sectionTitle("Output & status", fg))
        content.addView(TextView(this).apply { text = "Export location: $outputFolderLabel"; setTextColor(fg); setPadding(8) })
        content.addView(button("Choose export folder") { pickOutputFolder.launch(null) })
        content.addView(button("Use public Downloads folder") { selectedOutputTree = null; outputFolderLabel = "Downloads/AllMediaStudio"; buildUi(); setStatus("Exports will be saved in Downloads/AllMediaStudio") })
        content.addView(button("Search YouTube by words") { showYouTubeSearch() })
        content.addView(button("Open exported media folder") { openExports() })
        status = TextView(this).apply { text = "Ready"; textSize = 14f; setTextColor(fg); setPadding(10, 14, 10, 24) }; content.addView(status)
        scroll.addView(content); root.addView(scroll); setContentView(root)
    }
    private fun sectionTitle(s: String, color: Int) = TextView(this).apply { text = s; textSize = 19f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(color); setPadding(4, 20, 4, 8) }
    private fun button(label: String, action: () -> Unit): View = MaterialButton(this).apply { text = label; isAllCaps = false; setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 } }
    private fun setStatus(s: String) { if (::status.isInitialized) status.text = s }
    private fun displayName(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString().takeLast(35)
    private fun preparePlayer(uri: Uri) {
        player?.release(); player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        val pv = androidx.media3.ui.PlayerView(this).apply { this.player = this@MainActivity.player; layoutParams = LinearLayout.LayoutParams(-1, 220) }
        (root.getChildAt(0) as? ScrollView)?.let { scroll -> (scroll.getChildAt(0) as? LinearLayout)?.let { content ->
            val old = content.findViewWithTag<View>("player-preview"); if (old != null) content.removeView(old)
            pv.tag = "player-preview"; content.addView(pv, 3)
        } }
        player?.setMediaItem(MediaItem.fromUri(uri)); player?.prepare(); player?.playWhenReady = false
    }
    private fun showTrimDialog(mute: Boolean) {
        if (selectedVideo == null) { setStatus("Choose a video first"); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20) }
        val start = EditText(this).apply { hint = "Start time (seconds)"; inputType = 8194; setText("0") }
        val end = EditText(this).apply { hint = "End time (seconds)"; inputType = 8194; setText("10") }
        layout.addView(start); layout.addView(end)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Trim video").setView(layout).setNegativeButton("Cancel", null).setPositiveButton("Export") { _, _ ->
            val a = start.text.toString().toLongOrNull() ?: 0L; val b = end.text.toString().toLongOrNull() ?: 0L
            if (a < 0 || b <= a) setStatus("End time must be greater than start time") else exportVideo(startMs = a * 1000, endMs = b * 1000)
        }.show()
    }
    private fun showFrameDialog() {
        val uri = selectedVideo ?: run { setStatus("Choose a video first"); return }
        val input = EditText(this).apply { hint = "Time in seconds"; inputType = 8194; setText("1") }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Extract frame") .setMessage("Save one JPG image at this timestamp.").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save image") { _, _ ->
            val sec = input.text.toString().toLongOrNull() ?: 0L
            try {
                val retriever = MediaMetadataRetriever(); retriever.setDataSource(this, uri)
                val bitmap = retriever.getFrameAtTime(sec * 1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (bitmap == null) { setStatus("No frame found at that time"); return@setPositiveButton }
                val name = "AMS_frame_${stamp()}.jpg"
                val temp = File(cacheDir, name)
                FileOutputStream(temp).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out) }
                bitmap.recycle()
                val saved = publishExport(temp, name, "image/jpeg")
                setStatus("Saved $saved")
            } catch (e: Exception) { setStatus("Frame extraction failed: ${e.message}") }
        }.show()
    }
    private fun showClipDialog() {
        if (selectedVideo == null) { setStatus("Choose a video first"); return }
        val input = EditText(this).apply { hint = "Clip length in seconds"; inputType = 8194; setText("15") }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Split into clips").setMessage("Exports the first clip for the selected duration. Batch splitting into all clips is planned.").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Export first clip") { _, _ -> val sec = input.text.toString().toLongOrNull() ?: 0; if (sec > 0) exportVideo(startMs = 0, endMs = sec * 1000) else setStatus("Enter a positive duration") }.show()
    }
    private fun exportVideo(startMs: Long = 0, endMs: Long? = null, removeAudio: Boolean = false, removeVideo: Boolean = false) {
        val uri = selectedVideo ?: run { setStatus("Choose a video first"); return }
        try {
            val clip = MediaItem.Builder().setUri(uri).apply { if (endMs != null) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()) }.build()
            val edited = EditedMediaItem.Builder(clip).setRemoveAudio(removeAudio).setRemoveVideo(removeVideo).build()
            val name = "AMS_${if (removeVideo) "audio" else if (removeAudio) "muted" else "video"}_${stamp()}.${if (removeVideo) "m4a" else "mp4"}"
            val file = File(cacheDir, name)
            setStatus("Exporting… please keep the app open")
            val transformer = Transformer.Builder(this).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) { runOnUiThread { try { val saved = publishExport(file, name, if (removeVideo) "audio/mp4" else "video/mp4"); setStatus("Export complete: $saved") } catch (e: Exception) { setStatus("Export finished but could not save to Downloads: ${e.message}") } } }
                override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) { runOnUiThread { setStatus("Export failed: ${exportException.message}") } }
            }).build()
            transformer.start(edited, file.absolutePath)
        } catch (e: Exception) { setStatus("Could not start export: ${e.message}") }
    }

    // ---------- shared helpers for new tools ----------
    private fun secs(e: EditText) = e.text.toString().trim().toDoubleOrNull()
    private fun numField(hintText: String, def: String) = EditText(this).apply { hint = hintText; inputType = 8194; setText(def) }
    private fun editedItem(uri: Uri, startMs: Long = 0, endMs: Long? = null, audioOnly: Boolean = false, effects: Effects? = null, muted: Boolean = false): EditedMediaItem {
        val clip = MediaItem.Builder().setUri(uri).apply {
            if (endMs != null) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
        }.build()
        return EditedMediaItem.Builder(clip).setRemoveVideo(audioOnly).setRemoveAudio(muted).apply { if (effects != null) setEffects(effects) }.build()
    }
    private fun single(item: EditedMediaItem): Composition = Composition.Builder(listOf(EditedMediaItemSequence(listOf(item)))).build()
    private fun videoEffects(height: Int?, extra: Effect? = null): Effects {
        val list = mutableListOf<Effect>()
        if (height != null) list.add(Presentation.createForHeight(height))
        if (extra != null) list.add(extra)
        return Effects(listOf<AudioProcessor>(), list)
    }
    private fun runTransform(composition: Composition, name: String, mime: String, audioMime: String? = null, videoMime: String? = null, encoderFactory: Codec.EncoderFactory? = null) {
        try {
            val file = File(cacheDir, name); if (file.exists()) file.delete()
            setStatus("Processing… please keep the app open")
            val b = Transformer.Builder(this)
            if (audioMime != null) b.setAudioMimeType(audioMime)
            if (videoMime != null) b.setVideoMimeType(videoMime)
            if (encoderFactory != null) b.setEncoderFactory(encoderFactory)
            b.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { runOnUiThread { try { setStatus("Done: ${publishExport(file, name, mime)}") } catch (e: Exception) { setStatus("Finished but could not save: ${e.message}") } } }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { runOnUiThread { setStatus("Failed [${exportException.errorCodeName}]: ${exportException.message}" + (exportException.cause?.message?.let { " | cause: $it" } ?: "")) } }
            })
            b.build().start(composition, file.absolutePath)
        } catch (e: Exception) { setStatus("Could not start: ${e.message}") }
    }

    // ---------- audio tools ----------
    private fun showAudioCutDialog() {
        val uri = selectedAudio ?: selectedVideo ?: run { setStatus("Choose an audio file (or a video) first"); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20) }
        val start = numField("Start (seconds)", "0"); val end = numField("End (seconds)", "30")
        layout.addView(start); layout.addView(end)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Audio cutter").setView(layout).setNegativeButton("Cancel", null).setPositiveButton("Cut") { _, _ ->
            val a = secs(start); val b = secs(end)
            if (a == null || b == null || a < 0 || b <= a) { setStatus("End time must be greater than start time"); return@setPositiveButton }
            runTransform(single(editedItem(uri, (a * 1000).toLong(), (b * 1000).toLong(), audioOnly = true)), "AMS_audio_cut_${stamp()}.m4a", "audio/mp4", audioMime = MimeTypes.AUDIO_AAC)
        }.show()
    }
    private fun mergeAudio(uris: List<Uri>) {
        if (uris.size < 2) { setStatus("Select at least 2 audio files"); return }
        val seq = EditedMediaItemSequence(uris.map { editedItem(it, audioOnly = true) })
        runTransform(Composition.Builder(listOf(seq)).build(), "AMS_audio_merged_${stamp()}.m4a", "audio/mp4", audioMime = MimeTypes.AUDIO_AAC)
    }
    // Own mixer: decode every file to 44.1 kHz stereo PCM, sum, then encode to AAC/M4A.
    private fun mixAudio(uris: List<Uri>) {
        if (uris.size < 2) { setStatus("Select at least 2 audio files"); return }
        setStatus("Mixing… this can take a minute, keep the app open")
        Thread {
            try {
                val tracks = uris.mapIndexed { i, u -> runOnUiThread { setStatus("Decoding ${i + 1}/${uris.size}…") }; decodeToStereo44k(u) }
                val len = tracks.maxOf { it.size }
                val scale = 1f / sqrt(tracks.size.toFloat())
                val mix = ShortArray(len)
                for (i in 0 until len) {
                    var sum = 0
                    for (t in tracks) if (i < t.size) sum += t[i]
                    mix[i] = (sum * scale).toInt().coerceIn(-32768, 32767).toShort()
                }
                runOnUiThread { setStatus("Encoding…") }
                val name = "AMS_audio_mix_${stamp()}.m4a"; val tmp = File(cacheDir, name)
                encodeAac(mix, tmp)
                val saved = publishExport(tmp, name, "audio/mp4")
                runOnUiThread { setStatus("Mix saved: $saved") }
            } catch (e: Throwable) { runOnUiThread { setStatus("Mix failed: ${e.message ?: e.javaClass.simpleName}") } }
        }.start()
    }
    private fun decodeToStereo44k(uri: Uri): ShortArray {
        val ex = MediaExtractor(); ex.setDataSource(this, uri, null)
        var track = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) { val f = ex.getTrackFormat(i); if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break } }
        if (track < 0 || fmt == null) throw Exception("No audio track in ${displayName(uri)}")
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var buf = ShortArray(1 shl 20); var n = 0
        while (!outDone) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    val sz = ex.readSampleData(ib, 0)
                    if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat; sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (oi >= 0) {
                val ob = codec.getOutputBuffer(oi)!!
                ob.position(info.offset); ob.limit(info.offset + info.size)
                val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                val cnt = sb.remaining()
                if (n + cnt > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + cnt))
                sb.get(buf, n, cnt); n += cnt
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        if (ch < 1) ch = 2
        val frames = n / ch
        if (frames == 0) throw Exception("Could not decode ${displayName(uri)}")
        val stereo = ShortArray(frames * 2)
        for (i in 0 until frames) { val l = buf[i * ch]; val r = if (ch > 1) buf[i * ch + 1] else l; stereo[2 * i] = l; stereo[2 * i + 1] = r }
        if (sr == 44100) return stereo
        val outFrames = (frames.toLong() * 44100 / sr).toInt()
        val out = ShortArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val pos = i.toDouble() * sr / 44100
            val i0 = pos.toInt().coerceAtMost(frames - 1); val i1 = (i0 + 1).coerceAtMost(frames - 1); val fr = pos - i0
            for (c in 0..1) out[2 * i + c] = (stereo[2 * i0 + c] * (1 - fr) + stereo[2 * i1 + c] * fr).toInt().toShort()
        }
        return out
    }
    private fun encodeAac(pcm: ShortArray, outFile: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        val info = MediaCodec.BufferInfo(); var pos = 0; var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val ii = enc.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val ib = enc.getInputBuffer(ii)!!; ib.clear()
                    val cnt = minOf(minOf(ib.capacity() / 2, 8192) and 1.inv(), pcm.size - pos)
                    val pts = (pos / 2).toLong() * 1_000_000 / 44100
                    if (cnt <= 0) { enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, cnt); enc.queueInputBuffer(ii, 0, cnt * 2, pts, 0); pos += cnt }
                }
            }
            val oi = enc.dequeueOutputBuffer(info, 10000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(enc.outputFormat); muxer.start(); started = true }
            else if (oi >= 0) {
                val ob = enc.getOutputBuffer(oi)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); muxer.writeSampleData(track, ob, info) }
                enc.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        enc.stop(); enc.release(); muxer.stop(); muxer.release()
    }

    // ---------- add / mix audio into a video (no Media3 mixing) ----------
    private fun showAddAudioDialog() {
        val video = selectedVideo ?: run { setStatus("First tap \"Choose video\" and pick the (muted) merged video"); return }
        val keep = CheckBox(this).apply { text = "Also keep the video's original audio" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20); addView(keep) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Add audio to video")
            .setMessage("Uses the video chosen under Media selection. Next, select one or more audio files (music, voice). They are mixed together and trimmed to the video length.")
            .setView(box).setNegativeButton("Cancel", null).setPositiveButton("Choose audio files") { _, _ ->
                val keepOriginal = keep.isChecked
                pendingMulti = { uris -> addAudioToVideo(video, uris, keepOriginal) }
                pickMulti.launch(arrayOf("audio/*"))
            }.show()
    }
    private fun addAudioToVideo(video: Uri, audioUris: List<Uri>, keepOriginal: Boolean) {
        setStatus("Preparing audio… keep the app open")
        Thread {
            try {
                val r = MediaMetadataRetriever(); r.setDataSource(this, video)
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val hasOwn = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                r.release()
                val sources = mutableListOf<Uri>()
                if (keepOriginal && hasOwn) sources.add(video)
                sources.addAll(audioUris)
                val tracks = sources.mapIndexed { i, u -> runOnUiThread { setStatus("Decoding audio ${i + 1}/${sources.size}…") }; decodeToStereo44k(u) }
                var len = tracks.maxOf { it.size }
                if (durMs > 0) len = minOf(len, (durMs * 44100 / 1000).toInt() * 2)
                val scale = if (tracks.size > 1) 1f / sqrt(tracks.size.toFloat()) else 1f
                val mix = ShortArray(len)
                for (i in 0 until len) {
                    var sum = 0
                    for (t in tracks) if (i < t.size) sum += t[i]
                    mix[i] = (sum * scale).toInt().coerceIn(-32768, 32767).toShort()
                }
                runOnUiThread { setStatus("Encoding audio…") }
                val aac = File(cacheDir, "mix_${stamp()}.m4a"); encodeAac(mix, aac)
                runOnUiThread { setStatus("Combining with video…") }
                val name = "AMS_video_with_audio_${stamp()}.mp4"; val tmp = File(cacheDir, name)
                muxVideoWithAudio(video, aac, tmp, rot)
                aac.delete()
                val saved = publishExport(tmp, name, "video/mp4")
                runOnUiThread { setStatus("Saved: $saved") }
            } catch (e: Throwable) { runOnUiThread { setStatus("Add audio failed: ${e.message ?: e.javaClass.simpleName}") } }
        }.start()
    }
    private fun muxVideoWithAudio(video: Uri, audioFile: File, out: File, rotation: Int) {
        val vx = MediaExtractor(); vx.setDataSource(this, video, null)
        var vt = -1
        for (i in 0 until vx.trackCount) if (vx.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vt = i; break }
        if (vt < 0) throw Exception("No video track found")
        val ax = MediaExtractor(); ax.setDataSource(audioFile.absolutePath)
        var at = -1
        for (i in 0 until ax.trackCount) if (ax.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { at = i; break }
        if (at < 0) throw Exception("Audio encoding produced no track")
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        val vi = muxer.addTrack(vx.getTrackFormat(vt)); val ai = muxer.addTrack(ax.getTrackFormat(at))
        muxer.start()
        val buf = ByteBuffer.allocateDirect(8 * 1024 * 1024); val info = MediaCodec.BufferInfo()
        fun copy(ex: MediaExtractor, track: Int, idx: Int) {
            ex.selectTrack(track)
            while (true) {
                buf.clear()
                val sz = ex.readSampleData(buf, 0); if (sz < 0) break
                info.set(0, sz, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(idx, buf, info); ex.advance()
            }
        }
        copy(vx, vt, vi); copy(ax, at, ai)
        muxer.stop(); muxer.release(); vx.release(); ax.release()
    }

    // ---------- video tools ----------
    private fun showVideoMergeDialog() {
        pendingMulti = { uris ->
            if (uris.size < 2) setStatus("Select at least 2 videos") else showMergeOrderDialog(uris.toMutableList())
        }
        setStatus("Select 2 or more videos")
        pickMulti.launch(arrayOf("video/*"))
    }
    private fun showMergeOrderDialog(items: MutableList<Uri>) {
        val filterNames = arrayOf("No filter", "Black & white", "Inverted colours", "Brighter", "Higher contrast")
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, filterNames) }
        fun render() {
            list.removeAllViews()
            items.forEachIndexed { i, uri ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply { text = "${i + 1}. ${displayName(uri)}"; maxLines = 2; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                row.addView(Button(this).apply { text = "↑"; isEnabled = i > 0; setOnClickListener { items.add(i - 1, items.removeAt(i)); render() } })
                row.addView(Button(this).apply { text = "↓"; isEnabled = i < items.size - 1; setOnClickListener { items.add(i + 1, items.removeAt(i)); render() } })
                list.addView(row)
            }
        }
        render()
        outer.addView(TextView(this).apply { text = "Order (top plays first):" })
        outer.addView(list)
        outer.addView(TextView(this).apply { text = "Filter:"; setPadding(0, 16, 0, 0) })
        outer.addView(spinner)
        val muteBox = CheckBox(this).apply { text = "Mute merged video (more reliable)" }
        outer.addView(muteBox)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Merge videos").setView(ScrollView(this).apply { addView(outer) })
            .setNegativeButton("Cancel", null).setPositiveButton("Merge") { _, _ ->
                val filter: Effect? = when (spinner.selectedItemPosition) {
                    1 -> RgbFilter.createGrayscaleFilter()
                    2 -> RgbFilter.createInvertedFilter()
                    3 -> Brightness(0.2f)
                    4 -> Contrast(0.4f)
                    else -> null
                }
                val allHaveAudio = items.all { u ->
                    runCatching { val r = MediaMetadataRetriever(); r.setDataSource(this, u); val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"; r.release(); h }.getOrDefault(false)
                }
                val mute = muteBox.isChecked || !allHaveAudio
                val seq = EditedMediaItemSequence(items.map { editedItem(it, effects = videoEffects(720, filter), muted = mute) })
                runTransform(Composition.Builder(listOf(seq)).build(), "AMS_merged_${stamp()}.mp4", "video/mp4")
                if (!allHaveAudio && !muteBox.isChecked) setStatus("Some clips have no audio, so the merged video is muted. Processing…")
            }.show()
    }
    private fun showConvertDialog() {
        val names = arrayOf("Video → MP4 (H.264)", "Video → MP4 (H.265, smaller)", "Audio or video → M4A (AAC audio)")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Convert").setItems(names) { _, which ->
            if (which == 2) {
                val uri = selectedAudio ?: selectedVideo ?: run { setStatus("Choose an audio file or video first"); return@setItems }
                runTransform(single(editedItem(uri, audioOnly = true)), "AMS_converted_${stamp()}.m4a", "audio/mp4", audioMime = MimeTypes.AUDIO_AAC)
            } else {
                val uri = selectedVideo ?: run { setStatus("Choose a video first"); return@setItems }
                runTransform(single(editedItem(uri)), "AMS_converted_${stamp()}.mp4", "video/mp4", videoMime = if (which == 1) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun showCompressDialog() {
        val uri = selectedVideo ?: run { setStatus("Choose a video first"); return }
        val names = arrayOf("720p – good quality (2.5 Mbps)", "480p – balanced (1 Mbps)", "360p – smallest (0.6 Mbps)")
        val heights = intArrayOf(720, 480, 360); val rates = intArrayOf(2_500_000, 1_000_000, 600_000)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Compress video").setItems(names) { _, i ->
            val factory = DefaultEncoderFactory.Builder(this).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(rates[i]).build()).build()
            runTransform(single(editedItem(uri, effects = videoEffects(heights[i]))), "AMS_compressed_${stamp()}.mp4", "video/mp4", encoderFactory = factory)
        }.setNegativeButton("Cancel", null).show()
    }
    private fun showGifDialog() {
        val uri = selectedVideo ?: run { setStatus("Choose a video first"); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20) }
        val start = numField("Start (seconds)", "0"); val len = numField("Length (seconds, max 10)", "3"); val fps = numField("Frames per second (1-12)", "8")
        layout.addView(start); layout.addView(len); layout.addView(fps)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Video to GIF").setView(layout).setNegativeButton("Cancel", null).setPositiveButton("Create GIF") { _, _ ->
            val s = secs(start) ?: 0.0; val l = (secs(len) ?: 3.0).coerceIn(0.5, 10.0); val f = (secs(fps) ?: 8.0).toInt().coerceIn(1, 12)
            makeGif(uri, s, l, f)
        }.show()
    }
    private fun makeGif(uri: Uri, startSec: Double, lenSec: Double, fps: Int) {
        setStatus("Creating GIF…")
        Thread {
            try {
                val r = MediaMetadataRetriever(); r.setDataSource(this, uri)
                var vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 320
                var vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 240
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = vw; vw = vh; vh = t }
                val w = minOf(vw, 320); val h = maxOf(2, vh * w / vw)
                val name = "AMS_gif_${stamp()}.gif"; val tmp = File(cacheDir, name)
                val frames = (lenSec * fps).toInt().coerceAtLeast(1)
                var enc: GifEncoder? = null
                FileOutputStream(tmp).buffered().use { os ->
                    for (i in 0 until frames) {
                        val t = ((startSec + i.toDouble() / fps) * 1_000_000).toLong()
                        val bmp = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h) ?: continue
                        if (enc == null) enc = GifEncoder(os, bmp.width, bmp.height, 100 / fps)
                        enc!!.addFrame(bmp); bmp.recycle()
                        runOnUiThread { setStatus("Creating GIF… ${i + 1}/$frames") }
                    }
                    if (enc == null) throw Exception("No frames found at that time")
                    enc!!.finish()
                }
                r.release()
                val saved = publishExport(tmp, name, "image/gif")
                runOnUiThread { setStatus("GIF saved: $saved") }
            } catch (e: Exception) { runOnUiThread { setStatus("GIF failed: ${e.message}") } }
        }.start()
    }
    private fun info(title: String, message: String) = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    private fun publishExport(source: File, name: String, mime: String): String {
        val tree = selectedOutputTree
        if (tree != null) {
            val rootDoc = DocumentFile.fromTreeUri(this, tree) ?: throw Exception("Selected folder is no longer available")
            val out = rootDoc.createFile(mime, name) ?: throw Exception("Cannot create file in selected folder")
            contentResolver.openOutputStream(out.uri, "w").use { dst -> source.inputStream().use { src -> src.copyTo(dst ?: throw Exception("Cannot open selected folder output")) } }
            source.delete()
            return "$outputFolderLabel/$name"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AllMediaStudio")
        }
        val collection = if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values) ?: throw Exception("Could not create output in Downloads")
        contentResolver.openOutputStream(uri, "w").use { dst -> source.inputStream().use { src -> src.copyTo(dst ?: throw Exception("Cannot open Downloads output")) } }
        source.delete()
        return "Downloads/AllMediaStudio/$name"
    }
    private fun showUrlDownloader() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20) }
        val url = EditText(this).apply { hint = "Direct media URL (MP4, MP3, etc.)"; inputType = 17 }
        box.addView(url)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Download media from URL")
            .setMessage("Paste a direct link to a media file you are allowed to download. YouTube page links are not supported.")
            .setView(box).setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ ->
                val link = url.text.toString().trim()
                val parsed = Uri.parse(link)
                val host = (parsed.host ?: "").lowercase()
                if (parsed.scheme != "https" && parsed.scheme != "http") { setStatus("Enter a valid http/https URL"); return@setPositiveButton }
                if (host.contains("youtube.com") || host == "youtu.be" || host.endsWith(".youtu.be")) {
                    info("YouTube links can't be downloaded here", "YouTube doesn't provide a direct file for its pages, and this app does not bypass its restrictions. Use the Download button inside the YouTube app (Premium) or a direct file link from the creator.")
                    return@setPositiveButton
                }
                setStatus("Checking link…")
                Thread {
                    var mime: String? = null; var disp: String? = null
                    try {
                        val c = java.net.URL(link).openConnection() as java.net.HttpURLConnection
                        c.requestMethod = "HEAD"; c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
                        mime = c.contentType?.substringBefore(';')?.trim(); disp = c.getHeaderField("Content-Disposition"); c.disconnect()
                    } catch (_: Exception) { }
                    runOnUiThread {
                        if (mime != null && (mime!!.startsWith("text/") || mime == "application/json")) { setStatus("That link is a web page, not a media file."); return@runOnUiThread }
                        try {
                            var fname = URLUtil.guessFileName(link, disp, mime)
                            if (fname.endsWith(".bin") && mime != null) { val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime); if (ext != null) fname = fname.removeSuffix(".bin") + "." + ext }
                            val request = DownloadManager.Request(parsed).setTitle(fname)
                                .setDescription("All Media Studio download")
                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                            if (mime != null) request.setMimeType(mime)
                            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AllMediaStudio/$fname")
                            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                            setStatus("Download queued: Downloads/AllMediaStudio/$fname")
                        } catch (e: Exception) { setStatus("Download could not start: ${e.message}") }
                    }
                }.start()
            }.show()
    }
    private fun showYouTubeSearch() {
        val input = EditText(this).apply { hint = "Search words" }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Search YouTube")
            .setMessage("Opens YouTube search. Downloading YouTube streams through unofficial extraction is not included; use YouTube's own offline/download options where available or direct downloads explicitly offered by the creator.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Search") { _, _ ->
                val q = android.net.Uri.encode(input.text.toString().trim())
                if (q.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")))
            }.show()
    }
    private fun openExports() {
        val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FAllMediaStudio"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { startActivity(intent) } catch (_: Exception) { info("Export location", "Your exported files are saved in the public Downloads/AllMediaStudio folder. You can open the Downloads app or Files app to view them.") }
    }
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
