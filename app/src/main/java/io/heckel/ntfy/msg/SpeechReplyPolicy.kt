package io.heckel.ntfy.msg

/** Listening is for pushes that wait on his answer, independent of the audio route. */
object SpeechReplyPolicy {
    fun expectsAnswer(tags: Collection<String>): Boolean =
        tags.any { it == "keep-waiting" || it == "listen" || it == "ring-voice" }
}
