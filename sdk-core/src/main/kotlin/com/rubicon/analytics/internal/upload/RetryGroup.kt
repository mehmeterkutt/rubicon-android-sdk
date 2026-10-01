package com.rubicon.analytics.internal.upload

import com.rubicon.analytics.internal.http.FailureKind
import com.rubicon.analytics.internal.http.HttpResult

/**
 * The five groups of Retry 1, asked of every upload result: is the problem in this batch or
 * somewhere else? Only [BAD_BATCH] deletes a batch the server did not accept.
 */
internal enum class RetryGroup {
    /** A. 2xx: delete the batch. */
    SUCCESS,

    /** B. Network failures, 408, most 5xx, 1xx and non-standard codes: keep, back off. */
    TRANSIENT,

    /** C. 429, or 503 with `Retry-After`: keep, pause everything. */
    THROTTLE,

    /** D. 400, 422 and other 4xx: delete, counted as `rejected`. */
    BAD_BATCH,

    /** E. Configuration or environment: keep, pause an hour, ERROR once. */
    CONFIG,
    ;

    companion object {
        /** One `when` over the result (Retry 1, 4). */
        fun of(result: HttpResult): RetryGroup =
            when (result) {
                is HttpResult.Success -> SUCCESS
                is HttpResult.NetworkFailure ->
                    when (result.kind) {
                        FailureKind.TIMEOUT, FailureKind.CONNECT, FailureKind.IO, FailureKind.DNS -> TRANSIENT
                        FailureKind.TLS, FailureKind.BLOCKED, FailureKind.UNEXPECTED -> CONFIG
                    }
                is HttpResult.HttpError -> ofStatus(result.code, result.retryAfterMs != null)
            }

        private fun ofStatus(
            code: Int,
            hasRetryAfter: Boolean,
        ): RetryGroup =
            when (code) {
                in 200..299 -> SUCCESS
                429 -> THROTTLE
                503 -> if (hasRetryAfter) THROTTLE else TRANSIENT
                401, 403, 404, 410, 413, 501, 505 -> CONFIG
                in 300..399 -> CONFIG
                408 -> TRANSIENT
                in 400..499 -> BAD_BATCH
                in 500..599 -> TRANSIENT
                else -> TRANSIENT // 1xx and non-standard codes
            }
    }
}
