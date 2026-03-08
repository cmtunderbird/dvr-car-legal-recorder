package com.dashcam.dvr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * DVRApplication
 *
 * Application entry point.
 * Responsibilities:
 *  - Create notification channels required by the Foreground Service (Android 8+)
 *  - Expose a singleton AppContainer for dependency injection without a DI framework
 */
class DVRApplication : Application() {

    companion object {
        // Notification channel IDs
        const val CHANNEL_RECORDING  = "dvr_recording"
        const val CHANNEL_ALERT      = "dvr_alert"
        const val CHANNEL_EXPORT     = "dvr_export"

        lateinit var instance: DVRApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Persistent recording indicator — low importance so it doesn't make sound
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                "Recording Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows DVR recording status in the status bar"
                setShowBadge(false)
            }
        )

        // Event alerts (collision detected, storage full, GPS lost)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "DVR Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts from the DVR system"
            }
        )

        // Evidence export progress
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EXPORT,
                "Evidence Export",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Evidence package export progress"
            }
        )
    }
}
