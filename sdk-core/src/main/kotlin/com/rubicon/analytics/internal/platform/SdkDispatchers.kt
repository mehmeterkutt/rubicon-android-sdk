package com.rubicon.analytics.internal.platform

import com.rubicon.analytics.RubiconInternal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The dispatchers of the SDK's coroutines (Queue 1): the writer, the uploader and the HTTP watchdog
 * (HTTP 3). Tests pass a `TestDispatcher`.
 */
@RubiconInternal
public class SdkDispatchers(
    public val writer: CoroutineDispatcher,
    public val uploader: CoroutineDispatcher,
    public val watchdog: CoroutineDispatcher,
) {
    public companion object {
        /**
         * Each consumer gets its own single slot of `Dispatchers.IO`: the SDK creates no threads and
         * still runs when the app saturates the IO pool (Queue 1).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        public fun default(): SdkDispatchers =
            SdkDispatchers(
                writer = Dispatchers.IO.limitedParallelism(1),
                uploader = Dispatchers.IO.limitedParallelism(1),
                watchdog = Dispatchers.IO.limitedParallelism(1),
            )
    }
}
