package io.heckel.ntfy.player

import android.content.ComponentName
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.heckel.ntfy.R

/**
 * Opened from the player's notification: the artwork, a seek bar, play/pause, back/forward 30 s and
 * the smaller and larger skips (the owner, 2026-11-21, of the first version: "looks like Android 2003").
 * Portrait puts the controls at the bottom, where his thumb is; landscape (the car) puts them beside the artwork.
 */
@UnstableApi
class NowPlayingActivity : AppCompatActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var seek: Slider
    private lateinit var at: TextView
    private lateinit var length: TextView
    private lateinit var play: MaterialButton
    private lateinit var art: ImageView
    private var shownArt: MediaMetadata? = null
    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 500) } }
    private val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        val root = findViewById<android.view.View>(R.id.np_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val side = (24 * resources.displayMetrics.density).toInt()
            v.setPadding(side + bars.left, bars.top, side + bars.right, bars.bottom); insets
        }
        title = findViewById(R.id.np_title); state = findViewById(R.id.np_state)
        seek = findViewById(R.id.np_seek); at = findViewById(R.id.np_at); length = findViewById(R.id.np_length)
        play = findViewById(R.id.np_play); art = findViewById(R.id.np_art)
        findViewById<MaterialButton>(R.id.np_close).setOnClickListener { finish() }
        play.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        mapOf(R.id.np_back30 to -30, R.id.np_fwd30 to 30, R.id.np_back10 to -10, R.id.np_back2m to -120, R.id.np_fwd2m to 120, R.id.np_fwd5m to 300)
            .forEach { (id, by) -> findViewById<MaterialButton>(id).setOnClickListener { skip(by) } }
        findViewById<MaterialButton>(R.id.np_stop).setOnClickListener { controller?.stop(); finish() }
        seek.setLabelFormatter { clock((it * 1000).toLong()) }
        seek.addOnChangeListener { _, value, fromUser -> if (fromUser) at.text = clock((value * 1000).toLong()) }
        seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { dragging = true }
            override fun onStopTrackingTouch(s: Slider) { dragging = false; controller?.seekTo((s.value * 1000).toLong()) }
        })
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

    private fun skip(by: Int) = controller?.let { c -> c.seekTo((c.currentPosition + by * 1000L).coerceIn(0, maxOf(0, c.duration - 1000))) }
    private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

    private fun refresh() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) { title.text = "Nothing playing"; state.text = ""; showArt(null); return }
        val meta = c.mediaMetadata
        title.text = meta.title ?: "Playing"
        state.text = when { c.isPlaying -> "Playing"; c.playbackState == Player.STATE_BUFFERING -> "Loading…"; c.playbackState == Player.STATE_ENDED -> "Finished"; else -> "Paused" }
        val playing = c.isPlaying || c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
        play.setIconResource(if (playing) androidx.media3.session.R.drawable.media3_icon_pause else androidx.media3.session.R.drawable.media3_icon_play)
        play.contentDescription = if (playing) "Pause" else "Play"
        if (c.duration > 0) {
            val total = (c.duration / 1000f).coerceAtLeast(1f)
            if (seek.valueTo != total) { seek.valueTo = total }
            length.text = clock(c.duration)
        }
        if (!dragging) { seek.value = (c.currentPosition / 1000f).coerceIn(seek.valueFrom, seek.valueTo); at.text = clock(c.currentPosition) }
        if (meta != shownArt) showArt(meta)
    }

    /** The recording's own artwork (the Office's carries the Liturgy of the Hours cover), else a quiet note. */
    private fun showArt(meta: MediaMetadata?) {
        shownArt = meta
        val bytes = meta?.artworkData
        val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (bitmap != null) { art.scaleType = ImageView.ScaleType.CENTER_CROP; art.setImageBitmap(bitmap) }
        else { art.scaleType = ImageView.ScaleType.CENTER; art.setImageResource(R.drawable.ic_player_artwork) }
    }
}
