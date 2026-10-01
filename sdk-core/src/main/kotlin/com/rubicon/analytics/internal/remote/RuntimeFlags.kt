package com.rubicon.analytics.internal.remote

/**
 * The two remote switches that apply at once, outside the snapshot (Configuration 1, rule 3): the
 * kill switch and network request capture. They only ever turn off within a process; turning back
 * on waits for the next launch, like every other remote value.
 *
 * The pipeline checks [collecting] before it creates or uploads an event (6.3, 6.4), and the
 * network interceptor checks [capturingNetworkRequests] on every request (7.6). Read from any thread.
 */
internal class RuntimeFlags(
    collecting: Boolean = true,
    capturingNetworkRequests: Boolean = true,
) {
    /** `false` after the kill switch, or on a device outside `sample_rate`. */
    @Volatile
    var collecting: Boolean = collecting
        private set

    @Volatile
    var capturingNetworkRequests: Boolean = capturingNetworkRequests
        private set

    fun stopCollecting() {
        collecting = false
    }

    fun stopCapturingNetworkRequests() {
        capturingNetworkRequests = false
    }
}
