package io.github.szymonrychu.booxsketch.api

import kotlinx.serialization.Serializable

/** Connector error body: `{"error": <code>, "detail": <text>}`. */
@Serializable
data class ErrorResponse(
    val error: String,
    val detail: String,
)

/** Typed failures of [ConnectorClient], mapped from the HTTP status and `ErrorResponse.error`. */
sealed class ApiError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 401: the token is missing, wrong or revoked. */
    data object Unauthorized : ApiError("unauthorized")

    /** 409 in_progress: a request with the same client id is still being handled. */
    data object InProgress : ApiError("in_progress")

    /** Other 409 (for example no_spec). */
    data class Conflict(
        val code: String,
    ) : ApiError("conflict: $code")

    /** 413 (too_large, quota_exceeded). */
    data class TooLarge(
        val code: String,
    ) : ApiError("too large: $code")

    /** 415: unsupported media type. */
    data object Unsupported : ApiError("unsupported")

    /** 422 (idempotency_mismatch, invalid_request, ...). */
    data class Invalid(
        val code: String,
        val detail: String,
    ) : ApiError("invalid: $code $detail")

    /** 404 (not_found, unknown_session, ...). */
    data class NotFound(
        val code: String,
    ) : ApiError("not found: $code")

    /** 410: the stored file was purged after retention. */
    data object Purged : ApiError("purged")

    /** 429, with the `Retry-After` seconds when the server sent them. */
    data class RateLimited(
        val retryAfterS: Long?,
    ) : ApiError("rate limited")

    /** Any other non-success status (5xx and unmapped 4xx). */
    data class Server(
        val status: Int,
    ) : ApiError("server error $status")

    /** Transport failure (DNS, connect, timeout, broken stream). */
    class Network(
        cause: Throwable,
    ) : ApiError("network error: ${cause.message}", cause)
}

internal const val UNKNOWN_CODE = "unknown"

internal fun mapError(
    status: Int,
    code: String,
    detail: String,
    retryAfter: String?,
): ApiError =
    when (status) {
        401 -> ApiError.Unauthorized
        404 -> ApiError.NotFound(code)
        409 -> if (code == "in_progress") ApiError.InProgress else ApiError.Conflict(code)
        413 -> ApiError.TooLarge(code)
        410 -> ApiError.Purged
        415 -> ApiError.Unsupported
        422 -> ApiError.Invalid(code, detail)
        429 -> ApiError.RateLimited(retryAfter?.trim()?.toLongOrNull())
        else -> ApiError.Server(status)
    }
