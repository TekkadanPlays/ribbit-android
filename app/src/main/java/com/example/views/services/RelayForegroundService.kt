package com.example.views.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.views.ribbit.BuildConfig
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.repository.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service to keep relay connections alive while the app is backgrounded.
 * Shows a persistent notification to comply with Android background execution limits.
 * Updates the notification with the count of new notes from followed users.
 */
class RelayForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ribbit_relay_channel"
        private const val CHANNEL_NAME = "ribbit relay connection"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(0))
        } catch (e: SecurityException) {
            Log.w("RelayForegroundService", "Foreground start blocked: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException or other runtime errors
            Log.w("RelayForegroundService", "Foreground start failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        // Ensure relay connection is active when the service starts
        RelayConnectionStateMachine.getInstance().requestReconnectOnResume()

        // Observe new note counts and update the notification
        serviceScope.launch {
            NotesRepository.getInstance().newNotesCounts.collectLatest { counts ->
                val followingCount = counts.following
                if (followingCount > 0) {
                    updateNotification(followingCount)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(followingCount: Int): Notification {
        val contentText = if (followingCount > 0) {
            "$followingCount new note${if (followingCount != 1) "s" else ""} from people you follow"
        } else {
            "Keeping relay connections active"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ribbit is running")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(followingCount: Int) {
        val notification = buildNotification(followingCount)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
