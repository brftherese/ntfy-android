package io.heckel.ntfy.player

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/** Opened from the player's notification: the whole recording, a seek bar, and every skip. */
@UnstableApi
class NowPlayingActivity : AppCompatActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var seek: SeekBar
    private lateinit var at: TextView
    private lateinit var length: TextView
    private lateinit var play: Button
    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 500) } }
    private val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
    }

    override fun onStart() {
        super.onStart()
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlayerService::class.java))).buildAsync().also { f ->
            f.addListener({ controller = runCatching { f.get() }.getOrNull()?.also { it.addListener(listener) }; refresh() }, MoreExecutors.directExecutor())
        }
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null; future = null
        super.onStop()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

    private fun buildView(): ViewGroup {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(32), dp(20), dp(20)) }
        title = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f) }
        state = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(4), 0, dp(20)) }
        seek = SeekBar(this).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }
        at = TextView(this); length = TextView(this).apply { gravity = Gravity.END }
        val times = LinearLayout(this).apply {
            addView(at, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(length, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setPadding(dp(8), 0, dp(8), dp(16))
        }
        play = Button(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); minHeight = dp(72) }
        play.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        fun row(vararg skips: Pair<String, Int>) = LinearLayout(this).apply {
            skips.forEach { (label, by) ->
                addView(Button(this@NowPlayingActivity).apply {
                    text = label; minHeight = dp(64); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setOnClickListener { controller?.let { c -> c.seekTo((c.currentPosition + by * 1000L).coerceIn(0, maxOf(0, c.duration - 1000))) } }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val stop = Button(this).apply { text = "Stop (keeps the place)"; setOnClickListener { controller?.stop(); finish() } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) at.text = clock(p * 1000L) }
            override fun onStartTrackingTouch(b: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(b: SeekBar) { dragging = false; controller?.seekTo(b.progress * 1000L) }
        })
        listOf(title, state, seek, times).forEach { box.addView(it) }
        box.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(row("−2m" to -120, "−30s" to -30, "−10s" to -10))
        box.addView(row("+30s" to 30, "+2m" to 120, "+5m" to 300))
        box.addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        return box
    }

    private fun refresh() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) { title.text = "Nothing playing"; state.text = ""; play.text = "Play"; return }
        title.text = c.mediaMetadata.title ?: "Playing"
        state.text = when { c.isPlaying -> "Playing"; c.playbackState == Player.STATE_BUFFERING -> "Loading…"; c.playbackState == Player.STATE_ENDED -> "Finished"; else -> "Paused" }
        play.text = if (c.isPlaying || c.playWhenReady && c.playbackState == Player.STATE_BUFFERING) "Pause" else "Play"
        if (c.duration > 0) { seek.max = (c.duration / 1000).toInt(); length.text = clock(c.duration) }
        if (!dragging) { seek.progress = (c.currentPosition / 1000).toInt(); at.text = clock(c.currentPosition) }
    }
}
