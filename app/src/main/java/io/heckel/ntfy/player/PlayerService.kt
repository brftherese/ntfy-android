package io.heckel.ntfy.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import android.os.Bundle
import io.heckel.ntfy.R
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app's own player (own10, 2026-11-21: the owner wanted "a real player", used whenever ntfy plays
 * something). Recordings infinity sends (a push tagged `player`, or a shell command through Shizuku, see
 * PlayerCommandReceiver) play here with a media session: lock screen, shade, car, headset and watch get
 * play/pause, back/forward 30 s and a seek bar. Streams at once and caches the whole file, so a dead zone
 * doesn't stop it. Remembers the place per item, and reports where it is to infinity.
 *
 * Everything that touches the player runs on the main thread: ExoPlayer refuses calls from any other.
 */
@UnstableApi
class PlayerService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var cache: SimpleCache
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private var item: PlayerRequest? = null
    private var lastReport = 0L
    private var pausedForSpeech = false
    // A queue (music, own24): the playlist infinity sent, played gapless; near its end infinity is asked for more.
    private var queueMode = false
    private var queueMore = false
    private var fetchingMore = false
    private var failedInARow = 0
    private var pauseReason: String? = null
    private lateinit var buttons: List<CommandButton>
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    private val ticker = object : Runnable {
        override fun run() { if (player.isPlaying) { report("playing", force = false); save(); markPlaying(true) }; main.postDelayed(this, 15_000) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cache = SimpleCache(File(cacheDir, "player-cache"), LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024), StandaloneDatabaseProvider(this))
        val dataSource = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DefaultDataSource.Factory(this))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SKIP_MS).setSeekForwardIncrementMs(SKIP_MS)
            .build()
        player.addListener(object : Player.Listener {
            // Paused not by his tap: his car or headphones went away, or another app took the audio for good. infinity
            // then gives the ring's clicks back at once (a click after leaving the car must not start the phone's speaker).
            // (Set before onIsPlayingChanged reports the pause, which carries it.)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                pauseReason = if (playWhenReady) null else when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "noisy"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "focus"
                    else -> null
                }
                if (!playWhenReady && pauseReason != null && !player.isPlaying) { report("paused"); save() } // already not playing: no other report comes
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) return
                markPlaying(isPlaying)
                if (!isPlaying && (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED)) return // reported as stopped/ended
                report(if (isPlaying) "playing" else "paused"); save()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) report("buffering")
                if (state == Player.STATE_ENDED) { report("ended"); item?.let { prefs.edit().remove("pos_${it.id}").apply() }; finish() }
            }
            // A seek from the lock screen, the car or Now Playing: infinity hears the new place at once.
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) { report(if (player.playWhenReady) "playing" else "paused"); save() }
            }
            // Gapless to the next track of a queue, or next/previous from the lock screen or the car.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!queueMode || mediaItem == null) return
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) main.postDelayed({ if (player.isPlaying) failedInARow = 0 }, 5_000)
                item = item?.copy(id = mediaItem.mediaId, title = mediaItem.mediaMetadata.title?.toString() ?: "")
                prefs.edit().putString("last_id", mediaItem.mediaId).putString("last_title", item?.title).apply()
                report(if (player.playWhenReady) "playing" else "paused")
                moreIfNearEnd()
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback failed", error); report("error", error.message)
                // A queue skips a track that won't play (Qobuz refused it, or the network gave out on it) and goes on,
                // giving up after three in a row; a recording stops as before.
                if (queueMode && player.hasNextMediaItem() && failedInARow < 3) {
                    failedInARow++
                    player.seekToNextMediaItem(); player.prepare(); player.playWhenReady = true
                } else { failedInARow = 0; stopPlayback() }
            }
        })
        val openNowPlaying = PendingIntent.getActivity(this, 0, Intent(this, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Back 30 and forward 30 (⏪ ⏩: the circular 30-second arrows looked like two rewinds, the owner
        // 2026-11-21: "why only rewind buttons?") in the lock screen's and shade's back and forward slots. They must be
        // custom session commands (Android's media controls show only those as extra buttons), and
        // previous/next are withheld so those slots go to them (one recording has no other track).
        val back = CommandButton.Builder(CommandButton.ICON_REWIND).setDisplayName("Back 30 seconds")
            .setSessionCommand(BACK_30).setSlots(CommandButton.SLOT_BACK).build()
        val forward = CommandButton.Builder(CommandButton.ICON_FAST_FORWARD).setDisplayName("Forward 30 seconds")
            .setSessionCommand(FORWARD_30).setSlots(CommandButton.SLOT_FORWARD).build()
        buttons = listOf(back, forward)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openNowPlaying)
            .setMediaButtonPreferences(buttons)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(BACK_30).add(FORWARD_30).build())
                        .setAvailablePlayerCommands(playerCommands())
                        .setMediaButtonPreferences(if (queueMode) emptyList() else buttons)
                        .build()
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) { BACK_30.customAction -> player.seekBack(); FORWARD_30.customAction -> player.seekForward() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // The car's, headset's or watch's next/previous (and fast-forward/rewind) skip 30 s.
                override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    val forwardKey = when (key.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> true
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> false
                        else -> return false
                    }
                    if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0) return true
                    // In a queue the track keys change track; fast-forward and rewind still skip 30 s.
                    val trackKey = key.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || key.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    if (queueMode && trackKey) { if (forwardKey) player.seekToNextMediaItem() else player.seekToPreviousMediaItem() }
                    else if (forwardKey) player.seekForward() else player.seekBack()
                    return true
                }
            })
            .build()
        // Started by a push or a command, no controller binds, so the session is handed to Media3's
        // notification manager here: without it the media notification (its controls, the lock-screen
        // buttons) never appears and only the start-up placeholder shows.
        addSession(session)
        main.postDelayed(ticker, 15_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started with startForegroundService (a push, or PLAY/RESUME/TOGGLE): Android needs a foreground
        // notification within seconds or it kills the whole app, ntfy included. Media3 replaces this
        // placeholder (same id) with the media notification once it plays.
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) goForeground()
        if (intent?.action?.startsWith(ACTION_PREFIX) != true) {
            // Media3's own starts (media buttons, or its notification manager restarting the service with an empty
            // intent through startForegroundService) owe Android a foreground notification too. After a STOP the
            // player is idle and Media3 posts none, so Android killed the whole app 30 s later and its listener
            // came back unsubscribed (2026-11-26 08:25: the ring's "Office stop", then no pushes at all). Go
            // foreground at once, let Media3 handle the intent, and drop it again if nothing is loaded.
            val idle = player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED
            if (idle) goForeground() // loaded, Media3 posts its own media notification
            val result = super.onStartCommand(intent, flags, startId)
            if (idle) main.post { stopIfIdle() }
            return result
        }
        handle(intent)
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID, "Now playing", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(prefs.getString("last_title", null) ?: "Starting…")
            .setOngoing(true).setSilent(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, n)
        }.onFailure { Log.w(TAG, "Could not go foreground", it) }
    }

    private fun handle(i: Intent) {
        when (i.action) {
            ACTION_PLAY -> {
                val url = i.getStringExtra("url"); val id = i.getStringExtra("id")
                if (url == null || id == null) { Log.w(TAG, "PLAY without url or id"); stopIfIdle(); return }
                play(PlayerRequest(url, i.getStringExtra("title") ?: "", id, if (i.hasExtra("start")) i.getIntExtra("start", 0) else null, i.getBooleanExtra("queue", false)))
            }
            ACTION_NEXT -> if (ensureItem(autoplay = false)) player.seekToNextMediaItem()
            ACTION_PREVIOUS -> if (ensureItem(autoplay = false)) player.seekToPreviousMediaItem()
            ACTION_PAUSE -> player.pause()
            ACTION_RESUME -> if (ensureItem()) player.play() else stopIfIdle()
            ACTION_TOGGLE -> if (ensureItem()) { if (player.isPlaying) player.pause() else player.play() } else stopIfIdle()
            ACTION_STOP -> stopPlayback()
            ACTION_SEEK -> if (ensureItem(autoplay = false)) player.seekTo(i.getIntExtra("to", 0).coerceAtLeast(0) * 1000L)
            ACTION_SKIP -> if (ensureItem(autoplay = false)) player.seekTo((player.currentPosition + i.getIntExtra("by", 0) * 1000L).coerceAtLeast(0))
        }
    }

    private fun play(request: PlayerRequest, autoplay: Boolean = true) {
        if (!PlayerLogic.allowedUrl(request.url)) { Log.w(TAG, "Refused player URL ${request.url}"); stopIfIdle(); return }
        save()
        if (request.queue) { playQueue(request, autoplay); return }
        setQueueMode(false); item = request
        prefs.edit().putString("last_url", request.url).putString("last_title", request.title).putString("last_id", request.id).putBoolean("last_queue", false).apply()
        player.setMediaItem(MediaItem.Builder().setMediaId(request.id).setUri(request.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).setArtist("infinity").build()).build())
        player.prepare()
        val savedAt = prefs.getLong("at_${request.id}", 0)
        val saved = if (System.currentTimeMillis() - savedAt < KEEP_MS) prefs.getLong("pos_${request.id}", 0) else 0
        val position = request.start?.times(1000L) ?: PlayerLogic.resumePosition(saved, prefs.getLong("dur_${request.id}", 0))
        if (position > 0) player.seekTo(position)
        player.playWhenReady = autoplay
        val spec = DataSpec(Uri.parse(request.url))
        scope.launch { runCatching { CacheWriter(CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DefaultDataSource.Factory(this@PlayerService)).createDataSource(), spec, null, null).cache() } }
    }

    /** A queue from infinity: fetched (off the main thread), then played as one playlist from its current track. */
    private fun playQueue(request: PlayerRequest, autoplay: Boolean) {
        item = request
        prefs.edit().putString("last_url", request.url).putString("last_title", request.title).putString("last_id", request.id).putBoolean("last_queue", true).apply()
        scope.launch {
            val json = runCatching { fetchJson(request.url) }.getOrElse { Log.w(TAG, "Queue fetch failed", it); null }
            main.post {
                if (item?.url != request.url) return@post // something else was played meanwhile
                if (json == null) { report("error", "queue not reachable"); stopIfIdle(); return@post }
                val items = mediaItems(json)
                if (items.isEmpty()) { report("error", "empty queue"); stopIfIdle(); return@post }
                setQueueMode(true)
                queueMore = json.optBoolean("more", false)
                val start = json.optInt("start", 0).coerceIn(0, items.size - 1)
                val id = items[start].mediaId
                item = request.copy(id = id, title = items[start].mediaMetadata.title?.toString() ?: request.title)
                val savedAt = prefs.getLong("at_$id", 0)
                val saved = if (System.currentTimeMillis() - savedAt < KEEP_MS) prefs.getLong("pos_$id", 0) else 0
                val position = request.start?.times(1000L) ?: PlayerLogic.resumePosition(saved, prefs.getLong("dur_$id", 0))
                player.setMediaItems(items, start, position)
                player.prepare()
                player.playWhenReady = autoplay
                moreIfNearEnd()
            }
        }
    }

    private fun mediaItems(json: JSONObject): List<MediaItem> {
        val arr = json.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { k ->
            val o = arr.optJSONObject(k) ?: return@mapNotNull null
            val url = o.optString("url"); val id = o.optString("id")
            if (!PlayerLogic.allowedUrl(url) || id.isEmpty()) return@mapNotNull null
            MediaItem.Builder().setMediaId(id).setUri(url).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(o.optString("title")).setArtist(o.optString("artist").ifEmpty { "infinity" }).setAlbumTitle(o.optString("album"))
                // The album cover (Qobuz's static images) on the lock screen, in the car and in Now Playing.
                .apply { o.optString("art").takeIf { it.startsWith("https://") }?.let { setArtworkUri(Uri.parse(it)) } }.build()).build()
        }
    }

    private fun fetchJson(url: String): JSONObject {
        val client = http.newBuilder().readTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("queue answered ${r.code}")
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    /** A station (more = true) near its last two tracks asks infinity for the next ones. */
    private fun moreIfNearEnd() {
        val r = item ?: return
        if (!queueMode || !queueMore || fetchingMore || player.currentMediaItemIndex < player.mediaItemCount - 2) return
        fetchingMore = true
        val page = PlayerLogic.queuePage(r.url, player.mediaItemCount)
        scope.launch {
            val json = runCatching { fetchJson(page) }.getOrNull()
            main.post {
                fetchingMore = false
                if (json == null || item?.url != r.url) return@post
                queueMore = json.optBoolean("more", false)
                val items = mediaItems(json)
                if (items.isNotEmpty()) player.addMediaItems(items)
            }
        }
    }

    /** A queue gets the track buttons (previous/next) and music's audio; a recording keeps back/forward 30 s. */
    private fun setQueueMode(on: Boolean) {
        if (queueMode == on) return
        queueMode = on; queueMore = false
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(if (on) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        session.setMediaButtonPreferences(if (on) emptyList() else buttons)
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(BACK_30).add(FORWARD_30).build()
        for (c in session.connectedControllers) session.setAvailableCommands(c, sessionCommands, playerCommands())
    }

    private fun playerCommands(): Player.Commands {
        val all = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
        return if (queueMode) all.build() else all.removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
    }

    /** The current item, or the last one from where it was left (after STOP or the process died). */
    private fun ensureItem(autoplay: Boolean = true): Boolean {
        if (item != null && player.playbackState != Player.STATE_IDLE) return true
        val request = item ?: prefs.getString("last_url", null)?.let { url -> prefs.getString("last_id", null)?.let { id -> PlayerRequest(url, prefs.getString("last_title", "") ?: "", id, null, prefs.getBoolean("last_queue", false)) } }
        if (request == null || !PlayerLogic.allowedUrl(request.url)) return false
        play(request.copy(start = null), autoplay = autoplay)
        return true
    }

    /** Stopped, not finished: the place is kept for RESUME. */
    private fun stopPlayback() {
        save()
        val ended = player.playbackState == Player.STATE_ENDED
        player.stop()
        if (!ended && item != null) report("stopped")
        stopIfIdle()
    }

    /** Played to its end: unloaded and forgotten, so nothing but a new play from infinity starts it again. A car or
     *  headset sending PLAY as it connects, a spoken push handing the audio back, RESUME or Now Playing had
     *  started a finished Office over from the top (2026-11-26 13:56: Midday Prayer again and again). */
    private fun finish() {
        player.stop()
        player.clearMediaItems()
        item = null
        setQueueMode(false)
        prefs.edit().remove("last_url").remove("last_title").remove("last_id").remove("last_queue").apply()
        markPlaying(false)
        stopIfIdle()
    }

    /** Nothing loaded: drop the notification (the placeholder too) and end the service. A pause keeps both. */
    private fun stopIfIdle() {
        if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) return
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** When it last played (kept current every 15 s while playing, cleared on pause or stop): an update or a crash
     *  that kills the app mid-recording leaves it set, so the new app knows to carry on (resumeAfterUpdate). */
    private fun markPlaying(playing: Boolean) {
        if (playing) prefs.edit().putLong("playing_at", System.currentTimeMillis()).apply() else prefs.edit().remove("playing_at").apply()
    }

    private fun save() {
        val r = item ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        prefs.edit().putLong("pos_${r.id}", player.currentPosition).putLong("dur_${r.id}", player.duration.coerceAtLeast(0))
            .putLong("at_${r.id}", System.currentTimeMillis()).apply()
    }

    private fun report(state: String, error: String? = null, force: Boolean = true, reason: String? = null) {
        val r = item ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastReport < 14_000) return
        lastReport = now
        val json = JSONObject().put("id", r.id).put("title", r.title).put("state", state)
            .put("position_s", player.currentPosition / 1000).put("duration_s", if (player.duration > 0) player.duration / 1000 else 0)
            .put("at_ms", now).apply { if (error != null) put("error", error); (reason ?: pauseReason.takeIf { state == "paused" })?.let { put("reason", it) } }.toString()
        scope.launch {
            runCatching { File(getExternalFilesDir(null), "player-state.json").writeText(json) }
            runCatching {
                http.newCall(Request.Builder().url("$REPORT_URL${r.id}").post(json.toRequestBody("application/json".toMediaType())).build())
                    .enqueue(object : Callback { override fun onFailure(c: Call, e: java.io.IOException) {}; override fun onResponse(c: Call, x: Response) { x.close() } })
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady) stopSelf() }

    override fun onDestroy() {
        main.removeCallbacks(ticker); instance = null; save()
        scope.cancel(); session.release(); player.release(); cache.release()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PREFIX = "io.heckel.ntfy.player."
        const val ACTION_PLAY = "io.heckel.ntfy.player.PLAY"
        const val ACTION_PAUSE = "io.heckel.ntfy.player.PAUSE"
        const val ACTION_RESUME = "io.heckel.ntfy.player.RESUME"
        const val ACTION_TOGGLE = "io.heckel.ntfy.player.TOGGLE"
        const val ACTION_STOP = "io.heckel.ntfy.player.STOP"
        const val ACTION_SEEK = "io.heckel.ntfy.player.SEEK"
        const val ACTION_SKIP = "io.heckel.ntfy.player.SKIP"
        const val ACTION_NEXT = "io.heckel.ntfy.player.NEXT"
        const val ACTION_PREVIOUS = "io.heckel.ntfy.player.PREVIOUS"
        const val EXTRA_FOREGROUND = "io.heckel.ntfy.player.FOREGROUND"
        private const val TAG = "NtfyPlayer"
        private val BACK_30 = SessionCommand("io.heckel.ntfy.player.BACK_30", Bundle.EMPTY)
        private val FORWARD_30 = SessionCommand("io.heckel.ntfy.player.FORWARD_30", Bundle.EMPTY)
        private const val SKIP_MS = 30_000L
        private const val KEEP_MS = 24 * 3_600_000L
        private const val REPORT_URL = "https://infinity.frft.org/player/state/"
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var instance: PlayerService? = null

        /** Starts or controls the player. PLAY, RESUME and TOGGLE may start playback, so they come up in the foreground. */
        fun send(c: Context, action: String, extras: Intent? = null) {
            val starts = action == ACTION_PLAY || action == ACTION_RESUME || action == ACTION_TOGGLE
            if (!starts && instance == null && action != ACTION_SEEK && action != ACTION_NEXT && action != ACTION_PREVIOUS) return // nothing loaded: a pause or stop has nothing to do
            val intent = Intent(c, PlayerService::class.java).setAction(action).apply { extras?.extras?.let { putExtras(it) } }
            if (starts || instance == null) intent.putExtra(EXTRA_FOREGROUND, true)
            runCatching { if (intent.getBooleanExtra(EXTRA_FOREGROUND, false)) ContextCompat.startForegroundService(c, intent) else c.startService(intent) }
                .onFailure { Log.w(TAG, "Could not reach the player for $action", it) }
        }

        fun startFromPush(c: Context, r: PlayerRequest) {
            send(c, ACTION_PLAY, Intent().putExtra("url", r.url).putExtra("title", r.title).putExtra("id", r.id).putExtra("queue", r.queue).apply { r.start?.let { putExtra("start", it) } })
        }

        /** Now Playing opened with nothing loaded (the app was killed, 2026-11-26: "there is no now playing when the
         *  app has been killed"): the last recording comes back paused at its saved place, ready for Play. */
        fun loadLast() { main.post { instance?.let { if (it.item == null || it.player.playbackState == Player.STATE_IDLE) it.ensureItem(autoplay = false) } } }

        /** After an update installed over the top (infinity through Shizuku), what was playing a moment before carries on. */
        fun resumeAfterUpdate(c: Context) {
            val at = c.getSharedPreferences("player", Context.MODE_PRIVATE).getLong("playing_at", 0)
            if (at > 0 && System.currentTimeMillis() - at < 60_000) send(c, ACTION_RESUME)
        }

        // Speech runs on its own thread; the player only on the main one.
        fun pauseForSpeech() { main.post { instance?.let { if (it.player.isPlaying || (it.player.playWhenReady && it.player.playbackState == Player.STATE_BUFFERING)) { it.pausedForSpeech = true; it.player.pause() } } } }
        /** From speech's own thread: pauses the player if it is playing, waiting for the main thread; true if it was. */
        fun pauseForSpeechNow(): Boolean {
            val svc = instance ?: return false
            val done = java.util.concurrent.CountDownLatch(1)
            var paused = false
            main.post {
                runCatching { if (svc.player.isPlaying || (svc.player.playWhenReady && svc.player.playbackState == Player.STATE_BUFFERING)) { svc.pausedForSpeech = true; svc.player.pause(); paused = true } }
                done.countDown()
            }
            done.await(1, TimeUnit.SECONDS)
            return paused
        }
        fun resumeAfterSpeech() { main.post { instance?.let { if (it.pausedForSpeech) { it.pausedForSpeech = false; it.player.play() } } } }
    }
}

/** Commands from infinity through Shizuku (`am broadcast`, as the shell: the DUMP permission keeps other apps out). */
@UnstableApi
class PlayerCommandReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (ExternalMedia.handles(i.action)) { resultData = runCatching { ExternalMedia.handle(c, i) }.getOrElse { JSONObject().put("error", it.message ?: "failed").toString() }; return }
        PlayerService.send(c, i.action ?: return, i)
    }
}

