package io.heckel.ntfy.player
import org.junit.Assert.*
import org.junit.Test
class PlayerLogicTest {
 @Test fun tagsAndStart(){ val r=PlayerLogic.parse("player,player-id-abcdefgh,player-start-12","msg","T","https://infinity.frft.org/a.mp3",100,100); assertEquals("abcdefgh",r!!.id); assertEquals(12,r.start) }
 @Test fun rejectsBadUrl(){ assertFalse(PlayerLogic.allowedUrl("https://evil.example/a")); assertFalse(PlayerLogic.allowedUrl("http://infinity.frft.org/a")) }
 @Test fun resumesTwoSecondsBefore(){ assertEquals(98_000,PlayerLogic.resumePosition(100_000,200_000)); assertEquals(0,PlayerLogic.resumePosition(1000,200_000)) }
 @Test fun queueTag(){ val r=PlayerLogic.parse("player,player-id-abcdefgh,player-queue","msg","T","https://infinity.frft.org/music/queue/tok?from=0",100,100); assertTrue(r!!.queue); assertFalse(PlayerLogic.parse("player","msg","T","https://infinity.frft.org/a.mp3",100,100)!!.queue) }
 @Test fun queuePages(){ assertEquals("https://infinity.frft.org/music/queue/tok?from=30",PlayerLogic.queuePage("https://infinity.frft.org/music/queue/tok?from=0",30)) }
}
