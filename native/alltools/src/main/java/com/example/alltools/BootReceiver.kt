package com.example.alltools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // Only the Voice Battery menu runs in the background; Magnify/Copy come back with the accessibility service.
        if (!Scheduler.isOn(c)) return
        Scheduler.rescheduleAll(c)
        ContextCompat.startForegroundService(c, Intent(c, AlertService::class.java))
    }
}
