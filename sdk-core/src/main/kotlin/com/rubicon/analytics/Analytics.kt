package com.rubicon.analytics

/**
 * The SDK's entry point (Public API 2, 8). Create one per process with the factory of
 * `sdk-android`, `Analytics(context, config)`, hold it in your `Application` or dependency
 * injection container, and pass it to the code that tracks (Public API 1). In tests, pass a fake.
 *
 * ```
 * class MyApp : Application() {
 *     lateinit var analytics: Analytics
 *     override fun onCreate() {
 *         super.onCreate()
 *         analytics = Analytics(this, AnalyticsConfig("api-key").apply { debug = BuildConfig.DEBUG })
 *     }
 * }
 * ```
 *
 * Threading (Public API 9):
 * 1. Every method can be called from any thread.
 * 2. No method blocks: disk and network work never runs on the calling thread. The calling thread
 *    only copies the arguments, validates properties and takes a snapshot of identity and super
 *    properties. The one exception is [isOptedOut] in the first milliseconds after startup.
 * 3. Calls made from the same thread take effect in call order. [identify], [reset], [register] and
 *    [unregister] change in-memory state at once and persist later, so a [track] after [identify]
 *    carries the new identity. There is no order between calls on different threads.
 *
 * Properties and traits are `Map<String, Any?>` of `String`, `Boolean`, `Int`, `Long`, `Float`,
 * `Double`, `null`, and lists and maps of these; `java.util.Date` and enums are converted. Other
 * values are dropped with a warning, or throw when `debug` is on (Public API 3). The SDK never keeps
 * your map. From Java, pass every argument, for example `track("Signed Up", Collections.emptyMap())`.
 *
 * Methods may be added in minor versions, always with a default body.
 */
public interface Analytics {
    /**
     * Records an action, such as `Purchase Completed`. A [name] that is blank or longer than 255
     * characters drops the event with a warning, or throws when `debug` is on. Plain Title Case names, as the automatic events use, and
     * `snake_case` property keys are recommended.
     */
    public fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
    )

    /**
     * Records a screen view. Use a fixed key from your tracking plan, the same on every platform
     * (`checkout`), never displayed text or a filled route. Send each screen either automatically
     * (`Autocapture.SCREEN_VIEWS`) or with this method, never both.
     */
    public fun screen(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
    )

    /**
     * Attaches the user [userId] to this and every later event, and sends [traits] once with an
     * identify event; traits are never stored. Calling it again with the same id and no traits does
     * nothing. A different id while identified starts a new user: super properties and the session
     * are cleared first. A blank id, or one longer than 255 characters, is ignored with a warning,
     * or throws when `debug` is on.
     */
    public fun identify(
        userId: String,
        traits: Map<String, Any?> = emptyMap(),
    )

    /**
     * Returns to an anonymous user, for example on logout: clears the user id, super properties and
     * the session. [deviceId] stays, queued events are still sent, and no event is produced.
     */
    public fun reset()

    /**
     * Adds a super property, sent with every later event of this user until [unregister] or
     * [reset]. A property of the event itself with the same key wins. [value] follows the property
     * rules; `null` is stored and sent.
     */
    public fun register(
        key: String,
        value: Any?,
    )

    /** Removes the super property [key]. */
    public fun unregister(key: String)

    /**
     * Asks for an upload of every event tracked so far and returns at once. Uploads also happen by
     * themselves; calling this is rarely needed.
     */
    public fun flush()

    /**
     * Stops collection: no new events of any kind, until [optIn]. Events collected before are still
     * sent. With `privacy.persistConsent` (the default) the choice survives restarts. To also delete
     * the data on the device, call [clearLocalData] after this.
     */
    public fun optOut()

    /** Resumes collection after [optOut], or gives consent when `privacy.defaultConsent` is `PENDING`. */
    public fun optIn()

    /**
     * Whether nothing is being sent now: `true` after [optOut] and while consent is pending. Called in
     * the first milliseconds after startup, it may read a small file once on the calling thread.
     */
    public fun isOptedOut(): Boolean

    /**
     * Deletes everything on the device that belongs to a person: queued events, the user id, super
     * properties and the session, and renews [deviceId]. The consent choice is kept. An upload already
     * in flight may still complete. Collection goes on; "forget me" is [optOut] then this.
     *
     * ```
     * val oldId = analytics.deviceId          // read before clearing
     * myBackend.requestErasure(oldId)
     * analytics.optOut()
     * analytics.clearLocalData()
     * ```
     */
    public fun clearLocalData()

    /**
     * The id of this installation, sent with every event, for example to request erasure on your
     * backend. It changes only with [clearLocalData]; [reset] keeps it.
     */
    public val deviceId: String
}
