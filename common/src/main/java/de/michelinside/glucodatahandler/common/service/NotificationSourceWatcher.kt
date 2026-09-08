package de.michelinside.glucodatahandler.common.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.receiver.NotificationReceiver
import de.michelinside.glucodatahandler.common.utils.Log

/**
 * Periodic keep-alive watchdog for the notification reader.
 *
 * Runs every WAKEUP_CHECK_INTERVAL_MS (managed by StartupTrigger.ensureSourceWakeupCheck).
 * For every source app selected in the notification reader settings it checks when the last
 * notification from that app was seen. If the app was silent for longer than one notification
 * interval + tolerance (default 5 min interval => 6 min), it is considered killed/not running
 * and we try to bring it back to the foreground so it keeps posting glucose notifications.
 *
 * Note: starting an activity from background is restricted by Android. It only works while
 * GlucoDataHandler holds the overlay (draw-over-other-apps) permission or is otherwise allowed
 * to start background activities. Force-stopped apps can not be woken up at all.
 */
class NotificationSourceWatcher : BroadcastReceiver() {

    private val LOG_ID = "GDH.NotificationSourceWatcher"

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            if(!sharedPref.getBoolean(Constants.SHARED_PREF_SOURCE_NOTIFICATION_ENABLED, false) ||
                !sharedPref.getBoolean(Constants.SHARED_PREF_SOURCE_NOTIFICATION_WAKEUP_ENABLED, false)) {
                Log.d(LOG_ID, "Wake-up disabled - stopping watchdog")
                StartupTrigger.cancelSourceWakeupCheck()
                return
            }
            Log.i(LOG_ID, "Source wake-up check triggered (action: ${intent.action})")

            val glucoseApp = sharedPref.getString(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_APP, "").orEmpty()
            val iobApp = sharedPref.getString(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_IOB_APP, "").orEmpty()
            val iobEnabled = sharedPref.getBoolean(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_IOB_ENABLED, true)
            val cobEnabled = sharedPref.getBoolean(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_COB_ENABLED, false)
            val targets = mutableSetOf<String>()
            if(glucoseApp.isNotEmpty())
                targets.add(glucoseApp)
            if(iobApp.isNotEmpty() && (iobEnabled || cobEnabled))
                targets.add(iobApp)

            // timeout: one reader interval + 1 minute tolerance (interval default 5 min -> 6 min)
            val interval = sharedPref.getInt(Constants.SHARED_PREF_SOURCE_NOTIFICATION_READER_INTERVAl, 5).coerceAtLeast(1)
            val timeoutMs = (interval + 1) * 60 * 1000L
            val now = System.currentTimeMillis()

            for(pkg in targets) {
                val lastSeen = NotificationReceiver.getLastNotificationSeen(context, pkg)
                val silentFor = if(lastSeen > 0) now - lastSeen else -1L
                if(lastSeen == 0L || silentFor > timeoutMs) {
                    Log.w(LOG_ID, "Source app $pkg is silent (last seen: ${if(lastSeen>0) silentFor/1000 else "never"}s ago, timeout ${timeoutMs/1000}s) - try to wake it up")
                    wakeUpApp(context, pkg)
                } else {
                    Log.d(LOG_ID, "Source app $pkg is alive (last seen ${silentFor/1000}s ago, timeout ${timeoutMs/1000}s)")
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onReceive exception: " + exc.message.toString())
        } finally {
            // chain the next check - only schedules if the feature is still enabled
            StartupTrigger.ensureSourceWakeupCheck(context)
        }
    }

    private fun wakeUpApp(context: Context, packageName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if(launchIntent == null) {
                Log.w(LOG_ID, "No launch intent for $packageName - cannot wake it up")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
            Log.i(LOG_ID, "Wake-up intent sent for $packageName")
        } catch (exc: Exception) {
            // background activity start is restricted on Android 10+ without overlay permission
            Log.e(LOG_ID, "Cannot wake up $packageName: " + exc.message.toString())
        }
    }
}
