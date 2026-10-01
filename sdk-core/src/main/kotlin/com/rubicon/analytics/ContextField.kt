package com.rubicon.analytics

/**
 * A field of an event's `context` that can be switched off with `context.disabledFields`
 * (Context 6). A disabled field is never read from the device and never sent. `library.name` and
 * `library.version` are always sent.
 *
 * Fields may be added in minor versions; a `when` over this enum should keep an `else` branch.
 *
 * ```
 * // For a child-directed app, for example:
 * context.disabledFields = setOf(ContextField.NETWORK_CARRIER, ContextField.LOCALE, ContextField.TIMEZONE)
 * ```
 */
public enum class ContextField {
    /** `app.name` */
    APP_NAME,

    /** `app.version` */
    APP_VERSION,

    /** `app.build` */
    APP_BUILD,

    /** `app.namespace` */
    APP_NAMESPACE,

    /** `device.manufacturer` */
    DEVICE_MANUFACTURER,

    /** `device.model` */
    DEVICE_MODEL,

    /** `device.type` */
    DEVICE_TYPE,

    /** `device.is_emulator` */
    DEVICE_IS_EMULATOR,

    /** `os.name` */
    OS_NAME,

    /** `os.version` */
    OS_VERSION,

    /** `os.api_level` */
    OS_API_LEVEL,

    /** `screen.width` */
    SCREEN_WIDTH,

    /** `screen.height` */
    SCREEN_HEIGHT,

    /** `screen.density` */
    SCREEN_DENSITY,

    /** `network.type` */
    NETWORK_TYPE,

    /** `network.carrier`: the operator of the SIM used for mobile data. */
    NETWORK_CARRIER,

    /** `locale` */
    LOCALE,

    /** `timezone` */
    TIMEZONE,
}
