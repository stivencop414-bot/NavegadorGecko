package com.ejemplo.navegador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionProtocolTest {
    @Test fun listenUrlUsesStreamingOptionsAndSafeLanguage() {
        val url = CaptionProtocol.listenUrl("multi")
        assertTrue(url.startsWith("wss://api.deepgram.com/v1/listen?model=nova-3&language=multi"))
        assertTrue(url.contains("interim_results=true"))
        assertTrue(CaptionProtocol.listenUrl("es").contains("model=nova-2&language=es"))
        assertTrue(CaptionProtocol.listenUrl("es&x=1").contains("language=multi"))
    }

    @Test fun parsesInterimAndFinalResults() {
        val interim = CaptionProtocol.parseResult(
            """{"type":"Results","is_final":false,"speech_final":false,"channel":{"alternatives":[{"transcript":"hola a"}]}}""")
        assertEquals("hola a", interim?.text)
        assertFalse(interim!!.isFinal)
        val final = CaptionProtocol.parseResult(
            """{"type":"Results","is_final":true,"speech_final":true,"channel":{"alternatives":[{"transcript":" Hola a todos. "}]}}""")
        assertEquals("Hola a todos.", final?.text)
        assertTrue(final!!.isFinal)
        assertNull(CaptionProtocol.parseResult("""{"type":"Metadata"}"""))
        assertTrue(CaptionProtocol.isUtteranceEnd("""{"type":"UtteranceEnd","last_word_end":2.1}"""))
    }

    @Test fun parsesTranslators() {
        assertEquals("Hola mundo. Adiós.", CaptionProtocol.parseGoogle("""[[["Hola mundo. ","Hello world. ",null],["Adiós.","Bye.",null]],null,"en"]"""))
        assertNull(CaptionProtocol.parseGoogle("<html>"))
        assertEquals("Hola", CaptionProtocol.parseDeepl("""{"translations":[{"detected_source_language":"EN","text":"Hola"}]}"""))
        assertEquals("https://api-free.deepl.com/v2/translate", CaptionProtocol.deeplEndpoint("abc:fx"))
        assertEquals("EN-US", CaptionProtocol.deeplTarget("en"))
        assertTrue(CaptionProtocol.googleUrl("a b", "es").endsWith("tl=es&dt=t&q=a+b"))
    }

    @Test fun captionTextKeepsRecentLinesAndPrefersTranslations() {
        val text = CaptionText(maxChars = 40)
        val first = text.onTranscript(CaptionProtocol.Transcript("Hello everyone.", isFinal = true, speechFinal = true))!!
        text.onTranscript(CaptionProtocol.Transcript("Today we", isFinal = false, speechFinal = false))
        assertEquals("" to "Hello everyone. Today we", text.display(translating = true))
        text.setTranslation(first.id, "Hola a todos.")
        assertEquals("Hola a todos." to "Today we", text.display(translating = true))
        assertEquals("Hello everyone." to "Today we", text.display(translating = false))
        repeat(5) { text.onTranscript(CaptionProtocol.Transcript("Sentence number $it here.", isFinal = true, speechFinal = true)) }
        val (done, pending) = text.display(translating = false)
        assertTrue(done.length + pending.length <= 41)
        assertTrue(done.endsWith("number 4 here.") || pending.endsWith("number 4 here."))
    }
}
