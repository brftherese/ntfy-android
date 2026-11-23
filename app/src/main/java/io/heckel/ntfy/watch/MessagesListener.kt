package io.heckel.ntfy.watch

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MessagesListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (!matches(sbn)) return
            val url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(URL_KEY, null) ?: return
            buildPayload(sbn)?.let { payload ->
                sender.execute { post(url, payload) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle notification", e)
        }
    }

    override fun onListenerConnected() {
        try {
            activeNotifications?.filter(::matches)?.forEach(::onNotificationPosted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process active notifications", e)
        }
    }

    private fun buildPayload(sbn: StatusBarNotification): String? {
        val notification = sbn.notification
        val thread = sbn.tag?.substringAfter(TAG_PREFIX) ?: return null
        if (thread.isEmpty() || !thread.all { it.isDigit() }) return null
        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val messages = JSONArray()
        val styleMessages = style?.messages
        if (styleMessages != null) {
            styleMessages.takeLast(5).forEach { message ->
                messages.put(JSONObject().put("sender", message.person?.takeUnless { it == style.user }?.name ?: "")
                    .put("text", message.text?.toString() ?: "")
                    .put("time", message.timestamp))
            }
        } else {
            val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            raw?.mapNotNull { it as? android.os.Bundle }?.takeLast(5)?.forEach { message ->
                val sender = (message.getCharSequence("sender")
                    ?: (message.getParcelable("sender_person") as? android.app.Person)?.name)?.toString() ?: ""
                messages.put(JSONObject().put("sender", sender)
                    .put("text", message.getCharSequence("text")?.toString() ?: "")
                    .put("time", message.getLong("time", 0L)))
            }
        }
        return JSONObject()
            .put("v", 1)
            .put("key", sbn.key)
            .put("thread", thread)
            .put("postTime", sbn.postTime)
            .put("when", notification.`when`)
            .put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
            .put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
            .put("conversation", extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: "")
            .put("group", extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false))
            .put("messages", messages)
            .toString()
    }

    private fun post(url: String, payload: String) {
        val delays = longArrayOf(0, 2_000, 5_000, 15_000)
        for (delay in delays) {
            if (delay != 0L) try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                connection.disconnect()
                if (status in 200..299) return
                Log.w(TAG, "POST failed with status $status")
            } catch (e: Exception) {
                Log.w(TAG, "POST failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun matches(sbn: StatusBarNotification): Boolean =
        sbn.packageName == MESSAGES_PACKAGE &&
            sbn.tag?.startsWith(TAG_PREFIX) == true &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0

    companion object {
        private const val TAG = "InfinityWatch"
        private const val PREFS = "infinity_watch"
        private const val URL_KEY = "url"
        private const val MESSAGES_PACKAGE = "com.google.android.apps.messaging"
        private const val TAG_PREFIX = "com.google.android.apps.messaging:incoming_message:"
        private val sender = Executors.newSingleThreadExecutor()
    }
}
