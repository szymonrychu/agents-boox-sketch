package io.github.szymonrychu.booxsketch.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class FakeKeyProvider : KeyProvider {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun key(): SecretKey = key
}

class TokenStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keys = FakeKeyProvider()

    @After
    fun tearDown() = scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()

    private fun dataStore(file: File = File(tmp.root, "t.preferences_pb")): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { file }

    @Test
    fun roundtrip_with_fake_key_provider() =
        runBlocking {
            val ds = dataStore()
            TokenStore(ds, keys).save("https://connector.example", "test-token")
            val fresh = TokenStore(ds, keys)
            fresh.load()
            assertThat(fresh.token()).isEqualTo("test-token")
            assertThat(fresh.origin()).isEqualTo("https://connector.example")
            assertThat(fresh.loaded.value).isTrue()
        }

    @Test
    fun ciphertext_not_plaintext() =
        runBlocking {
            val file = File(tmp.root, "t.preferences_pb")
            val ds = dataStore(file)
            TokenStore(ds, keys).save("https://connector.example", "test-token")
            val prefs = ds.data.first()
            assertThat(prefs[TokenStore.CIPHERTEXT]).isNotEmpty()
            assertThat(prefs[TokenStore.CIPHERTEXT]).doesNotContain("test-token")
            assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).doesNotContain("test-token")
        }

    @Test
    fun token_served_from_cache_after_load() =
        runBlocking {
            val ds = dataStore()
            TokenStore(ds, keys).save("https://connector.example", "test-token")
            var keyCalls = 0
            val counting =
                object : KeyProvider {
                    override fun key(): SecretKey = keys.key().also { keyCalls++ }
                }
            val store = TokenStore(ds, counting)
            assertThat(store.token()).isNull()
            store.load()
            val afterLoad = keyCalls
            repeat(3) { assertThat(store.token()).isEqualTo("test-token") }
            assertThat(keyCalls).isEqualTo(afterLoad)
        }

    @Test
    fun clear_removes_token_and_origin() =
        runBlocking {
            val store = TokenStore(dataStore(), keys)
            store.save("https://connector.example", "test-token")
            store.clear()
            assertThat(store.token()).isNull()
            assertThat(store.origin()).isNull()
        }
}
