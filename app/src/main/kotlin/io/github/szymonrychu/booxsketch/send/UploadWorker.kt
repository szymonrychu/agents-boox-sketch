package io.github.szymonrychu.booxsketch.send

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
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.szymonrychu.booxsketch.App
import io.github.szymonrychu.booxsketch.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox in the background until it is empty or every left item waits for backoff
 * (the runner then schedules the next run). A refused token pauses the queue and posts a
 * notification that opens the app, whose gate shows Pairing.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val summary = (applicationContext as App).container.outboxRunner.drain()
        if (summary.needsPairing) PairingNotification.post(applicationContext)
        return Result.success()
    }
}

/** Enqueues the unique `outbox` work, network-constrained. */
class OutboxScheduler(
    private val context: Context,
) {
    /**
     * Run now. Own unique name, so a pending backoff run ([later]) never delays it; two runs
     * are harmless, the runner is single-flight.
     */
    fun kick() = enqueue(KICK_WORK, 0, ExistingWorkPolicy.KEEP)

    /**
     * Run again after [delayMs] (backoff or Retry-After of the items left). Called from inside
     * the running worker, so it must append, never replace (REPLACE would cancel the caller).
     */
    fun later(delayMs: Long) = enqueue(LATER_WORK, delayMs, ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** Drops the queued runs (Unpair clears the outbox they would drain). */
    fun cancelAll() {
        try {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(KICK_WORK)
                cancelUniqueWork(LATER_WORK)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "outbox work not cancelled: $e")
        }
    }

    private fun enqueue(
        name: String,
        delayMs: Long,
        policy: ExistingWorkPolicy,
    ) {
        val request =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        } catch (e: IllegalStateException) {
            // WorkManager not initialised (only in tests that never start it); foreground drains still run.
            Log.w(TAG, "outbox work not scheduled: $e")
        }
    }

    companion object {
        const val KICK_WORK = "outbox"
        const val LATER_WORK = "outbox-later"
        private const val TAG = "BooxSketch"
    }
}

/** The "pair again" notification; skipped quietly when notifications are not allowed. */
object PairingNotification {
    const val CHANNEL = "pairing"
    const val ID = 21

    fun post(context: Context) {
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_DEFAULT))
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Pair again to keep sending")
                .setContentText("The server refused this device's token. Uploads are paused until you pair again.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(ID, notification)
    }
}
