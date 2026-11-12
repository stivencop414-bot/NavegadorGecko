package com.nexo.browser

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Recoverable file replacement, on one filesystem. Journal precedes every mutation. */
class TransferCommit(private val base: File, private val journalDir: File) {
    private val journal get() = File(journalDir, "journal.json")
    private fun durable(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    private fun target(path: String): File {
        require(MigrationArchive.safePath(path))
        val f = File(base, path)
        var p: File? = f
        while (p != null && p != base) { require(!Files.isSymbolicLink(p.toPath())); p = p.parentFile }
        require(f.canonicalPath.startsWith(base.canonicalPath + File.separator))
        return f
    }
    fun apply(replacements: Map<String, File?>, checkpoint: (Int) -> Unit = {}) {
        require(!journal.exists()) { "Restaura o elimina primero la copia anterior del destino." }
        require(replacements.keys.none { a -> replacements.keys.any { b -> a != b && b.startsWith("$a/") } }) { "Rutas solapadas" }
        // No journal means no target was ever modified; discard a preparation
        // interrupted while collecting backups, then rebuild it from originals.
        if (journalDir.exists()) check(journalDir.deleteRecursively())
        check(journalDir.mkdirs())
        val items = JSONArray()
        replacements.forEach { (path, _) ->
            val f = target(path); val old = File(journalDir, "backup/$path")
            require(!f.exists() || f.isFile)
            if (f.exists()) { old.parentFile?.mkdirs(); f.copyTo(old); java.io.RandomAccessFile(old, "rw").use { it.fd.sync() } }
            items.put(JSONObject().put("path", path).put("existed", f.exists()))
        }
        val state = JSONObject().put("state", "applying").put("items", items)
        durable(journal, state.toString().toByteArray())
        // Backups are immutable. A crash here always restores the complete previous set.
        replacements.entries.forEachIndexed { i, (path, staged) -> if (staged != null) durable(target(path), staged.readBytes()) else target(path).let { if(it.exists()) check(it.delete()) }; checkpoint(i) }
        state.put("state", "committed"); durable(journal, state.toString().toByteArray())
    }
    fun state(): String? = if (journal.exists()) JSONObject(journal.readText()).getString("state") else null
    fun restore(checkpoint: (Int) -> Unit = {}) {
        if (!journal.exists()) return
        val state = JSONObject(journal.readText()).put("state", "restoring")
        durable(journal, state.toString().toByteArray())
        val items = state.getJSONArray("items")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i); val path = item.getString("path"); val f = target(path)
            if (item.getBoolean("existed")) durable(f, File(journalDir, "backup/$path").readBytes())
            else if (f.exists()) check(f.delete())
            checkpoint(i)
        }
        check(journal.delete())
        journalDir.deleteRecursively()
    }
    fun recover() { if (state() in listOf("applying", "restoring")) restore() }
    fun discardBackup() { require(state() == "committed"); check(journalDir.deleteRecursively()) }
}
