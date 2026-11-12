package com.ejemplo.navegador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlanTest {
    private val mb = 1024L * 1024

    @Test fun parsesContentRange() {
        assertEquals(DownloadPlan.ContentRange(100, 199, 1000), DownloadPlan.parseContentRange("bytes 100-199/1000"))
        assertEquals(-1L, DownloadPlan.parseContentRange("bytes 0-9/*")?.total)
        assertNull(DownloadPlan.parseContentRange("bytes 200-100/1000"))
        assertNull(DownloadPlan.parseContentRange("bytes 0-1000/1000"))
        assertNull(DownloadPlan.parseContentRange("items 0-1/2"))
        assertNull(DownloadPlan.parseContentRange(null))
    }

    @Test fun splitsOnlyLargeUncompressedRangeableFiles() {
        val ok = mapOf("accept-ranges" to "bytes", "Content-Length" to "1")
        assertTrue(DownloadPlan.canSplit(ok, 20 * mb))
        assertFalse(DownloadPlan.canSplit(ok, 8 * mb))
        assertFalse(DownloadPlan.canSplit(mapOf("Accept-Ranges" to "none"), 20 * mb))
        assertFalse(DownloadPlan.canSplit(ok + ("Content-Encoding" to "gzip"), 20 * mb))
        assertEquals(2, DownloadPlan.connectionsFor(20 * mb))
        assertEquals(4, DownloadPlan.connectionsFor(500 * mb))
    }

    @Test fun middleSegmentsCoverTheGapExactly() {
        val total = 200 * mb
        val tail = DownloadPlan.tailStart(total, 4)
        assertEquals(150 * mb, tail)
        val (limit, ranges) = DownloadPlan.split(10 * mb, tail, 2)!!
        assertEquals(2, ranges.size)
        assertEquals(limit, ranges.first().first)
        assertEquals(tail, ranges.last().last + 1)
        ranges.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        assertTrue(limit > 10 * mb)
    }

    @Test fun noSplitWhenTheOriginalConnectionIsAlmostThere() {
        assertNull(DownloadPlan.split(147 * mb, 150 * mb, 2))
        // Pocos datos para tres conexiones: se usan menos.
        assertEquals(1, DownloadPlan.split(0, 10 * mb, 2)!!.second.size)
        val (limit, ranges) = DownloadPlan.split(0, 6 * mb, 2)!!
        assertTrue(ranges.isEmpty())
        assertEquals(6 * mb, limit)
    }

    @Test fun rejectsPartsOfAnotherVersion() {
        assertTrue(DownloadPlan.sameEntity(mapOf("ETag" to "\"a\""), mapOf("etag" to "W/\"a\"")))
        assertFalse(DownloadPlan.sameEntity(mapOf("ETag" to "\"a\""), mapOf("ETag" to "\"b\"")))
        assertTrue(DownloadPlan.sameEntity(emptyMap(), mapOf("ETag" to "\"b\"")))
    }
}
