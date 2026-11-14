package com.prakash.pmeeting.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Notification channel/id shared by the transcription foreground worker. */
object TranscriptionNotifications {

    const val CHANNEL_ID = "transcription"
    const val NOTIFICATION_ID = 4211

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transcription",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "On-device transcription progress"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
