package io.github.szymonrychu.booxsketch.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

    /** 400 invalid_code: the pairing code is wrong, expired, used or locked (the server does not say which). */
    data object InvalidCode : ApiError("invalid_code")

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

    /** 409 stale_base: the spec has a newer version than the one the board showed. */
    data object StaleBase : ApiError("stale_base")

    /** 409 op_replayed: an op id was already reported by an earlier spec version. */
    data object OpReplayed : ApiError("op_replayed")

    /** 409 busy: a translator turn of the conversation is open. */
    data object Busy : ApiError("busy")

    /** 422 stale_target: an op target no longer quotes the source text of its line. */
    data class StaleTarget(
        val detail: String,
    ) : ApiError("stale target: $detail")

    /** 410: the stored file was purged after retention. */
    data object Purged : ApiError("purged")

    /** 429, with the `Retry-After` seconds when the server sent them. */
    data class RateLimited(
        val retryAfterS: Long?,
    ) : ApiError("rate limited")

    /** 412 stale_version: the task changed since it was loaded. */
    data object StaleVersion : ApiError("stale_version")

    /** 409 already_answered: the decision was answered or withdrawn meanwhile. */
    data object AlreadyAnswered : ApiError("already_answered")

    /** 503 `taskboard_unavailable` or `taskboard_unconfigured`: the connector cannot reach the taskboard. */
    data class Unavailable(
        val code: String,
    ) : ApiError("unavailable: $code")

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
        400 -> {
            if (code == "invalid_code") ApiError.InvalidCode else ApiError.Server(status)
        }

        401 -> {
            ApiError.Unauthorized
        }

        404 -> {
            ApiError.NotFound(code)
        }

        409 -> {
            conflictOf(code)
        }

        412 -> {
            ApiError.StaleVersion
        }

        413 -> {
            ApiError.TooLarge(code)
        }

        410 -> {
            ApiError.Purged
        }

        415 -> {
            ApiError.Unsupported
        }

        422 -> {
            invalidOf(code, detail)
        }

        429 -> {
            ApiError.RateLimited(retryAfter?.trim()?.toLongOrNull())
        }

        503 -> {
            unavailableOf(status, code)
        }

        else -> {
            ApiError.Server(status)
        }
    }

private fun invalidOf(
    code: String,
    detail: String,
): ApiError = if (code == "stale_target") ApiError.StaleTarget(detail) else ApiError.Invalid(code, detail)

private fun conflictOf(code: String): ApiError =
    when (code) {
        "in_progress" -> ApiError.InProgress
        "already_answered" -> ApiError.AlreadyAnswered
        "stale_base" -> ApiError.StaleBase
        "op_replayed" -> ApiError.OpReplayed
        "busy" -> ApiError.Busy
        else -> ApiError.Conflict(code)
    }

private fun unavailableOf(
    status: Int,
    code: String,
): ApiError = if (code in UNAVAILABLE_CODES) ApiError.Unavailable(code) else ApiError.Server(status)

private val UNAVAILABLE_CODES = setOf("taskboard_unavailable", "taskboard_unconfigured")

/**
 * The (code, detail) of an error body in either shape: the connector's `{"error": "<code>", "detail": "<text>"}` or the
 * taskboard's `{"error": {"code": "<code>", "message": "<text>"}}`. Null when the body is neither.
 */
internal fun parseErrorBody(text: String): Pair<String, String>? {
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return when (val error = root["error"]) {
        is JsonPrimitive -> {
            error.contentOrNull?.let { it to (root["detail"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        }

        is JsonObject -> {
            val code = (error["code"] as? JsonPrimitive)?.contentOrNull
            code?.let { it to (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        }

        else -> {
            null
        }
    }
}
