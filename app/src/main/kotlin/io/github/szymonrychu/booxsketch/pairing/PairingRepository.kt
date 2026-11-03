package io.github.szymonrychu.booxsketch.pairing

import io.github.szymonrychu.booxsketch.BuildConfig
import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException

sealed interface PairResult {
    data class Ok(
        val config: ConfigDto,
    ) : PairResult

    data object InvalidOrigin : PairResult

    data object TokenRejected : PairResult

    data class StorageFailed(
        val message: String,
    ) : PairResult

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
    @Suppress("SwallowedException", "TooGenericExceptionCaught") // the failure kind is the result
    suspend fun pair(
        origin: String,
        token: String,
    ): PairResult {
        val normalized = normalizeOrigin(origin) ?: return PairResult.InvalidOrigin
        val config =
            try {
                clientFactory(normalized) { token }.config()
            } catch (e: ApiError.Unauthorized) {
                return PairResult.TokenRejected
            } catch (e: ApiError) {
                return PairResult.Unreachable(e.message ?: e.javaClass.simpleName)
            }
        return try {
            store.save(normalized, token)
            PairResult.Ok(config)
        } catch (e: GeneralSecurityException) {
            PairResult.StorageFailed(e.message ?: e.javaClass.simpleName)
        } catch (e: IOException) {
            PairResult.StorageFailed(e.message ?: e.javaClass.simpleName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // unchecked keystore failures (for example ProviderException) must not crash the caller
            PairResult.StorageFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** A request was refused with 401 while paired: drop the token and show the pairing screen again. */
    suspend fun rejectToken() = store.rejectToken()

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
        val LOOPBACK_HOSTS = setOf("127.0.0.1")
    }
}
