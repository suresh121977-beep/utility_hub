package com.example.alltools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Menu 1 (Magnify) and Menu 2 (Screen Copy) in ONE accessibility service,
 * so the phone only needs a single "turn on" in Accessibility settings.
 * The switches in the app (prefs "magnify_on" / "copy_on") show or hide each tool live.
 */
class ToolsService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "magnify_on" || key == "copy_on") handler.post { syncOverlays() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("s", 0)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        syncOverlays()
    }

    private fun syncOverlays() {
        if (prefs.getBoolean("magnify_on", false)) showMagnify() else hideMagnify()
        if (prefs.getBoolean("copy_on", false)) showCopy() else hideCopy()
    }

    // =====================================================================
    //  MENU 1: MAGNIFY
    // =====================================================================

    private var mRoot: LinearLayout? = null
    private var mPanel: LinearLayout? = null
    private var mLp: LP? = null

    private var mScale = 1.5f
    private var mZoomed = false
    private val minScale = 1.1f
    private val maxScale = 4f
    private val zoomStep = 0.25f
    private val panStep = 120f

    private fun makeBtn(label: String, sizeDp: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1565C0"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        }

    private fun holdable(v: View, action: () -> Unit) {
        val r = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 70)
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    handler.postDelayed(r, 350)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    handler.removeCallbacks(r)
            }
            true
        }
    }

    private fun showMagnify() {
        if (mRoot != null) return
        try {
            val main = makeBtn("🔍", 56)

            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
            }
            val items = listOf<Pair<String, () -> Unit>>(
                "−" to { changeZoom(-zoomStep) },
                "+" to { changeZoom(zoomStep) },
                "◀" to { pan(-1f, 0f) },
                "▲" to { pan(0f, -1f) },
                "▼" to { pan(0f, 1f) },
                "▶" to { pan(1f, 0f) }
            )
            for ((label, action) in items) {
                val b = makeBtn(label, 44)
                holdable(b, action)
                panel.addView(b)
            }

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(main)
                addView(panel)
            }

            val lp = LP(
                LP.WRAP_CONTENT, LP.WRAP_CONTENT,
                LP.TYPE_ACCESSIBILITY_OVERLAY,
                LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 400
            }

            main.setOnTouchListener(DragTapListener(root, lp))
            wm.addView(root, lp)
            mRoot = root
            mPanel = panel
            mLp = lp
        } catch (e: Exception) {
            toast("Magnify error: " + e.message)
        }
    }

    private fun hideMagnify() {
        mRoot?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
        }
        mRoot = null
        mPanel = null
        mLp = null
        if (mZoomed) {
            mZoomed = false
            try { magnificationController.reset(true) } catch (e: Exception) { }
        }
    }

    private fun toggleZoom() {
        mZoomed = !mZoomed
        val c = magnificationController
        if (mZoomed) {
            val m = resources.displayMetrics
            c.setScale(mScale, true)
            c.setCenter(m.widthPixels / 2f, m.heightPixels / 2f, true)
            mPanel?.visibility = View.VISIBLE
        } else {
            c.reset(true)
            mPanel?.visibility = View.GONE
        }
    }

    private fun changeZoom(delta: Float) {
        mScale = (mScale + delta).coerceIn(minScale, maxScale)
        if (mZoomed) magnificationController.setScale(mScale, true)
    }

    private fun pan(dx: Float, dy: Float) {
        if (!mZoomed) return
        val c = magnificationController
        val step = panStep / mScale
        c.setCenter(c.centerX + dx * step, c.centerY + dy * step, false)
    }

    // Tap = zoom on/off, drag = move the buttons
    private inner class DragTapListener(
        private val target: View,
        private val lp: LP
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 15 || abs(dy) > 15) {
                        moved = true
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm.updateViewLayout(target, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggleZoom()
            }
            return true
        }
    }

    // =====================================================================
    //  MENU 2: SCREEN COPY
    // =====================================================================

    private var copyBar: View? = null
    private var copyPanel: View? = null
    private var running = false
    private var addBtn: TextView? = null
    private val collected = mutableListOf<List<Row>>()

    private data class TextItem(val text: String, val top: Int, val left: Int)
    private data class Row(val text: String, val top: Int)

    private fun showCopy() {
        if (copyBar != null) return
        try {
            fun btn(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(color)
                setPadding(32, 24, 32, 24)
                setOnClickListener { onClick() }
            }
            fun lp() = LinearLayout.LayoutParams(LP.WRAP_CONTENT, LP.WRAP_CONTENT).apply { topMargin = 10 }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(btn("Copy", 0xCC1A73E8.toInt()) { showCopyPanel() }, lp())
            val add = btn("+ Page", 0xCC188038.toInt()) { addPage() }
            addBtn = add
            col.addView(add, lp())
            col.addView(btn("Done", 0xCCD93025.toInt()) { donePages() }, lp())
            wm.addView(col, params(LP.WRAP_CONTENT, LP.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            })
            copyBar = col
        } catch (e: Exception) {
            toast("Screen Copy error: " + e.message)
        }
    }

    private fun hideCopy() {
        closeCopyPanel()
        copyBar?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
        }
        copyBar = null
        addBtn = null
        collected.clear()
    }

    // ---- Manual mode: scroll yourself, tap "+ Page" on each screen, then "Done" ----

    private fun addPage() {
        val page = pageOf(rootInActiveWindow)
        if (page.isEmpty()) { toast("No readable text on this screen"); return }
        val prev = collected.lastOrNull()
        if (prev != null && page == prev) { toast("Same screen. Scroll down first."); return }
        val gap = prev != null && page.none { r -> prev.any { it.text == r.text } }
        collected.add(page)
        addBtn?.text = "+ Page (${collected.size})"
        toast(
            if (gap) "Page ${collected.size} added, but no overlap: you may have skipped rows. Scroll back a little and add again."
            else "Page ${collected.size} added. Scroll, then tap + Page."
        )
    }

    private fun donePages() {
        if (collected.isEmpty()) { toast("Nothing added yet. Tap + Page first."); return }
        finishCopy(collected.toList())
        collected.clear()
        addBtn?.text = "+ Page"
    }

    private fun params(w: Int, h: Int) =
        LP(w, h, LP.TYPE_ACCESSIBILITY_OVERLAY, LP.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<TextItem>) {
        if (n == null) return
        val t = (n.text ?: n.contentDescription)?.toString()?.trim()
        if (!t.isNullOrEmpty() && n.isVisibleToUser) {
            val r = Rect(); n.getBoundsInScreen(r)
            out.add(TextItem(t, r.top, r.left))
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    // Items on the same screen row become one tab-separated line (table-friendly).
    private fun pageOf(root: AccessibilityNodeInfo?): List<Row> {
        val items = mutableListOf<TextItem>(); collect(root, items)
        val sorted = items.distinct().sortedWith(compareBy({ it.top }, { it.left }))
        val rows = mutableListOf<MutableList<TextItem>>()
        for (item in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(item.top - last[0].top) <= 20) last.add(item) else rows.add(mutableListOf(item))
        }
        return rows.map { r -> Row(r.sortedBy { it.left }.joinToString("\t") { it.text }, r[0].top) }
    }

    private fun readScreen() = pageOf(rootInActiveWindow).map { it.text }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("screen", text))
    }

    // ---- Scroll + copy: swipes the screen like a finger, so it works in any app ----

    private fun swipe(up: Boolean, done: () -> Unit) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * (if (up) 0.72f else 0.28f)
        val y2 = dm.heightPixels * (if (up) 0.30f else 0.70f)
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { handler.postDelayed({ done() }, 700) }
            override fun onCancelled(d: GestureDescription?) { handler.postDelayed({ done() }, 300) }
        }, null)
        if (!ok) done()
    }

    // Appends a page, skipping rows that overlap with what we already have.
    private fun merge(acc: MutableList<String>, page: List<String>) {
        var k = minOf(acc.size, page.size)
        while (k > 0 && acc.subList(acc.size - k, acc.size) != page.subList(0, k)) k--
        acc.addAll(page.subList(k, page.size))
    }

    private fun scrollCopy() {
        if (running) return
        running = true
        toast("Scrolling to top, then copying. Don't touch the screen.")
        goTop(0, null)
    }

    private fun goTop(n: Int, prev: List<Row>?) {
        val cur = pageOf(rootInActiveWindow)
        if (cur == prev || n >= 60) { capture(mutableListOf(), null, 0); return }
        swipe(false) { goTop(n + 1, cur) }
    }

    private fun capture(pages: MutableList<List<Row>>, prev: List<Row>?, n: Int) {
        val page = pageOf(rootInActiveWindow)
        if (page == prev || n >= 120) { finishCopy(pages); return }
        pages.add(page)
        swipe(true) { capture(pages, page, n + 1) }
    }

    private fun finishCopy(pages: List<List<Row>>) {
        running = false
        // Rows that stay in the same place on every page are fixed headers/footers: keep once.
        val fixed = if (pages.size >= 3) pages[0].filter { r -> pages.all { it.contains(r) } } else emptyList()
        val half = resources.displayMetrics.heightPixels / 2
        val out = mutableListOf<String>()
        out.addAll(fixed.filter { it.top < half }.map { it.text })
        val body = mutableListOf<String>()
        for (p in pages) merge(body, p.filter { it !in fixed }.map { it.text })
        out.addAll(body)
        out.addAll(fixed.filter { it.top >= half }.map { it.text })
        copy(out.joinToString("\n"))
        toast("Copied ${out.size} lines from ${pages.size} pages")
    }

    // ---- Panel: pick a line, copy the visible screen, or scroll copy ----

    private fun closeCopyPanel() {
        copyPanel?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
        }
        copyPanel = null
    }

    private fun showCopyPanel() {
        if (copyPanel != null) { closeCopyPanel(); return }
        val lines = readScreen()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2FFFFFF.toInt())
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(
            Button(this).apply {
                text = "Copy all"
                setOnClickListener { copy(lines.joinToString("\n")); toast("Copied"); closeCopyPanel() }
            },
            LinearLayout.LayoutParams(0, LP.WRAP_CONTENT, 1f)
        )
        top.addView(
            Button(this).apply {
                text = "Scroll copy all"
                setOnClickListener { closeCopyPanel(); handler.postDelayed({ scrollCopy() }, 300) }
            },
            LinearLayout.LayoutParams(0, LP.WRAP_CONTENT, 1f)
        )
        top.addView(
            Button(this).apply { text = "Close"; setOnClickListener { closeCopyPanel() } },
            LinearLayout.LayoutParams(0, LP.WRAP_CONTENT, 1f)
        )
        root.addView(top)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 24) }
        if (lines.isEmpty()) list.addView(TextView(this).apply {
            text = "No readable text here. This app may block access, or draws text as an image."
        })
        for (line in lines) list.addView(TextView(this).apply {
            text = line.replace("\t", "   |   ")
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(8, 20, 8, 20)
            setOnClickListener { copy(line); toast("Copied"); closeCopyPanel() }
        })
        root.addView(ScrollView(this).apply { addView(list) })
        val h = (resources.displayMetrics.heightPixels * 0.7).toInt()
        wm.addView(root, params(LP.MATCH_PARENT, h).apply { gravity = Gravity.BOTTOM })
        copyPanel = root
    }

    // =====================================================================

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::wm.isInitialized) {
            hideMagnify()
            hideCopy()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
