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
    private var wakeupAlarmManager: AlarmManager? = null
    private var wakeupPendingIntent: PendingIntent? = null
    private const val WAKEUP_REQUEST_CODE = 913
    private const val WAKEUP_CHECK_INTERVAL_MS = 5 * 60 * 1000L

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

    /**
     * Notification reader keep-alive: schedule a periodic check that the selected source app
     * is still posting notifications. If it is silent for too long, NotificationSourceWatcher
     * tries to wake it up again. The alarm chain re-schedules itself in the receiver as long
     * as the feature stays enabled.
     */
    fun ensureSourceWakeupCheck(context: Context) {
        try {
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            val sourceEnabled = sharedPref.getBoolean(Constants.SHARED_PREF_SOURCE_NOTIFICATION_ENABLED, false)
            val wakeupEnabled = sharedPref.getBoolean(Constants.SHARED_PREF_SOURCE_NOTIFICATION_WAKEUP_ENABLED, false)
            val glucoseApp = sharedPref.getString(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_APP, "").orEmpty()
            val iobApp = sharedPref.getString(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_IOB_APP, "").orEmpty()
            val iobEnabled = sharedPref.getBoolean(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_IOB_ENABLED, true)
            val cobEnabled = sharedPref.getBoolean(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_COB_ENABLED, false)
            val hasTarget = glucoseApp.isNotEmpty() || (iobApp.isNotEmpty() && (iobEnabled || cobEnabled))
            if(!sourceEnabled || !wakeupEnabled || !hasTarget) {
                cancelSourceWakeupCheck()
                return
            }
            if(wakeupAlarmManager != null && wakeupPendingIntent != null) {
                Log.d(LOG_ID, "Source wakeup check already scheduled")
                return
            }
            val intent = Intent(context, NotificationSourceWatcher::class.java)
            intent.action = Constants.ACTION_SOURCE_WAKEUP_CHECK
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            wakeupPendingIntent = PendingIntent.getBroadcast(
                context,
                WAKEUP_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
            wakeupAlarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            var hasExactAlarmPermission = true
            if (!Utils.canScheduleExactAlarms(context)) {
                Log.d(LOG_ID, "No exact alarm permission for source wakeup check!")
                hasExactAlarmPermission = false
            }
            val alarmTime = System.currentTimeMillis() + WAKEUP_CHECK_INTERVAL_MS
            Log.i(LOG_ID, "Schedule source wakeup check at ${Utils.getUiTimeStamp(alarmTime)} - exactAlarm: $hasExactAlarmPermission")
            if (hasExactAlarmPermission) {
                wakeupAlarmManager!!.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    wakeupPendingIntent!!
                )
            } else {
                wakeupAlarmManager!!.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    wakeupPendingIntent!!
                )
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "ensureSourceWakeupCheck exception: " + exc.message.toString())
            cancelSourceWakeupCheck()
        }
    }

    fun cancelSourceWakeupCheck() {
        try {
            if(wakeupAlarmManager != null && wakeupPendingIntent != null) {
                Log.i(LOG_ID, "Cancel source wakeup check")
                wakeupAlarmManager!!.cancel(wakeupPendingIntent!!)
                wakeupAlarmManager = null
                wakeupPendingIntent = null
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "cancelSourceWakeupCheck exception: " + exc.message.toString())
            wakeupAlarmManager = null
            wakeupPendingIntent = null
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