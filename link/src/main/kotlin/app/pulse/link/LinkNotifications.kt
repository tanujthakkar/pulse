package app.pulse.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ongoing notification for [StrapLinkService].
 *
 * The channel is low importance so a connected strap does not make a sound.
 * The small icon and the title use the Pulse name.
 */
@Singleton
class LinkNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun build(snapshot: LinkSnapshot, syncing: Boolean): Notification {
        ensureChannel()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_link_status)
            .setContentTitle(TITLE)
            .setContentText(linkStatusLine(snapshot, syncing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        launchIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.link_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = context.getString(R.string.link_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun launchIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID: String = "pulse.strap_link"
        const val NOTE_ID: Int = 41
        const val TITLE: String = "Pulse"
    }
}
