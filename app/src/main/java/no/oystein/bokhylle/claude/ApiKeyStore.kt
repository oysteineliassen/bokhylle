package no.oystein.bokhylle.claude

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
 * API-nøkkelen lagres kryptert med AES-GCM. Krypteringsnøkkelen ligger i Android Keystore og kan ikke leses ut av appen.
 * Nøkkelen logges aldri.
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("hemmelig", Context.MODE_PRIVATE)

    fun get(): String? {
        val stored = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun set(apiKey: String?) {
        val value = apiKey?.trim()
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(KEY).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    val isSet: Boolean get() = prefs.contains(KEY)

    /** De siste fire tegnene, så brukeren ser hvilken nøkkel som er lagret. */
    fun hint(): String? = get()?.takeLast(4)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY = "claude_api_nokkel"
        private const val ALIAS = "bokhylle_claude"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
