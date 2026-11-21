package io.heckel.ntfy.msg

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.splitTags
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A spoken answer to a card (own11, 2026-11-21: "I should be able to speak a destination when my car connects
 * and asks for where to"). A push tagged `listen` with a Reply (an http action marked X-Ntfy-Input) is read aloud
 * by SpeechPlayer; when that ends by itself, this screen opens over the lock screen, listens once, and posts what
 * he said to the Reply's URL, as typing it would. Nothing is sent when nothing was heard; Cancel or silence ends it.
 * Android lets a background app use the microphone only from a visible screen, and lets it open one only with
 * "display over other apps" (SYSTEM_ALERT_WINDOW, granted through Shizuku).
 */
class ListenActivity : Activity() {
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "infinity"
        status = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = "Listening…" }
        val heading = TextView(this).apply { textSize = 18f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; text = title }
        val cancel = Button(this).apply { text = "Cancel"; textSize = 20f; setOnClickListener { finishQuietly("cancelled") } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK); setPadding(48, 48, 48, 48)
            addView(heading); addView(status); addView(cancel)
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else finishQuietly("no microphone permission")
    }

    private var routed = false
    private var modeBefore = AudioManager.MODE_NORMAL

    /**
     * The car's or headset's microphone when one is connected, else the phone's (the owner, 2026-11-21: "Phone or
     * Bluetooth depending on what's connected"). Android takes a Bluetooth microphone only as the call device, so
     * it is asked for as that while listening and given back after.
     */
    private fun useBluetoothMic(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "phone"
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val bt = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26 /* BLE headset */ }
            ?: return "phone"
        return runCatching {
            modeBefore = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            routed = audio.setCommunicationDevice(bt)
            if (!routed) { audio.mode = modeBefore; "phone" } else bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
        }.getOrElse { Log.w(TAG, "Bluetooth microphone not available", it); "phone" }
    }

    private fun releaseMic() {
        if (!routed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        routed = false
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { audio.clearCommunicationDevice(); audio.mode = modeBefore }
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { finishQuietly("no speech recognition on this phone"); return }
        val mic = useBluetoothMic()
        status.text = if (mic == "phone") "Listening…" else "Listening ($mic)…"
        Log.d(TAG, "Listening on the $mic microphone")
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (said.isEmpty()) finishQuietly("heard nothing") else send(said)
            }
            override fun onError(error: Int) { if (!done) finishQuietly("recognizer error $error") }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status.text = "…" }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) status.text = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        })
        // Never left listening: ten seconds at most.
        main.postDelayed({ if (!done) { recognizer?.stopListening() } }, 10_000)
        main.postDelayed({ if (!done) finishQuietly("timed out") }, 15_000)
    }

    private fun send(said: String) {
        done = true
        releaseMic()
        status.text = "“$said”"
        val url = intent.getStringExtra(EXTRA_URL) ?: return finishQuietly("no reply URL")
        val headers = (intent.getStringArrayExtra(EXTRA_HEADERS) ?: emptyArray()).toList()
        Thread {
            val ok = runCatching {
                val req = Request.Builder().url(url).post(said.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                headers.chunked(2).forEach { if (it.size == 2) req.header(it[0], it[1]) }
                client.newCall(req.build()).execute().use { it.isSuccessful }
            }.getOrElse { Log.w(TAG, "Spoken answer not sent", it); false }
            main.post { status.text = if (ok) "Sent: “$said”" else "Couldn't send it" }
            main.postDelayed({ finish() }, if (ok) 1_500 else 3_000)
        }.start()
    }

    private fun finishQuietly(why: String) {
        done = true
        releaseMic()
        Log.d(TAG, "Not listening any more: $why")
        status.text = when { why.startsWith("heard nothing") || why.startsWith("timed out") || why.startsWith("recognizer error 7") -> "Didn't hear anything"; why == "cancelled" -> "Cancelled"; else -> why }
        main.postDelayed({ finish() }, 1_200)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        releaseMic()
        // What the speech paused (the Office, a book) goes on once he has answered.
        io.heckel.ntfy.player.PlayerService.resumeAfterSpeech()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NtfyListen"
        const val LISTEN_TAG = "listen"
        private const val EXTRA_URL = "url"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_TITLE = "title"
        private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        /** The Reply this push's spoken answer goes to, if it is tagged listen and has one. */
        private fun replyOf(notification: Notification): Action? {
            if (!splitTags(notification.tags).contains(LISTEN_TAG)) return null
            return notification.actions?.firstOrNull { NotificationService.isReplyHttpAction(it) && !it.url.isNullOrBlank() }
        }

        /** After SpeechPlayer has read a listen card aloud to the end. */
        fun maybeListen(context: Context, notification: Notification) {
            val reply = replyOf(notification) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot open the listening screen from the background: display over other apps is off")
                return
            }
            val headers = (reply.headers ?: emptyMap()).filterKeys { !it.equals(NotificationService.HEADER_NTFY_INPUT, ignoreCase = true) }
                .flatMap { listOf(it.key, it.value) }.toTypedArray()
            context.startActivity(Intent(context, ListenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(EXTRA_URL, reply.url).putExtra(EXTRA_HEADERS, headers).putExtra(EXTRA_TITLE, notification.title))
        }

        fun isListenTag(tag: String) = tag == LISTEN_TAG
    }
}
