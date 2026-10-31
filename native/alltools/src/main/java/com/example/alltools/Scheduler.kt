package com.example.alltools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// days = bitmask, bit0 = Sunday ... bit6 = Saturday. 0 = one time, 127 = every day
data class Item(
    val id: Long,
    val type: String,      // "alarm" or "task"
    val text: String,
    val hour: Int,
    val minute: Int,
    val time: Long,        // used only for one-time items
    val days: Int,
    var enabled: Boolean,
    var done: Boolean = false   // one-time item that has already rung
)

object Scheduler {
    private fun prefs(c: Context) = c.getSharedPreferences("items", 0)

    /** The Voice Battery menu switch. When OFF, nothing in the notepad rings. */
    fun isOn(c: Context): Boolean = c.getSharedPreferences("s", 0).getBoolean("vb_on", false)

    fun load(c: Context): MutableList<Item> {
        val list = mutableListOf<Item>()
        val a = JSONArray(prefs(c).getString("list", "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            list.add(
                Item(
                    o.getLong("id"), o.getString("type"), o.getString("text"),
                    o.getInt("hour"), o.getInt("minute"), o.getLong("time"),
                    o.getInt("days"), o.getBoolean("enabled"), o.optBoolean("done", false)
                )
            )
        }
        return list
    }

    private fun save(c: Context, l: List<Item>) {
        val a = JSONArray()
        l.forEach {
            a.put(
                JSONObject().put("id", it.id).put("type", it.type).put("text", it.text)
                    .put("hour", it.hour).put("minute", it.minute).put("time", it.time)
                    .put("days", it.days).put("enabled", it.enabled).put("done", it.done)
            )
        }
        prefs(c).edit().putString("list", a.toString()).apply()
    }

    fun get(c: Context, id: Long): Item? = load(c).find { it.id == id }

    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun nextTime(item: Item): Long {
        if (item.days == 0) return item.time
        for (d in 0..7) {
            val t = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, item.hour)
                set(Calendar.MINUTE, item.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val bit = 1 shl (t.get(Calendar.DAY_OF_WEEK) - 1)
            if ((item.days and bit) != 0 && t.timeInMillis > System.currentTimeMillis()) return t.timeInMillis
        }
        return -1
    }

    private fun pi(c: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            c, id.toInt(),
            Intent(c, AlarmReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(c: Context, item: Item): Boolean {
        if (!isOn(c)) return true          // menu is OFF: keep the entry, do not ring
        if (!canExact(c)) return false
        val t = nextTime(item)
        if (t <= System.currentTimeMillis()) return true
        c.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, item.id))
        return true
    }

    fun cancel(c: Context, item: Item) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, item.id))
    }

    /** Called when the Voice Battery menu is switched OFF. Entries stay in the notepad. */
    fun cancelAll(c: Context) {
        load(c).forEach { cancel(c, it) }
    }

    fun add(c: Context, item: Item) {
        val l = load(c)
        l.add(item)
        save(c, l)
        schedule(c, item)
    }

    fun remove(c: Context, item: Item) {
        cancel(c, item)
        save(c, load(c).filter { it.id != item.id })
    }

    fun setEnabled(c: Context, id: Long, on: Boolean) {
        val l = load(c)
        val item = l.find { it.id == id } ?: return
        item.enabled = on
        save(c, l)
        if (on) schedule(c, item) else cancel(c, item)
    }

    /** A one-time entry has rung: switch it off and mark it completed (shown green). */
    fun markDone(c: Context, id: Long) {
        val l = load(c)
        val item = l.find { it.id == id } ?: return
        item.enabled = false
        item.done = true
        save(c, l)
        cancel(c, item)
    }

    fun rescheduleAll(c: Context) {
        if (!isOn(c)) return
        val l = load(c)
        var changed = false
        l.forEach {
            if (it.enabled) {
                if (it.days == 0 && it.time <= System.currentTimeMillis()) {
                    it.enabled = false
                    it.done = true
                    changed = true
                } else schedule(c, it)
            }
        }
        if (changed) save(c, l)
    }
}
