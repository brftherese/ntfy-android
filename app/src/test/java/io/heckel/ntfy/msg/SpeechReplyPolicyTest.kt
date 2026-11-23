package io.heckel.ntfy.msg

import org.junit.Assert.*
import org.junit.Test

class SpeechReplyPolicyTest {
    @Test fun fyiAndChatterNeverListen() {
        for (tags in listOf(emptyList(), listOf("speak-token"), listOf("watch-token", "speak-token"), listOf("alert-again", "speak-token"))) {
            assertFalse(tags.toString(), SpeechReplyPolicy.expectsAnswer(tags))
        }
    }
    @Test fun onlyWaitingListenAndRingAnswerPushesListen() {
        for (tag in listOf("keep-waiting", "listen", "ring-voice")) {
            assertTrue(tag, SpeechReplyPolicy.expectsAnswer(listOf("speak-token", tag)))
        }
    }
    @Test fun replyAndSpeechMetadataDoNotRequestListening() {
        assertFalse(SpeechReplyPolicy.expectsAnswer(listOf("spoken-answer", "answer-token", "warning")))
    }
}
