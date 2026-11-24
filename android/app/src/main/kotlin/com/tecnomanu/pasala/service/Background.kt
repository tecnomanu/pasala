package com.tecnomanu.pasala.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Battery-optimization exemption so peers can reach the phone while it's idle. */
object Background {
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    // See HISTORY.md#idle-reachability
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Fallback when a vendor build doesn't handle the direct request. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * Where the person changes it either way. Apps can't revoke the exemption themselves, so once it's on
     * this goes to the app's page (Battery: Unrestricted / Optimized), falling back to the system list.
     */
    fun change(context: Context) {
        if (!isExempt(context)) return open(context)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(details) }.onFailure { runCatching { context.startActivity(settingsIntent()) } }
    }

    /**
     * Some vendors (Honor/MagicOS) block apps from starting on their own (after a restart or an update)
     * unless the person allows it in the vendor's app-launch screen. See HISTORY.md#vendor-autostart
     */
    fun vendorLaunchSettings(context: Context): Intent? =
        Intent(Intent.ACTION_VIEW, Uri.parse("systemmanager://com.hihonor.systemmanager/appcontrol"))
            .takeIf { it.resolveActivity(context.packageManager) != null }

    fun open(context: Context) {
        val i = requestIntent(context)
        runCatching { context.startActivity(i) }.onFailure { runCatching { context.startActivity(settingsIntent()) } }
    }
}