/**
 * Another app's player, for infinity (2026-11-26: ElevenReader loses his place, loops, and starts a finished Office
 * over; infinity keeps the place and puts it right). Through Android's own media controls, which this app may use
 * because its Messages listener has notification access: EXT_STATE answers the package's player state as the
 * broadcast's result (JSON); EXT_SEEK {to_ms}, EXT_PAUSE and EXT_PLAY act on it. Only from infinity through Shizuku
 * (the receiver requires DUMP, as for the own player's commands).
 */
object ExternalMedia {
    const val ACTION_STATE = "io.heckel.ntfy.player.EXT_STATE"
    const val ACTION_SEEK = "io.heckel.ntfy.player.EXT_SEEK"
    const val ACTION_PAUSE = "io.heckel.ntfy.player.EXT_PAUSE"
    const val ACTION_PLAY = "io.heckel.ntfy.player.EXT_PLAY"
    fun handles(action: String?) = action == ACTION_STATE || action == ACTION_SEEK || action == ACTION_PAUSE || action == ACTION_PLAY

    private fun controller(c: Context, pkg: String): android.media.session.MediaController? {
        val msm = c.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return null
        val listener = android.content.ComponentName(c, io.heckel.ntfy.watch.MessagesListener::class.java)
        return runCatching { msm.getActiveSessions(listener) }.getOrNull()?.firstOrNull { it.packageName == pkg }
    }

    fun handle(c: Context, i: Intent): String {
        val pkg = i.getStringExtra("pkg")?.takeIf { Regex("[A-Za-z0-9_.]{3,100}").matches(it) } ?: return JSONObject().put("error", "no package").toString()
        val mc = controller(c, pkg) ?: return JSONObject().put("error", "no session").toString()
        when (i.action) {
            ACTION_SEEK -> mc.transportControls.seekTo(i.getLongExtra("to_ms", i.getIntExtra("to_ms", 0).toLong()).coerceAtLeast(0))
            ACTION_PAUSE -> mc.transportControls.pause()
            ACTION_PLAY -> mc.transportControls.play()
        }
        val s = mc.playbackState
        val m = mc.metadata
        // Where it is now: the position Android was told, carried forward while playing.
        val pos = s?.let { st -> if (st.state == android.media.session.PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0)
            st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else st.position }
        return JSONObject().put("state", s?.state ?: -1).put("position_ms", pos ?: -1).put("speed", s?.playbackSpeed ?: 0f)
            .put("actions", s?.actions ?: 0L).put("duration_ms", m?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1)
            .put("title", m?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artist", m?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            .put("album", m?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            .put("media_id", m?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "").toString()
    }
}
