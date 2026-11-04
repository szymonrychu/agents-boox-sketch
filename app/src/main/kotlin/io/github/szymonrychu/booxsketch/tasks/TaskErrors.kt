package io.github.szymonrychu.booxsketch.tasks

import io.github.szymonrychu.booxsketch.api.ApiError

/** The exact text of the stale-version banner (web parity). */
const val STALE_TEXT = "The task changed since you opened it; it was reloaded, try again."

/** Shown when a decision was answered or withdrawn by someone else first. */
const val ALREADY_ANSWERED_TEXT = "Already answered"

/** Default wait when a 429 carries no `Retry-After`. */
internal const val DEFAULT_RETRY_AFTER_S = 5L

/** A short, user-facing sentence for a failed task request. */
internal fun ApiError.userText(): String =
    when (this) {
        ApiError.StaleVersion -> {
            STALE_TEXT
        }

        ApiError.AlreadyAnswered -> {
            ALREADY_ANSWERED_TEXT
        }

        ApiError.Unauthorized -> {
            "This device is no longer paired."
        }

        is ApiError.Unavailable -> {
            if (code ==
                "taskboard_unconfigured"
            ) {
                "Tasks are not set up on the server."
            } else {
                "The task service is not reachable. Try again later."
            }
        }

        is ApiError.RateLimited -> {
            "Too many requests. Try again in ${retryAfterS ?: DEFAULT_RETRY_AFTER_S} s."
        }

        is ApiError.Network -> {
            "No connection to the server."
        }

        is ApiError.Invalid -> {
            detail.ifBlank { "The server refused the request." }
        }

        is ApiError.NotFound -> {
            "Not found."
        }

        is ApiError.Server -> {
            "Server error ($status)."
        }

        else -> {
            message ?: "Request failed."
        }
    }
