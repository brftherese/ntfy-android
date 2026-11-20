package io.heckel.ntfy.msg

import android.app.*
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.heckel.ntfy.R
import io.heckel.ntfy.db.*
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.*
import java.util.*
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class NotificationService(val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repository = Repository.getInstance(context)
    private val markwon = MarkwonFactory.createForNotification(context)
    private val appBaseUrl = context.getString(R.string.app_base_url)

    fun display(subscription: Subscription, notification: Notification) {
        Log.d(TAG, "Displaying notification $notification")
        displayInternal(subscription, notification)
    }

    fun update(subscription: Subscription, notification: Notification) {
        val active = notificationManager.activeNotifications.find { it.id == notification.notificationId } != null
        if (active) {
            Log.d(TAG, "Updating notification $notification")
            displayInternal(subscription, notification, update = true)
        }
    }

    fun cancel(notification: Notification) {
        if (notification.notificationId != 0) {
            Log.d(TAG, "Cancelling notification ${notification.id}: ${decodeMessage(notification)}")
            notificationManager.cancel(notification.notificationId)
        }
    }

    fun cancel(notificationId: Int) {
        if (notificationId != 0) {
            Log.d(TAG, "Cancelling notification $notificationId")
            notificationManager.cancel(notificationId)
        }
    }

    fun createDefaultNotificationChannels() {
        maybeCreateNotificationGroup(DEFAULT_GROUP, context.getString(R.string.channel_notifications_group_default_name))
        ALL_PRIORITIES.forEach { priority -> maybeCreateNotificationChannel(DEFAULT_GROUP, priority) }
    }

    fun createSubscriptionNotificationChannels(subscription: Subscription) {
        val groupId = subscriptionGroupId(subscription)
        maybeCreateNotificationGroup(groupId, subscriptionGroupName(subscription))
        ALL_PRIORITIES.forEach { priority -> maybeCreateNotificationChannel(groupId, priority) }
    }

    fun deleteSubscriptionNotificationChannels(subscription: Subscription) {
        val groupId = subscriptionGroupId(subscription)
        ALL_PRIORITIES.forEach { priority -> maybeDeleteNotificationChannel(groupId, priority) }
        maybeDeleteNotificationGroup(groupId)
    }

    private fun subscriptionGroupId(subscription: Subscription): String {
        return SUBSCRIPTION_GROUP_PREFIX + subscription.id.toString()
    }

    private fun subscriptionGroupName(subscription: Subscription): String {
        return subscription.displayName ?: subscriptionTopicShortUrl(subscription)
    }

    /**
     * Pebble build: the time a fresh push reached the phone, in ms. The server's stamp is whole
     * seconds and its clock can step back, so three cards sent within a second showed out of order
     * (2026-11-05). A push caught up after being offline keeps the server's time.
     */
    private fun arrivedAt(notification: Notification): Long {
        val sent = notification.timestamp * 1000
        return arrivals.getOrPut(notification.id) {
            val now = System.currentTimeMillis()
            if (kotlin.math.abs(now - sent) < FRESH_MS) now else sent
        }
    }

    private fun displayInternal(subscription: Subscription, notification: Notification, update: Boolean = false) {
        val title = formatTitle(appBaseUrl, subscription, notification)
        val groupId = if (subscription.dedicatedChannels) subscriptionGroupId(subscription) else DEFAULT_GROUP
        val channelId = toChannelId(groupId, notification.priority)
        val insistent = notification.priority == PRIORITY_MAX &&
                (repository.getInsistentMaxPriorityEnabled() || subscription.insistent == Repository.INSISTENT_MAX_PRIORITY_ENABLED)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Colors.notificationIcon(context))
            .setContentTitle(title)
            .setWhen(arrivedAt(notification)) // Pebble build: when it reached the phone, so a burst stays in order
            .setShowWhen(true)
            .setOnlyAlertOnce(true) // Do not vibrate or play sound if already showing (updates!)
            .setAutoCancel(true) // Cancel when notification is clicked
        setStyleAndText(builder, subscription, notification) // Preview picture or big text style
        setClickAction(builder, subscription, notification)
        maybeSetDeleteIntent(builder, insistent)
        maybeSetSound(builder, insistent, update)
        maybeSetProgress(builder, notification)
        val wearableExtender = NotificationCompat.WearableExtender()
        maybeAddWatchAction(wearableExtender, notification)
        maybeAddOpenAction(builder, wearableExtender, notification)
        maybeAddBrowseAction(builder, wearableExtender, notification)
        maybeAddDownloadAction(builder, wearableExtender, notification)
        maybeAddCancelAction(builder, wearableExtender, notification)
        maybeAddUserActions(builder, wearableExtender, notification)
        builder.extend(wearableExtender)

        maybeCreateNotificationGroup(groupId, subscriptionGroupName(subscription))
        maybeCreateNotificationChannel(groupId, notification.priority)
        maybePlayInsistentSound(groupId, insistent)

        notificationManager.notify(notification.notificationId, builder.build())
    }

    private fun maybeSetDeleteIntent(builder: NotificationCompat.Builder, insistent: Boolean) {
        if (!insistent) {
            return
        }
        val intent = Intent(context, DeleteBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
        builder.setDeleteIntent(pendingIntent)
    }

    private fun maybeSetSound(builder: NotificationCompat.Builder, insistent: Boolean, update: Boolean) {
        // Note that the sound setting is ignored in Android => O (26) in favor of notification channels
        val hasSound = !update && !insistent
        if (hasSound) {
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(defaultSoundUri)
        } else {
            builder.setSound(null)
        }
    }

    private fun setStyleAndText(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        val contentUri = notification.attachment?.contentUri
        val isSupportedImage = supportedImage(notification.attachment?.type)
        val subscriptionIcon = subscription.icon?.readBitmapFromUriOrNull(context)
        val notificationIcon = notification.icon?.contentUri?.readBitmapFromUriOrNull(context)
        val largeIcon = notificationIcon ?: subscriptionIcon
        if (contentUri != null && isSupportedImage) {
            try {
                val attachmentBitmap = contentUri.readBitmapFromUri(context)
                builder
                    .setContentText(maybeAppendActionErrors(maybeMarkdown(formatMessage(notification), notification), notification))
                    .setLargeIcon(attachmentBitmap)
                    .setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(attachmentBitmap)
                        .bigLargeIcon(largeIcon)) // May be null
            } catch (_: Exception) {
                val message = maybeAppendActionErrors(formatMessageMaybeWithAttachmentInfos(notification), notification)
                builder
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            }
        } else {
            val message = maybeAppendActionErrors(formatMessageMaybeWithAttachmentInfos(notification), notification)
            builder
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setLargeIcon(largeIcon) // May be null
        }
    }

    private fun formatMessageMaybeWithAttachmentInfos(notification: Notification): CharSequence {
        val message = maybeMarkdown(formatMessage(notification), notification)
        val attachment = notification.attachment ?: return message
        val attachmentInfos = if (attachment.size != null) {
            "${attachment.name}, ${formatBytes(attachment.size)}"
        } else {
            attachment.name
        }
        if (attachment.progress in 0..99) {
            return context.getString(R.string.notification_popup_file_downloading, attachmentInfos, attachment.progress, message)
        }
        if (attachment.progress == ATTACHMENT_PROGRESS_DONE) {
            return context.getString(R.string.notification_popup_file_download_successful, message, attachmentInfos)
        }
        if (attachment.progress == ATTACHMENT_PROGRESS_FAILED) {
            return context.getString(R.string.notification_popup_file_download_failed, message, attachmentInfos)
        }
        return context.getString(R.string.notification_popup_file, message, attachmentInfos)
    }

    private fun setClickAction(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        if (notification.click == "") {
            builder.setContentIntent(detailActivityIntent(subscription))
        } else {
            try {
                val uri = notification.click.toUri()
                val viewIntent = PendingIntent.getActivity(context, Random().nextInt(), Intent(Intent.ACTION_VIEW, uri), PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(viewIntent)
            } catch (_: Exception) {
                builder.setContentIntent(detailActivityIntent(subscription))
            }
        }
    }

    private fun maybeSetProgress(builder: NotificationCompat.Builder, notification: Notification) {
        val progress = notification.attachment?.progress
        if (progress in 0..99) {
            builder.setProgress(100, progress!!, false)
        } else {
            builder.setProgress(0, 0, false) // Remove progress bar
        }
    }

    private fun addAction(
        builder: NotificationCompat.Builder,
        wearableExtender: NotificationCompat.WearableExtender,
        action: NotificationCompat.Action
    ) {
        builder.addAction(action)
        wearableExtender.addAction(action)
    }

    private fun maybeAddOpenAction(builder: NotificationCompat.Builder, wearableExtender: NotificationCompat.WearableExtender, notification: Notification) {
        if (notification.attachment?.contentUri != null) {
            val contentUri = notification.attachment.contentUri.toUri()
            val intent = Intent(Intent.ACTION_VIEW, contentUri).apply {
                setDataAndType(contentUri, notification.attachment.type ?: "application/octet-stream") // Required for Android <= P
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            addAction(builder, wearableExtender, NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_open), pendingIntent).build())
        }
    }

    /** Pebble build: this belongs on the watch notification only, not in ntfy's phone actions. */
    private fun maybeAddWatchAction(wearableExtender: NotificationCompat.WearableExtender, notification: Notification) {
        val token = splitTags(notification.tags)
            .firstOrNull { it.startsWith(WATCH_PREFIX) }
            ?.removePrefix(WATCH_PREFIX)
            ?.takeIf { WATCH_TOKEN.matches(it) }
            ?: return
        val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
            putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_WATCH_ACTION)
            putExtra(BROADCAST_EXTRA_TOKEN, token)
            putExtra(BROADCAST_EXTRA_BASE_URL, notification.actions.orEmpty()
                .asSequence()
                .mapNotNull { it.url?.let(::extractBaseUrl)?.takeIf(String::isNotEmpty) }
                .firstOrNull()
                ?: WATCH_DEFAULT_BASE_URL)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, Random().nextInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        wearableExtender.addAction(NotificationCompat.Action.Builder(
            0, context.getString(R.string.notification_popup_action_open_on_watch), pendingIntent
        ).build())
    }

    private fun maybeAddBrowseAction(builder: NotificationCompat.Builder, wearableExtender: NotificationCompat.WearableExtender, notification: Notification) {
        if (notification.attachment?.contentUri != null) {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            addAction(builder, wearableExtender, NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_browse), pendingIntent).build())
        }
    }

    private fun maybeAddDownloadAction(builder: NotificationCompat.Builder, wearableExtender: NotificationCompat.WearableExtender, notification: Notification) {
        if (notification.attachment?.contentUri == null && listOf(ATTACHMENT_PROGRESS_NONE, ATTACHMENT_PROGRESS_FAILED).contains(notification.attachment?.progress)) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_START)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            addAction(builder, wearableExtender, NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_download), pendingIntent).build())
        }
    }

    private fun maybeAddCancelAction(builder: NotificationCompat.Builder, wearableExtender: NotificationCompat.WearableExtender, notification: Notification) {
        if (notification.attachment?.contentUri == null && notification.attachment?.progress in 0..99) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_CANCEL)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            addAction(builder, wearableExtender, NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_cancel), pendingIntent).build())
        }
    }

    private fun maybeAddUserActions(builder: NotificationCompat.Builder, wearableExtender: NotificationCompat.WearableExtender, notification: Notification) {
        notification.actions?.forEach { action ->
            val actionType = action.action.lowercase(Locale.getDefault())
            if (actionType == ACTION_VIEW) {
                // Hack: Action "view" with "clear=true" is a special case, because it's apparently impossible to start a
                // URL activity from PendingIntent.getActivity() and also close the notification. To clear it, we
                // launch our own Activity (ViewActionWithClearActivity) which then calls the actual activity

                if (action.clear == true) {
                    addViewUserActionWithClear(builder, wearableExtender, notification, action)
                } else {
                    addViewUserActionWithoutClear(builder, wearableExtender, action)
                }
            } else {
                addHttpBroadcastOrCopyUserAction(builder, wearableExtender, notification, action)
            }
        }
    }

    /**
     * Open the URL and do NOT cancel the notification (clear=false). This uses a normal Intent with the given URL.
     * The other case is much more interesting.
     */
    private fun addViewUserActionWithoutClear(builder: NotificationCompat.Builder, wearableExtender: NotificationCompat.WearableExtender, action: Action) {
        try {
            val url = action.url ?: return
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            addAction(builder, wearableExtender, NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    /**
     * HACK: Open the URL and CANCEL the notification (clear=true). This is a SPECIAL case with a horrible workaround.
     * We call our own activity ViewActionWithClearActivity and open the URL from there.
     */
    private fun addViewUserActionWithClear(builder: NotificationCompat.Builder, wearableExtender: NotificationCompat.WearableExtender, notification: Notification, action: Action) {
        try {
            val url = action.url ?: return
            val intent = Intent(context, ViewActionWithClearActivity::class.java).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(VIEW_ACTION_EXTRA_URL, url)
                putExtra(VIEW_ACTION_EXTRA_NOTIFICATION_ID, notification.notificationId)
                putExtra(VIEW_ACTION_EXTRA_SUBSCRIPTION_ID, notification.subscriptionId)
                putExtra(VIEW_ACTION_EXTRA_SEQUENCE_ID, notification.sequenceId)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            addAction(builder, wearableExtender, NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    private fun addHttpBroadcastOrCopyUserAction(builder: NotificationCompat.Builder, wearableExtender: NotificationCompat.WearableExtender, notification: Notification, action: Action) {
        val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
            putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_USER_ACTION)
            putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(BROADCAST_EXTRA_ACTION_ID, action.id)
        }
        val isReply = isReplyHttpAction(action)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or if (isReply) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, pendingFlags)
        val label = formatActionLabel(action)
        val actionBuilder = NotificationCompat.Action.Builder(0, label, pendingIntent)
        if (isReply) {
            val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
                .setLabel(action.label)
                .build()
            // Marked as an inline reply that opens no screen, as messaging apps do;
            // otherwise the shade can show the field's cursor without raising the keyboard.
            actionBuilder
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
        }
        addAction(builder, wearableExtender, actionBuilder.build())
    }

    /**
     * Receives the broadcast from
     * - the "http", "broadcast", and "copy" action button (the "view" action is handled differently)
     * - the "download"/"cancel" action button
     *
     * Then queues a Worker via WorkManager to execute the action in the background
     */
    class UserActionBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = intent.getStringExtra(BROADCAST_EXTRA_TYPE) ?: return
            if (type == BROADCAST_TYPE_WATCH_ACTION) {
                val pendingResult = goAsync()
                val token = intent.getStringExtra(BROADCAST_EXTRA_TOKEN)
                val baseUrl = intent.getStringExtra(BROADCAST_EXTRA_BASE_URL)
                    ?: WATCH_DEFAULT_BASE_URL
                if (token == null) {
                    pendingResult.finish()
                    return
                }
                val app = context.applicationContext as? io.heckel.ntfy.app.Application
                if (app == null) {
                    PebbleWatchAction.start(context)
                    pendingResult.finish()
                    return
                }
                app.ioScope.launch {
                    try {
                        withTimeoutOrNull(WATCH_LAUNCH_DEADLINE_MS) {
                            PebbleWatchAction.focus(context.applicationContext, baseUrl, token)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not focus infinity approval on watch", e)
                    } finally {
                        // Launch even when focus failed or timed out: the watchapp can show its list.
                        PebbleWatchAction.start(context.applicationContext)
                        pendingResult.finish()
                    }
                }
                return
            }
            val notificationId = intent.getStringExtra(BROADCAST_EXTRA_NOTIFICATION_ID) ?: return
            when (type) {
                BROADCAST_TYPE_DOWNLOAD_START -> DownloadManager.enqueue(context, notificationId, userAction = true, DownloadType.ATTACHMENT)
                BROADCAST_TYPE_DOWNLOAD_CANCEL -> DownloadManager.cancel(context, notificationId)
                BROADCAST_TYPE_USER_ACTION -> {
                    val actionId = intent.getStringExtra(BROADCAST_EXTRA_ACTION_ID) ?: return
                    val remoteInputText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
                    UserActionManager.enqueue(context, notificationId, actionId, remoteInputText)
                }
            }
        }
    }

    /**
     * Receives a broadcast when a notification is swiped away. This is currently
     * only called for notifications with an insistent sound.
     */
    class DeleteBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Media player: Stopping insistent ring")
            val mediaPlayer = Repository.getInstance(context).mediaPlayer
            mediaPlayer.stop()
        }
    }

    private fun detailActivityIntent(subscription: Subscription): PendingIntent? {
        val intent = Intent(context, DetailActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(appBaseUrl, subscription))
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        }
        return TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent) // Add the intent, which inflates the back stack
            getPendingIntent(Random().nextInt(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) // Get the PendingIntent containing the entire back stack
        }
    }

    private fun maybeCreateNotificationChannel(group: String, priority: Int) {
        // Note: To change a notification channel, you must delete the old one and create a new one!

        val channelId = toChannelId(group, priority)
        val pause = 300L
        val channel = when (priority) {
            PRIORITY_MIN -> NotificationChannel(channelId, context.getString(R.string.common_priority_min_name), NotificationManager.IMPORTANCE_MIN)
            PRIORITY_LOW -> NotificationChannel(channelId, context.getString(R.string.common_priority_low_name), NotificationManager.IMPORTANCE_LOW)
            PRIORITY_HIGH -> {
                val channel = NotificationChannel(channelId, context.getString(R.string.common_priority_high_name), NotificationManager.IMPORTANCE_HIGH)
                channel.enableVibration(true)
                channel.vibrationPattern = longArrayOf(
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000
                )
                channel
            }
            PRIORITY_MAX -> {
                val channel = NotificationChannel(channelId, context.getString(R.string.common_priority_max_name), NotificationManager.IMPORTANCE_HIGH) // IMPORTANCE_MAX does not exist
                channel.enableLights(true)
                channel.enableVibration(true)
                channel.setBypassDnd(true)
                channel.vibrationPattern = longArrayOf(
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000,
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000,
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000
                )
                channel
            }
            else -> NotificationChannel(channelId, context.getString(R.string.common_priority_default_name), NotificationManager.IMPORTANCE_DEFAULT)
        }
        channel.group = group
        notificationManager.createNotificationChannel(channel)
    }

    private fun maybeDeleteNotificationChannel(group: String, priority: Int) {
        notificationManager.deleteNotificationChannel(toChannelId(group, priority))
    }

    private fun maybeCreateNotificationGroup(id: String, name: String) {
        notificationManager.createNotificationChannelGroup(NotificationChannelGroup(id, name))
    }

    private fun maybeDeleteNotificationGroup(id: String) {
        notificationManager.deleteNotificationChannelGroup(id)
    }

    fun toChannelId(groupId: String, priority: Int): String {
        return when (priority) {
            PRIORITY_MIN -> groupId + GROUP_SUFFIX_PRIORITY_MIN
            PRIORITY_LOW -> groupId + GROUP_SUFFIX_PRIORITY_LOW
            PRIORITY_HIGH -> groupId + GROUP_SUFFIX_PRIORITY_HIGH
            PRIORITY_MAX -> groupId + GROUP_SUFFIX_PRIORITY_MAX
            else -> groupId + GROUP_SUFFIX_PRIORITY_DEFAULT
        }
    }

    private fun maybePlayInsistentSound(groupId: String, insistent: Boolean) {
        if (!insistent) {
            return
        }
        try {
            val mediaPlayer = repository.mediaPlayer
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
                Log.d(TAG, "Media player: Playing insistent alarm on alarm channel")
                mediaPlayer.reset()
                mediaPlayer.setDataSource(context, getInsistentSound(groupId))
                mediaPlayer.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                mediaPlayer.isLooping = true
                mediaPlayer.prepare()
                mediaPlayer.start()
            } else {
                Log.d(TAG, "Media player: Alarm volume is 0; not playing insistent alarm")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Media player: Failed to play insistent alarm", e)
        }
    }

    private fun getInsistentSound(groupId: String): Uri {
        val channelId = toChannelId(groupId, PRIORITY_MAX)
        val channel = notificationManager.getNotificationChannel(channelId)
        return channel.sound
    }

    /**
     * Activity used to launch a URL.
     * .
     * Horrible hack: Action "view" with "clear=true" is a special case, because it's apparently impossible to start a
     * URL activity from PendingIntent.getActivity() and also close the notification. To clear it, we
     * launch this activity which then calls the actual activity.
     */
    class ViewActionWithClearActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "Created $this")
            val url = intent.getStringExtra(VIEW_ACTION_EXTRA_URL)
            val notificationId = intent.getIntExtra(VIEW_ACTION_EXTRA_NOTIFICATION_ID, 0)
            val subscriptionId = intent.getLongExtra(VIEW_ACTION_EXTRA_SUBSCRIPTION_ID, 0)
            val sequenceId = intent.getStringExtra(VIEW_ACTION_EXTRA_SEQUENCE_ID) ?: ""
            if (url == null) {
                finish()
                return
            }

            // Immediately start the actual activity
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start activity from URL $url", e)
                val message = if (e is ActivityNotFoundException) url else e.message
                Toast
                    .makeText(this, getString(R.string.detail_item_cannot_open_url, message), Toast.LENGTH_LONG)
                    .show()
            }

            // Cancel notification
            val notifier = NotificationService(this)
            notifier.cancel(notificationId)

            // Mark notification as read; We can't use lifecycleScope here, because we
            // call finish() right after, so we do this awkward ioScope thing.
            if (subscriptionId != 0L && sequenceId.isNotEmpty()) {
                val app = applicationContext as io.heckel.ntfy.app.Application
                app.ioScope.launch {
                    val repository = Repository.getInstance(app)
                    repository.markAsReadBySequenceId(subscriptionId, sequenceId)
                }
            }

            // Close this activity
            finish()
        }
    }

    private fun maybeMarkdown(message: String, notification: Notification): CharSequence {
        if (notification.contentType == "text/markdown") {
            return markwon.toMarkdown(message)
        }
        return message
    }

    companion object {
        private const val FRESH_MS = 10 * 60_000L
        private val arrivals = java.util.concurrent.ConcurrentHashMap<String, Long>()

        const val ACTION_VIEW = "view"
        const val ACTION_HTTP = "http"
        const val ACTION_BROADCAST = "broadcast"
        const val ACTION_COPY = "copy"

        const val REMOTE_INPUT_KEY = "ntfy_remote_input"
        const val HEADER_NTFY_INPUT = "X-Ntfy-Input"

        fun isReplyHttpAction(action: Action): Boolean {
            if (action.action.lowercase(Locale.getDefault()) != ACTION_HTTP) return false
            val headerMatch = action.headers?.entries?.any {
                it.key.equals(HEADER_NTFY_INPUT, ignoreCase = true) && it.value.equals("true", ignoreCase = true)
            } == true
            return headerMatch || action.label.trim().equals("Reply", ignoreCase = true)
        }

        const val BROADCAST_EXTRA_TYPE = "type"
        const val BROADCAST_EXTRA_NOTIFICATION_ID = "notificationId"
        const val BROADCAST_EXTRA_ACTION_ID = "actionId"

        const val BROADCAST_TYPE_DOWNLOAD_START = "io.heckel.ntfy.DOWNLOAD_ACTION_START"
        const val BROADCAST_TYPE_DOWNLOAD_CANCEL = "io.heckel.ntfy.DOWNLOAD_ACTION_CANCEL"
        const val BROADCAST_TYPE_USER_ACTION = "io.heckel.ntfy.USER_ACTION_RUN"
        const val BROADCAST_TYPE_WATCH_ACTION = "io.heckel.ntfy.WATCH_ACTION"
        const val BROADCAST_EXTRA_TOKEN = "watchToken"
        const val BROADCAST_EXTRA_BASE_URL = "watchBaseUrl"

        private const val WATCH_PREFIX = "watch-"
        private val WATCH_TOKEN = Regex("[A-Za-z0-9_-]{20,40}")
        private const val WATCH_DEFAULT_BASE_URL = "https://infinity.frft.org"
        private const val WATCH_LAUNCH_DEADLINE_MS = 3_000L

        private const val TAG = "NtfyNotifService"

        const val DEFAULT_GROUP = "ntfy"
        private const val SUBSCRIPTION_GROUP_PREFIX = "ntfy-subscription-"
        private const val GROUP_SUFFIX_PRIORITY_MIN = "-min"
        private const val GROUP_SUFFIX_PRIORITY_LOW = "-low"
        private const val GROUP_SUFFIX_PRIORITY_DEFAULT = ""
        private const val GROUP_SUFFIX_PRIORITY_HIGH = "-high"
        private const val GROUP_SUFFIX_PRIORITY_MAX = "-max"

        private const val VIEW_ACTION_EXTRA_URL = "url"
        private const val VIEW_ACTION_EXTRA_NOTIFICATION_ID = "notificationId"
        private const val VIEW_ACTION_EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        private const val VIEW_ACTION_EXTRA_SEQUENCE_ID = "sequenceId"
    }
}

/** The phone-side half of infinity's Pebble approval deep link. */
private object PebbleWatchAction {

    suspend fun focus(context: Context, baseUrl: String, token: String) {
        val url = baseUrl.trimEnd('/') + "/watch/focus/" + token
        val client = HttpUtil.defaultClient(context, baseUrl).newBuilder()
            .callTimeout(2_500, TimeUnit.MILLISECONDS)
            .build()
        val request = HttpUtil.requestBuilder(url).post("".toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
        }
    }

    // Test (2026-11-19): Play Protect has blocked every build since the Pebble launch
    // broadcast (com.getpebble…START, uuid extra) arrived, so it is out of the APK
    // entirely, string and all. Open on watch still sets the focus, so the item is first
    // when he opens infinity on the watch himself. Restore from git (37f77ac3) if cleared.
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) {}
}
