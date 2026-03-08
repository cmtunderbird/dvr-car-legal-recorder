package com.dashcam.dvr.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver
 *
 * Receives BOOT_COMPLETED and automatically starts the DVR recording service
 * after a device reboot for continuous evidence capture in vehicle installations.
 *
 * NOTE: Starting the service via startForegroundService() from a BroadcastReceiver
 * is safe — there is no UI window for the HyperOS camera-access banner to steal
 * focus from. The binder-only rule applies only to the Activity.
 */
class BootReceiver : BroadcastReceiver() {

    companion object { private const val TAG = "BootReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Boot completed — checking auto-start preference")
            val prefs = context.getSharedPreferences("dvr_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)
            if (autoStart) {
                Log.i(TAG, "Auto-start enabled — launching RecordingService via intent")
                context.startForegroundService(
                    Intent(context, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_START_RECORDING)
                )
            } else {
                Log.i(TAG, "Auto-start disabled — skipping")
            }
        }
    }
}
