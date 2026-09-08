package de.michelinside.glucodatahandler.transfer

import android.content.Context
import android.content.Intent
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.Intents
import de.michelinside.glucodatahandler.common.receiver.XDripBroadcastReceiver
import de.michelinside.glucodatahandler.common.utils.Log

class XDripBroadcast: AppBroadcasts() {
    override val LOG_ID = "GDH.transfer.XDripBroadcast"
    override val receiverPrefKey = Constants.SHARED_PREF_XDRIP_BROADCAST_RECEIVERS
    override val enablePref = Constants.SHARED_PREF_SEND_XDRIP_BROADCAST

    override fun getIntent(context: Context): Intent? {
        val xDripExtras = XDripBroadcastReceiver.createExtras(context)
        if (xDripExtras != null) {
            val intent = Intent(Intents.XDRIP_BROADCAST_ACTION)
            intent.putExtras(xDripExtras)
            return intent
        }
        return null
    }

    override fun execute(context: Context): Boolean {
        try {
            // no data -> nothing to send
            if(XDripBroadcastReceiver.createExtras(context) == null)
                return false
            var receivers = GlucoDataService.sharedPref!!.getStringSet(receiverPrefKey, HashSet<String>())
            Log.i(LOG_ID, "Forward " + receiverPrefKey + " Broadcast to " + receivers?.size.toString() + " receivers")
            if (receivers == null || receivers.size == 0) {
                receivers = setOf("")
            }
            var sent = false
            for( receiver in receivers ) {
                // "AAPSv2(G7)" is stored as package:suffix -> send to AAPS with the G7 broadcast protocol
                val aapsV2G7 = receiver.endsWith(Constants.XDRIP_BROADCAST_AAPS_V2_G7_SUFFIX)
                val target = if(aapsV2G7) receiver.removeSuffix(Constants.XDRIP_BROADCAST_AAPS_V2_G7_SUFFIX) else receiver
                if(!aapsV2G7 && receiver == Constants.XDRIP_BROADCAST_AAPS_PACKAGE && receivers.contains(Constants.XDRIP_BROADCAST_AAPS_V2_G7_TARGET)) {
                    Log.d(LOG_ID, "Skip normal AAPS broadcast - AAPSv2(G7) target is also selected (they are mutually exclusive)")
                    continue
                }
                val extras = XDripBroadcastReceiver.createExtras(context, aapsV2G7)
                if(extras == null) {
                    Log.w(LOG_ID, "No extras to send - skip receiver $receiver")
                    continue
                }
                val sendIntent = Intent(Intents.XDRIP_BROADCAST_ACTION)
                sendIntent.putExtras(extras)
                sendIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                if (target.isNotEmpty()) {
                    sendIntent.setPackage(target)
                    Log.d(LOG_ID, "Send broadcast " + receiverPrefKey + " to " + target + (if(aapsV2G7) " (AAPSv2 G7 protocol)" else ""))
                } else {
                    Log.d(LOG_ID, "Send global broadcast " + receiverPrefKey)
                    sendIntent.putExtra(Constants.EXTRA_SOURCE_PACKAGE, context.packageName)
                }
                context.sendBroadcast(sendIntent)
                sent = true
            }
            return sent
        } catch (ex: Exception) {
            Log.e(LOG_ID, "Exception while sending xDrip broadcast: " + ex)
        }
        return false
    }
}