package io.heckel.ntfy.msg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SpeechStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SpeechPlayer.STOP_ACTION) SpeechPlayer.stopCurrent(context.applicationContext)
    }
}
