package app.veil.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.veil.core.web.UnreadChat
import app.veil.core.web.UnreadMessages
import app.veil.ui.site.UnreadCheck
import java.util.concurrent.TimeUnit

/**
 * Unread-message notifications. Instagram's site can't push to a WebView app, so while the app
 * is away a background job looks at the unread badge about every 15 minutes (Android's shortest
 * interval for such jobs; it stretches them further when the phone is idle) and notifies when
 * more chats are unread than the user knew about: who wrote and their latest message, as the
 * inbox list shows them. The lock screen shows only how many.
 */
object MessageAlerts {
    private const val WORK = "unread-messages"
    private const val CHANNEL = "messages"
    private const val NOTIFICATION_ID = 1
    /** Intent extra: open the Messages tab. */
    const val EXTRA_OPEN_MESSAGES = "app.veil.OPEN_MESSAGES"

    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(WORK)
            cancel(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Debug builds: runs one check now (`am broadcast -a app.veil.diag.CHECK_MESSAGES`). */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CheckWorker>().build())
    }

    /** The user is looking at the app: the badge there replaces the notification. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(context: Context, unread: Int, chats: List<UnreadChat>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Unread messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Who wrote, checked about every 15 minutes."
            },
        )
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(EXTRA_OPEN_MESSAGES, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // On the lock screen: only the count.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(UnreadMessages.notificationText(unread))
            .build()
        val first = chats.firstOrNull()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(UnreadMessages.senders(unread, chats))
            .setContentText(
                when {
                    first == null -> "Tap to open your messages."
                    chats.size == 1 -> first.preview.ifEmpty { "New message" }
                    else -> UnreadMessages.notificationText(unread)
                },
            )
            .apply {
                if (chats.size > 1) {
                    val lines = NotificationCompat.InboxStyle().setSummaryText(UnreadMessages.notificationText(unread))
                    chats.forEach { chat -> lines.addLine(if (chat.preview.isEmpty()) chat.name else "${chat.name}: ${chat.preview}") }
                    setStyle(lines)
                } else if (first != null && first.preview.isNotEmpty()) {
                    setStyle(NotificationCompat.BigTextStyle().bigText(first.preview))
                }
            }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setNumber(unread)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as VeilApplication
            val settings = app.settings
            val current = settings.load()
            // Off, held after a sign-in or security check, or the app is in front with its own badge.
            if (!current.messageNotifications || current.messageChecksHeld || UnreadCheck.appInFront) return Result.success()
            when (val result = UnreadCheck.run(app, current.knownUnread)) {
                is UnreadCheck.Result.Count -> {
                    val unread = result.unread
                    Log.d("Veil", "unread check: $unread (known ${current.knownUnread}, ${result.chats.size} named)")
                    if (UnreadMessages.isNews(unread, current.knownUnread) && canNotify(app) && !UnreadCheck.appInFront) {
                        notify(app, unread, result.chats)
                    }
                    if (unread == 0) cancel(app)
                    if (unread != current.knownUnread) settings.update { it.copy(knownUnread = unread) }
                }
                is UnreadCheck.Result.Blocked -> {
                    Log.d("Veil", "unread check reached ${result.kind.name.lowercase()}: holding until the app is opened")
                    settings.update { it.copy(messageChecksHeld = true) }
                }
                UnreadCheck.Result.Unknown -> Log.d("Veil", "unread check: no answer")
            }
            return Result.success()
        }
    }
}
