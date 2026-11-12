package com.nexo.browser

import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()

/** Counts encrypted input too: a GCM implementation may buffer plaintext until authentication. */
internal class TransferLimitedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    private fun received(n: Int) { if (n > 0) { count += n; require(count <= limit) { "Copia cifrada demasiado grande" } } }
    override fun read(): Int = `in`.read().also { if (it >= 0) received(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { received(it) }
}

/** Versioned, authenticated envelope. No plaintext is parsed before the GCM tag verifies. */
object MigrationArchive {
    private val magic = "NEXO-XFER-1\n".toByteArray()
    const val MAX_TOTAL = 128L * 1024 * 1024
    const val MAX_FILE = 64L * 1024 * 1024
    const val MAX_FILES = 4000
    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        require(password.size in 12..256) { "Usa una contraseña de 12 a 256 caracteres." }
        val spec = PBEKeySpec(password, salt, 210000, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, iv)); updateAAD(magic)
            }
        } finally { bytes.fill(0) }
    }
    fun safePath(path: String): Boolean = path.length in 1..512 && !path.startsWith('/') &&
        !path.contains('\\') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c.code < 32 || c == ':' } }
    fun hash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(32768); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun files(root: File): List<File> = root.walkTopDown().onFail { _, e -> throw e }.onEnter {
        require(!java.nio.file.Files.isSymbolicLink(it.toPath())) { "Enlace no permitido" }; true
    }.filter { it.isFile }.onEach {
        require(!java.nio.file.Files.isSymbolicLink(it.toPath()))
        require(safePath(it.relativeTo(root).invariantSeparatorsPath))
    }.toList().also { list ->
        require(list.size <= MAX_FILES && list.all { it.length() <= MAX_FILE } && list.sumOf { it.length() } <= MAX_TOTAL)
    }
    fun write(root: File, output: OutputStream, password: CharArray) {
        val items = files(root).filter { it.relativeTo(root).invariantSeparatorsPath != "manifest.json" }
        val manifest = JSONObject()
        items.forEach { manifest.put(it.relativeTo(root).invariantSeparatorsPath,
            JSONObject().put("size", it.length()).put("sha256", hash(it))) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        output.write(magic); output.write(salt); output.write(iv)
        ZipOutputStream(CipherOutputStream(output, c)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            items.forEach { f -> zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
    }
    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        var size = 0L; val b = ByteArray(32768)
        while (true) { val n = input.read(b); if (n < 0) break; size += n; require(size <= limit) { "Copia demasiado grande" }; output.write(b, 0, n) }
        return size
    }
    fun read(input: InputStream, root: File, password: CharArray) {
        require(!root.exists())
        root.mkdirs()
        val plain = File(root.parentFile, "${root.name}.authenticated.zip")
        try {
            val data = DataInputStream(TransferLimitedInput(input, MAX_TOTAL + 8 * 1024 * 1024))
            val header = ByteArray(magic.size).also { data.readFully(it) }; require(header.contentEquals(magic)) { "Formato de copia no compatible" }
            val salt = ByteArray(16).also { data.readFully(it) }; val iv = ByteArray(12).also { data.readFully(it) }
            CipherInputStream(data, cipher(Cipher.DECRYPT_MODE, password, salt, iv)).use { decrypted ->
                plain.outputStream().use { copyLimited(decrypted, it, MAX_TOTAL + 8 * 1024 * 1024) }
            } // authentication must complete before opening ZIP
            val seen = HashSet<String>(); var total = 0L
            ZipInputStream(plain.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && safePath(entry.name) && seen.add(entry.name) && seen.size <= MAX_FILES + 1) { "Ruta o entrada duplicada no válida" }
                    val file = File(root, entry.name); file.parentFile?.mkdirs()
                    total += file.outputStream().use { copyLimited(zip, it, if (entry.name == "manifest.json") 1024 * 1024 else MAX_FILE) }
                    require(total <= MAX_TOTAL)
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(File(root, "manifest.json").readText())
            require(manifest.keySet() == seen - "manifest.json") { "Manifiesto incompleto" }
            manifest.keySet().forEach { name ->
                val f = File(root, name); val meta = manifest.getJSONObject(name)
                require(f.length() == meta.getLong("size") && hash(f) == meta.getString("sha256")) { "Archivo dañado" }
            }
        } catch (e: Exception) { root.deleteRecursively(); throw e }
        finally { plain.delete() }
    }
}
