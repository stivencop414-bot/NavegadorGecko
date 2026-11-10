package com.ejemplo.navegador
import org.junit.Assert.*
import org.junit.Test
class PageTranslationTest {
    @Test fun resolvesRegionalDetectionAndPreservesExactVariants() {
        assertEquals(1, PageTranslation.sourceIndex("pt-BR", listOf("en", "pt", "es")))
        assertEquals(2, PageTranslation.sourceIndex("zh-Hant", listOf("en", "zh", "zh-Hant")))
        assertEquals(1, PageTranslation.sourceIndex("FR", listOf("en", "fr")))
    }
    @Test fun unavailableDetectionUsesAnAvailableFallback() {
        assertEquals(1, PageTranslation.sourceIndex(null, listOf("es", "en")))
        assertEquals(0, PageTranslation.sourceIndex("xx", listOf("es", "pt")))
    }
}
