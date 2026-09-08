package de.michelinside.glucodatahandler.common.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Intent
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService.Companion.foreground
import de.michelinside.glucodatahandler.common.utils.Log
import de.michelinside.glucodatahandler.common.utils.Utils

object StartupTrigger {
    private const val LOG_ID = "GDH.srv.StartupTrigger"

    private var alarmManager: AlarmManager? = null
    private var alarmPendingIntent: PendingIntent? = null
    private var restartAlarmManager: AlarmManager? = null
    private var restartPendingIntent: PendingIntent? = null

    fun cancelRestart() {
        try {
            if(restartAlarmManager != null && restartPendingIntent != null) {
                Log.i(LOG_ID, "Cancel scheduled restart")
                restartAlarmManager!!.cancel(restartPendingIntent!!)
                restartAlarmManager = null
                restartPendingIntent = null
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "cancelRestart exception: " + exc.message.toString())
        }
    }

    /**
     * Keep-alive: schedule a one-shot restart of the service after a delay.
     * Unlike triggerStartService this does NOT bail out while the service is still
     * in foreground - the registered receiver decides whether a restart is needed.
     * Used for onTaskRemoved (recent-apps swipe) and onDestroy (OEM task killer) recovery.
     */
    fun scheduleRestart(context: Context, receiver: Class<*>, delayMs: Long) {
        try {
            restartAlarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, receiver)
            intent.action = Constants.ACTION_START_FOREGROUND
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            restartPendingIntent = PendingIntent.getBroadcast(
                context,
                912,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
            var hasExactAlarmPermission = true
            if (!Utils.canScheduleExactAlarms(context)) {
                Log.d(LOG_ID, "Need permission to set exact alarm for restart!")
                hasExactAlarmPermission = false
            }
            val alarmTime = System.currentTimeMillis() + delayMs
            Log.i(LOG_ID, "Schedule restart at ${Utils.getUiTimeStamp(alarmTime)} - exactAlarm: $hasExactAlarmPermission")
            if (hasExactAlarmPermission) {
                restartAlarmManager!!.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    restartPendingIntent!!
                )
            } else {
                restartAlarmManager!!.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    restartPendingIntent!!
                )
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "scheduleRestart exception: " + exc.message.toString())
            cancelRestart()
        }
    }

    fun stopTrigger() {
        try {
            if(alarmManager != null && alarmPendingIntent != null) {
                Log.i(LOG_ID, "Stop trigger")
                alarmManager!!.cancel(alarmPendingIntent!!)
                alarmManager = null
                alarmPendingIntent = null
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "stopTrigger exception: " + exc.message.toString())
        }
    }

    fun triggerStartService(context: Context, receiver: Class<*>) {
        try {
            Log.i(LOG_ID, "Trigger start service - foreground: $foreground - alarm active: ${alarmManager != null && alarmPendingIntent != null}")
            if(foreground || (alarmManager != null && alarmPendingIntent != null))
                return
            alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            var hasExactAlarmPermission = true
            if (!Utils.canScheduleExactAlarms(context)) {
                Log.d(LOG_ID, "Need permission to set exact alarm!")
                hasExactAlarmPermission = false
            }
            val intent = Intent(context, receiver)
            intent.action = Constants.ACTION_START_FOREGROUND
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            alarmPendingIntent = PendingIntent.getBroadcast(
                context,
                911,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
            val alarmTime = System.currentTimeMillis() + 1000
            Log.i(LOG_ID, "Trigger alarm at ${Utils.getUiTimeStamp(alarmTime)} - exactAlarm: $hasExactAlarmPermission")
            if (hasExactAlarmPermission) {
                alarmManager!!.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    alarmPendingIntent!!
                )
            } else {
                alarmManager!!.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    alarmPendingIntent!!
                )
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "triggerStartService exception: " + exc.message.toString())
            stopTrigger()
        }
    }
}