package com.kokidayo.batterystatus.background

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.kokidayo.batterystatus.data.NotifyMode
import com.kokidayo.batterystatus.data.SettingsStore
import com.kokidayo.batterystatus.data.SnapshotCollector
import com.kokidayo.batterystatus.util.AppLog
import com.kokidayo.batterystatus.widget.ChargeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Schedules the background jobs: the 30 s charger notification and widget refreshes.
 *
 * Plain JobScheduler on purpose: WorkManager toggles its RescheduleReceiver component
 * whenever unfinished work appears/disappears, which fires PACKAGE_CHANGED and makes
 * launchers reset app widgets to their placeholder layout (visible flicker).
 */
object ChargingWork {
    private const val TAG = "ChargingWork"
    const val JOB_NOTIFY = 1001
    const val JOB_WIDGET = 1002
    const val JOB_WIDGET_LOOP = 1003
    const val JOB_CHARGING_WATCH = 1004

    const val NOTIFY_DURATION_MS = 30_000L
    const val NOTIFY_TICK_MS = 2_000L
    const val WATCH_IDLE_TICK_MS = 10_000L
    private const val WIDGET_LOOP_DELAY_S = 60L

    private fun scheduler(context: Context): JobScheduler =
        context.getSystemService(JobScheduler::class.java)

    private fun component(context: Context) = ComponentName(context, ChargingJobService::class.java)

    /** A job that should run right away, even from the background. */
    private fun immediate(context: Context, id: Int): JobInfo =
        JobInfo.Builder(id, component(context)).apply {
            if (Build.VERSION.SDK_INT >= 31) setExpedited(true) else setOverrideDeadline(0)
        }.build()

    const val EXTRA_TEST = "test"
    const val TEST_DURATION_MS = 10_000L

    /**
     * Starts the charger notification. [test] posts it for 10 s even when unplugged
     * (used by the settings button to verify permission and channel).
     */
    fun startConnectNotification(context: Context, test: Boolean = false) {
        val extras = PersistableBundle().apply { putInt(EXTRA_TEST, if (test) 1 else 0) }
        val job = JobInfo.Builder(JOB_NOTIFY, component(context)).apply {
            if (Build.VERSION.SDK_INT >= 31) setExpedited(true) else setOverrideDeadline(0)
            setExtras(extras)
        }.build()
        val result = scheduler(context).schedule(job)
        AppLog.i(TAG, "notify job scheduled (test=$test): ${if (result == JobScheduler.RESULT_SUCCESS) "ok" else "failed $result"}")
        // Safety net: even if the job never runs, a 30 s notification must not linger.
        val mode = SettingsStore.get(context).settings.value.notifyMode
        if (test || mode != NotifyMode.WHILE_CHARGING) {
            val duration = if (test) TEST_DURATION_MS else NOTIFY_DURATION_MS
            context.getSystemService(AlarmManager::class.java).set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + duration + 5_000,
                cancelIntent(context),
            )
        }
    }

    fun cancelConnectNotification(context: Context) {
        scheduler(context).cancel(JOB_NOTIFY)
        context.getSystemService(AlarmManager::class.java).cancel(cancelIntent(context))
        context.getSystemService(NotificationManager::class.java).cancel(ChargeNotification.ID)
    }

    private fun cancelIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 2,
        Intent(context, ChargerReceiver::class.java).setAction(ChargerReceiver.ACTION_CANCEL_NOTIFICATION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun refreshWidgetNow(context: Context) {
        if (!ChargeWidget.hasWidgets(context)) return
        scheduler(context).schedule(immediate(context, JOB_WIDGET))
    }

    /** While charging, refresh the widget about once a minute (constraint drops it when unplugged). */
    fun scheduleWidgetLoop(context: Context) {
        if (!ChargeWidget.hasWidgets(context)) return
        val job = JobInfo.Builder(JOB_WIDGET_LOOP, component(context))
            .setRequiresCharging(true)
            .setMinimumLatency(TimeUnit.SECONDS.toMillis(WIDGET_LOOP_DELAY_S))
            .build()
        scheduler(context).schedule(job)
    }

    fun cancelWidgetLoop(context: Context) {
        scheduler(context).cancel(JOB_WIDGET_LOOP)
    }

    /**
     * Arms the system-driven plug-in detector: a persisted job whose only constraint is
     * "charging". It runs when the system decides the device is charging and handles
     * the notification for that session. Cancelled when notifications are off.
     */
    fun armChargingWatch(context: Context) {
        val mode = SettingsStore.get(context).settings.value.notifyMode
        if (mode == NotifyMode.OFF) {
            scheduler(context).cancel(JOB_CHARGING_WATCH)
            return
        }
        val job = JobInfo.Builder(JOB_CHARGING_WATCH, component(context))
            .setRequiresCharging(true)
            .setPersisted(true)
            .build()
        val result = scheduler(context).schedule(job)
        if (result != JobScheduler.RESULT_SUCCESS) AppLog.w(TAG, "arm charging watch failed: $result")
    }
}

class ChargingJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.jobId
        running.remove(id)?.cancel()
        val collector = SnapshotCollector(applicationContext)
        running[id] = scope.launch {
            try {
                when (id) {
                    ChargingWork.JOB_NOTIFY -> runNotification(collector, params.extras.getInt(ChargingWork.EXTRA_TEST, 0) == 1)
                    ChargingWork.JOB_CHARGING_WATCH -> runChargingWatch(collector)
                    else -> {
                        val snapshot = collector.collect(backendWaitMs = 1_500)
                        if (snapshot.local.plugged == 0) ChargerEvents.syncUnplugged(applicationContext)
                        ChargeWidget.render(applicationContext, snapshot, force = id == ChargingWork.JOB_WIDGET)
                        if (snapshot.local.plugged != 0) ChargingWork.scheduleWidgetLoop(applicationContext)
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "job $id failed", e)
            } finally {
                running.remove(id)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        AppLog.w(TAG, "job ${params.jobId} stopped by system" +
            if (Build.VERSION.SDK_INT >= 31) " (reason ${params.stopReason})" else "")
        running.remove(params.jobId)?.cancel()
        when (params.jobId) {
            ChargingWork.JOB_NOTIFY -> {
                val mode = SettingsStore.get(applicationContext).settings.value.notifyMode
                if (mode == NotifyMode.WHILE_CHARGING) {
                    // The charging-watch job keeps the ongoing notification alive from here on.
                    ChargingWork.armChargingWatch(applicationContext)
                } else {
                    getSystemService(NotificationManager::class.java).cancel(ChargeNotification.ID)
                }
            }
            // Still charging: come straight back (the constraint is satisfied, so it reruns quickly).
            ChargingWork.JOB_CHARGING_WATCH -> ChargingWork.armChargingWatch(applicationContext)
        }
        return false
    }

    /**
     * Runs whenever the system reports charging. Handles the session (30 s notification or
     * continuous updates), waits for the unplug, then re-arms itself for the next session.
     */
    private suspend fun runChargingWatch(collector: SnapshotCollector) {
        val context = applicationContext
        val store = SettingsStore.get(context)
        val mode = store.settings.value.notifyMode
        var snapshot = collector.collect(backendWaitMs = 2_500)
        AppLog.i(TAG, "charging watch job running (mode=$mode, plugged=${snapshot.local.plugged}, " +
            "alreadyNotified=${store.notifiedThisSession})")
        try {
            if (mode == NotifyMode.OFF || snapshot.local.plugged == 0) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val canPost = ChargeNotification.canPost(context)
            val start = System.currentTimeMillis()
            var ticks = 0
            val firstTime = !store.notifiedThisSession
            store.notifiedThisSession = true
            while (snapshot.local.plugged != 0) {
                val elapsed = System.currentTimeMillis() - start
                val showing = canPost && (mode == NotifyMode.WHILE_CHARGING ||
                    (mode == NotifyMode.CONNECT_30S && firstTime && elapsed < ChargingWork.NOTIFY_DURATION_MS))
                if (showing) {
                    val left = ((ChargingWork.NOTIFY_DURATION_MS - elapsed) / 1000).toInt().coerceAtLeast(0)
                    manager.notify(ChargeNotification.ID, ChargeNotification.build(context, snapshot, left, mode))
                    ticks++
                } else if (ticks > 0 && mode == NotifyMode.CONNECT_30S) {
                    manager.cancel(ChargeNotification.ID)
                    ticks = 0
                }
                ChargeWidget.render(context, snapshot)
                delay(if (showing) ChargingWork.NOTIFY_TICK_MS else ChargingWork.WATCH_IDLE_TICK_MS)
                snapshot = collector.collect()
            }
            AppLog.i(TAG, "charging watch: unplugged")
            ChargerEvents.onPluggedChanged(context, false, "charging-watch")
        } finally {
            if (mode != NotifyMode.WHILE_CHARGING || snapshot.local.plugged == 0) {
                context.getSystemService(NotificationManager::class.java).cancel(ChargeNotification.ID)
            }
            if (snapshot.local.plugged == 0) store.notifiedThisSession = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun runNotification(collector: SnapshotCollector, test: Boolean) {
        val context = applicationContext
        if (!ChargeNotification.canPost(context)) {
            AppLog.w(TAG, "notification permission missing; skipping")
            ChargeWidget.render(context, collector.collect(backendWaitMs = 1_500))
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val mode = SettingsStore.get(context).settings.value.notifyMode
        val duration = when {
            test -> ChargingWork.TEST_DURATION_MS
            mode == NotifyMode.WHILE_CHARGING -> Long.MAX_VALUE
            else -> ChargingWork.NOTIFY_DURATION_MS
        }
        val start = System.currentTimeMillis()
        var snapshot = collector.collect(backendWaitMs = 2_500)
        AppLog.i(TAG, "charger notification job running (test=$test, mode=$mode, plugged=${snapshot.local.plugged}, " +
            "shell=${if (snapshot.shell != null) "yes" else "none"})")
        var ticks = 0
        try {
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                val left = if (duration == Long.MAX_VALUE) 0 else ((duration - elapsed) / 1000).toInt().coerceAtLeast(0)
                if (!test && snapshot.local.plugged == 0) break
                manager.notify(ChargeNotification.ID, ChargeNotification.build(context, snapshot, left, if (test) NotifyMode.CONNECT_30S else mode))
                ticks++
                ChargeWidget.render(context, snapshot)
                if (elapsed >= duration) break
                delay(ChargingWork.NOTIFY_TICK_MS)
                snapshot = collector.collect()
            }
        } finally {
            AppLog.i(TAG, "charger notification finished after $ticks updates")
            manager.cancel(ChargeNotification.ID)
            if (snapshot.local.plugged != 0) ChargingWork.scheduleWidgetLoop(context)
        }
    }

    private companion object {
        const val TAG = "ChargingJobService"
    }
}
