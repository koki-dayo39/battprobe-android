package com.kokidayo.batterystatus.background

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
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

    const val NOTIFY_DURATION_MS = 30_000L
    const val NOTIFY_TICK_MS = 2_000L
    private const val WIDGET_LOOP_DELAY_S = 60L

    private fun scheduler(context: Context): JobScheduler =
        context.getSystemService(JobScheduler::class.java)

    private fun component(context: Context) = ComponentName(context, ChargingJobService::class.java)

    /** A job that should run right away, even from the background. */
    private fun immediate(context: Context, id: Int): JobInfo =
        JobInfo.Builder(id, component(context)).apply {
            if (Build.VERSION.SDK_INT >= 31) setExpedited(true) else setOverrideDeadline(0)
        }.build()

    fun startConnectNotification(context: Context) {
        val result = scheduler(context).schedule(immediate(context, JOB_NOTIFY))
        if (result != JobScheduler.RESULT_SUCCESS) AppLog.w(TAG, "schedule notify job failed: $result")
    }

    fun cancelConnectNotification(context: Context) {
        scheduler(context).cancel(JOB_NOTIFY)
        context.getSystemService(NotificationManager::class.java).cancel(ChargeNotification.ID)
    }

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
                    ChargingWork.JOB_NOTIFY -> runNotification(collector)
                    else -> {
                        val snapshot = collector.collect(backendWaitMs = 1_500)
                        ChargeWidget.render(applicationContext, snapshot, full = id == ChargingWork.JOB_WIDGET)
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
        running.remove(params.jobId)?.cancel()
        if (params.jobId == ChargingWork.JOB_NOTIFY) {
            getSystemService(NotificationManager::class.java).cancel(ChargeNotification.ID)
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun runNotification(collector: SnapshotCollector) {
        val context = applicationContext
        if (!ChargeNotification.canPost(context)) {
            AppLog.w(TAG, "notification permission missing; skipping")
            ChargeWidget.render(context, collector.collect(backendWaitMs = 1_500))
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val start = System.currentTimeMillis()
        var snapshot = collector.collect(backendWaitMs = 2_500)
        AppLog.i(TAG, "charger notification started (shell=${if (snapshot.shell != null) "yes" else "none"})")
        try {
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                val left = ((ChargingWork.NOTIFY_DURATION_MS - elapsed) / 1000).toInt().coerceAtLeast(0)
                if (snapshot.local.plugged == 0) break
                manager.notify(ChargeNotification.ID, ChargeNotification.build(context, snapshot, left))
                ChargeWidget.render(context, snapshot)
                if (elapsed >= ChargingWork.NOTIFY_DURATION_MS) break
                delay(ChargingWork.NOTIFY_TICK_MS)
                snapshot = collector.collect()
            }
        } finally {
            manager.cancel(ChargeNotification.ID)
            if (snapshot.local.plugged != 0) ChargingWork.scheduleWidgetLoop(context)
        }
    }

    private companion object {
        const val TAG = "ChargingJobService"
    }
}
