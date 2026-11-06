package io.heckel.ntfy.msg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.splitTags
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Speaks infinity's pushes. A push tagged speak-<token> has its words as audio at
 * infinity's /speech/<token> (24 kHz mono PCM16, streamed as it is made, in the voice
 * of his phone line). It is played when headphones or a car are connected, or, for an
 * answer to his ring (tagged ring-voice), through the speaker as well. Do Not Disturb
 * and silent mode don't stop it: it plays as an assistant's voice, not a ringtone.
 * While speaking, an ongoing notification offers Stop. A newer push (including a
 * ring answer) stops and replaces the current speech; only the newest waiting push
 * is retained. A push older than two minutes is not spoken.
 */
class SpeechPlayer(private val context: Context, private val repository: Repository) {
    fun maybeSpeak(notification: Notification) {
        val tags = splitTags(notification.tags)
        val token = tags.firstOrNull { it.startsWith(SPEAK_PREFIX) }?.removePrefix(SPEAK_PREFIX) ?: return
        if (!repository.getSpeakEnabled()) return
        if (System.currentTimeMillis() / 1000 - notification.timestamp > MAX_AGE_SECONDS) return
        val ring = tags.contains(RING_TAG) && repository.getSpeakRingAloudEnabled()
        if (!ring && !listening()) return
        synchronized(stateLock) {
            generation++
            stopCurrentLocked(removeNotification = false)
            executor.queue.clear()
            val jobGeneration = generation
            executor.execute { play(token, jobGeneration, notification.title) }
        }
    }

    /** Headphones, a headset, or the car (Bluetooth audio, USB, Android Auto). */
    private fun listening(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in PRIVATE_OUTPUTS }
    }

    private fun play(token: String, jobGeneration: Long, title: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).build()
        val format = AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var track: AudioTrack? = null
        var focusRequested = false
        try {
            val call = client.newCall(Request.Builder().url(SPEECH_URL + token).build())
            synchronized(stateLock) {
                if (jobGeneration != generation) { call.cancel(); return }
                currentCall = call
            }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Speech not available: HTTP ${response.code}")
                    return
                }
                audio.requestAudioFocus(focus)
                focusRequested = true
                track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                synchronized(stateLock) {
                    if (jobGeneration != generation) return
                    currentTrack = track
                    currentAudio = audio
                    currentFocus = focus
                }
                track!!.play()
                showSpeaking(title, jobGeneration)
                val input = response.body.byteStream()
                val buffer = ByteArray(8192)
                var carry = -1 // PCM16: a read that ends mid-sample keeps its odd byte
                var written = 0L
                while (true) {
                    val n = input.read(buffer, if (carry >= 0) 1 else 0, buffer.size - 1)
                    if (n < 0) break
                    var len = n
                    if (carry >= 0) { buffer[0] = carry.toByte(); len += 1; carry = -1 }
                    if (len % 2 == 1) { carry = buffer[len - 1].toInt() and 0xff; len -= 1 }
                    if (len > 0) { track!!.write(buffer, 0, len); written += len }
                }
                val frames = (written / 2).toInt()
                while (isCurrent(jobGeneration) && track!!.playbackHeadPosition < frames && track!!.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    Thread.sleep(50)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not speak push", e)
        } finally {
            val releaseTrack: Boolean
            val abandonFocus: Boolean
            synchronized(stateLock) {
                releaseTrack = track != null && stoppedTrack !== track
                abandonFocus = focusRequested && stoppedFocus !== focus
                if (stoppedTrack === track) stoppedTrack = null
                if (stoppedFocus === focus) stoppedFocus = null
                if (jobGeneration == generation) {
                    currentCall = null; currentTrack = null; currentAudio = null; currentFocus = null
                    removeSpeakingNotification()
                }
            }
            if (releaseTrack) track?.let { runCatching { it.stop() }; it.release() }
            if (focusRequested && abandonFocus) audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun isCurrent(jobGeneration: Long) = synchronized(stateLock) { jobGeneration == generation }

    private fun stopCurrentLocked(removeNotification: Boolean = true) = stopLocked(context, removeNotification)

    private fun showSpeaking(title: String, jobGeneration: Long) {
        synchronized(stateLock) {
            if (jobGeneration != generation) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                SPEAKING_CHANNEL_ID, "Infinity speech", NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null); enableVibration(false) })
        }
        val stopIntent = Intent(context, SpeechStopReceiver::class.java).setAction(STOP_ACTION)
        val stopPendingIntent = PendingIntent.getBroadcast(context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(SPEAKING_NOTIFICATION_ID, NotificationCompat.Builder(context, SPEAKING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("infinity is speaking")
            .setContentText(title).setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(NotificationCompat.Action.Builder(0, "Stop", stopPendingIntent).build()).build())
        }
    }

    private fun removeSpeakingNotification() = removeSpeaking(context)


    companion object {
        private const val TAG = "NtfySpeech"
        const val SPEAK_PREFIX = "speak-"
        const val RING_TAG = "ring-voice"
        const val STOP_ACTION = "io.heckel.ntfy.STOP_SPEECH"
        private const val SPEECH_URL = "https://infinity.frft.org/speech/"
        private const val SAMPLE_RATE = 24000
        private const val MAX_AGE_SECONDS = 120
        private const val SPEAKING_CHANNEL_ID = "infinity_speaking"
        private const val SPEAKING_NOTIFICATION_ID = 0x534
        // One playback for the whole app: the service, the main screen and the poll
        // worker each make their own dispatcher and so their own SpeechPlayer, so the
        // state Stop and replacement act on lives here, not in an instance.
        private val stateLock = Any()
        private var generation = 0L
        private var currentCall: Call? = null
        private var currentTrack: AudioTrack? = null
        private var currentAudio: AudioManager? = null
        private var currentFocus: AudioFocusRequest? = null
        private var stoppedTrack: AudioTrack? = null
        private var stoppedFocus: AudioFocusRequest? = null
        private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()
        /** Stop: ends what is playing and drops anything waiting. */
        fun stopCurrent(context: Context) {
            synchronized(stateLock) {
                generation++
                stopLocked(context, true)
                executor.queue.clear()
            }
        }

        private fun stopLocked(context: Context, removeNotification: Boolean) {
            currentCall?.cancel()
            currentCall = null
            currentTrack?.let {
                stoppedTrack = it
                runCatching { it.stop() }
                it.release()
            }
            currentTrack = null
            currentAudio?.let { audio -> currentFocus?.let {
                stoppedFocus = it
                audio.abandonAudioFocusRequest(it)
            } }
            currentAudio = null; currentFocus = null
            if (removeNotification) removeSpeaking(context)
        }

        private fun removeSpeaking(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(SPEAKING_NOTIFICATION_ID)
        }
        private val PRIVATE_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUS,
            26, // TYPE_BLE_HEADSET (API 31)
            27, // TYPE_BLE_SPEAKER (API 31)
        )
        /** Tags that only steer the speech, never shown. */
        fun isSpeechTag(tag: String) = tag.startsWith(SPEAK_PREFIX) || tag == RING_TAG
    }
}
