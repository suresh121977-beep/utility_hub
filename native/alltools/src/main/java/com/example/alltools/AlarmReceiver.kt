package com.example.alltools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.util.Date

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!Scheduler.isOn(c)) return
        val id = i.getLongExtra("id", -1L)
        val item = Scheduler.get(c, id) ?: return
        if (!item.enabled) return
        val text = if (item.type == "alarm") {
            val t = DateFormat.getTimeFormat(c).format(Date())
            "Alarm. The time is now $t. " + item.text.ifBlank { "Time to wake up." }
        } else {
            "Task reminder. " + item.text
        }
        ContextCompat.startForegroundService(
            c,
            Intent(c, AlertService::class.java).setAction(ACTION_SPEAK)
                .putExtra("text", text).putExtra("times", 3)
        )
        // One-time entries become "done" (green, with a close mark). Repeating alarms are set for the next day.
        if (item.days == 0) Scheduler.markDone(c, id) else Scheduler.schedule(c, item)
    }
}
