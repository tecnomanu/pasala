package com.tecnomanu.pasala.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Keeps the CPU and Wi-Fi awake while something is being sent or received
 * (Wi-Fi power save otherwise adds tens of ms of latency and cuts throughput).
 */
object Radio {
    private var users = 0
    private var wake: PowerManager.WakeLock? = null
    private val wifi = mutableListOf<WifiManager.WifiLock>()

    @Synchronized
    fun acquire(context: Context) {
        users++
        if (users > 1) return
        val app = context.applicationContext
        wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pasala:transfer").apply { acquire(60 * 60 * 1000L) }
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WifiManager.WIFI_MODE_FULL_LOW_LATENCY).forEach { mode ->
            runCatching { wm.createWifiLock(mode, "pasala:$mode").apply { setReferenceCounted(false); acquire() } }
                .getOrNull()?.let { wifi += it }
        }
    }

    @Synchronized
    fun release() {
        if (users == 0) return
        users--
        if (users > 0) return
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        wifi.forEach { runCatching { it.release() } }
        wifi.clear()
    }
}
