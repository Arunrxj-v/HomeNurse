package com.homenurse.reminder

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the local care-task reminder job.
 *
 * WorkManager runs [ReminderWorker] every 15 minutes; everything it does is
 * on-device — no network, no analytics. The toggle lives in SecureStorage
 * (key [KEY_ENABLED]) and is read by the worker itself, so disabling takes
 * effect without rescheduling.
 */
class ReminderScheduler(private val context: Context) {

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    companion object {
        const val UNIQUE_WORK = "home_nurse_reminders"
        const val KEY_ENABLED = "reminders.enabled"
        const val KEY_LAST_RUN = "reminders.last_run"
    }
}
