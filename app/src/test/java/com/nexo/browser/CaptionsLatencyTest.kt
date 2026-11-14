package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Subtítulos: carrera entre dos modelos de IA, longitud configurable y borrado tras silencio. */
class CaptionsLatencyTest {
    private fun later(ms: Long, block: () -> Unit) = Thread { Thread.sleep(ms); block() }.apply { isDaemon = true; start() }

    @Test fun theFastestModelWinsAndTheSlowOneIsCancelled() {
        val cancelled = AtomicInteger()
        val result = FirstAnswer.race<String>(listOf(
            { done -> later(400) { done("lento") }; FirstAnswer.Attempt { cancelled.incrementAndGet() } },
            { done -> later(20) { done("rápido") }; FirstAnswer.Attempt { cancelled.incrementAndGet() } }
        ), 5_000L)
        assertEquals(1 to "rápido", result)
        assertEquals(2, cancelled.get())
    }

    @Test fun aFailingModelDoesNotEndTheRaceWhileTheOtherAnswers() {
        val result = FirstAnswer.race<String>(listOf(
            { done -> later(5) { done(null) }; FirstAnswer.Attempt { } },
            { done -> later(60) { done("traducido") }; FirstAnswer.Attempt { } }
        ), 5_000L)
        assertEquals(1 to "traducido", result)
        // Uno que ni siquiera arranca (sin clave o dirección insegura) tampoco.
        assertEquals(1 to "ok", FirstAnswer.race<String>(listOf({ _ -> null }, { done -> done("ok"); FirstAnswer.Attempt { } }), 1_000L))
    }

    @Test fun allFailingOrTooSlowGivesNoTranslationInsteadOfHanging() {
        val started = System.currentTimeMillis()
        assertNull(FirstAnswer.race<String>(listOf({ done -> done(null); FirstAnswer.Attempt { } }, { done -> later(10) { done(null) }; FirstAnswer.Attempt { } }), 5_000L))
        assertTrue(System.currentTimeMillis() - started < 2_000L)
        assertNull(FirstAnswer.race<String>(listOf({ _ -> FirstAnswer.Attempt { } }), 150L))
        assertNull(FirstAnswer.race<String>(emptyList(), 100L))
    }

    @Test fun shorterLengthKeepsOnlyTheMostRecentWords() {
        val text = CaptionText()
        text.onTranscript(CaptionProtocol.Transcript("Esta es una frase bastante larga que habla de muchas cosas a la vez", true, true))
        text.onTranscript(CaptionProtocol.Transcript("y luego llega otra frase nueva al final del todo", true, true))
        text.maxChars = 40
        val short = text.view("original", false).primary
        assertTrue(short.length <= 40)
        assertTrue(short.endsWith("al final del todo"))
        text.maxChars = 170
        assertTrue(text.view("original", false).primary.startsWith("Esta es una frase"))
    }

    @Test fun captionsExpireAfterTheChosenSilence() {
        assertFalse(CaptionText.expired(0L, 10_000L, 3_000L))
        assertFalse(CaptionText.expired(8_000L, 10_000L, 3_000L))
        assertTrue(CaptionText.expired(6_000L, 10_000L, 3_000L))
    }

    @Test fun moreAiProvidersBuildTheirOwnRequests() {
        val gemini = CaptionProtocol.aiRequest("gemini", "", "", "k", "Spanish", listOf("hello"), emptyList())
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", gemini.url)
        assertEquals("Bearer k", gemini.headers["Authorization"])
        assertTrue(gemini.body.contains("gemini-2.5-flash"))
        val claude = CaptionProtocol.aiRequest("claude", "", "", "c", "Spanish", listOf("hello"), emptyList())
        assertEquals("https://api.anthropic.com/v1/messages", claude.url)
        assertEquals("c", claude.headers["x-api-key"])
        for ((id, _) in CaptionProtocol.AI_PROVIDERS.filter { it.first != "custom" }) {
            assertTrue(id, CaptionProtocol.AI_ENDPOINTS[id].orEmpty().startsWith("https://"))
        }
        assertTrue(CaptionProtocol.listenUrl("es").contains("endpointing=250"))
    }
}
