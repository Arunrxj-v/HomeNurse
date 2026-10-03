package com.homenurse

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.homenurse.app.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Owns the [AppContainer], creates the (content-free) reminder notification
 * channel, schedules local reminders, restores/refreshes the authentication
 * session (account only — no medical data leaves the device) and warms the
 * local model in the background so an installed model is ready when the user
 * opens chat.
 */
class HomeNurseApp : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        loadSqlCipher()
        container = AppContainer(this)
        createReminderChannel()
        container.reminderScheduler.schedule()
        applicationScope.launch {
            // Auto-login: refresh the stored session when the access token
            // expired. A rejected refresh token signs out; an unreachable
            // server keeps the session (offline launch must not log out).
            runCatching { container.authRepository.refreshSession() }
            runCatching { container.modelManager.warmUp() }
        }
    }

    /**
     * SQLCipher for Android bundles `libsqlcipher.so` inside its AAR but never
     * loads it — Zetetic's integration docs require an explicit
     * `System.loadLibrary("sqlcipher")` **before any database operation**.
     * Without it the first medical-database open dies with
     * `UnsatisfiedLinkError: ... nativeOpen ... is the library loaded`, which
     * is what crashed this app as soon as it reached the local database.
     *
     * JVM unit tests run without the `.so` and use the in-memory platform
     * SQLite instead, so a failed load is logged and ignored there rather
     * than failing the test process.
     */
    private fun loadSqlCipher() {
        try {
            System.loadLibrary(SQLCIPHER_LIBRARY)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "$SQLCIPHER_LIBRARY is not loadable in this runtime", e)
        }
    }

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID,
                getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                // The channel itself never carries medical text.
                description = getString(R.string.reminder_channel_description)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "care_reminders"

        /** Native library shipped inside the net.zetetic:sqlcipher-android AAR. */
        private const val SQLCIPHER_LIBRARY = "sqlcipher"

        private const val TAG = "HomeNurseApp"
    }
}
