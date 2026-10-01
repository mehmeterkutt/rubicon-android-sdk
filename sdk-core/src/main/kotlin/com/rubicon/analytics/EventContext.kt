package com.rubicon.analytics

/**
 * What the SDK measured when an event was created (Event Model 1, Context 3 and 4). Hooks can read
 * it, for example to drop events from emulators, but never change it; a field that must not be sent
 * is switched off with `context.disabledFields`. A value that could not be read, or was switched off,
 * is `null` and is not sent.
 *
 * ```
 * BeforeSend { event -> if (event.context.device.isEmulator == true) null else event }
 * ```
 */
public class EventContext internal constructor(
    /** The app that sent the event. */
    public val app: AppContext,
    /** The device. */
    public val device: DeviceContext,
    /** The operating system. */
    public val os: OsContext,
    /** The display. */
    public val screen: ScreenContext,
    /** The network at the time of the event. */
    public val network: NetworkContext,
    /** The device language, such as `tr-TR`. */
    public val locale: String?,
    /** The device time zone, such as `Europe/Istanbul`. */
    public val timezone: String?,
    /** The session the event belongs to; `null` when there is none, such as in the background. */
    public val session: SessionContext?,
    /** This SDK; always present. */
    public val library: LibraryContext,
) {
    /** The library only: never measured values. */
    override fun toString(): String = "EventContext(library=${library.name} ${library.version})"
}

/** The app that sent the event. */
public class AppContext internal constructor(
    /** The app's name, such as `Rubicon`. */
    public val name: String?,
    /** The version users see, such as `1.4.0`. */
    public val version: String?,
    /** The build number, such as `140`. */
    public val build: String?,
    /** The application id, such as `com.rubicon.demo`. */
    public val namespace: String?,
)

/** The device. */
public class DeviceContext internal constructor(
    /** Such as `Google`. */
    public val manufacturer: String?,
    /** Such as `Pixel 8`. */
    public val model: String?,
    /** Such as `phone`, `tablet` or `tv`. */
    public val type: String?,
    /** Whether the device is an emulator. */
    public val isEmulator: Boolean?,
)

/** The operating system. */
public class OsContext internal constructor(
    /** Such as `Android`. */
    public val name: String?,
    /** The version users see, such as `16`. */
    public val version: String?,
    /** The API level, such as `36`. */
    public val apiLevel: Int?,
)

/** The display. */
public class ScreenContext internal constructor(
    /** In pixels. */
    public val width: Int?,
    /** In pixels. */
    public val height: Int?,
    /** Pixels per density-independent pixel, such as `2.625`. */
    public val density: Double?,
)

/** The network when the event was created. */
public class NetworkContext internal constructor(
    /** Such as `wifi` or `cellular`. */
    public val type: String?,
    /** The operator of the SIM used for mobile data. */
    public val carrier: String?,
)

/** The session an event belongs to (Session 2). */
public class SessionContext internal constructor(
    /** A UUIDv7. */
    public val id: String,
    /** When the session started, in epoch milliseconds. */
    public val startedAt: Long,
)

/** The SDK that sent the event; always present. */
public class LibraryContext internal constructor(
    /** `rubicon-android`. */
    public val name: String,
    /** The SDK version, such as `0.1.0`. */
    public val version: String,
)
