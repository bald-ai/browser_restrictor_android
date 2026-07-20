package com.michalkrsik.internetrestrictor.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.michalkrsik.internetrestrictor.R

class ProtectionNotificationHelper(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun showOffUsageReminder() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Protection reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Reminds you when Chrome usage continues while protection is off."
            },
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Protection is off")
                .setContentText("Chrome usage has reached your reminder point while restrictions are paused.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Protection is off and Chrome usage has reached your reminder point. This is an informational reminder.",
                    ),
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "protection_reminders"
        const val NOTIFICATION_ID = 4102
    }
}
