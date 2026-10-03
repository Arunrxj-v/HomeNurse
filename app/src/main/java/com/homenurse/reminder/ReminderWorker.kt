package com.homenurse.reminder

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.homenurse.HomeNurseApp
import com.homenurse.MainActivity
import com.homenurse.R
import com.homenurse.core.logging.PrivacyLog
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Local, privacy-safe care-task reminders.
 *
 * On each 15-minute tick the worker finds confirmed care tasks whose reminder
 * time falls inside the window `(lastRun, now]` (minute-of-day, handling the
 * midnight rollover) and posts ONE notification. The notification never
 * contains medical content (no medicine names, no task text) — it only says
 * that a care task is due; the user opens HomeNurse to see what it is.
 * Deduplication is the persisted last-run timestamp, so a task is never
 * announced twice.
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? HomeNurseApp ?: return Result.success()
        val container = app.container

        val enabled = container.secureStorage
            .getString(ReminderScheduler.KEY_ENABLED)
            ?.toBoolean() ?: true
        if (!enabled) {
            persistLastRun(container)
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val lastRun = container.secureStorage
            .getString(ReminderScheduler.KEY_LAST_RUN)?.toLongOrNull()
            ?: now // first run: never announce times that passed before install
        if (lastRun >= now) return Result.success()

        val windowStartMinute = minuteOfDayAt(lastRun) + 1
        val nowMinute = minuteOfDayAt(now)
        val inWindow: (Int) -> Boolean = if (nowMinute >= windowStartMinute) {
            { t -> t in windowStartMinute..nowMinute }
        } else {
            // Crossed midnight: [windowStart, 23:59] ∪ [0, now].
            { t -> t >= windowStartMinute || t <= nowMinute }
        }

        val dueCount = container.database.carePlanDao().getAllTasks().count { task ->
            !task.completed && task.timeOfDayMin != null && inWindow(task.timeOfDayMin)
        }

        if (dueCount > 0 && notificationsAllowed()) {
            postNotification(dueCount)
        }

        persistLastRun(container, now)
        return Result.success()
    }

    private fun minuteOfDayAt(epochMillis: Long): Int {
        val time = LocalTime.from(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
        return time.hour * 60 + time.minute
    }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
    }

    private fun postNotification(count: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (count == 1) {
            applicationContext.getString(R.string.reminder_single)
        } else {
            applicationContext.getString(R.string.reminder_multiple, count)
        }

        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            HomeNurseApp.REMINDER_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.reminder_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post.
            PrivacyLog.warn("reminder_notification_denied")
        }
    }

    private fun persistLastRun(
        container: com.homenurse.app.AppContainer,
        now: Long = System.currentTimeMillis(),
    ) {
        container.secureStorage.putString(ReminderScheduler.KEY_LAST_RUN, now.toString())
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
