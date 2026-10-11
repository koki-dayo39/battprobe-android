package com.kokidayo.batterystatus.background

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kokidayo.batterystatus.data.SnapshotCollector
import com.kokidayo.batterystatus.util.AppLog
import com.kokidayo.batterystatus.widget.ChargeWidget
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Schedules and runs the background jobs: 30 s charger notification and widget refreshes. */
object ChargingWork {
    private const val TAG = "ChargingWork"
    private const val WORK_NOTIFY = "charger-notify"
    private const val WORK_WIDGET = "widget-refresh"
    private const val WORK_WIDGET_LOOP = "widget-loop"
    const val KEY_MODE = "mode"
    const val MODE_NOTIFY = "notify"
    const val MODE_WIDGET = "widget"
    const val MODE_WIDGET_LOOP = "widget-loop"

    const val NOTIFY_DURATION_MS = 30_000L
    const val NOTIFY_TICK_MS = 2_000L
    private const val WIDGET_LOOP_DELAY_S = 60L

    fun startConnectNotification(context: Context) {
        val request = OneTimeWorkRequestBuilder<ChargingWorker>()
            .setInputData(workDataOf(KEY_MODE to MODE_NOTIFY))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOTIFY, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelConnectNotification(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NOTIFY)
        context.getSystemService(NotificationManager::class.java).cancel(ChargeNotification.ID)
    }

    fun refreshWidgetNow(context: Context) {
        if (!ChargeWidget.hasWidgets(context)) return
        val request = OneTimeWorkRequestBuilder<ChargingWorker>()
            .setInputData(workDataOf(KEY_MODE to MODE_WIDGET))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_WIDGET, ExistingWorkPolicy.REPLACE, request)
    }

    /** While charging, refresh the widget about once a minute (constraint drops it when unplugged). */
    fun scheduleWidgetLoop(context: Context) {
        if (!ChargeWidget.hasWidgets(context)) return
        val request = OneTimeWorkRequestBuilder<ChargingWorker>()
            .setInputData(workDataOf(KEY_MODE to MODE_WIDGET_LOOP))
            .setInitialDelay(WIDGET_LOOP_DELAY_S, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_WIDGET_LOOP, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelWidgetLoop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_WIDGET_LOOP)
    }
}

class ChargingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(ChargingWork.KEY_MODE) ?: ChargingWork.MODE_WIDGET
        val collector = SnapshotCollector(applicationContext)
        return try {
            when (mode) {
                ChargingWork.MODE_NOTIFY -> runNotification(collector)
                else -> {
                    val snapshot = collector.collect(backendWaitMs = 1_500)
                    ChargeWidget.render(applicationContext, snapshot)
                    if (snapshot.local.plugged != 0) ChargingWork.scheduleWidgetLoop(applicationContext)
                }
            }
            Result.success()
        } catch (e: Exception) {
            AppLog.w("ChargingWorker", "$mode failed", e)
            Result.failure()
        }
    }

    private suspend fun runNotification(collector: SnapshotCollector) {
        val context = applicationContext
        if (!ChargeNotification.canPost(context)) {
            AppLog.w("ChargingWorker", "notification permission missing; skipping")
            ChargeWidget.render(context, collector.collect(backendWaitMs = 1_500))
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val start = System.currentTimeMillis()
        var snapshot = collector.collect(backendWaitMs = 2_500)
        AppLog.i("ChargingWorker", "charger notification started (backend=${snapshot.shell?.let { "yes" } ?: "none"})")
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

    /** Required for expedited work below Android 12, where it runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = ChargeNotification.build(applicationContext, null, 30)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(ChargeNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ChargeNotification.ID, notification)
        }
    }
}
