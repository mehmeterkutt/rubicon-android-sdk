package com.rubicon.analytics.internal.remote

import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.internal.config.ConfigSnapshot
import com.rubicon.analytics.internal.http.Headers
import com.rubicon.analytics.internal.http.HttpRequest
import com.rubicon.analytics.internal.http.HttpResult
import com.rubicon.analytics.internal.http.HttpTransport
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.RemoteConfigCache

/**
 * Remote configuration (Configuration 1): values saved by the last launch restrict the code's
 * configuration at startup, and one background fetch per launch saves new values for the next one.
 *
 * - [startup] runs once, after `DeviceStore.load`, and gives the snapshot the SDK runs with.
 * - [fetch] runs once per launch in the background: `GET {host}/v1/config` with `If-None-Match`, also
 *   while consent is pending or the user opted out, since it carries no personal data (Privacy 4).
 *   Only `enabled = false` and switching off `network_requests` take effect at once ([RuntimeFlags]).
 * - A failure keeps the saved values and never stops the SDK (rule 4); a 404 means "no remote
 *   values" and deletes them.
 */
internal class RemoteConfigClient(
    private val code: ConfigSnapshot,
    private val deviceStore: DeviceStore,
    private val transport: HttpTransport,
    private val flags: RuntimeFlags,
    private val log: Log,
) {
    /** What the SDK runs with until the next launch. */
    class Effective(
        val snapshot: ConfigSnapshot,
        /** `false` when the kill switch is on or the device is outside `sample_rate`. */
        val collecting: Boolean,
        /** Whether `SDK Diagnostics` is sent: on in code and inside `diagnostics_sample_rate`. */
        val sendsDiagnostics: Boolean,
    )

    fun startup(): Effective {
        val remote = deviceStore.remoteConfig?.let { RemoteConfig.from(it.values, code, log) } ?: RemoteConfig.NONE
        val snapshot = remote.restrict(code)
        val deviceId = deviceStore.state.deviceId
        val sampled = Sampling.isIn(deviceId, remote.sampleRate ?: 1.0, Sampling.Purpose.EVENTS)
        val collecting = remote.enabled && sampled
        val diagnostics =
            snapshot.sendDiagnostics &&
                Sampling.isIn(deviceId, remote.diagnosticsSampleRate ?: 1.0, Sampling.Purpose.DIAGNOSTICS)
        if (!collecting) flags.stopCollecting()
        if (Autocapture.NETWORK_REQUESTS !in snapshot.autocapture) flags.stopCapturingNetworkRequests()
        when {
            !remote.enabled -> log.info { "Remote configuration has switched the SDK off; nothing is collected or sent" }
            !sampled -> log.info { "This device is outside the remote sample rate; nothing is collected or sent" }
        }
        return Effective(snapshot, collecting, diagnostics)
    }

    fun fetch() {
        val etag = deviceStore.remoteConfig?.etag
        val headers = if (etag != null) mapOf(Headers.IF_NONE_MATCH to etag) else emptyMap()
        when (val result = transport.execute(HttpRequest(HttpRequest.Method.GET, code.configUrl, headers))) {
            is HttpResult.Success -> {
                val values = result.body?.let(RemoteConfig::parseBody)
                if (values == null) {
                    failed("the response is not a JSON object")
                } else {
                    deviceStore.updateRemoteConfig(RemoteConfigCache(result.etag, values))
                    applyNow(RemoteConfig.from(values, code, log))
                    log.debug { "Remote configuration saved; it applies at the next launch" }
                }
            }
            is HttpResult.HttpError ->
                when (result.code) {
                    NOT_MODIFIED -> log.debug { "Remote configuration unchanged" }
                    NOT_FOUND -> {
                        deviceStore.updateRemoteConfig(null)
                        log.debug { "No remote configuration for this key (404)" }
                    }
                    else -> failed("HTTP ${result.code}")
                }
            is HttpResult.NetworkFailure -> failed(result.kind.name)
        }
    }

    private fun applyNow(remote: RemoteConfig) {
        if (!remote.enabled && flags.collecting) {
            flags.stopCollecting()
            log.info { "Remote configuration switched the SDK off; collection and uploads stop now" }
        }
        if (Autocapture.NETWORK_REQUESTS in remote.autocaptureOff && flags.capturingNetworkRequests) {
            flags.stopCapturingNetworkRequests()
            log.info { "Remote configuration switched off network request capture" }
        }
    }

    private fun failed(reason: String) {
        log.warn(Code.REMOTE_CONFIG_FAILED) { "Could not fetch the remote configuration ($reason); the last valid values are kept" }
    }

    private companion object {
        const val NOT_MODIFIED = 304
        const val NOT_FOUND = 404
    }
}
