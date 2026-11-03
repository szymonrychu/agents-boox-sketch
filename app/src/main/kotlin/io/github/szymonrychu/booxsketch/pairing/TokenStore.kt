package io.github.szymonrychu.booxsketch.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supplies the AES key that protects the device token at rest. */
interface KeyProvider {
    fun key(): SecretKey
}

/** AES-256-GCM key in the Android Keystore, alias `token`, non-exportable. */
class AndroidKeystoreKeyProvider : KeyProvider {
    override fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "token"
        const val KEY_BITS = 256
    }
}

/**
 * The paired origin (plain) and device token (AES-GCM ciphertext and IV) in DataStore. [token] is
 * non-suspend because OkHttp threads call it: it serves a decrypted in-memory cache that [load],
 * [save], [rejectToken] and [clear] keep current. A null [tokenFlow] value means pairing is needed.
 * Keystore crypto runs on [io], never on the caller's (possibly Main) thread.
 */
class TokenStore(
    private val dataStore: DataStore<Preferences>,
    private val keyProvider: KeyProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    private val cache = MutableStateFlow<String?>(null)
    private val originCache = MutableStateFlow<String?>(null)
    private val _loaded = MutableStateFlow(false)

    /** True once [load] has finished (or a save or clear made the state known). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** The cached token; null while unpaired or after [rejectToken]. Thread-safe. */
    val tokenFlow: StateFlow<String?> = cache.asStateFlow()

    /** The stored origin, kept after [rejectToken] so the pairing screen can prefill it. */
    val originFlow: StateFlow<String?> = originCache.asStateFlow()

    /**
     * Reads and decrypts the stored token into the cache. An unreadable one (lost key, corrupt
     * file) counts as unpaired: the bad entry is cleared and the cause logged, never thrown.
     */
    suspend fun load() {
        try {
            val prefs = dataStore.data.first()
            val sealed = prefs[CIPHERTEXT]
            val iv = prefs[IV]
            originCache.value = prefs[ORIGIN]
            cache.value =
                if (sealed == null || iv == null) {
                    null
                } else {
                    withContext(io) {
                        decrypt(Base64.getDecoder().decode(sealed), Base64.getDecoder().decode(iv))
                    }
                }
        } catch (e: GeneralSecurityException) {
            discardUnreadable(e)
        } catch (e: IllegalArgumentException) {
            discardUnreadable(e)
        } catch (e: IOException) {
            discardUnreadable(e)
        }
        _loaded.value = true
    }

    private suspend fun discardUnreadable(e: Exception) {
        log("stored token unreadable, treating as unpaired: $e")
        cache.value = null
        originCache.value = null
        try {
            dataStore.edit { it.clear() }
        } catch (second: IOException) {
            log("could not clear the unreadable token store: $second")
        }
    }

    suspend fun save(
        origin: String,
        token: String,
    ) {
        val (sealed, iv) =
            withContext(io) {
                val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, keyProvider.key()) }
                cipher.doFinal(token.toByteArray(Charsets.UTF_8)) to cipher.iv
            }
        dataStore.edit {
            it[ORIGIN] = origin
            it[CIPHERTEXT] = Base64.getEncoder().encodeToString(sealed)
            it[IV] = Base64.getEncoder().encodeToString(iv)
        }
        cache.value = token
        originCache.value = origin
        _loaded.value = true
    }

    suspend fun origin(): String? = dataStore.data.first()[ORIGIN]

    fun token(): String? = cache.value

    /** The server refused the token at runtime: drop it, keep the origin, flip to needs-pairing. */
    suspend fun rejectToken() {
        dataStore.edit {
            it.remove(CIPHERTEXT)
            it.remove(IV)
        }
        cache.value = null
        _loaded.value = true
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
        cache.value = null
        originCache.value = null
        _loaded.value = true
    }

    private fun decrypt(
        sealed: ByteArray,
        iv: ByteArray,
    ): String {
        val cipher =
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, keyProvider.key(), GCMParameterSpec(TAG_BITS, iv))
            }
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        internal val ORIGIN = stringPreferencesKey("origin")
        internal val CIPHERTEXT = stringPreferencesKey("token_ciphertext")
        internal val IV = stringPreferencesKey("token_iv")
    }
}
