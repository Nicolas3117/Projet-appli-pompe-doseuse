package com.esp32pumpwifi.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object CriticalAlarmScheduler {
    private const val INACTIVITY_ACTION = "com.esp32pumpwifi.app.action.CHECK_INACTIVITY"
    private const val TANK_ACTION = "com.esp32pumpwifi.app.action.CHECK_TANK"

    private const val INACTIVITY_REQUEST_CODE = 41001
    private const val TANK_REQUEST_CODE = 41002

    private const val TANK_WORK_NAME = "tank_recalc"
    private const val INACTIVITY_WORK_NAME = "app_inactivity"

    fun ensureScheduled(context: Context) {
        if (ExactAlarmPermissionHelper.canScheduleExactAlarms(context)) {
            cancelBestEffortWork(context)
            scheduleInactivityAlarmDaily(context)
            scheduleTankAlarmEvery15Min(context)
        } else {
            scheduleBestEffortWork(context)
        }
    }

    fun scheduleInactivityAlarmDaily(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = nextNoonMillis()
        val pendingIntent = inactivityPendingIntent(context)
        scheduleExact(alarmManager, triggerAtMillis, pendingIntent)
    }

    fun scheduleTankAlarmEvery15Min(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = nextQuarterHourMillis()
        val pendingIntent = tankPendingIntent(context)
        scheduleExact(alarmManager, triggerAtMillis, pendingIntent)
    }

    fun rescheduleAfterBoot(context: Context) {
        ensureScheduled(context)
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(inactivityPendingIntent(context))
        alarmManager.cancel(tankPendingIntent(context))
    }

    private fun scheduleExact(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return
        }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )
    }

    private fun inactivityPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, InactivityAlarmReceiver::class.java).apply {
            action = INACTIVITY_ACTION
        }
        return PendingIntent.getBroadcast(
            context,
            INACTIVITY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun tankPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TankAlarmReceiver::class.java).apply {
            action = TANK_ACTION
        }
        return PendingIntent.getBroadcast(
            context,
            TANK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextNoonMillis(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Long {
        var target = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
        if (!now.isBefore(target)) {
            target = target.plusDays(1)
        }
        return target.toInstant().toEpochMilli()
    }

    private fun nextQuarterHourMillis(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Long {
        val currentMinute = now.minute
        val nextMinute = ((currentMinute / 15) + 1) * 15
        val target = if (nextMinute >= 60) {
            now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        } else {
            now.withMinute(nextMinute).withSecond(0).withNano(0)
        }
        return target.toInstant().toEpochMilli()
    }

    private fun scheduleBestEffortWork(context: Context) {
        val tankWork =
            PeriodicWorkRequestBuilder<TankRecalcWorker>(15, TimeUnit.MINUTES)
                .addTag(TANK_WORK_NAME)
                .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                TANK_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                tankWork
            )

        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val target = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
        val next = if (now.isBefore(target)) target else target.plusDays(1)
        val delayMs = Duration.between(now, next).toMillis()

        val inactivityWork =
            PeriodicWorkRequestBuilder<AppInactivityWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(INACTIVITY_WORK_NAME)
                .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                INACTIVITY_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                inactivityWork
            )
    }

    private fun cancelBestEffortWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TANK_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(INACTIVITY_WORK_NAME)
    }
}
