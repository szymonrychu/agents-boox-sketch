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

    /** Wrong, expired, used or locked code: the server answers the same for every cause. */
    data object CodeRejected : PairResult

    data class RateLimited(
        val retryAfterS: Long?,
    ) : PairResult

    data class StorageFailed(
        val message: String,
    ) : PairResult

    data class Unreachable(
        val message: String,
    ) : PairResult
}

/**
 * Validates the origin and the 6-digit code, exchanges the code for the device token with
 * `POST /pair`, proves the token with `GET /config` and stores origin and token on success.
 * [clientFactory] receives the origin and the token provider to authenticate with; the exchange
 * uses no token. Neither the code nor the token is ever logged or put in a result.
 */
class PairingRepository(
    private val store: TokenStore,
    private val clientFactory: (String, () -> String?) -> ConnectorClient,
    private val allowLoopbackHttp: Boolean = BuildConfig.DEBUG,
) {
    @Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount") // the failure kind is the result
    suspend fun pair(
        origin: String,
        code: String,
    ): PairResult {
        val normalized = normalizeOrigin(origin) ?: return PairResult.InvalidOrigin
        if (!CODE_PATTERN.matches(code)) return PairResult.CodeRejected
        val token: String
        val config: ConfigDto
        try {
            token = clientFactory(normalized) { null }.pair(code).token
            config = clientFactory(normalized) { token }.config()
        } catch (e: ApiError.InvalidCode) {
            return PairResult.CodeRejected
        } catch (e: ApiError.Invalid) {
            return PairResult.CodeRejected
        } catch (e: ApiError.RateLimited) {
            return PairResult.RateLimited(e.retryAfterS)
        } catch (e: ApiError) {
            return PairResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }
        return save(normalized, token, config)
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // the failure kind is the result
    private suspend fun save(
        origin: String,
        token: String,
        config: ConfigDto,
    ): PairResult =
        try {
            store.save(origin, token)
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

    /** A request was refused with 401 while paired: drop the token and show the pairing screen again. */
    suspend fun rejectToken() = store.rejectToken()

    /**
     * `scheme://host[:port]` without a trailing slash, or null when the origin is not acceptable.
     * A bare host (no `://`) gets `https://`. No path, query, fragment or user info is accepted.
     */
    @Suppress("SwallowedException") // unparsable means invalid
    internal fun normalizeOrigin(raw: String): String? {
        val typed = raw.trim().trimEnd('/')
        val trimmed = if ("://" in typed) typed else "https://$typed"
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
        val CODE_PATTERN = Regex("[0-9]{6}")
    }
}
