package com.rubicon.analytics.internal.event

import com.rubicon.analytics.AppContext
import com.rubicon.analytics.DeviceContext
import com.rubicon.analytics.EventContext
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.IdentifyEvent
import com.rubicon.analytics.LibraryContext
import com.rubicon.analytics.NetworkContext
import com.rubicon.analytics.OsContext
import com.rubicon.analytics.ScreenContext
import com.rubicon.analytics.ScreenEvent
import com.rubicon.analytics.SessionContext
import com.rubicon.analytics.TrackEvent

// The events of the golden files, built through the internal constructors.
internal object SampleEvents {
    const val DEVICE_ID = "0191d0c2-1a2b-7c3d-9e4f-5a6b7c8d9e0f"
    private val library = LibraryContext("rubicon-android", "0.1.0")
    private val session = SessionContext("0192e49f-0b1c-7d2e-8f3a-4b5c6d7e8f90", 1_759_147_082_000L)

    private fun fullContext(network: NetworkContext) =
        EventContext(
            app = AppContext("Rubicon", "1.4.0", "140", "com.rubicon.demo"),
            device = DeviceContext("Google", "Pixel 8", "phone", false),
            os = OsContext("Android", "16", 36),
            screen = ScreenContext(1080, 2400, 2.625),
            network = network,
            locale = "tr-TR",
            timezone = "Europe/Istanbul",
            session = session,
            library = library,
        )

    // Everything unknown except the OS: empty groups and missing values are left out.
    val sparseContext =
        EventContext(
            app = AppContext(null, null, null, null),
            device = DeviceContext(null, null, null, null),
            os = OsContext("Android", "16", 36),
            screen = ScreenContext(null, null, null),
            network = NetworkContext(null, null),
            locale = null,
            timezone = null,
            session = null,
            library = library,
        )

    val track =
        TrackEvent(
            source = EventSource.APP,
            messageId = "0192e4a1-7c3f-7a2b-8d4e-1f2a3b4c5d6e",
            timestamp = 1_759_147_200_123L,
            userId = "user-42",
            deviceId = DEVICE_ID,
            name = "Purchase Completed",
            properties =
                linkedMapOf(
                    "amount" to 9.99,
                    "currency" to "USD",
                    "trial_ends_at" to "2026-10-13T00:00:00.000Z",
                    "ab_group" to "B",
                    "items" to listOf(mapOf("sku" to "A1", "qty" to 2)),
                    "coupon" to null,
                ),
            context = fullContext(NetworkContext("wifi", "Vodafone TR")),
        )

    val screen =
        ScreenEvent(
            source = EventSource.AUTO,
            messageId = "0192e4a1-6b2e-7f10-9a3b-2c4d6e8f0a1b",
            timestamp = 1_759_147_190_000L,
            userId = null,
            deviceId = DEVICE_ID,
            name = "Checkout",
            properties = mapOf("name_source" to "annotation"),
            context = fullContext(NetworkContext("cellular", null)),
        )

    val identify =
        IdentifyEvent(
            source = EventSource.APP,
            messageId = "0192e4a1-5a1d-7e0f-8b2c-1d3e5f7a9b0c",
            timestamp = 1_759_147_100_000L,
            userId = "user-42",
            deviceId = DEVICE_ID,
            traits = linkedMapOf("plan" to "pro", "signup_at" to "2026-09-01T12:00:00.000Z"),
            context = sparseContext,
        )
}
