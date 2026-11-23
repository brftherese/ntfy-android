package io.heckel.ntfy.watch

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.splitTags

/**
 * Pebble build -pebble-own20: infinity turns Wireless debugging back on through this app when Shizuku has
 * stopped (Android drops Wireless debugging with Wi-Fi), so Termux can start Shizuku again over adb.
 * A push tagged adb-wifi-on from his own server is acted on and never shown. Needs WRITE_SECURE_SETTINGS
 * (granted by the owner through Shizuku); Termux's shell can't do it, Android refuses its settings command.
 */
object AdbWifi {
    private const val TAG = "InfinityAdbWifi"
    const val TAG_ON = "adb-wifi-on"

    fun handles(subscription: Subscription, notification: Notification): Boolean =
        splitTags(notification.tags).contains(TAG_ON) &&
            (Uri.parse(subscription.baseUrl).host ?: "").let { it == "frft.org" || it.endsWith(".frft.org") }

    fun enable(context: Context) {
        try {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            Log.i(TAG, "Wireless debugging turned on")
        } catch (e: Exception) {
            Log.w(TAG, "Could not turn Wireless debugging on: ${e.javaClass.simpleName}")
        }
    }
}
