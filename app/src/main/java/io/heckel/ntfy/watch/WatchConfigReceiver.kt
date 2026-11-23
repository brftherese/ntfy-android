package io.heckel.ntfy.watch

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService

class WatchConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val value = intent.getStringExtra("url")
        val preferences = context.getSharedPreferences("infinity_watch", Context.MODE_PRIVATE)
        if (value == "clear") {
            preferences.edit().remove("url").apply()
            setResultData("ok")
            return
        }
        val uri = value?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null || uri.scheme != "https" || uri.host != "infinity.frft.org" ||
            !uri.path.orEmpty().startsWith("/texts/capture/")) {
            setResultData("rejected")
            return
        }
        preferences.edit().putString("url", value).apply()
        if (Build.VERSION.SDK_INT >= 24) {
            NotificationListenerService.requestRebind(ComponentName(context, MessagesListener::class.java))
        }
        setResultData("ok")
    }
}
