package io.github.szymonrychu.booxsketch.send

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The connector limits (`GET /config`) last seen, kept in [file] so offline sends check against
 * them. Until the first fetch the connector's documented defaults apply; the server still
 * enforces its own limits, a refusal there fails the item with the server's reason.
 */
class ConfigCache(
    private val file: File,
) {
    @Volatile
    private var cached: ConfigDto? = null

    /** True once this process fetched the limits; drains refresh them until then. */
    @Volatile
    var fetched: Boolean = false
        private set

    fun current(): ConfigDto = cached ?: load() ?: DEFAULT

    /** Fetches and stores the limits; failures keep the previous ones. */
    suspend fun refresh(client: ConnectorClient) {
        val cfg =
            try {
                client.config()
            } catch (_: ApiError) {
                return
            }
        cached = cfg
        fetched = true
        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(ConfigDto.serializer(), cfg))
            } catch (_: IOException) {
                // Kept in memory; the next refresh writes it again.
            }
        }
    }

    private fun load(): ConfigDto? =
        try {
            if (file.isFile) json.decodeFromString(ConfigDto.serializer(), file.readText()).also { cached = it } else null
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }

    companion object {
        /** agents-app-connector defaults (v0.3.0). */
        val DEFAULT =
            ConfigDto(
                maxLongEdgePx = 1568,
                maxPngBytes = 6_291_456,
                maxStrokesBytes = 2_097_152,
                maxPages = 4,
                maxUploadBytes = 8_388_608,
                minEdgePx = 200,
                apiVersion = 1,
            )
        private val json = Json { ignoreUnknownKeys = true }
    }
}
