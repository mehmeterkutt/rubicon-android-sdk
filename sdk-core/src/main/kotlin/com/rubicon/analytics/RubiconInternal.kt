package com.rubicon.analytics

/**
 * Marks a declaration that is public only so that other Rubicon SDK modules can use it.
 *
 * It is not part of the SDK's API: it may change or disappear in any release. Application code must
 * not opt in.
 */
@RequiresOptIn(
    message = "Internal Rubicon SDK API. It may change or be removed in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class RubiconInternal
