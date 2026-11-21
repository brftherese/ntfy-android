package io.heckel.ntfy.player

import java.net.URI

data class PlayerRequest(val url: String, val title: String, val id: String, val start: Int?)

object PlayerLogic {
    private val idPattern = Regex("[A-Za-z0-9_-]{8,64}")
    fun isPlayer(tags: String?) = tags.orEmpty().split(',').any { it.trim() == "player" }
    fun parse(tags: String?, messageId: String, title: String, url: String?, timestamp: Long, now: Long = System.currentTimeMillis()/1000): PlayerRequest? {
        if (!isPlayer(tags) || url == null || now - timestamp > 600 || !allowedUrl(url)) return null
        val parts = tags.orEmpty().split(',').map { it.trim() }
        val id = parts.firstOrNull { it.startsWith("player-id-") }?.removePrefix("player-id-")?.takeIf { idPattern.matches(it) } ?: messageId
        val start = parts.firstOrNull { it.startsWith("player-start-") }?.removePrefix("player-start-")?.toIntOrNull()?.takeIf { it >= 0 }
        return PlayerRequest(url, title, id, start)
    }
    fun allowedUrl(value: String): Boolean = runCatching {
        val u = URI(value); u.scheme == "https" && (u.host == "infinity.frft.org" || u.host == "ntfy.frft.org")
    }.getOrDefault(false)
    fun resumePosition(saved: Long, duration: Long): Long = if (saved > 2000 && (duration <= 0 || saved < duration)) saved - 2000 else 0
}
