package io.heckel.ntfy.msg

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.splitTags
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Speaks infinity's pushes. A push tagged speak-<token> has its words as audio at
 * infinity's /speech/<token> (24 kHz mono PCM16, streamed as it is made, in the voice
 * of his phone line). It is played when headphones or a car are connected, or, for an
 * answer to his ring (tagged ring-voice), through the speaker as well. Do Not Disturb
 * and silent mode don't stop it: it plays as an assistant's voice, not a ringtone.
 * One at a time, in order; a push older than two minutes (caught up after being
 * offline) is not spoken.
 */
class SpeechPlayer(private val context: Context, private val repository: Repository) {
    fun maybeSpeak(notification: Notification) {
        val tags = splitTags(notification.tags)
        val token = tags.firstOrNull { it.startsWith(SPEAK_PREFIX) }?.removePrefix(SPEAK_PREFIX) ?: return
        if (!repository.getSpeakEnabled()) return
        if (System.currentTimeMillis() / 1000 - notification.timestamp > MAX_AGE_SECONDS) return
        val ring = tags.contains(RING_TAG) && repository.getSpeakRingAloudEnabled()
        if (!ring && !listening()) return
        executor.execute { play(token) }
    }

    /** Headphones, a headset, or the car (Bluetooth audio, USB, Android Auto). */
    private fun listening(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in PRIVATE_OUTPUTS }
    }

    private fun play(token: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var track: AudioTrack? = null
        try {
            val request = Request.Builder().url(SPEECH_URL + token).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Speech not available: HTTP ${response.code}")
                    return
                }
                audio.requestAudioFocus(focus)
                track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE)) // half a second
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track!!.play()
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
                // Let the last of it play out before letting go.
                val frames = (written / 2).toInt()
                while (track!!.playbackHeadPosition < frames && track!!.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    Thread.sleep(50)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not speak push", e)
        } finally {
            track?.let { it.stop(); it.release() }
            audio.abandonAudioFocusRequest(focus)
        }
    }

    companion object {
        private const val TAG = "NtfySpeech"
        const val SPEAK_PREFIX = "speak-"
        const val RING_TAG = "ring-voice"
        private const val SPEECH_URL = "https://infinity.frft.org/speech/"
        private const val SAMPLE_RATE = 24000
        private const val MAX_AGE_SECONDS = 120
        private val PRIVATE_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUS,
            26, // TYPE_BLE_HEADSET (API 31)
            27, // TYPE_BLE_SPEAKER (API 31)
        )
        private val executor = Executors.newSingleThreadExecutor()
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Tags that only steer the speech, never shown. */
        fun isSpeechTag(tag: String) = tag.startsWith(SPEAK_PREFIX) || tag == RING_TAG
    }
}
