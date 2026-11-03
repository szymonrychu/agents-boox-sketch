package io.github.szymonrychu.booxsketch.pairing

import io.github.szymonrychu.booxsketch.BuildConfig
import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import java.net.URI
import java.net.URISyntaxException

sealed interface PairResult {
    data class Ok(
        val config: ConfigDto,
    ) : PairResult

    data object InvalidOrigin : PairResult

    data object TokenRejected : PairResult

    data class Unreachable(
        val message: String,
    ) : PairResult
}

/**
 * Validates the origin, proves the token with `GET /config` and stores both on success.
 * [clientFactory] receives the origin and the token provider to authenticate with, so the
 * candidate token is tried before anything is stored.
 */
class PairingRepository(
    private val store: TokenStore,
    private val clientFactory: (String, () -> String?) -> ConnectorClient,
    private val allowLoopbackHttp: Boolean = BuildConfig.DEBUG,
) {
    @Suppress("SwallowedException") // the failure kind is the result
    suspend fun pair(
        origin: String,
        token: String,
    ): PairResult {
        val normalized = normalizeOrigin(origin) ?: return PairResult.InvalidOrigin
        return try {
            val config = clientFactory(normalized) { token }.config()
            store.save(normalized, token)
            PairResult.Ok(config)
        } catch (e: ApiError.Unauthorized) {
            PairResult.TokenRejected
        } catch (e: ApiError) {
            PairResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    /** `scheme://host[:port]` without a trailing slash, or null when the origin is not acceptable. */
    @Suppress("SwallowedException") // unparsable means invalid
    internal fun normalizeOrigin(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        val uri =
            try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return null
            }
        val host = uri.host
        val bare =
            host != null &&
                uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        val schemeOk =
            uri.scheme == "https" ||
                (allowLoopbackHttp && uri.scheme == "http" && host in LOOPBACK_HOSTS)
        return trimmed.takeIf { bare && schemeOk }
    }

    private companion object {
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "10.0.2.2")
    }
}
