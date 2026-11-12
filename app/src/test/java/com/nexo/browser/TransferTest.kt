package com.nexo.browser

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class TransferTest {
    private fun temp() = Files.createTempDirectory("nexo-transfer-test").toFile()
    private val password get() = "Una contraseña larga 2026".toCharArray()
    @Test fun archiveRoundTripAndWrongPassword() {
        val root=temp()
        try {
            val source=File(root,"source").apply { mkdirs() }
            File(source,"native.json").writeText("native secret")
            File(source,"gecko/default/origin/idb/db.sqlite").apply { parentFile.mkdirs(); writeBytes(ByteArray(10000) { (it%251).toByte() }) }
            val bytes=ByteArrayOutputStream().also { MigrationArchive.write(source,it,password) }.toByteArray()
            assertFalse(String(bytes).contains("native secret"))
            val target=File(root,"valid");MigrationArchive.read(ByteArrayInputStream(bytes),target,password)
            assertArrayEquals(File(source,"gecko/default/origin/idb/db.sqlite").readBytes(),File(target,"gecko/default/origin/idb/db.sqlite").readBytes())
            for (candidate in listOf(bytes,bytes.copyOf(bytes.size-1),bytes.clone().also { it[it.size-2]=(it[it.size-2].toInt() xor 1).toByte() })) {
                val failed=File(root,"invalid")
                assertThrows(Exception::class.java) { MigrationArchive.read(ByteArrayInputStream(candidate),failed,"wrong password!".toCharArray()) }
                assertFalse(failed.exists());assertFalse(File(root,"invalid.authenticated.zip").exists())
            }
            assertThrows(Exception::class.java) { MigrationArchive.read(ByteArrayInputStream(bytes.clone().also { it[it.size-1]=(it.last().toInt() xor 1).toByte() }),File(root,"tampered"),password) }
        } finally { root.deleteRecursively() }
    }
    @Test fun unsafePathsAndPrivacyOrigins() {
        for (p in listOf("../secret","/secret","a/../b","a//b","C:/a","a\\b","a/./b","a/\u0000")) assertFalse(p,MigrationArchive.safePath(p))
        val uuid="12345678-1234-1234-1234-123456789abc"
        assertTrue(TransferSchema.origin("moz-extension+++$uuid",setOf(uuid)))
        assertTrue(TransferSchema.origin("moz-extension+++$uuid^userContextId=4294967295",setOf(uuid)))
        for(suffix in listOf("^privateBrowsingId=1","^userContextId=1","^geckoViewSessionContextId=burner")) assertFalse(TransferSchema.origin("moz-extension+++$uuid$suffix",setOf(uuid)))
        assertFalse(TransferSchema.payloadPath("gecko/default/https+++site/idb/foo",setOf(uuid),emptySet()))
        assertFalse(TransferSchema.payloadPath("gecko/prefs.js",setOf(uuid),emptySet()))
        assertFalse(TransferSchema.payloadPath("addons/../../extensions.json",setOf(uuid),emptySet()))
    }
    @Test fun tabsDropPrivateBurnerAndOpaqueState() {
        val raw="""[{"url":"https://normal.test/","title":"Normal","groupName":"Trabajo","sessionState":"opaque","network":{"type":"socks"}},
            {"url":"https://private.test","isPrivate":true},{"url":"https://burner.test","burnerId":"identity"},
            {"url":"javascript:alert(1)"}]"""
        val tabs=org.json.JSONArray(TransferSchema.tabs(raw)); assertEquals(1,tabs.length())
        val tab=tabs.getJSONObject(0);assertEquals("Trabajo",tab.getString("groupName"));assertFalse(tab.has("sessionState"));assertFalse(tab.has("network"))
    }
    @Test fun rollbackAtEveryFileBoundaryIncludingNewFilesAndDeletions() {
        for (cut in 0..2) {
            val root=temp()
            try {
                val base=File(root,"data").apply { mkdirs() }
                File(base,"old").writeText("original");File(base,"removed").writeText("keep")
                val staged=File(root,"new").apply { writeText("imported") }
                val journal=File(root,"journal")
                assertThrows(IllegalStateException::class.java) {
                    TransferCommit(base,journal).apply(linkedMapOf("old" to staged,"new" to staged,"removed" to null)) { if(it==cut) error("simulated process death") }
                }
                TransferCommit(base,journal).recover()
                assertEquals("original",File(base,"old").readText());assertEquals("keep",File(base,"removed").readText());assertFalse(File(base,"new").exists())
                assertNull(TransferCommit(base,journal).state())
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun committedTransferKeepsRestorableOriginalsAndCannotOverwriteBackup() {
        val root=temp()
        try {
            val base=File(root,"data").apply { mkdirs() }; val f=File(base,"item").apply { writeText("old") }
            val staged=File(root,"new").apply { writeText("new") };val tx=TransferCommit(base,File(root,"journal"))
            tx.apply(mapOf("item" to staged));assertEquals("committed",tx.state());assertEquals("new",f.readText());tx.recover();assertEquals("new",f.readText())
            assertThrows(IllegalArgumentException::class.java) { tx.apply(mapOf("item" to staged)) }
            tx.restore();assertEquals("old",f.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun symlinkTargetsAndSourcesAreRejected() {
        val root=temp()
        try {
            val base=File(root,"data").apply { mkdirs() };val outside=File(root,"outside").apply { writeText("keep") }
            Files.createSymbolicLink(File(base,"link").toPath(),outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { TransferCommit(base,File(root,"journal")).apply(mapOf("link" to outside)) }
            assertEquals("keep",outside.readText())
            assertThrows(IllegalArgumentException::class.java) { MigrationArchive.write(base,ByteArrayOutputStream(),password) }
        } finally { root.deleteRecursively() }
    }
    @Test fun profileResolutionRejectsAmbiguityAndEscapes() {
        val root=temp()
        try {
            val home=File(root,"mozilla").apply { mkdirs() };File(home,"abc.default").mkdirs()
            val ini=File(home,"profiles.ini").apply { writeText("[Profile0]\nName=default\nIsRelative=1\nPath=abc.default\nDefault=1\n") }
            assertEquals(File(home,"abc.default"),TransferSchema.profile(root))
            ini.writeText("[Profile0]\nIsRelative=1\nPath=../outside\nDefault=1\n")
            assertThrows(IllegalArgumentException::class.java) { TransferSchema.profile(root) }
        } finally { root.deleteRecursively() }
    }
    @Test fun protocolKeepsContainerConfigurationAndParsesProviderErrors() {
        assertTrue(CaptionProtocol.supportedMime("audio/ogg; codecs=opus"));assertFalse(CaptionProtocol.supportedMime("audio/mp4"))
        assertTrue(CaptionProtocol.hasContainerHeader("OggSxxx".toByteArray()));assertFalse(CaptionProtocol.hasContainerHeader(byteArrayOf(0,1,2,3)))
        val url=CaptionProtocol.listenUrl("multi");assertFalse(url.contains("sample_rate"));assertFalse(url.contains("encoding="));assertFalse(url.contains("channels="))
        assertEquals("bad data",CaptionProtocol.parseError("""{"type":"Error","description":"bad data"}"""))
    }
    @Test fun interruptedRestoreIsIdempotentAndPartialBackupPreparationIsRetryable() {
        val root=temp()
        try {
            val base=File(root,"data").apply { mkdirs() };File(base,"old").writeText("original")
            val journal=File(root,"journal").apply { mkdirs() };File(journal,"backup/old").apply { parentFile.mkdirs();writeText("incomplete") }
            val staged=File(root,"new").apply { writeText("imported") }
            val tx=TransferCommit(base,journal);tx.apply(linkedMapOf("old" to staged,"new" to staged))
            assertThrows(IllegalStateException::class.java) { tx.restore { if(it==0) error("death during restore") } }
            TransferCommit(base,journal).recover()
            assertEquals("original",File(base,"old").readText());assertFalse(File(base,"new").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun encryptedInputIsBoundedBeforeTheCipherCanBufferIt() {
        val input=TransferLimitedInput(ByteArrayInputStream(ByteArray(100)),16)
        assertEquals(12,input.read(ByteArray(12),0,12))
        assertThrows(IllegalArgumentException::class.java) { input.read(ByteArray(8),0,8) }
        val single=TransferLimitedInput(ByteArrayInputStream(ByteArray(3)),2)
        single.read();single.read();assertThrows(IllegalArgumentException::class.java) { single.read() }
    }

}
