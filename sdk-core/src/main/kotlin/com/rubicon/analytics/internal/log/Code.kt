package com.rubicon.analytics.internal.log

import com.rubicon.analytics.RubiconInternal

/**
 * The stable code of every WARN and ERROR line (Logging 1, 2). The names are part of the wire
 * contract: `SDK Diagnostics` reports them in `issues` (Logging 5), and `docs/issue-codes.md`
 * documents each one. Never rename a code; add a new one.
 *
 * Codes are listed with the level they are logged at, as documented in the catalogue.
 */
@RubiconInternal
public enum class Code {
    // Logging

    /** WARN. The developer's logger threw; the platform logger is used from now on. */
    LOGGER_THREW,

    /** ERROR. The developer's event observer threw; it is switched off. */
    OBSERVER_THREW,

    /** ERROR. An SDK coroutine failed with an unexpected exception. */
    WORKER_FAILED,

    // Configuration

    /** ERROR. The API key is blank; the SDK does nothing. */
    INVALID_API_KEY,

    /** ERROR. The host is not a valid base URL; the SDK does nothing. */
    INVALID_HOST,

    /** WARN. The host uses `http://`. */
    CLEARTEXT_HOST,

    /** WARN. A numeric setting was out of bounds and was clamped. */
    SETTING_OUT_OF_BOUNDS,

    /** WARN. Settings of a group contradict each other; the group's defaults are used. */
    CONTRADICTING_SETTINGS,

    /** WARN. A setting was written after the SDK was created; the write is ignored. */
    CONFIG_FROZEN,

    /** WARN. `network.headers` tried to override a reserved header; it is ignored. */
    RESERVED_HEADER,

    /** ERROR. A second instance was created in the process; the first one is returned. */
    SECOND_INSTANCE,

    /** WARN. The process name could not be determined; the main process is assumed. */
    PROCESS_NAME_UNKNOWN,

    /** WARN. The remote configuration could not be fetched or read; the last valid values are kept. */
    REMOTE_CONFIG_FAILED,

    /** WARN. A remote configuration value was invalid and is ignored. */
    REMOTE_CONFIG_INVALID_VALUE,

    // Events and properties

    /** WARN. A property or trait was dropped: unsupported type, invalid key or too deep. */
    PROPERTY_DROPPED,

    /** WARN. A string value was longer than 1024 characters and was truncated. */
    PROPERTY_TRUNCATED,

    /** WARN. An event had more than 256 properties; the extra ones were dropped. */
    TOO_MANY_PROPERTIES,

    /** WARN. An event or screen name was blank or longer than 255 characters; the event was dropped. */
    INVALID_EVENT_NAME,

    /** ERROR. A `beforeSend` hook threw; the event was dropped. */
    HOOK_THREW,

    // Identity

    /** WARN. `identify` was called with a blank user id or one longer than 255 characters. */
    INVALID_USER_ID,

    // Storage

    /** WARN. `device.json` or `person.json` exists but cannot be read; it is left untouched. */
    STORE_UNREADABLE,

    /** ERROR. An event or a store could not be written. */
    STORAGE_WRITE_FAILED,

    /** ERROR. A stored event line was corrupt and was skipped. */
    CORRUPT_LINE,

    /** ERROR. A segment could not be read; it is deleted after three failures. */
    UNREADABLE_SEGMENT,

    /** WARN. An unknown file in the events directory was deleted. */
    UNKNOWN_FILE,

    // Upload

    /** WARN. The host resolves to a local address, as ad blockers and private DNS do. */
    HOST_BLOCKED,

    /** ERROR. The host answered with a redirect; set `host` to the final URL. */
    HTTP_REDIRECT,

    /** ERROR. The TLS handshake failed, for example on an untrusted certificate. */
    TLS_FAILED,

    /** ERROR. The HTTP stack threw an unexpected exception. */
    HTTP_UNEXPECTED,

    /** ERROR. The server rejected the API key (401, 403). */
    UPLOAD_UNAUTHORIZED,

    /** ERROR. The endpoint does not exist (404, 410); check `host`. */
    UPLOAD_ENDPOINT_MISSING,

    /** ERROR. A gateway rejected the request size (413). */
    UPLOAD_TOO_LARGE,

    /** ERROR. The server does not support the request (501, 505). */
    UPLOAD_NOT_SUPPORTED,

    /** WARN. Compressing a request failed; it was sent uncompressed. */
    COMPRESSION_FAILED,

    // Network

    /** WARN. The app removed `ACCESS_NETWORK_STATE`; connectivity is assumed. */
    NETWORK_STATE_PERMISSION_MISSING,

    /** WARN. Registering the network callback failed; connectivity is queried before each flush. */
    NETWORK_CALLBACK_FAILED,
}
