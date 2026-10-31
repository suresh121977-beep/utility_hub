package com.example.alltools

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var p: SharedPreferences
    private lateinit var a11yWarn: TextView
    private lateinit var status: TextView
    private lateinit var note: EditText
    private lateinit var listBox: LinearLayout

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                val s = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!s.isNullOrBlank()) {
                    val cur = note.text.toString().trim()
                    note.setText(if (cur.isEmpty()) s else "$cur $s")
                    note.setSelection(note.text.length)
                }
            }
        }

    // Refresh the list live when an alert fires and the entry turns green.
    private val itemsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (::listBox.isInitialized) listBox.post { refresh() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    // ---------- small UI helpers ----------

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 16)
        }
        parent.addView(
            c,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        )
        return c
    }

    /** Menu title with its ON/OFF switch. The switch value is saved under [key]. */
    private fun header(title: String, key: String, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = title; textSize = 20f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(SwitchCompat(this).apply {
            isChecked = p.getBoolean(key, false)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean(key, on).apply()
                onChange(on)
            }
        })
        return row
    }

    private fun label(parent: LinearLayout, t: String, size: Float = 14f, bold: Boolean = false) =
        parent.addView(TextView(this).apply {
            text = t; textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        })

    private fun button(parent: LinearLayout, t: String, a: () -> Unit) =
        parent.addView(Button(this).apply { text = t; setOnClickListener { a() } })

    private fun sw(parent: LinearLayout, t: String, key: String, def: Boolean) =
        parent.addView(SwitchCompat(this).apply {
            text = t; isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply() }
        })

    // ---------- screen ----------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("s", 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFFEFEFF4.toInt())
            addView(root)
        })

        root.addView(TextView(this).apply {
            text = "All-in-One Tools"; textSize = 24f; setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(4), 0, 0)
        })

        a11yWarn = TextView(this).apply {
            text = "⚠ Accessibility is OFF. Tap here, open “All-in-One Tools” and turn it ON. " +
                "(Android 13+: if greyed out, go to Settings > Apps > All-in-One Tools > ⋮ > Allow restricted settings.)"
            setTextColor(Color.parseColor("#7A1F00"))
            background = rounded(0xFFFFE0B2.toInt(), 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { openAccessibility() }
            visibility = View.GONE
        }
        root.addView(
            a11yWarn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        )

        // ===== MENU 1: Magnify =====
        val c1 = card(root)
        c1.addView(header("🔍 Magnify", "magnify_on") { on ->
            if (on) needAccessibility()
            updateWarn()
        })
        label(c1, "ON: a floating 🔍 button appears over every app. Tap it to zoom in/out, then use − + ◀ ▲ ▼ ▶ to change zoom and move around. Drag it to reposition.")

        // ===== MENU 2: Screen Copy =====
        val c2 = card(root)
        c2.addView(header("📋 Screen Copy", "copy_on") { on ->
            if (on) needAccessibility()
            updateWarn()
        })
        label(c2, "ON: Copy / + Page / Done buttons appear on the right edge of every app. Tap Copy to see all on-screen text, tap a line to copy it, or “Copy all” (tab-separated, pastes into Sheets as a table).")

        // ===== MENU 3: Voice Battery (+ Notepad) =====
        val c3 = card(root)
        c3.addView(header("🔋 Voice Battery", "vb_on") { on ->
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                if (!Settings.canDrawOverlays(this)) toast("Allow “Display over other apps” (below) to see the red circle")
                Scheduler.rescheduleAll(this)
                startAlert()
            } else {
                stopService(Intent(this, AlertService::class.java))
                Scheduler.cancelAll(this)
            }
            updatePerms()
            refresh()
        })
        label(c3, "ON: speaks when the charge is full, and when the charge is below your level (3 times only) with a small red circle on screen until the charge is back above that level. Also rings your notepad alarms and tasks.")

        label(c3, "Low battery level (%)", 14f, true)
        c3.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(p.getInt("low", 50).toString())
            doAfterTextChanged { s ->
                s.toString().toIntOrNull()?.takeIf { it in 1..99 }
                    ?.let { p.edit().putInt("low", it).apply() }
            }
        })
        sw(c3, "Low battery alert (voice ×3 + red circle)", "lowOn", true)
        sw(c3, "Speak when charge reaches 100% (unplug charger)", "full", true)
        sw(c3, "Play 3 bell sounds before speaking", "bells", true)

        val rateLabel = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        c3.addView(rateLabel)
        fun showRate(pct: Int) {
            rateLabel.text = "Speech speed: $pct%" + if (pct == 100) " (normal)" else ""
        }
        val startPct = p.getInt("rate", 100)
        showRate(startPct)
        c3.addView(SeekBar(this).apply {
            max = 150
            progress = startPct - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    val pct = v + 50
                    showRate(pct)
                    p.edit().putInt("rate", pct).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        button(c3, "Test voice") {
            if (!p.getBoolean("vb_on", false)) {
                toast("Switch Voice Battery ON first")
            } else {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AlertService::class.java).setAction(ACTION_SPEAK)
                        .putExtra("text", "This is a test of the voice speed.").putExtra("times", 1)
                )
            }
        }

        // ----- Notepad (expand / close) -----
        val npHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        npHead.addView(TextView(this).apply {
            text = "📝 Notepad · Alarms & Tasks"; textSize = 17f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val npBtn = Button(this)
        npHead.addView(npBtn)
        c3.addView(npHead)

        val npBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c3.addView(npBody)
        fun applyOpen(open: Boolean) {
            npBody.visibility = if (open) View.VISIBLE else View.GONE
            npBtn.text = if (open) "Close ▲" else "Open ▼"
        }
        applyOpen(p.getBoolean("np_open", false))
        npBtn.setOnClickListener {
            val open = npBody.visibility != View.VISIBLE
            p.edit().putBoolean("np_open", open).apply()
            applyOpen(open)
        }

        val noteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        note = EditText(this).apply {
            hint = "Type, or tap 🎤 and speak (pay, receive, meet...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        noteRow.addView(note)
        noteRow.addView(Button(this).apply { text = "🎤"; setOnClickListener { startVoiceTyping() } })
        npBody.addView(noteRow)

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(Button(this).apply {
            text = "＋ Task alert"; setOnClickListener { addTask() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(Button(this).apply {
            text = "⏰ Alarm"; setOnClickListener { addAlarm() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        npBody.addView(addRow)

        label(npBody, "Task alert = your sentence + date + time. Alarm = time + days (the sentence is optional and is spoken too). Add as many as you like. When a task has rung it turns green; tap ✕ to delete.")
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        npBody.addView(listBox)

        // ===== Permissions =====
        val c4 = card(root)
        label(c4, "Permissions & settings", 18f, true)
        status = TextView(this)
        c4.addView(status)
        button(c4, "Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        button(c4, "Allow exact alarms") {
            if (Build.VERSION.SDK_INT >= 31)
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        button(c4, "Allow display over other apps (red circle)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(c4, "Disable battery optimization") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button(c4, "Open Accessibility settings") { openAccessibility() }
        button(c4, "Open app settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        if (p.getBoolean("vb_on", false)) {
            Scheduler.rescheduleAll(this)
            startAlert()
        }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("items", 0).registerOnSharedPreferenceChangeListener(itemsListener)
    }

    override fun onStop() {
        getSharedPreferences("items", 0).unregisterOnSharedPreferenceChangeListener(itemsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updatePerms()
        updateWarn()
        refresh()
        // Re-check battery state (e.g. after "Display over other apps" was just allowed)
        if (p.getBoolean("vb_on", false)) startAlert()
    }

    private fun startAlert() =
        ContextCompat.startForegroundService(this, Intent(this, AlertService::class.java))

    // ---------- accessibility (Magnify + Screen Copy) ----------

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == packageName && it.className == ToolsService::class.java.name }
    }

    private fun openAccessibility() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun needAccessibility() {
        if (!a11yEnabled()) {
            toast("Turn ON “All-in-One Tools” in Accessibility")
            openAccessibility()
        }
    }

    private fun updateWarn() {
        val need = p.getBoolean("magnify_on", false) || p.getBoolean("copy_on", false)
        a11yWarn.visibility = if (need && !a11yEnabled()) View.VISIBLE else View.GONE
    }

    private fun updatePerms() {
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val exact = Scheduler.canExact(this)
        val opt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val overlay = Settings.canDrawOverlays(this)
        fun m(b: Boolean) = if (b) "✅" else "❌"
        status.text = "${m(notif)} Notifications\n${m(exact)} Exact alarms\n" +
            "${m(overlay)} Display over other apps (red circle)\n${m(opt)} Battery optimization off\n" +
            "${m(a11yEnabled())} Accessibility (Magnify & Screen Copy)"
    }

    // ---------- notepad: alarms & tasks ----------

    private fun startVoiceTyping() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your note")
        try {
            voiceLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            toast("Voice typing not available. Use the 🎤 on your keyboard instead.")
        }
    }

    private fun newId(): Long = System.currentTimeMillis() and 0x7FFFFFFFL

    private fun requireExact(): Boolean {
        if (!Scheduler.canExact(this)) {
            toast("Allow exact alarms first")
            if (Build.VERSION.SDK_INT >= 31)
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            return false
        }
        return true
    }

    private fun nextOccurrence(h: Int, m: Int): Long {
        val t = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (t.timeInMillis <= System.currentTimeMillis()) t.add(Calendar.DAY_OF_YEAR, 1)
        return t.timeInMillis
    }

    /** All 7 days start ticked. Untick any day. "One time only" gives mask 0. */
    private fun askDays(onDone: (Int) -> Unit) {
        val names = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val checked = BooleanArray(7) { true }
        AlertDialog.Builder(this)
            .setTitle("Alarm repeats on (untick days)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("OK") { _, _ ->
                var mask = 0
                checked.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
                if (mask == 0) {
                    toast("Tick at least one day, or choose One time only")
                    askDays(onDone)
                } else onDone(mask)
            }
            .setNeutralButton("One time only") { _, _ -> onDone(0) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addAlarm() {
        if (!requireExact()) return
        val label = note.text.toString().trim()
        val c = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m ->
            askDays { days ->
                addItem(Item(newId(), "alarm", label, h, m, nextOccurrence(h, m), days, true))
                note.setText("")
            }
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
    }

    private fun addTask() {
        val txt = note.text.toString().trim()
        if (txt.isEmpty()) { toast("Write or speak the task first"); return }
        if (!requireExact()) return
        pickDateTime { ms ->
            val cal = Calendar.getInstance().apply { timeInMillis = ms }
            addItem(Item(newId(), "task", txt, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), ms, 0, true))
            note.setText("")
        }
    }

    private fun addItem(item: Item) {
        Scheduler.add(this, item)
        toast(if (p.getBoolean("vb_on", false)) "Saved" else "Saved. Switch Voice Battery ON so it rings.")
        refresh()
    }

    private fun daysText(mask: Int): String {
        if (mask == 127) return "Every day"
        val n = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return n.filterIndexed { i, _ -> (mask and (1 shl i)) != 0 }.joinToString(", ")
    }

    private fun describe(item: Item): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, item.hour); set(Calendar.MINUTE, item.minute)
        }
        val whenText = if (item.days == 0)
            SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(item.time))
        else
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time) + " · " + daysText(item.days)
        val mark = if (item.done) "✔ Done · " else ""
        return if (item.type == "alarm") {
            "⏰ " + (if (item.text.isNotBlank()) item.text + "\n" else "") + mark + whenText
        } else "📝 ${item.text}\n$mark$whenText"
    }

    private fun refresh() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val items = Scheduler.load(this)
            .sortedWith(compareBy({ it.done }, { if (it.days == 0) it.time else Scheduler.nextTime(it) }))
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "No entries yet"; setPadding(0, dp(8), 0, 0) })
            return
        }
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(4), dp(8))
                background = rounded(if (item.done) 0xFFC8E6C9.toInt() else 0xFFF3F3F3.toInt(), 12)
            }
            row.addView(TextView(this).apply {
                text = describe(item)
                textSize = 15f
                setTextColor(if (item.done) 0xFF1B5E20.toInt() else Color.BLACK)
                if (item.done) setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!item.done) {
                row.addView(SwitchCompat(this).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, on -> toggle(item, on) }
                })
            }
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 22f
                setTextColor(if (item.done) 0xFF1B5E20.toInt() else 0xFF666666.toInt())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { Scheduler.remove(this@MainActivity, item); refresh() }
            })
            listBox.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) }
            )
        }
    }

    private fun toggle(item: Item, on: Boolean) {
        if (on) {
            if (!requireExact()) { refresh(); return }
            if (item.days == 0 && item.time <= System.currentTimeMillis()) {
                toast("That time has already passed")
                refresh()
                return
            }
        }
        Scheduler.setEnabled(this, item.id, on)
    }

    private fun pickDateTime(cb: (Long) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi ->
                val t = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }
                if (t.timeInMillis <= System.currentTimeMillis()) toast("Pick a future time") else cb(t.timeInMillis)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
}
