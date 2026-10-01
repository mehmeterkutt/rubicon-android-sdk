package com.rubicon.analytics

/**
 * The name of an activity's automatic screen view (Lifecycle 3). Without it the name is the simple
 * class name without a trailing `Activity` (`CheckoutActivity` becomes `Checkout`), sent with
 * `name_source = "class"`; with it, the value, sent with `name_source = "annotation"`.
 *
 * The name is the screen's key on every platform: use the name from your tracking plan, the same
 * on Android, iOS and web, preferably `snake_case`, never displayed text.
 *
 * ```
 * @ScreenName("checkout")
 * class PaymentFlowActivity : ComponentActivity()
 * ```
 *
 * A screen is tracked either automatically or with `screen()`, never both: for an activity whose
 * screens you send yourself, such as a single-activity Compose app, remove
 * [Autocapture.SCREEN_VIEWS].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class ScreenName(
    /** The screen name, 1 to 255 characters. */
    public val value: String,
)
