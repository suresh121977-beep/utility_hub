package com.allmediastudio.app

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Minimal GIF89a encoder (fixed 3-3-2 colour palette, looping). */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int, private val delayCs: Int) {
    private var started = false

    private fun w16(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }

    private fun header() {
        out.write("GIF89a".toByteArray())
        w16(width); w16(height)
        out.write(0xF7); out.write(0); out.write(0)           // global palette, 256 colours
        for (i in 0 until 256) {
            out.write(((i shr 5) and 7) * 255 / 7)
            out.write(((i shr 2) and 7) * 255 / 7)
            out.write((i and 3) * 255 / 3)
        }
        out.write(0x21); out.write(0xFF); out.write(11)       // looping extension
        out.write("NETSCAPE2.0".toByteArray())
        out.write(3); out.write(1); w16(0); out.write(0)
        started = true
    }

    fun addFrame(src: Bitmap) {
        if (!started) header()
        val bmp = if (src.width != width || src.height != height) Bitmap.createScaledBitmap(src, width, height, true) else src
        val px = IntArray(width * height)
        bmp.getPixels(px, 0, width, 0, 0, width, height)
        val idx = ByteArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            idx[i] = (((r shr 5) shl 5) or ((g shr 5) shl 2) or (b shr 6)).toByte()
        }
        out.write(0x21); out.write(0xF9); out.write(4); out.write(0); w16(delayCs); out.write(0); out.write(0)
        out.write(0x2C); w16(0); w16(0); w16(width); w16(height); out.write(0)
        out.write(8)
        val data = lzw(idx)
        var pos = 0
        while (pos < data.size) {
            val n = minOf(255, data.size - pos)
            out.write(n); out.write(data, pos, n); pos += n
        }
        out.write(0)
        if (bmp !== src) bmp.recycle()
    }

    fun finish() { if (!started) header(); out.write(0x3B); out.flush() }

    private fun lzw(pixels: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        var cur = 0; var nbits = 0
        fun put(code: Int, size: Int) {
            cur = cur or (code shl nbits); nbits += size
            while (nbits >= 8) { bytes.write(cur and 255); cur = cur ushr 8; nbits -= 8 }
        }
        val clear = 256; val eoi = 257
        var codeSize = 9; var next = 258
        val dict = HashMap<Int, Int>()
        put(clear, codeSize)
        var prefix = pixels[0].toInt() and 255
        for (i in 1 until pixels.size) {
            val c = pixels[i].toInt() and 255
            val key = (prefix shl 8) or c
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            put(prefix, codeSize)
            if (next < 4096) {
                dict[key] = next
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                next++
            } else {
                put(clear, codeSize); dict.clear(); next = 258; codeSize = 9
            }
            prefix = c
        }
        put(prefix, codeSize)
        put(eoi, codeSize)
        if (nbits > 0) bytes.write(cur and 255)
        return bytes.toByteArray()
    }
}
