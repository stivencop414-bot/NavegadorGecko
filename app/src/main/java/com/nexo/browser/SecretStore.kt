package com.nexo.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Claves de servicios (Deepgram, DeepL, IA de traducción) cifradas con una
 * clave AES-GCM del Android Keystore, que nunca sale del almacén del sistema.
 * Solo el proceso nativo las usa; jamás se entregan a páginas ni extensiones.
 */
object SecretStore {
    private const val PREFS = "nexo_secrets"
    private const val ALIAS = "nexo_service_keys"
    private const val TAG_BITS = 128
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun get(context: Context, name: String): String {
        cache[name]?.let { return it }
        val stored = prefs(context).getString(name, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrDefault("").also { if (it.isNotEmpty()) cache[name] = it }
    }

    fun put(context: Context, name: String, value: String) {
        val clean = value.trim()
        cache.remove(name)
        if (clean.isEmpty()) {
            prefs(context).edit().remove(name).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            prefs(context).edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        }
    }

    fun has(context: Context, name: String): Boolean = prefs(context).contains(name)
}
