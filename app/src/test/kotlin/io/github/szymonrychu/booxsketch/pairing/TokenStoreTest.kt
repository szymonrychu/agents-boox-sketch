package io.github.szymonrychu.booxsketch.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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

    @Test
    fun reject_token_keeps_origin_and_flips_to_needs_pairing() =
        runBlocking {
            val ds = dataStore()
            val store = TokenStore(ds, keys)
            store.save("https://connector.example", "test-token")
            assertThat(store.tokenFlow.value).isEqualTo("test-token")
            store.rejectToken()
            assertThat(store.tokenFlow.value).isNull()
            assertThat(store.token()).isNull()
            assertThat(store.originFlow.value).isEqualTo("https://connector.example")
            assertThat(store.origin()).isEqualTo("https://connector.example")
            assertThat(store.loaded.value).isTrue()
            val fresh = TokenStore(ds, keys)
            fresh.load()
            assertThat(fresh.token()).isNull()
            assertThat(fresh.originFlow.value).isEqualTo("https://connector.example")
        }

    @Test
    fun load_with_undecryptable_ciphertext_is_unpaired_and_clears_entry() =
        runBlocking {
            val ds = dataStore()
            TokenStore(ds, keys).save("https://connector.example", "test-token")
            val logs = mutableListOf<String>()
            val lostKey = TokenStore(ds, FakeKeyProvider(), log = { logs += it })
            lostKey.load()
            assertThat(lostKey.token()).isNull()
            assertThat(lostKey.loaded.value).isTrue()
            assertThat(logs).hasSize(1)
            assertThat(ds.data.first()[TokenStore.CIPHERTEXT]).isNull()
        }

    @Test
    fun load_with_corrupt_file_is_unpaired() =
        runBlocking {
            val file = File(tmp.root, "bad.preferences_pb")
            file.writeBytes(ByteArray(64) { 0x7f })
            val logs = mutableListOf<String>()
            val ds =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                ) { file }
            val store = TokenStore(ds, keys, log = { logs += it })
            store.load()
            assertThat(store.token()).isNull()
            assertThat(store.loaded.value).isTrue()
            store.save("https://connector.example", "test-token")
            assertThat(store.token()).isEqualTo("test-token")
        }

    @Test
    fun load_with_corrupt_file_and_no_handler_does_not_throw() =
        runBlocking {
            val file = File(tmp.root, "bad2.preferences_pb")
            file.writeBytes(ByteArray(64) { 0x7f })
            val logs = mutableListOf<String>()
            val store = TokenStore(dataStore(file), keys, log = { logs += it })
            store.load()
            assertThat(store.token()).isNull()
            assertThat(store.loaded.value).isTrue()
            assertThat(logs).isNotEmpty()
        }
}
