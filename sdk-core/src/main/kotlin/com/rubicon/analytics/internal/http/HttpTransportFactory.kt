package com.rubicon.analytics.internal.http

import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Platform

/**
 * What a transport takes from the validated configuration and the instance: the API key for
 * `Authorization`, `network.headers` with the reserved ones already removed, and the instance's log.
 */
@RubiconInternal
public class TransportSettings(
    public val apiKey: String,
    public val headers: Map<String, String>,
    public val log: Log,
) {
    override fun toString(): String = "TransportSettings(${headers.size} custom headers)"
}

/**
 * Builds the instance's [HttpTransport] once the configuration is validated (Configuration 5). The
 * platform carries a factory rather than a transport, because the transport needs the instance's log
 * and key, which exist only after validation. Production uses [DEFAULT]; tests return a fake.
 */
@RubiconInternal
public fun interface HttpTransportFactory {
    public fun create(
        settings: TransportSettings,
        platform: Platform,
    ): HttpTransport

    public companion object {
        public val DEFAULT: HttpTransportFactory =
            HttpTransportFactory {
                settings,
                platform,
                ->
                HttpURLConnectionTransport(settings, platform)
            }
    }
}
