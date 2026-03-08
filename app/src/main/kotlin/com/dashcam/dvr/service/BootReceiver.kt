package com.dashcam.dvr.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver
 *
 * Receives BOOT_COMPLETED and automatically restarts the DVR recording service
 * after a device reboot, ensuring continuous evidence capture in vehicle installations.
 *
 * Registered in AndroidManifest.xml with RECEIVE_BOOT_COMPLETED permission.
 * The user can disable auto-start in Settings if desired.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Boot completed — checking auto-start preference")

            // Read user preference (persisted SharedPreferences set in SetupWizard)
            val prefs = context.getSharedPreferences("dvr_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)

            if (autoStart) {
                Log.i(TAG, "Auto-start enabled — launching RecordingService")
                RecordingService.startRecording(context)
            } else {
                Log.i(TAG, "Auto-start disabled — skipping")
            }
        }
    }
}
