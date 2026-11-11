package com.nexo.browser
import org.junit.Assert.*
import org.junit.Test
class IconPayloadTest {
    @Test fun acceptsRasterAndRejectsMalformedIco() {
        val png = byteArrayOf(-119,80,78,71,13,10,26,10)
        assertArrayEquals(png, IconPayload.pngOrImage(png))
        assertNull(IconPayload.pngOrImage(byteArrayOf(0,0,1,0,1,0,0,0)))
        assertNull(IconPayload.pngOrImage(ByteArray(262145)))
        val ico = ByteArray(30)
        ico[2]=1;ico[4]=1;ico[6]=16;ico[14]=8;ico[18]=22
        png.copyInto(ico,22)
        assertArrayEquals(png, IconPayload.pngOrImage(ico))
        ico[21]=127
        assertNull(IconPayload.pngOrImage(ico))
    }
}
