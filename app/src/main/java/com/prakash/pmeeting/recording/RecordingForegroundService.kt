package com.prakash.pmeeting.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.prakash.pmeeting.MainActivity
import com.prakash.pmeeting.R

/**
 * Keeps an active recording alive when the screen turns off or the app goes to
 * the background.
 *
 * Android 11+ revokes microphone access (and background camera access) from
 * apps that are not in the foreground unless they run a foreground service with
 * the matching type. A partial wake lock additionally stops the CPU from dozing
 * while MediaRecorder / CameraX writes the take, so a locked phone no longer
 * cuts the recording short.
 *
 * The service owns no recorder: the ViewModel still owns capture. It only keeps
 * the process foreground and the session legitimate while the take runs.
 */
class RecordingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    /** Wall-clock start of the current take; keeps the notification chronometer stable. */
    private var sessionStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        startInForeground(video)
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        sessionStartedAt = 0L
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // The foreground-service type constants are inlined at compile time, so
    // using them on API 26-29 (where startForeground ignores the type) is safe.
    @android.annotation.SuppressLint("InlinedApi")
    private fun startInForeground(video: Boolean) {
        createChannel()
        if (sessionStartedAt == 0L) sessionStartedAt = System.currentTimeMillis()
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(if (video) "Recording video" else "Recording audio")
            .setContentText("P-Meeting is still recording. Tap to open.")
            .setContentIntent(launch)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(sessionStartedAt)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        // Android 14+ rejects a foreground type whose runtime permission is
        // missing (for example silent video when the mic was denied), so the
        // type mask is built from the grants actually held.
        var type = 0
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (video && hasPermission(Manifest.permission.CAMERA)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while P-Meeting records audio or video."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Generous cap only as a safety net if a stop is ever missed.
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "recording_session"
        private const val NOTIFICATION_ID = 4201
        private const val WAKE_LOCK_TAG = "P-Meeting:recording"
        private const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
        private const val EXTRA_VIDEO = "extra_video"

        /** Starts (or refreshes) the foreground session for a running take. */
        fun start(context: Context, video: Boolean) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .putExtra(EXTRA_VIDEO, video)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Stops the foreground session; safe to call when it is not running. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, RecordingForegroundService::class.java))
            }
        }
    }
}
