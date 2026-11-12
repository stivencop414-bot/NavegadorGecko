package com.nexo.browser

import org.junit.Assert.*
import org.junit.Test

class NavigationStabilityTest {
    @Test fun neverRestoreASnapshotFromAnEarlierSpaPage() {
        assertFalse(TabNavigation.canRestore("https://video.test/watch/2","https://video.test/watch/1"))
        assertFalse(TabNavigation.canRestore("https://video.test/watch/2",null))
        assertTrue(TabNavigation.canRestore("https://video.test/watch/2","https://video.test/watch/2"))
        assertFalse(TabNavigation.canRestore("https://video.test/watch/2#next","https://video.test/watch/2"))
    }
    @Test fun stolenCrossOriginVideoTapRequiresWindowConsent() {
        assertTrue(TabNavigation.needsWindowConfirmation("https://player.test/embed","https://ads.test/open"))
        assertTrue(TabNavigation.needsWindowConfirmation("https://player.test/embed","about:blank"))
        assertTrue(TabNavigation.needsWindowConfirmation("https://player.test/embed","http://player.test/open"))
        assertTrue(TabNavigation.needsWindowConfirmation("https://player.test/embed","https://player.test:444/open"))
        assertFalse(TabNavigation.needsWindowConfirmation("https://player.test/embed","https://PLAYER.test:443/login"))
    }
    @Test fun pcmSignedSamplesAndNoiseThreshold() {
        assertFalse(PlaybackPcm.hasSignal(ByteArray(8000)))
        assertFalse(PlaybackPcm.hasSignal(byteArrayOf(120,0)))
        assertTrue(PlaybackPcm.hasSignal(byteArrayOf(121,0)))
        assertTrue(PlaybackPcm.hasSignal(byteArrayOf(0,128.toByte()))) // -32768, no abs overflow
        assertFalse(PlaybackPcm.hasSignal(byteArrayOf(12))) // partial frame
    }
    @Test fun rawAudioParametersAreOnlyUsedByTheNativePcmSource() {
        val native=PlaybackPcm.listenUrl("ja","nova-2")
        assertTrue(native.contains("encoding=linear16&sample_rate=16000&channels=1"))
        assertFalse(CaptionProtocol.listenUrl("ja").contains("encoding="))
        assertFalse(CaptionProtocol.listenUrl("ja").contains("sample_rate="))
    }
}
