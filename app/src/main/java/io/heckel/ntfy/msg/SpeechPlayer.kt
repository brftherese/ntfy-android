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
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.lifecycle.MutableLiveData
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
 * While speaking (from the moment the audio is requested), an ongoing notification near
 * the top of the shade, on the lock screen and the watch offers Stop; tapping it stops too. A newer push (including a
 * ring answer) stops and replaces the current speech; only the newest waiting push
 * is retained. A push older than two minutes is not spoken.
 *
 * Stopping must never need the shade (the owner, 2026-11-19: on a call while driving,
 * the only way out was force-closing the app). So: nothing is spoken during a phone
 * or video call; a call, or any app taking the audio, stops it; the car's, headset's
 * or watch's pause/stop media button stops it (a MediaSession while speaking); and
 * every screen of the app shows a large Stop button while it speaks (SpeechStopOverlay).
 */
class SpeechPlayer(private val context: Context, private val repository: Repository) {
    fun maybeSpeak(notification: Notification) {
        val tags = splitTags(notification.tags)
        val token = tags.firstOrNull { it.startsWith(SPEAK_PREFIX) }?.removePrefix(SPEAK_PREFIX) ?: return
        if (!repository.getSpeakEnabled()) return
        if (System.currentTimeMillis() / 1000 - notification.timestamp > MAX_AGE_SECONDS) return
        val ring = tags.contains(RING_TAG) && repository.getSpeakRingAloudEnabled()
        if (!ring && !listening()) return
        if (inCall(context)) return
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
        // A call or another app taking the audio ends the speech; it is never resumed.
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                // Only a lasting loss stops it (a call is caught by inCall); a brief transient loss
                // right as it started may have cut ring answers off silently (2026-11-19).
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    if (isCurrent(jobGeneration)) stopCurrent(context.applicationContext, "audio focus lost")
                }
            }.build()
        val format = AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var track: AudioTrack? = null
        var focusRequested = false
        var outcome = "error"
        val started = System.currentTimeMillis()
        synchronized(stateLock) { stopReason = null }
        try {
            val call = client.newCall(Request.Builder().url(SPEECH_URL + token).build())
            synchronized(stateLock) {
                if (jobGeneration != generation) { call.cancel(); return }
                currentCall = call
            }
            showSpeaking(title, jobGeneration)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Speech not available: HTTP ${response.code}")
                    outcome = "speech HTTP ${response.code}"
                    return
                }
                if (inCall(context)) { outcome = "in a call (audio mode ${audio.mode})"; return }
                if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.w(TAG, "Audio focus refused; not speaking")
                    outcome = "audio focus refused"
                    return
                }
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
                // A MediaSession needs a Looper: made on the playback thread, it threw and every
                // spoken answer went silent (own4, 2026-11-19). It is made on the main thread, and
                // if it fails the speech goes on without it.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { startMediaSession(jobGeneration) }.onFailure { Log.w(TAG, "No media session", it) }
                }
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
                    if (!isCurrent(jobGeneration)) break
                    if (inCall(context)) { stopCurrent(context.applicationContext, "call started (audio mode ${audio.mode})"); break }
                    if (len > 0) { track!!.write(buffer, 0, len); written += len }
                }
                val frames = (written / 2).toInt()
                outcome = if (!isCurrent(jobGeneration)) "replaced by a newer push after ${frames / SAMPLE_RATE}s" else "spoken ${frames / SAMPLE_RATE}s, volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
                while (isCurrent(jobGeneration) && track!!.playbackHeadPosition < frames && track!!.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    Thread.sleep(50)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not speak push", e)
            outcome = "error: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            val stoppedBy = synchronized(stateLock) { stopReason }
            report(token, if (stoppedBy != null) "stopped: $stoppedBy" else outcome, System.currentTimeMillis() - started)
            val releaseTrack: Boolean
            val abandonFocus: Boolean
            synchronized(stateLock) {
                releaseTrack = track != null && stoppedTrack !== track
                abandonFocus = focusRequested && stoppedFocus !== focus
                if (stoppedTrack === track) stoppedTrack = null
                if (stoppedFocus === focus) stoppedFocus = null
                if (jobGeneration == generation) {
                    currentCall = null; currentTrack = null; currentAudio = null; currentFocus = null
                    currentSession?.let { runCatching { it.isActive = false; it.release() } }
                    currentSession = null
                    removeSpeakingNotification()
                }
            }
            if (releaseTrack) track?.let { runCatching { it.stop() }; it.release() }
            if (focusRequested && abandonFocus) audio.abandonAudioFocusRequest(focus)
        }
    }

    /**
     * While speaking, the car's, headset's or watch's pause, stop or play/pause button stops it.
     * Not a "play", and nothing in the first second: a Bluetooth device answering a new session's
     * "playing" with a play or pause would otherwise cut the speech off at once (2026-11-19).
     */
    private fun startMediaSession(jobGeneration: Long) {
        val session = MediaSession(context.applicationContext, "infinity-speech")
        val since = System.currentTimeMillis()
        fun pressed(what: String) {
            if (System.currentTimeMillis() - since < 1000) { Log.d(TAG, "Ignored $what in the first second"); return }
            stopCurrent(context.applicationContext, "media button: $what")
        }
        session.setCallback(object : MediaSession.Callback() {
            override fun onPause() = pressed("pause")
            override fun onStop() = pressed("stop")
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return true
                if (key.action == KeyEvent.ACTION_DOWN && key.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)) pressed("key ${key.keyCode}")
                return true
            }
        })
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        session.isActive = true
        synchronized(stateLock) {
            // Made a moment later on the main thread: keep it only while that speech still plays.
            if (jobGeneration != generation || currentTrack == null) { runCatching { session.isActive = false }; session.release(); return }
            currentSession = session
        }
    }

    /** Tells infinity how the speech ended, so a silent failure can be seen (POST /speech/<token>/outcome). */
    private fun report(token: String, outcome: String, ms: Long) {
        runCatching {
            val body = okhttp3.FormBody.Builder().add("outcome", outcome.take(300)).add("ms", ms.toString()).build()
            client.newCall(Request.Builder().url(SPEECH_URL + token + "/outcome").post(body).build())
                .enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {}
                    override fun onResponse(call: Call, response: okhttp3.Response) { response.close() }
                })
        }
    }

    private fun isCurrent(jobGeneration: Long) = synchronized(stateLock) { jobGeneration == generation }

    private fun stopCurrentLocked(removeNotification: Boolean = true) = stopLocked(context, removeNotification)

    private fun showSpeaking(title: String, jobGeneration: Long) {
        synchronized(stateLock) {
            if (jobGeneration != generation) return
            speaking.postValue(true)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                SPEAKING_CHANNEL_ID, "Infinity speech", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setSound(null, null); enableVibration(false) })
        }
        val stopIntent = Intent(context, SpeechStopReceiver::class.java).setAction(STOP_ACTION)
        val stopPendingIntent = PendingIntent.getBroadcast(context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(SPEAKING_NOTIFICATION_ID, NotificationCompat.Builder(context, SPEAKING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("infinity is speaking")
            .setContentText("Tap to stop · $title").setPriority(NotificationCompat.PRIORITY_DEFAULT).setOngoing(true)
            .setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(stopPendingIntent)
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
        private const val SPEAKING_CHANNEL_ID = "infinity_speaking_v2"
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
        private var currentSession: MediaSession? = null
        /** Why the last speech was stopped from outside its loop (null: it ended by itself). */
        private var stopReason: String? = null
        /** True while infinity speaks; every screen shows Stop while it is. */
        val speaking = MutableLiveData(false)
        private var stoppedTrack: AudioTrack? = null
        private var stoppedFocus: AudioFocusRequest? = null
        private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()
        /** Stop: ends what is playing and drops anything waiting. */
        fun stopCurrent(context: Context, reason: String = "Stop tapped") {
            synchronized(stateLock) {
                if (currentTrack != null || currentCall != null) stopReason = reason
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
            currentSession?.let { runCatching { it.isActive = false; it.release() } }
            currentSession = null
            if (removeNotification) removeSpeaking(context)
        }

        private fun removeSpeaking(context: Context) {
            speaking.postValue(false)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(SPEAKING_NOTIFICATION_ID)
        }
        /** A phone call, a video or voice call in any app, or a call ringing. */
        fun inCall(context: Context): Boolean {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
                audio.mode == AudioManager.MODE_RINGTONE || audio.mode == AudioManager.MODE_CALL_SCREENING
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
